package dev.metajetcore.agents

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.metajetcore.registry.SessionRecord
import dev.metajetcore.registry.SessionRegistry
import dev.metajetcore.roles.Role
import dev.metajetcore.roles.Roles
import dev.metajetcore.settings.MjcSettings
import dev.metajetcore.settings.RoleDelivery
import dev.metajetcore.shell.ShellDialect
import dev.metajetcore.terminal.OpenTabRequest
import dev.metajetcore.terminal.TabHandle
import dev.metajetcore.terminal.TerminalBackends
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
    data class Started(val agent: AgentInfo) : SpawnResult

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

        val briefing = buildBriefing(role, task)
        sendOnEdt(handle, briefing)

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
        return SpawnResult.Started(info)
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

    private fun dialect(): ShellDialect {
        val configured = settings.shellDialect.lowercase()
        return when (configured) {
            "posix" -> ShellDialect.POSIX
            "powershell" -> ShellDialect.POWERSHELL
            "cmd" -> ShellDialect.CMD
            "fish" -> ShellDialect.FISH
            else -> ShellDialect.detect(System.getenv("SHELL") ?: System.getenv("ComSpec"))
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

        val flags = when (settings.roleDelivery) {
            RoleDelivery.INLINE -> {
                val json = Roles.agentsJson(role, model)
                " --agents ${shell.quoteArgument(json)} --agent ${role.id}"
            }
            RoleDelivery.FLAG -> " --agent ${role.id}"
            RoleDelivery.MESSAGE -> ""
        }

        val unset = if (settings.stripApiKeys) {
            shell.unsetPrefix(listOf("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN"))
        } else {
            ""
        }

        return unset + shell.composeCommand(env, settings.launchCommand + flags)
    }

    private fun buildBriefing(role: Role, task: String): String =
        if (settings.roleDelivery == RoleDelivery.MESSAGE) {
            Roles.roleAsMessage(role) + "\n\n---\n\nЗадача: " + task
        } else {
            task
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
        const val POLL_INTERVAL_MS = 250L
        const val EXIT_TIMEOUT_MS = 15_000L
    }
}
