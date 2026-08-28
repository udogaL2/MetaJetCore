package dev.metajetcore.agents

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.metajetcore.registry.SessionRecord
import dev.metajetcore.registry.SessionRegistry
import dev.metajetcore.roles.Role
import dev.metajetcore.roles.RoleInstaller
import dev.metajetcore.settings.MjcSettings
import dev.metajetcore.shell.ShellDialect
import dev.metajetcore.terminal.OpenTabRequest
import dev.metajetcore.terminal.TabHandle
import dev.metajetcore.terminal.TerminalBackends
import dev.metajetcore.terminal.TerminalShell
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/** Что плагин знает про запущенного агента. */
data class AgentInfo(
    val name: String,
    val role: Role,
    val model: String,
    val sessionId: String?,
    val pid: Int?,
    val status: String?,
    val tabId: String?,
)

/** Результат спавна: либо агент поднят, либо команда, которую надо выполнить руками. */
sealed interface SpawnResult {
    /**
     * @param pendingBriefing текст, который оркестратор обязан отправить агенту через
     *   SendMessage. Плагин его не печатает: см. комментарий в [AgentManager.spawn].
     */
    data class Started(val agent: AgentInfo, val pendingBriefing: String) : SpawnResult

    /** Терминал недоступен или сессия не поднялась. Оркестратор попросит разработчика. */
    data class Manual(val command: String, val reason: String) : SpawnResult

    data class Failed(val reason: String) : SpawnResult
}

@Service(Service.Level.PROJECT)
class AgentManager(private val project: Project) {

    private val log = Logger.getInstance(AgentManager::class.java)
    private val tabs = ConcurrentHashMap<String, TabHandle>()
    private val spawned = ConcurrentHashMap<String, AgentInfo>()

    private val settings get() = MjcSettings.getInstance()

    fun projectDirectory(): Path =
        project.basePath?.let { Paths.get(it) } ?: Paths.get(System.getProperty("user.dir"))

    fun prefix(): String =
        settings.namePrefix.ifBlank { MjcSettings.derivePrefix(project.name) }

    // ------------------------------------------------------------------ spawn

    fun spawn(
        role: Role,
        task: String,
        parentName: String?,
        requestedName: String?,
        requestedModel: String?,
        domain: String?,
    ): SpawnResult {
        val name = requestedName?.takeIf { it.isNotBlank() } ?: generateName(role, domain)
        if (SessionRegistry.isNameTaken(name)) {
            return SpawnResult.Failed(
                "имя '$name' уже занято живой сессией; передай другое или закрой ту сессию",
            )
        }

        val model = requestedModel?.takeIf { it.isNotBlank() } ?: role.defaultModel
        val commandLine = buildCommandLine(role, name, model)

        val backend = TerminalBackends.resolve()
        if (!backend.isAvailable()) {
            return SpawnResult.Manual(
                command = commandLine,
                reason = "терминальное API недоступно (backend=${backend.id})",
            )
        }

        val parentTab = parentName?.let { tabs[it] }
        val handle = openTabOnEdt(
            OpenTabRequest(
                tabName = name,
                workingDirectory = projectDirectory(),
                nearTab = parentTab,
            ),
        ) ?: return SpawnResult.Manual(commandLine, "не удалось открыть вкладку терминала")

        tabs[name] = handle

        if (!awaitTabReady(handle)) {
            return SpawnResult.Manual(commandLine, "шелл во вкладке не поднялся за ${TAB_READY_TIMEOUT_MS / 1000} c")
        }

        if (!sendOnEdt(handle, commandLine)) {
            return SpawnResult.Manual(commandLine, "вкладка открыта, но команду напечатать не удалось")
        }

        val record = awaitSession(name)
            ?: return SpawnResult.Manual(
                commandLine,
                "сессия не появилась в реестре за ${settings.readyTimeoutSeconds} c; " +
                    "проверь команду запуска в настройках плагина",
            )

        // Имя могло измениться: при коллизии Claude Code переименовывает сессию в вариант.
        val actualName = record.name ?: name
        if (actualName != name) {
            tabs.remove(name)
            tabs[actualName] = handle
        }

        // Задачу в терминал НЕ печатаем.
        //
        // Проверено живым прогоном: печать текста в поднявшийся TUI Claude Code даёт две
        // проблемы. Во-первых, кодировка — виджет пишет в pty в кодировке JVM (в песочнице
        // это была windows-1251), и кириллица приезжала кракозябрами. Во-вторых, executeCommand
        // рассчитан на шелл, а не на TUI: текст оседал в поле ввода неотправленным.
        //
        // Правильный канал — межсессионный обмен: он UTF-8-безопасен, и именно им оркестратор
        // общается с агентом дальше. Поэтому плагин печатает только команду запуска (чистый
        // ASCII), а задачу отправляет оркестратор через SendMessage.
        val info = AgentInfo(
            name = actualName,
            role = role,
            model = model,
            sessionId = record.sessionId,
            pid = record.pid,
            status = record.status,
            tabId = handle.id,
        )
        spawned[actualName] = info
        return SpawnResult.Started(info, pendingBriefing = buildBriefing(role, task))
    }

