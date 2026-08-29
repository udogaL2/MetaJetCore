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
import dev.metajetcore.terminal.TabHandle
import dev.metajetcore.terminal.TabPlacement
import dev.metajetcore.terminal.TabResolver
import dev.metajetcore.terminal.Terminal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/** Что плагин знает про запущенного агента. */
data class AgentInfo(
    val name: String,
    /** null — сессию плагин не заводил и роль из реестра не выводится. */
    val role: Role?,
    val model: String,
    val sessionId: String?,
    val pid: Int?,
    val status: String?,
    val managed: Boolean,
)

/** Результат печати текста во вкладку. */
sealed interface BriefResult {
    data object Sent : BriefResult

    /** Вкладки с таким именем плагин не знает и сопоставить её не смог. */
    data object UnknownTab : BriefResult

    /** В тексте есть символы, которые терминал исказит. Отправлять такое нельзя. */
    data class NotAscii(val offending: String) : BriefResult
}

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

    /**
     * Куда агенты кладут развёрнутые отчёты: `~/.claude/metajetcore/<проект>/reports`.
     *
     * Вне репозитория намеренно: иначе каждый новый проект требовал бы строки в .gitignore,
     * а следы работы агентов засоряли бы историю.
     */
    fun reportsDirectory(): Path {
        val home = System.getenv("CLAUDE_CONFIG_DIR")?.takeIf { it.isNotBlank() }
            ?.let { Paths.get(it) }
            ?: Paths.get(System.getProperty("user.home"), ".claude")
        val dir = home.resolve("metajetcore").resolve(sanitize(project.name)).resolve("reports")
        runCatching { Files.createDirectories(dir) }
            .onFailure { log.warn("MetaJetCore: не удалось создать $dir", it) }
        return dir
    }

    private fun sanitize(name: String): String =
        name.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
            .joinToString("")
            .trim('-')
            .ifBlank { "project" }

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
        val manualCommand = manualCommand(role, name, model, parentName)

        if (!runOnEdt { Terminal.isAvailable(project) }) {
            return SpawnResult.Manual(manualCommand, "терминальное API IDE недоступно")
        }

        val parentTab = parentName?.let { tabFor(it) }
        val handle = runOnEdt {
            Terminal.openTab(
                project = project,
                name = name,
                workingDirectory = projectDirectory(),
                env = agentEnv(role, name, model, parentName),
            )?.also { child -> TabPlacement.placeNear(project, parentTab, child) }
        } ?: return SpawnResult.Manual(manualCommand, "не удалось открыть вкладку терминала")

        tabs[name] = handle

        if (!awaitTabRunning(handle)) {
            return SpawnResult.Manual(
                manualCommand,
                "шелл во вкладке не поднялся за ${TAB_READY_TIMEOUT_MS / 1000} c",
            )
        }

        // Диалект — по фактической командной строке шелла этой вкладки. Настройка терминала
        // у большинства пуста, а окружение процесса IDE описывает не тот шелл, который IDE
        // открывает во вкладке: раньше на этом в PowerShell уезжал синтаксис cmd.
        val shell = ShellDialect.detect(runOnEdt { Terminal.process(handle) }?.shellCommand?.firstOrNull())
        val line = launchLine(role, shell)

        if (!runOnEdt { Terminal.send(handle, line, execute = true) }) {
            return SpawnResult.Manual(manualCommand, "вкладка открыта, но команду напечатать не удалось")
        }

        val record = awaitSession(name)
            ?: return SpawnResult.Manual(
                manualCommand,
                "сессия не появилась в реестре за ${settings.readyTimeoutSeconds} c; " +
                    "проверь команду запуска в настройках плагина",
            )

        // Имя могло измениться: при коллизии Claude Code переименовывает сессию в вариант.
        val actualName = record.name ?: name
        if (actualName != name) {
            tabs.remove(name)
            tabs[actualName] = handle
        }

        // Задачу в терминал НЕ печатаем, хотя технически теперь можем.
        //
        // Содержательный канал в этой схеме ровно один — межсессионные сообщения: они
        // адресные, UTF-8-безопасные, доставляются в очередь агента и не зависят от того,
        // что сейчас на экране вкладки. Печать в TUI осталась только для управляющих команд
        // (/exit, /clear) и аварийного brief_agent.
        val info = AgentInfo(
            name = actualName,
            role = role,
            model = model,
            sessionId = record.sessionId,
            pid = record.pid,
            status = record.status,
            managed = true,
        )
        spawned[actualName] = info
        return SpawnResult.Started(info, pendingBriefing = task)
    }

    // ------------------------------------------------------------- lifecycle

    /** Мягкое завершение: /exit, дождаться исчезновения из реестра, закрыть вкладку. */
    fun close(name: String): Boolean {
        val handle = tabFor(name) ?: return false
        runOnEdt { Terminal.send(handle, "/exit", execute = true) }
        awaitSessionGone(name)
        runOnEdt { Terminal.closeTab(project, handle) }
        tabs.remove(name)
        spawned.remove(name)
        return true
    }

    /** Сброс контекста между этапами: сессия и вкладка остаются живыми. */
    fun reset(name: String): Boolean {
        val handle = tabFor(name) ?: return false
        return runOnEdt { Terminal.send(handle, "/clear", execute = true) }
    }

    /**
     * Впечатать текст во вкладку как ввод пользователя.
     *
     * Аварийный канал: обычная переписка идёт через SendMessage. Текст уходит вставкой
     * (bracketed paste), поэтому многострочный текст не разъезжается по строкам ввода TUI.
     *
     * Только ASCII, и это не перестраховка. Живой прогон на 2026.2: отправили
     * «Ответь ровно одним словом: ПРОБА-OK», в TUI приехало
     * «❯ ������ ����� ����� ������: �����-OK», и агент ответил на выдуманный текст.
     * Терминал пишет в pty не в UTF-8, а Claude Code читает stdin как UTF-8; ответ агента при
     * этом отрисовался кириллицей верно, то есть портится именно наш ввод. Молча искажать
     * текст хуже, чем отказаться: искажение выглядит как исполненная команда.
     */
    fun brief(name: String, text: String): BriefResult {
        val nonAscii = text.filter { it.code >= 128 }
        if (nonAscii.isNotEmpty()) return BriefResult.NotAscii(nonAscii.toSet().joinToString(""))
        val handle = tabFor(name) ?: return BriefResult.UnknownTab
        return if (runOnEdt { Terminal.send(handle, text, execute = true, paste = true) }) {
            BriefResult.Sent
        } else {
            BriefResult.UnknownTab
        }
    }

    /** Что сейчас на экране вкладки агента. Для разбора «запустился, но молчит». */
    fun readScreen(name: String): String? {
        val handle = tabFor(name) ?: return null
        return runOnEdt { Terminal.readScreen(handle) }
    }

    /**
     * Вкладка по имени сессии: сперва своя карта, затем поиск по дереву процессов.
     *
     * Второй путь нужен, чтобы запуск оркестратора оставался ровно `/orchestrate` в любой
     * вкладке. Плагин знает только те вкладки, которые создал сам; всё остальное он
     * доопределяет сопоставлением pid — см. [TabResolver]. Найденное запоминаем, чтобы не
     * искать заново на каждый вызов.
     */
    private fun tabFor(name: String): TabHandle? {
        tabs[name]?.let { return it }
        val resolved = runOnEdt { TabResolver.findTabForSession(project, name) } ?: return null
        tabs[name] = resolved
        return resolved
    }

    /** Имена вкладок, которые ведёт плагин, — включая те, где спавн не доехал. */
    fun knownTabs(): Set<String> = tabs.keys.toSet()

    /**
     * Состояние терминала для диагностики.
     *
     * Через runOnEdt, а не напрямую: MCP-запросы приходят на своих потоках, а перечисление
     * вкладок и выбранного редактора — это чтение UI. Мимо EDT оно в лучшем случае врёт
     * (`currentWindow` отдавал null, из-за чего диагностика показывала «активной вкладки
     * нет» при открытом терминале), в худшем — падает на проверке потока.
     */
    fun describeTerminal(): String = runOnEdt { Terminal.describe(project) }

    /** Запомнить вкладку, открытую действием плагина (оркестратор). */
    fun register(name: String, handle: TabHandle) {
        tabs[name] = handle
    }

    /**
     * Запустить обычную сессию Claude Code в уже открытой вкладке — без роли и без флагов.
     *
     * Ждать шелл приходится в фоне: действие вызывается из EDT, а между созданием вкладки и
     * стартом процесса проходит заметное время, и напечатанная в этот промежуток строка
     * теряется молча. Диалект берём по фактическому процессу этой вкладки — до его старта он
     * неизвестен, а угадывать нельзя: во вкладку с PowerShell уезжал синтаксис cmd.
     */
    fun startPlainSession(handle: TabHandle) {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!awaitTabRunning(handle)) {
                log.warn("MetaJetCore: шелл во вкладке '${handle.name}' не поднялся, команда не напечатана")
                return@executeOnPooledThread
            }
            val shell = ShellDialect.detect(runOnEdt { Terminal.process(handle) }?.shellCommand?.firstOrNull())
            val line = shell.unsetNames(inheritedMarkers()) + settings.launchCommand
            runOnEdt { Terminal.send(handle, line, execute = true) }
        }
    }

    fun focus(name: String): Boolean {
        val handle = tabFor(name) ?: return false
        return runOnEdt { Terminal.focus(handle) }
    }

    /** Живые сессии в директории проекта, обогащённые тем, что плагин знает про вкладки. */
    fun list(): List<AgentInfo> =
        SessionRegistry.inDirectory(projectDirectory()).map { record ->
            val name = record.name.orEmpty()
            val known = spawned[name]
            AgentInfo(
                name = name,
                role = known?.role ?: record.agent?.let { Role.fromId(it) },
                model = known?.model ?: "",
                sessionId = record.sessionId,
                pid = record.pid,
                status = record.status,
                managed = tabs.containsKey(name),
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

    /**
     * Окружение агента. Уезжает через API терминала, а не в командной строке: так не нужны
     * ни кавычки, ни диалекты шеллов, ни ASCII-ограничение на значения.
     */
    internal fun agentEnv(
        role: Role,
        name: String,
        model: String,
        parentName: String? = null,
    ): Map<String, String> {
        val env = LinkedHashMap<String, String>()
        // Имя сессии — это адрес для SendMessage. Без него имя выводится платформой из имени
        // проекта, и агентов одного проекта не различить.
        env["CLAUDE_CODE_SESSION_NAME"] = name
        env["ANTHROPIC_MODEL"] = model
        // Метка для реестра: роль этим НЕ задаётся (проверено), но по ней list() узнаёт роль
        // сессий, которые плагин не создавал.
        env["CLAUDE_CODE_AGENT"] = role.id
        // Каталог для развёрнутых отчётов — ВНЕ репозитория, иначе в каждом проекте
        // пришлось бы добавлять строку в .gitignore, а артефакты агентов там не нужны.
        env["MJC_REPORTS_DIR"] = reportsDirectory().toString().replace(BACKSLASH, '/')
        // Имя оркестратора, который завёл этого агента. Плагин знает его только здесь и
        // сейчас: карта вкладок родителя не хранит, а в реестре Claude Code такого поля нет
        // вовсе. Читают переменную сторонние наблюдатели, чтобы построить дерево команды
        // точно, а не догадкой по общему префиксу имени — она разваливается на двух командах
        // в одном проекте. Имя передаём как есть: получатель сравнивает его с именем сессии
        // из реестра, и любая нормализация здесь сломала бы сопоставление.
        parentName?.takeIf { it.isNotBlank() }?.let { env["MJC_PARENT"] = it.take(MAX_PARENT_NAME) }
        env.putAll(settings.extraEnv())
        return env
    }

    /**
     * Окружение вкладки оркестратора.
     *
     * Роль здесь только меткой: `CLAUDE_CODE_AGENT` её НЕ применяет (§2.2.1), оркестратором
     * сессия становится по вызову `/orchestrate`. Метка нужна, чтобы оркестратора было видно
     * снаружи — и нам в `list_agents`, и сторонним наблюдателям. Без неё его опознают только
     * по имени вида `<префикс>-orc`, а имя разработчик волен задать любое.
     */
    internal fun orchestratorEnv(name: String): Map<String, String> = linkedMapOf(
        "CLAUDE_CODE_SESSION_NAME" to name,
        "CLAUDE_CODE_AGENT" to Role.ORCHESTRATOR.id,
    )

    /**
     * Строка, которую плагин печатает во вкладку. Только ASCII и только то, что нельзя
     * задать окружением.
     *
     * Роль — флагом `--agent`: через `CLAUDE_CODE_AGENT` она пишется в реестр, но не
     * применяется (проверено). Режим прав — флагом `--permission-mode`: `permissions.
     * defaultMode` из настроек проекта Claude Code игнорирует, а без режима агент встанет на
     * первом же запросе прав во вкладке, которую никто не читает.
     */
    internal fun launchLine(role: Role, shell: ShellDialect): String =
        shell.unsetNames(inheritedMarkers()) + commandWithFlags(role)

    private fun commandWithFlags(role: Role): String {
        val roleFlag = " --agent ${RoleInstaller.agentName(role)}"
        val modeFlag = settings.permissionMode
            .trim()
            .takeIf { it.isNotEmpty() && it.all { ch -> ch.isLetter() } }
            ?.let { " --permission-mode $it" }
            .orEmpty()
        return settings.launchCommand + roleFlag + modeFlag
    }

    /**
     * Команда для ручного режима: разработчик выполняет её сам, поэтому окружение здесь
     * приходится вписывать в строку — API вкладки в этом сценарии не участвует.
     *
     * Порядок частей важен: сначала снять унаследованное, потом задать своё. Иначе
     * присваивания достанутся команде `unset`, а до `claude` не доедут.
     *
     * Форма присваиваний — POSIX: команда предназначена человеку, а он выполнит её в том
     * шелле, где ему удобно. Для полностью корректного ручного запуска есть
     * `scripts/spawn-agent.sh`.
     */
    internal fun manualCommand(
        role: Role,
        name: String,
        model: String,
        parentName: String? = null,
    ): String {
        val shell = ShellDialect.detect(System.getenv("SHELL"))
        val env = agentEnv(role, name, model, parentName)
            .entries.joinToString(" ") { (key, value) -> "$key='$value'" }
        return shell.unsetNames(inheritedMarkers()) + env + " " + commandWithFlags(role)
    }

    /**
     * Маркеры Claude Code, которые реально протекли в процесс IDE, — их и снимаем.
     *
     * Список имён здесь не зашит намеренно: он молча устарел бы, стоит Claude Code завести
     * новую переменную. Мы вместо этого смотрим на окружение самой IDE: агент наследует
     * именно его, значит вычистить надо ровно то, что там лежит.
     *
     * Зачем вообще: если IDE запущена из терминала, который сам живёт внутри сессии Claude
     * Code, маркеры доезжают до агента по цепочке процессов. `CLAUDE_CODE_CHILD_SESSION`
     * заставляет агента считать себя вложенным и не регистрироваться в реестре (снаружи это
     * выглядит как «процесс есть, а в ListAgents его нет»), а `CLAUDE_CODE_MESSAGING_SOCKET`
     * и `..._TOKEN` — это инбокс РОДИТЕЛЬСКОЙ сессии. `ANTHROPIC_API_KEY` молча уводит с
     * подписки на API-биллинг.
     */
    internal fun inheritedMarkers(): List<String> {
        val prefixes = buildList {
            if (settings.stripInheritedClaudeMarkers) add("CLAUDE")
            if (settings.stripApiKeys) add("ANTHROPIC_")
        }
        if (prefixes.isEmpty()) return emptyList()
        return System.getenv().keys
            .filter { key -> prefixes.any { key.startsWith(it) } }
            // Имя переменной, вставленное в командную строку, обязано быть безобидным.
            .filter { key -> key.all { it.isLetterOrDigit() || it == '_' } }
            .sorted()
    }

    /** Ждём, пока во вкладке поднимется шелл: до этого печатать бессмысленно. */
    private fun awaitTabRunning(handle: TabHandle): Boolean {
        val deadline = System.currentTimeMillis() + TAB_READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (runOnEdt { Terminal.isRunning(handle) }) return true
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
        log.warn("MetaJetCore: сессия '$name' осталась в реестре после /exit")
    }

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
            log.warn("MetaJetCore: операция в EDT упала", it)
            throw it
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private companion object {
        val BACKSLASH: Char = 92.toChar()

        /** Длина, по которой имя родителя режет приёмник; режем сами, чтобы совпадало. */
        const val MAX_PARENT_NAME = 64

        const val POLL_INTERVAL_MS = 250L
        const val EXIT_TIMEOUT_MS = 15_000L
        const val TAB_READY_TIMEOUT_MS = 20_000L

    }
}
