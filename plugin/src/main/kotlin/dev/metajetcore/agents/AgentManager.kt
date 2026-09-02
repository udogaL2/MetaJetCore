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
import dev.metajetcore.terminal.SessionTab
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

/**
 * Результат закрытия агента.
 *
 * Boolean тут не хватало: «вкладку не закрыли» и «вкладки не знаем» — разные новости для
 * оркестратора, и в первом случае агент всё-таки завершён.
 */
sealed interface CloseResult {
    /** Сессия завершена, вкладка закрыта. */
    data object Closed : CloseResult

    /** Вкладки с таким именем плагин не знает. */
    data object UnknownTab : CloseResult

    /**
     * Сессия завершена, но во вкладке остался живой процесс, поэтому вкладку не закрывали:
     * платформа спросила бы подтверждение модальным диалогом.
     */
    data class TabLeftOpen(val reason: String) : CloseResult
}

/** Результат спавна: либо агент поднят, либо команда, которую надо выполнить руками. */
sealed interface SpawnResult {
    /**
     * @param pendingBriefing текст, который оркестратор обязан отправить агенту через
     *   SendMessage. Плагин его не печатает: см. комментарий в [AgentManager.spawn].
     * @param siblings агенты той же роли, которые уже работали в проекте на момент вызова.
     *   Непустой список — не ошибка: несколько имплементеров по непересекающимся доменам
     *   это штатная схема. Но оркестратор обязан увидеть, что он только что продублировал
     *   роль, — сам он этого не проверяет (см. комментарий в [AgentManager.spawn]).
     */
    data class Started(
        val agent: AgentInfo,
        val pendingBriefing: String,
        val siblings: List<AgentInfo> = emptyList(),
    ) : SpawnResult

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

    /**
     * Сколько ждать смерти процесса вкладки перед её закрытием.
     *
     * `var` ради теста негативного пути: там процесс не умирает никогда, и с продовым
     * значением тест просто стоял бы полный таймаут.
     */
    internal var tabDeadTimeoutMs: Long = EXIT_TIMEOUT_MS

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
        // Имя родителя приводим к тому, как сессия называется в реестре: оркестратор
        // копирует его из ListAgents вместе с ref-хвостом (см. canonicalName).
        val parent = parentName?.takeIf { it.isNotBlank() }?.let { canonicalName(it) }

        // Кто этой роли уже работает — снимаем ДО открытия вкладки, иначе в списке окажется
        // и сам новичок. Спавн этим не отменяется: несколько имплементеров по
        // непересекающимся доменам — штатная схема, и запрещать её значило бы ломать
        // нормальную работу ради борьбы с дублями. Но в ответе список будет: живой прогон
        // дал четырёх имплементеров подряд, то есть сам оркестратор на живых не смотрит.
        val siblings = reusable(role)

        // Родителя разрешаем один раз: нужна и вкладка (чтобы открыть агента рядом), и
        // запись реестра (чтобы отдать наружу sessionId вместо неуникального имени).
        val parentTab = parent?.let { resolveParent(it) }
        if (parent != null && parentTab?.handle == null) {
            // Не отказ: агент нужнее, чем его место на экране. Но в логе это должно быть
            // видно — иначе «вкладка снова упала вниз» разбирается вслепую.
            log.info("MetaJetCore: вкладка родителя '$parent' не найдена, агент откроется в тулвиндоу")
        }
        val parentSession = parent?.let { parentSessionId(it, parentTab?.record) }

        val name = requestedName?.takeIf { it.isNotBlank() } ?: generateName(role, domain)
        if (SessionRegistry.isNameTaken(name)) {
            return SpawnResult.Failed(
                "имя '$name' уже занято живой сессией; передай другое или закрой ту сессию",
            )
        }

        val model = requestedModel?.takeIf { it.isNotBlank() } ?: role.defaultModel
        val manualCommand = manualCommand(role, name, model, parent, parentSession)

        if (!runOnEdt { Terminal.isAvailable(project) }) {
            return SpawnResult.Manual(manualCommand, "терминальное API IDE недоступно")
        }

        val handle = runOnEdt {
            Terminal.openTab(
                project = project,
                name = name,
                workingDirectory = projectDirectory(),
                env = agentEnv(role, name, model, parent, parentSession),
            )?.also { child -> TabPlacement.placeNear(project, parentTab?.handle, child) }
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
        return SpawnResult.Started(info, pendingBriefing = task, siblings = siblings)
    }

    // ------------------------------------------------------------- lifecycle