    // ------------------------------------------------------------- lifecycle

    /** Мягкое завершение: /exit, дождаться исчезновения из реестра, закрыть вкладку. */
    fun close(name: String): Boolean {
        val handle = tabs[name]
        if (handle != null) {
            sendOnEdt(handle, "/exit")
            awaitSessionGone(name)
            runOnEdt { TerminalBackends.resolve().closeTab(handle) }
            tabs.remove(name)
        }
        spawned.remove(name)
        return handle != null
    }

    /** Сброс контекста между этапами: сессия и вкладка остаются живыми. */
    fun reset(name: String, task: String?): Boolean {
        val handle = tabs[name] ?: return false
        if (!sendOnEdt(handle, "/clear")) return false
        if (!task.isNullOrBlank()) {
            val role = spawned[name]?.role
            sendOnEdt(handle, if (role == null) task else buildBriefing(role, task))
        }
        return true
    }

    fun brief(name: String, text: String): Boolean {
        val handle = tabs[name] ?: return false
        return sendOnEdt(handle, text)
    }

    /** Что сейчас на экране вкладки агента. Для разбора «запустился, но молчит». */
    fun readScreen(name: String): String? {
        val handle = tabs[name] ?: return null
        return runOnEdt { TerminalBackends.resolve().readScreen(handle) }
    }

    /** Имена вкладок, которые ведёт плагин, — включая те, где спавн не доехал. */
    fun knownTabs(): Set<String> = tabs.keys.toSet()

    fun focus(name: String): Boolean {
        val handle = tabs[name] ?: return false
        return runOnEdt { TerminalBackends.resolve().focusTab(handle) }
    }

    /** Живые сессии в директории проекта, обогащённые тем, что плагин знает про вкладки. */
    fun list(): List<AgentInfo> =
        SessionRegistry.inDirectory(projectDirectory()).map { record ->
            val name = record.name.orEmpty()
            val known = spawned[name]
            AgentInfo(
                name = name,
                role = known?.role ?: record.agent?.let { Role.fromId(it) } ?: Role.IMPLEMENTER,
                model = known?.model ?: "",
                sessionId = record.sessionId,
                pid = record.pid,
                status = record.status,
                tabId = tabs[name]?.id,
            )
        }

    // ------------------------------------------------------------- internals

    private fun generateName(role: Role, domain: String?): String {
        val base = buildString {
            append(prefix())
            append('-')
            append(role.shortName)
            if (!domain.isNullOrBlank()) {
                append('-')
                append(domain.lowercase().filter { it.isLetterOrDigit() || it == '-' }.take(12))
            }
        }
        if (!SessionRegistry.isNameTaken(base)) return base
        // Имена глобальны на машину, поэтому коллизия реальна при нескольких проектах.
        for (suffix in 2..99) {
            val candidate = "$base-$suffix"
            if (!SessionRegistry.isNameTaken(candidate)) return candidate
        }
        return "$base-${System.currentTimeMillis() % 10_000}"
    }

    fun dialectForDiagnostics(): ShellDialect = dialect()

    private fun dialect(): ShellDialect {
        val configured = settings.shellDialect.lowercase()
        return when (configured) {
            "posix" -> ShellDialect.POSIX
            "powershell" -> ShellDialect.POWERSHELL
            "cmd" -> ShellDialect.CMD
            "fish" -> ShellDialect.FISH
            // auto: спрашиваем саму IDE, какой шелл она откроет во вкладке. Переменные
            // окружения описывают шелл процесса IDE, а это не одно и то же.
            else -> TerminalShell.detectDialect()
        }
    }

    /**
     * Сборка одной строки, которую напечатаем в терминал.
     *
     * Всё конфигурируемое едет здесь, а не через API терминала: env инлайном, роль флагом.
     * Единственное исключение — режим MESSAGE, где роль уходит первым сообщением.
     */
    internal fun buildCommandLine(role: Role, name: String, model: String): String {
        val shell = dialect()

        val env = LinkedHashMap<String, String>()
        env["CLAUDE_CODE_SESSION_NAME"] = name
        env["ANTHROPIC_MODEL"] = model
        // Метка для реестра: роль этим НЕ задаётся (проверено), но удобна для list().
        env["CLAUDE_CODE_AGENT"] = role.id
        env.putAll(settings.extraEnv())

        // Роль — одним флагом из файла, который плагин ставит в ~/.claude/agents/ сам.
        // Инлайновый JSON в командной строке был выброшен: под PowerShell он не передаётся
        // вовсе, а кириллицу в промптах портит кодировка терминала (docs/ARCHITECTURE.md §2.6).
        val roleFlag = " --agent ${RoleInstaller.agentName(role)}"

        // Без этого агент стартует в manual mode и встанет на первом запросе прав в вкладке,
        // которую никто не смотрит. Значение — чистый ASCII, кавычек не требует.
        val modeFlag = settings.permissionMode
            .trim()
            .takeIf { it.isNotEmpty() && it.all { ch -> ch.isLetter() } }
            ?.let { " --permission-mode $it" }
            .orEmpty()

        val flags = roleFlag + modeFlag

        // Вычистка окружения по ПРЕФИКСУ, а не по списку имён: список устаревает молча,
        // стоит Claude Code завести новую переменную. Подробности — в ShellDialect.purgeByPrefix.
        val prefixes = buildList {
            if (settings.stripInheritedClaudeMarkers) add("CLAUDE")
            if (settings.stripApiKeys) add("ANTHROPIC_")
        }
        val unset = if (shell.supportsPrefixPurge) {
            shell.purgeByPrefix(prefixes)
        } else {
            // cmd.exe перечислить окружение одной строкой не умеет — только поимённо.
            shell.unsetNames(
                buildList {
                    if (settings.stripApiKeys) addAll(listOf("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN"))
                    if (settings.stripInheritedClaudeMarkers) addAll(INHERITED_CLAUDE_MARKERS)
                },
            )
        }

        return unset + shell.composeCommand(env, settings.launchCommand + flags)
    }

    /** Брифинг — это просто задача: роль агент уже получил флагом при запуске. */
    private fun buildBriefing(role: Role, task: String): String = task

    /** Ждём, пока во вкладке поднимется шелл: до этого печатать бессмысленно. */
    private fun awaitTabReady(handle: TabHandle): Boolean {
        val deadline = System.currentTimeMillis() + TAB_READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (runOnEdt { TerminalBackends.resolve().isReady(handle) }) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    private fun awaitSession(name: String): SessionRecord? {
        val deadline = System.currentTimeMillis() + settings.readyTimeoutSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            SessionRegistry.findByName(name)?.let { return it }
            // Появление записи в реестре означает, что claude поднялся и забиндил
            // inbox-сокет, то есть готов и к вводу, и к межсессионным сообщениям.
            // Таймер вместо этого признака флакует: обёртка может стартовать долго.
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return null
    }

    private fun awaitSessionGone(name: String) {
        val deadline = System.currentTimeMillis() + EXIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (SessionRegistry.findByName(name) == null) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        log.warn("MetaJetCore: session '$name' still in registry after /exit")
    }

    private fun openTabOnEdt(request: OpenTabRequest): TabHandle? =
        runOnEdt { TerminalBackends.resolve().openTab(project, request) }

    private fun sendOnEdt(handle: TabHandle, text: String): Boolean =
        runOnEdt { TerminalBackends.resolve().sendLine(handle, text) }

    /**
     * Работа с терминалом обязана идти в EDT, а MCP-запросы приходят на своих потоках.
     * invokeAndWait, а не invokeLater: вызывающему нужен результат, чтобы решить, не пора
     * ли деградировать в ручной режим.
     */
    private fun <T> runOnEdt(block: () -> T): T {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) return block()
        var result: T? = null
        var failure: Throwable? = null
        app.invokeAndWait {
            try {
                result = block()
            } catch (e: Throwable) {
                failure = e
            }
        }
        failure?.let {
            log.warn("MetaJetCore: EDT operation failed", it)
            throw it
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private companion object {
        /**
         * Поимённый список маркеров — резервный путь ТОЛЬКО для cmd.exe, который не умеет
         * перечислить окружение одной строкой. Во всех остальных шеллах вычистка идёт по
         * префиксу, и список знать не требуется.
         *
         * Они наследуются по всей цепочке процессов. Если IDE запущена из терминала, который
         * сам живёт внутри сессии Claude Code, маркеры доезжают до спавненного агента, и он
         * ведёт себя как вложенный дочерний процесс: не сохраняет транскрипт и НЕ РЕГИСТРИРУЕТСЯ
         * в ~/.claude/sessions. Снаружи это выглядит как «агент запустился, но его нет в
         * ListAgents» — проверено живым прогоном, агент показывал
         * «Transcript saving is off — inherited CLAUDE_CODE_CHILD_SESSION marker».
         *
         * Опаснее всех три последних: сокет и токен — это инбокс РОДИТЕЛЬСКОЙ сессии, и агент
         * принял бы его за свой, сломав межсессионный обмен непредсказуемым образом.
         */
        val INHERITED_CLAUDE_MARKERS = listOf(
            "CLAUDECODE",
            "CLAUDE_CODE_CHILD_SESSION",
            "CLAUDE_CODE_ENTRYPOINT",
            "CLAUDE_CODE_EXECPATH",
            "CLAUDE_JOB_DIR",
            "CLAUDE_PID",
            "CLAUDE_AGENTS_SELECT",
            "CLAUDE_EFFORT",
            "CLAUDE_CODE_SESSION_ID",
            "CLAUDE_CODE_MESSAGING_SOCKET",
            "CLAUDE_CODE_MESSAGING_TOKEN",
        )

        const val POLL_INTERVAL_MS = 250L
        const val EXIT_TIMEOUT_MS = 15_000L
        const val TAB_READY_TIMEOUT_MS = 20_000L
    }
}