    /**
     * Мягкое завершение: `/exit`, дождаться исчезновения из реестра, дождаться смерти
     * процесса вкладки и только тогда её закрыть.
     *
     * Последний шаг не перестраховка. `closeTab` на вкладке с живым процессом показывает
     * модальный вопрос «в терминале что-то запущено, точно закрыть?», а вызываем мы его под
     * `invokeAndWait` — то есть диалог блокирует поток MCP-запроса, и оркестратор ждёт не
     * IDE, а человека. Поэтому закрытие зовётся только там, где спрашивать не о чем.
     *
     * Нормальный путь до диалога не доходит: строка запуска содержит хвостовой выход из
     * шелла (`ShellDialect.exitWhenDone`), так что вместе с агентом умирает и процесс
     * вкладки. Ветка [CloseResult.TabLeftOpen] остаётся для вкладок, которые плагин не
     * запускал (подхваченных по дереву процессов, перезапущенных руками), и для агента,
     * оставившего после себя живого потомка.
     */
    fun close(rawName: String): CloseResult {
        // Имя нормализуем один раз здесь: дальше оно уходит и в реестр, и в обе карты,
        // и расхождение между ними означало бы вкладку-сироту.
        val name = canonicalName(rawName)
        val handle = tabFor(name) ?: return CloseResult.UnknownTab
        runOnEdt { Terminal.send(handle, "/exit", execute = true) }
        awaitSessionGone(name)
        // Управлять больше нечем: сессии нет. Дальше речь только об уборке вкладки.
        spawned.remove(name)

        if (!awaitTabDead(handle)) {
            // Вкладку намеренно оставляем под управлением: read_tab по ней ещё работает, а
            // именно её экран объясняет, почему процесс не завершился.
            return CloseResult.TabLeftOpen(
                "сессия '$name' завершена, но во вкладке остался живой процесс, поэтому " +
                    "вкладка не закрыта: IDE спросила бы подтверждение. Посмотри её экран " +
                    "(read_tab) и закрой вкладку руками",
            )
        }

        runOnEdt { Terminal.closeTab(project, handle) }
        tabs.remove(name)
        return CloseResult.Closed
    }

    /** Сброс контекста между этапами: сессия и вкладка остаются живыми. */
    fun reset(rawName: String): Boolean {
        val handle = tabFor(rawName) ?: return false
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
    fun brief(rawName: String, text: String): BriefResult {
        val nonAscii = text.filter { it.code >= 128 }
        if (nonAscii.isNotEmpty()) return BriefResult.NotAscii(nonAscii.toSet().joinToString(""))
        val handle = tabFor(rawName) ?: return BriefResult.UnknownTab
        return if (runOnEdt { Terminal.send(handle, text, execute = true, paste = true) }) {
            BriefResult.Sent
        } else {
            BriefResult.UnknownTab
        }
    }

    /** Что сейчас на экране вкладки агента. Для разбора «запустился, но молчит». */
    fun readScreen(rawName: String): String? {
        val handle = tabFor(rawName) ?: return null
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
    private fun tabFor(rawName: String): TabHandle? {
        val name = canonicalName(rawName)
        tabs[name]?.let { return it }
        val resolved = runOnEdt { TabResolver.findTabForSession(project, name) } ?: return null
        tabs[name] = resolved
        return resolved
    }

    /**
     * Вкладка родителя вместе с его записью в реестре.
     *
     * Отдельно от [tabFor] ради записи: карта вкладок её не хранит, а для связи
     * «агент → оркестратор» нужна именно она — см. [parentSessionId].
     */
    private fun resolveParent(name: String): SessionTab? {
        val resolved = runOnEdt { TabResolver.resolve(project, name) } ?: return null
        tabs[name] = resolved.handle
        return resolved
    }

    /**
     * `sessionId` оркестратора — то, чем связь «агент → его оркестратор» задаётся на самом
     * деле.
     *
     * Имя для этого не годится, и это не придирка: имя задаёт человек, оно не уникально
     * (замер: три живые записи реестра с именем «слитие мастера») и меняется по ходу работы.
     * `sessionId` уникален и переживает resume — у тех же трёх записей он один и тот же.
     *
     * Источник по убыванию надёжности:
     *
     *  1. Запись, по которой нашлась вкладка родителя. Она получена сопоставлением pid, то
     *     есть привязана к конкретному процессу `claude`, — единственный способ развести
     *     тёзок.
     *  2. Реестр по имени, но **только если ответ однозначен**: все записи с этим именем
     *     дают один `sessionId`. Иначе — null: отсутствие связи честнее, чем связь наугад,
     *     потому что наугад означает чужую команду на экране наблюдателя.
     */
    internal fun parentSessionId(name: String, matched: SessionRecord? = null): String? {
        matched?.sessionId?.takeIf { it.isNotBlank() }?.let { return it }
        val ids = SessionRegistry.byName(name).map { it.sessionId }.filter { it.isNotBlank() }.distinct()
        return if (ids.size == 1) {
            ids.first()
        } else {
            if (ids.size > 1) {
                log.info("MetaJetCore: имя '$name' носят ${ids.size} разные сессии, родитель не определён")
            }
            null
        }
    }

    /**
     * Имя сессии из строки, которую дал оркестратор.
     *
     * `ListAgents` печатает каждую сессию как `слитие мастера [815818]`, и его контракт велит
     * копировать имя ровно так, как напечатано, — оркестратор так и делает. Но хвост в
     * скобках это ref платформы, а не часть имени: в реестре сессия называется
     * «слитие мастера». Ref ниоткуда не выводится — ни из pid, ни из sessionId (замер:
     * сессия `metajetcore-df`, sessionId `6e65d7ec-…`, ref `ebe653`), — то есть опознать по
     * нему нечего, его можно только снять.
     *
     * Пока хвост не снимался, ломалось всё, что опирается на имя, и ломалось молча: вкладка
     * родителя не находилась, поэтому агент открывался в тулвиндоу вместо места рядом с
     * оркестратором, а `MJC_PARENT` уезжал наблюдателю в виде, который тот не сопоставит ни
     * с одной живой сессией — дерево команды не собиралось вовсе.
     *
     * Снимаем только тогда, когда имени с хвостом в реестре нет: сессия вправе называть себя
     * как угодно, включая скобки на конце.
     */
    internal fun canonicalName(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || SessionRegistry.isNameTaken(trimmed)) return trimmed
        val bare = REF_SUFFIX.replace(trimmed, "").trim()
        return bare.ifEmpty { trimmed }
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
     * Хвостового выхода из шелла (`ShellDialect.exitWhenDone`) здесь намеренно нет, хотя в
     * строке запуска агентов он есть. Это вкладка оркестратора: её закрывает человек, а не
     * `close_agent`, и после выхода из claude он обычно остаётся в шелле — например чтобы
     * запустить сессию заново.
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

    fun focus(rawName: String): Boolean {
        val handle = tabFor(rawName) ?: return false
        return runOnEdt { Terminal.focus(handle) }
    }

    /**
     * Живые агенты этой роли в проекте — те, кому задачу можно отдать вместо нового спавна.
     *
     * Оркестратор в списке не участвует: он один и не заменяем. Занятость агента тоже не
     * смотрим — `busy` означает «сейчас думает», а не «занят другой задачей»: сообщение всё
     * равно встанет в его очередь.
     */
    fun reusable(role: Role): List<AgentInfo> =
        if (role == Role.ORCHESTRATOR) emptyList()
        else list().filter { it.role == role }

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
        parentSessionId: String? = null,
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
        // Кто завёл этого агента. Связь знает только плагин и только здесь: в реестре Claude
        // Code поля «родитель» нет вовсе, а сторонним наблюдателям дерево команды иначе
        // приходится угадывать по общему префиксу имени — на двух командах в одном проекте
        // догадка разваливается.
        //
        // Две переменные, и это не дубль. `MJC_PARENT_SESSION` — настоящий ключ: sessionId
        // уникален и переживает resume. `MJC_PARENT` — имя, оно для человека и для старых
        // получателей; **ключом оно быть не может**, потому что не уникально (замер: три
        // живые записи реестра с именем «слитие мастера») и меняется по ходу работы.
        //
        // Ключа может не быть, даже когда имя есть: родителя не удалось определить
        // однозначно (см. parentSessionId). Тогда наблюдатель обязан считать связь
        // неизвестной, а не достраивать её по имени.
        parentName?.takeIf { it.isNotBlank() }?.let { env["MJC_PARENT"] = it.take(MAX_PARENT_NAME) }
        parentSessionId?.takeIf { it.isNotBlank() }?.let { env["MJC_PARENT_SESSION"] = it }
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
        shell.unsetNames(inheritedMarkers()) + commandWithFlags(role) + shell.exitWhenDone()

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
     *
     * Хвостового выхода из шелла здесь тоже нет: он существует ради автоматического
     * закрытия вкладки, а человеку закрыл бы его собственный шелл.
     */
    internal fun manualCommand(
        role: Role,
        name: String,
        model: String,
        parentName: String? = null,
        parentSessionId: String? = null,
    ): String {
        val shell = ShellDialect.detect(System.getenv("SHELL"))
        val env = agentEnv(role, name, model, parentName, parentSessionId)
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

    /**
     * Ждём, пока во вкладке не останется живого процесса: ни самой сессии, ни её потомков.
     *
     * Признак основной — состояние сессии вкладки. `hasChildProcesses` добавлен как второй:
     * агент мог оставить после себя живой процесс, и тогда платформа спросит подтверждение
     * даже при мёртвой сессии. Недоступный признак (null) не считаем отрицательным ответом —
     * решает тогда состояние сессии.
     */
    private fun awaitTabDead(handle: TabHandle): Boolean {
        val deadline = System.currentTimeMillis() + tabDeadTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            val dead = runOnEdt {
                Terminal.isTerminated(handle) && Terminal.hasChildProcesses(handle) != true
            }
            if (dead) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        log.warn("MetaJetCore: во вкладке '${handle.name}' остался живой процесс после /exit")
        return false
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

        /**
         * Хвост, которым `ListAgents` дописывает к имени сессии её ref: `имя [815818]`.
         *
         * Ref — шестнадцатеричный, длину не фиксируем: она может подрасти, когда одного
         * префикса перестанет хватать на различение.
         */
        val REF_SUFFIX = Regex("""\s*\[[0-9a-fA-F]{4,32}]${'$'}""")
    }
}
