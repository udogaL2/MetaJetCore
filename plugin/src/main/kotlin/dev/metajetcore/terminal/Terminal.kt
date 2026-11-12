package dev.metajetcore.terminal

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.content.Content
import java.nio.file.Path

/**
 * Ссылка на вкладку терминала, которой управляет плагин.
 *
 * Платформенные объекты держим как Any: типы живут в модуле `intellij.terminal.frontend` и
 * помечены `@Experimental`, поэтому в сигнатурах плагина их быть не должно — иначе смена
 * API превращается в NoSuchMethodError вместо мягкой деградации.
 */
class TabHandle(
    val name: String,
    /**
     * Вкладка тулвиндоу. null — терминал живёт в editor area: там вкладки платформы нет,
     * есть файл и его редактор.
     */
    internal val tab: Any?,
    internal val view: Any,
    val content: Content?,
) {
    /**
     * Файл, под которым вкладка открыта в editor area, если её туда перенесли.
     * По нему находится окно родителя, когда рядом нужно открыть агента.
     */
    @Volatile
    var editorFile: VirtualFile? = null

    /**
     * Процесс вкладки — сам агент, запущенный плагином (NON_SHELL, см. [Terminal.openTab]).
     *
     * От этого зависит, можно ли его добить при закрытии. Агента, которого запустил плагин,
     * — можно и нужно: окно не должно висеть. Вкладку, подхваченную по дереву процессов, —
     * нельзя: её процесс это шелл разработчика, а не наш агент.
     */
    @Volatile
    var ownsProcess: Boolean = false

    /** Тот же терминал под именем сессии, которую в нём нашли. */
    fun renamed(newName: String): TabHandle =
        TabHandle(newName, tab, view, content).also {
            it.editorFile = editorFile
            it.ownsProcess = ownsProcess
        }
}

/** Что известно про процесс во вкладке: pid шелла и командная строка запуска. */
data class TabProcess(val pid: Long?, val shellCommand: List<String>)

/**
 * Терминал IDE, начиная с 2026.3 (переработанный движок).
 *
 * Работаем через `com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager` —
 * это и есть API нового терминала. Поколения G1/G2 (JediTerm-виджеты `ShellTerminalWidget`,
 * `TerminalToolWindowManager.createLocalShellWidget`) выброшены: в 2026.2 первый путь бросает
 * `toShellJediTermWidgetOrThrow`, а второй отдаёт виджет, в который нечем печатать.
 * Нижняя граница — 2026.3: там появились API агентов терминала и NON_SHELL-вкладки с
 * `restoreOnProjectReopen`, на которых держится запуск агентов.
 *
 * Почему рефлексия, если плагин и так собирается против 2026.3: классы модуля помечены
 * `@ApiStatus.Experimental`, сигнатуры между версиями меняются. Прямой вызов при смене
 * сигнатуры даёт NoSuchMethodError в рантайме, рефлексия — null и переход в ручной режим.
 * Зависимость на модуль в plugin.xml нужна только чтобы classloader вообще видел эти классы.
 *
 * Все методы обязаны вызываться в EDT: этим занимается AgentManager.
 */
object Terminal {
    private val log = Logger.getInstance(Terminal::class.java)

    private const val TABS_MANAGER = "com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager"
    private const val VIEW_KT = "com.intellij.terminal.frontend.view.TerminalViewKt"
    private const val TERMINAL_VIEW_FILE = "com.intellij.terminal.frontend.editor.TerminalViewVirtualFile"

    /**
     * Вкладка AIR (Agent Threads, `plugins/air-plugin`): сессия Claude Code в своём редакторе,
     * а не в терминале. Плагин AIR внутренний и не наша зависимость — только по имени.
     */
    private const val AIR_THREAD_FILE = "com.intellij.air.thread.view.AgentThreadViewContentFile"
    private const val PROCESS_TYPE = "org.jetbrains.plugins.terminal.startup.TerminalProcessType"
    private const val TERMINAL_AGENT = "org.jetbrains.plugins.terminal.agent.TerminalAgent"
    private const val OPTIONS_PROVIDER = "org.jetbrains.plugins.terminal.TerminalOptionsProvider"

    /** Сколько символов экрана отдаём наружу: больше оркестратору не нужно, а контекст дорог. */
    private const val SCREEN_LIMIT = 4_000L

    fun isAvailable(project: Project): Boolean = manager(project) != null

    /**
     * Новая вкладка терминала.
     *
     * Переменные окружения уезжают через API билдера, а не внутри печатаемой строки: так
     * отпадают экранирование, кавычки и разница диалектов шеллов.
     *
     * [command] задан — вкладка запускает **этот процесс вместо интерактивного шелла**
     * (`processType(NON_SHELL)` + `shellCommand`). Ровно так 2026.3 сама открывает Claude Code и
     * Codex из меню терминала (`TerminalAgentsSupportKt.launchTerminalAgent`), и это решает
     * сразу три старые беды:
     *  - argv уходит в API создания процесса, а не печатается в pty — кодировка JVM его не
     *    портит, ограничение «только ASCII» для строки запуска снимается;
     *  - процесс вкладки — сам агент: он умер — вкладка закрылась (`closeOnProcessTermination`),
     *    и в тулвиндоу, и в editor area (`TerminalViewFileEditor` закрывает себя сам ровно по
     *    этому флагу вкладки; при `false` окно редактора висело с мёртвым терминалом);
     *  - нет шелла, который пережил бы агента, значит нет и модального «в терминале что-то
     *    запущено» при закрытии.
     *
     * [command] не задан — обычная вкладка с шеллом (оркестратор, ручной режим): её закрывает
     * человек, и после выхода из claude он обычно остаётся в шелле.
     */
    fun openTab(
        project: Project,
        name: String,
        workingDirectory: Path,
        env: Map<String, String>,
        requestFocus: Boolean = false,
        command: List<String>? = null,
    ): TabHandle? {
        val manager = manager(project) ?: return null
        return try {
            val builder = call(manager, "createTabBuilder") ?: return null
            chain(builder, "tabName", String::class.java, name)
            // Иначе заголовок вкладки переписывается заголовком процесса (claude ставит свой),
            // а по заголовку мы находим вкладку, когда она не в нашей карте.
            chain(builder, "userDefinedName", java.lang.Boolean.TYPE, true)
            chain(builder, "workingDirectory", String::class.java, workingDirectory.toString())
            chain(builder, "envVariables", Map::class.java, env)
            chain(builder, "requestFocus", java.lang.Boolean.TYPE, requestFocus)
            // Сессия должна стартовать сразу: мы ждём появления записи в реестре, а вкладка
            // может быть не показана, если разработчик смотрит в другую.
            chain(builder, "deferSessionStartUntilUiShown", java.lang.Boolean.TYPE, false)
            if (command != null) {
                processType("NON_SHELL")?.let { chain(builder, "processType", it.javaClass, it) }
                chain(builder, "shellCommand", List::class.java, command)
                chain(builder, "closeOnProcessTermination", java.lang.Boolean.TYPE, true)
                // 2026.3 восстанавливает вкладки при повторном открытии проекта. Агента это не
                // вернёт — вернётся пустой процесс без сессии и роли, то есть мусор на экране.
                chain(builder, "restoreOnProjectReopen", java.lang.Boolean.TYPE, false)
            } else {
                chain(builder, "closeOnProcessTermination", java.lang.Boolean.TYPE, false)
            }

            val tab = call(builder, "createTab") ?: return null
            handleOf(name, tab)?.also { it.ownsProcess = command != null }
        } catch (e: Throwable) {
            log.warn("MetaJetCore: не удалось открыть вкладку терминала", e)
            null
        }
    }

    /** Константа enum `TerminalProcessType` по имени; null — в этой версии API её нет. */
    private fun processType(name: String): Any? =
        loadClass(PROCESS_TYPE)?.enumConstants?.firstOrNull { (it as Enum<*>).name == name }

    /**
     * Иконка агента на вкладке — та же, что ставит сама IDE (`TerminalAgent.icon`).
     *
     * Список агентов терминала — API 2026.3 (`terminalAgentProvider`). Не нашёлся агент или
     * API сменилось — вкладка просто останется без иконки. Ключ — `agentKey` IDE:
     * `claude_code`, `codex`.
     */
    fun setAgentIcon(handle: TabHandle, agentKey: String) {
        val icon = agentIcon(agentKey) ?: return
        handle.content?.icon = icon
    }

    /** Иконка агента терминала IDE по ключу; null — агента нет или API сменилось. */
    fun agentIcon(agentKey: String): javax.swing.Icon? = try {
        terminalAgents().firstOrNull { agentKeyOf(it) == agentKey }
            ?.let { call(it, "getIcon") as? javax.swing.Icon }
    } catch (e: Throwable) {
        log.debug("MetaJetCore: иконку агента '$agentKey' получить не вышло", e)
        null
    }

    /** Агенты, которых знает терминал IDE. Пусто — API сменилось. */
    fun terminalAgents(): List<Any> = try {
        loadClass(TERMINAL_AGENT)?.methods
            ?.firstOrNull { it.name == "getAllTerminalAgents" && it.parameterCount == 0 }
            ?.invoke(null)
            .let { (it as? Collection<*>)?.filterNotNull() ?: emptyList() }
    } catch (e: Throwable) {
        log.debug("MetaJetCore: список агентов терминала недоступен", e)
        emptyList()
    }

    /**
     * `agentKey` агента. Это Kotlin value class, поэтому геттер в байткоде с суффиксом
     * манглинга (`getAgentKey-LamVg8w`) и возвращает уже распакованную строку. Суффикс между
     * версиями может смениться — ищем по префиксу.
     */
    /**
     * Ключи агентов, которых знает терминал IDE: `claude_code`, `codex`, `junie`, сторонние.
     * Пусто — API нет (не 263+) или поменялось; тогда плагин работает только с Claude Code.
     */
    fun terminalAgentKeys(): List<String> = terminalAgents().mapNotNull { agentKeyOf(it) }

    fun agentKeyOf(agent: Any): String? = try {
        agent.javaClass.methods
            .firstOrNull { it.name.startsWith("getAgentKey") && it.parameterCount == 0 }
            ?.also { it.isAccessible = true }
            ?.invoke(agent)?.toString()
    } catch (_: Throwable) {
        null
    }

    /**
     * Шелл, который IDE открывает во вкладках, — из настроек терминала.
     *
     * Пустая настройка — обычное дело: тогда IDE берёт `$SHELL`, и мы тоже.
     */
    fun configuredShellPath(project: Project): String? = try {
        val cls = loadClass(OPTIONS_PROVIDER)
        val provider = cls?.methods?.firstOrNull { it.name == "getInstance" && it.parameterCount == 0 }
            ?.invoke(null)
            ?: cls?.let { c -> runCatching { c.getField("Companion").get(null) }.getOrNull() }
                ?.let { call(it, "getInstance") }
        (provider?.let { call(it, "getShellPath") } as? String)?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }

    /** Хендл по объекту вкладки платформы. */
    fun handleOf(name: String, tab: Any): TabHandle? {
        val view = call(tab, "getView") ?: return null
        val content = call(tab, "getContent") as? Content
        return TabHandle(name = name, tab = tab, view = view, content = content)
    }

    /**
     * Снимает вкладку с тулвиндоу, не трогая сессию, — перед переносом в editor area.
     *
     * Без этого в тулвиндоу оставалась пустая вкладка с именем агента («Nothing to show»):
     * `TerminalInEditorSupport.openInEditor` открывает терминал в редакторе, но вкладку не
     * убирает — при перетаскивании мышью это делает сам drag-and-drop. Штатный «Move to Editor»
     * (`MoveTerminalSessionToEditorAction`) поэтому сначала зовёт `detachTab`, а потом
     * открывает файл; повторяем ровно этот порядок (проверено по байткоду 263.7307).
     *
     * Возвращает менеджер содержимого, из которого вкладку сняли, — чтобы вернуть её туда,
     * если в редактор она не попадёт. null — снимать нечего или API нет: тогда перенос идёт
     * как раньше, с пустой вкладкой внизу, но без поломки.
     */
    fun detachFromToolWindow(project: Project, handle: TabHandle): Any? {
        val tab = handle.tab ?: return null
        val contentManager = handle.content?.manager ?: return null
        val manager = manager(project) ?: return null
        return try {
            val method = manager.javaClass.methods.firstOrNull { it.name == "detachTab" && it.parameterCount == 1 }
                ?: return null
            method.isAccessible = true
            method.invoke(manager, tab)
            contentManager
        } catch (e: Throwable) {
            log.info("MetaJetCore: снять '${handle.name}' с тулвиндоу не вышло", e)
            null
        }
    }

    /** Возвращает снятую вкладку в тулвиндоу: перенос в редактор не удался, а агент жив. */
    fun reattachToToolWindow(project: Project, handle: TabHandle, contentManager: Any) {
        val tab = handle.tab ?: return
        val manager = manager(project) ?: return
        try {
            manager.javaClass.methods
                .firstOrNull { it.name == "attachTab" && it.parameterCount == 2 }
                ?.also { it.isAccessible = true }
                ?.invoke(manager, tab, contentManager)
        } catch (e: Throwable) {
            log.warn("MetaJetCore: вернуть '${handle.name}' в тулвиндоу не вышло — вкладка без окна", e)
        }
    }

    /** Вкладки терминала в тулвиндоу. */
    fun tabs(project: Project): List<Any> {
        val manager = manager(project) ?: return emptyList()
        return (call(manager, "getTabs") as? Collection<*>)?.filterNotNull() ?: emptyList()
    }

    /**
     * Все терминалы проекта — и в тулвиндоу, и в editor area.
     *
     * Второе не роскошь: `getTabs()` возвращает ТОЛЬКО вкладки тулвиндоу, а перенесённая в
     * editor area вкладка из этого списка исчезает (проверено живым прогоном: после Move to
     * Editor плагин переставал видеть её вовсе). Между тем именно там по дизайну живёт
     * оркестратор — то есть без этого мы не находим ровно того, кого ищем чаще всего.
     *
     * Терминал в редакторе — это `TerminalViewVirtualFile`, за которым стоит та же вкладка
     * платформы (`getTab()`), поэтому перечисляем открытые файлы и берём вкладку оттуда.
     * Одна вкладка не должна попасть в список дважды — отсюда дедупликация по объекту.
     */
    fun handles(project: Project): List<TabHandle> {
        val result = ArrayList<TabHandle>()
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        for ((file, tab) in editorTabs(project)) {
            val handle = handleOf(file.name, tab) ?: continue
            handle.editorFile = file
            seen.add(tab)
            result.add(handle)
        }
        for (tab in tabs(project)) {
            if (tab in seen) continue
            handleOf(titleOf(tab).orEmpty(), tab)?.let { result.add(it) }
        }
        return result
    }

    /** Имя выбранной сейчас вкладки редактора — для диагностики. */
    private fun selectedEditorTab(project: Project): String? = try {
        Editors.ex(project)
            ?.currentWindow
            ?.selectedComposite
            ?.file
            ?.name
    } catch (_: Throwable) {
        null
    }

    /**
     * Терминалы, открытые в editor area: файл редактора и вкладка платформы за ним.
     *
     * `TerminalViewVirtualFile` отдаёт вкладку методом `getTab()` — и только им. Прежний код
     * искал `getTerminalView()`, которого нет ни в 2026.2, ни в 2026.3 (сверено по jar-ам), поэтому
     * терминалы в редакторе плагин не видел вовсе: оркестратор, перенесённый туда руками, не
     * находился, и агент уезжал в тулвиндоу вместо соседней вкладки.
     */
    private fun editorTabs(project: Project): List<Pair<VirtualFile, Any>> {
        val files = try {
            Editors.of(project).openFiles
        } catch (e: Throwable) {
            log.warn("MetaJetCore: не удалось перечислить открытые файлы", e)
            return emptyList()
        }
        return files.mapNotNull { file ->
            if (file.javaClass.name != TERMINAL_VIEW_FILE) null
            else call(file, "getTab")?.let { tab -> file to tab }
        }
    }

    /**
     * Открытая вкладка AIR с сессией [sessionId], или null.
     *
     * У файла AIR `getThreadId()` — это `sessionId` Claude Code (проверено на PhpStorm
     * 263.8054: в workspace.xml `threadIdentity="claude:<sessionId>" threadId="<sessionId>"`).
     * Через вложенный `TerminalView` и pid было бы длиннее и хрупче: `threadId` — часть
     * публичного интерфейса файла.
     */
    fun airThreadFile(project: Project, sessionId: String): VirtualFile? = airThreadFiles(project)
        .firstOrNull { call(it, "getThreadId") == sessionId }

    /** Открытые вкладки AIR — для поиска родителя и диагностики. */
    fun airThreadFiles(project: Project): List<VirtualFile> = try {
        Editors.of(project).openFiles.filter { implementsByName(it.javaClass, AIR_THREAD_FILE) }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun implementsByName(cls: Class<*>?, name: String): Boolean {
        var c = cls
        while (c != null) {
            if (c.name == name || c.interfaces.any { it.name == name || implementsByName(it, name) }) return true
            c = c.superclass
        }
        return false
    }

    /**
     * Файл editor area, под которым сейчас открыта вкладка, или null — она в тулвиндоу.
     *
     * Ищем по самой вкладке, а не по выбранному редактору после переноса: выбор к этому
     * моменту мог уйти куда угодно (мы сами возвращаем его на оркестратора).
     */
    fun editorFileOf(project: Project, handle: TabHandle): VirtualFile? {
        if (handle.tab == null) return handle.editorFile
        return editorTabs(project).firstOrNull { isSameTab(it.second, handle) }?.first
    }

    /**
     * Открыта ли вкладка где-нибудь — в тулвиндоу или в editor area.
     *
     * Сверяемся со списками платформы (вкладки менеджера и файлы редактора), а не с
     * `Content.manager`: у вкладки, перенесённой в редактор, `Content` остаётся в менеджере
     * контента редактора и после её закрытия — проверено живым прогоном на 2026.3, плагин
     * считал самостоятельно вышедшего агента живым.
     */
    fun isOpen(project: Project, handle: TabHandle): Boolean {
        if (handle.tab == null) {
            return handle.editorFile?.let { Editors.of(project).isFileOpen(it) } == true
        }
        return tabs(project).any { isSameTab(it, handle) } || editorFileOf(project, handle) != null
    }

    /** Та же вкладка: по объекту или по её view — обёртки платформы бывают разными объектами. */
    private fun isSameTab(candidate: Any, handle: TabHandle): Boolean =
        candidate === handle.tab || call(candidate, "getView") === handle.view

    /**
     * Swing-компонент вкладки. По нему находится окно editor area, в котором она показана:
     * это единственный способ узнать, где вкладка сейчас, если утащил её туда разработчик.
     */
    fun componentOf(handle: TabHandle): javax.swing.JComponent? =
        call(handle.view, "getComponent") as? javax.swing.JComponent

    /** Заголовок вкладки: для вкладок плагина это имя сессии. */
    fun titleOf(tab: Any): String? = try {
        val view = call(tab, "getView")
        val title = view?.let { call(it, "getTitle") }
        title?.let { call(it, "buildTitle") as? String }
    } catch (_: Throwable) {
        null
    }

    /**
     * Запущен ли процесс во вкладке.
     *
     * `sessionState` — StateFlow из трёх состояний: NotStarted / Running / Terminated.
     * До Running писать во вкладку бессмысленно: текст уходит в никуда.
     */
    fun state(handle: TabHandle): String {
        val flow = call(handle.view, "getSessionState") ?: return "unknown"
        val value = call(flow, "getValue") ?: return "unknown"
        return value.javaClass.simpleName
    }

    fun isRunning(handle: TabHandle): Boolean = state(handle) == "Running"

    /**
     * Процесс во вкладке завершился. Вкладка с шеллом при этом остаётся, вкладка агента
     * закрывается сама — см. [openTab].
     */
    fun isTerminated(handle: TabHandle): Boolean = state(handle) == "Terminated"

    /**
     * Жив ли процесс вкладки или кто-то из его потомков.
     *
     * Через `ProcessHandle` JDK, а не `TerminalView.hasChildProcesses`: тот — suspend-функция,
     * рефлексией без continuation не вызывается, и прежняя проверка всегда возвращала null.
     * null — pid вкладки неизвестен.
     */
    fun isProcessTreeAlive(handle: TabHandle): Boolean? {
        val pid = process(handle)?.pid ?: return null
        val root = ProcessHandle.of(pid).orElse(null) ?: return false
        return root.isAlive || root.descendants().anyMatch { it.isAlive }
    }

    /**
     * Добить процесс вкладки вместе с потомками: сначала мягко, потом принудительно.
     *
     * Только для процессов, которые запустил плагин ([TabHandle.ownsProcess]): после `/exit`
     * сессия уже ушла из реестра, и висящее окно — это хвост вроде зависшего потомка.
     */
    fun killProcessTree(handle: TabHandle, graceMs: Long): Boolean {
        val pid = process(handle)?.pid ?: return false
        val root = ProcessHandle.of(pid).orElse(null) ?: return true
        val all = root.descendants().toList() + root
        all.forEach { it.destroy() }
        val deadline = System.currentTimeMillis() + graceMs
        while (System.currentTimeMillis() < deadline && all.any { it.isAlive }) Thread.sleep(100)
        all.filter { it.isAlive }.forEach { it.destroyForcibly() }
        return all.none { it.isAlive }
    }

    /**
     * Поднялась ли интеграция с шеллом.
     *
     * Состояния `Running` мало: процесс уже есть, но приглашение ещё не готово, и строка,
     * напечатанная в этот момент, попадает в буфер ввода, а Enter к ней не применяется —
     * команда просто остаётся набранной и не выполняется. Поймано автотестом: PowerShell
     * показывал наш launch-line в приглашении и стоял так до таймаута.
     */
    fun isShellIntegrationReady(handle: TabHandle): Boolean = try {
        val deferred = call(handle.view, "getShellIntegrationDeferred")
        deferred?.let { call(it, "isCompleted") as? Boolean } ?: false
    } catch (_: Throwable) {
        false
    }

    /**
     * Печать текста во вкладку.
     *
     * `TerminalSendTextBuilder` — единственный способ, который работает и с шеллом, и с уже
     * поднявшимся TUI: `shouldExecute()` дожимает Enter (TUI ждёт CR, а не LF, из-за чего
     * прежний путь оставлял текст висеть в поле ввода), `useBracketedPasteMode()` заставляет
     * TUI принять текст как вставку целиком, а не как набор символов по одному.
     */
    fun send(handle: TabHandle, text: String, execute: Boolean, paste: Boolean = false): Boolean = try {
        val created = call(handle.view, "createSendTextBuilder")
        if (created == null) {
            false
        } else {
            // Билдер возвращает себя же, но на всякий случай не полагаемся на это: если
            // очередная настройка не нашлась, продолжаем с тем, что есть.
            var builder: Any = created
            if (execute) builder = call(builder, "shouldExecute") ?: builder
            if (paste) builder = call(builder, "useBracketedPasteMode") ?: builder
            val send = builder.javaClass.methods.firstOrNull {
                it.name == "send" && it.parameterCount == 1
            }
            if (send == null) {
                log.warn("MetaJetCore: у ${builder.javaClass.name} нет send(String)")
                false
            } else {
                send.isAccessible = true
                send.invoke(builder, text)
                true
            }
        }
    } catch (e: Throwable) {
        log.warn("MetaJetCore: не удалось напечатать во вкладку '${handle.name}'", e)
        false
    }

    /** Размер сетки вкладки. Только для диагностики: вырожденный размер ломает ввод. */
    fun gridSizeForDiagnostics(handle: TabHandle): String =
        call(handle.view, "getGridSize")?.toString() ?: "неизвестен"

    /**
     * Хвост экрана вкладки.
     *
     * Нужен ровно для одного случая: агент поднялся, но молчит. Увидеть, на чём он встал
     * (например, на интерактивном вопросе), можно только так.
     */
    fun readScreen(handle: TabHandle): String? = try {
        val model = staticCall(VIEW_KT, "activeOutputModel", handle.view)
        if (model == null) {
            null
        } else {
            val end = call(model, "getEndOffset")
            val start = call(model, "getStartOffset")
            if (end == null || start == null) {
                null
            } else {
                val length = minus(end, start)
                val from = if (length != null && length > SCREEN_LIMIT) shift(end, -SCREEN_LIMIT) ?: start else start
                val getText = model.javaClass.methods.firstOrNull {
                    it.name == "getText" && it.parameterCount == 2
                }?.also { it.isAccessible = true }
                (getText?.invoke(model, from, end) as? CharSequence)?.toString()
            }
        }
    } catch (e: Throwable) {
        log.warn("MetaJetCore: не удалось прочитать экран вкладки '${handle.name}'", e)
        null
    }

    /**
     * Закрыть вкладку, где бы она ни была.
     *
     * Сначала editor area, потом тулвиндоу, и порядок важен: вкладку, перенесённую в
     * редактор, `closeTab` менеджера не убирает — окно редактора оставалось висеть с мёртвым
     * терминалом. Это и был симптом «агент завершился, а окно осталось».
     */
    fun closeTab(project: Project, handle: TabHandle): Boolean = try {
        val file = editorFileOf(project, handle)
        if (file != null) {
            Editors.of(project).closeFile(file)
        }
        val tab = handle.tab
        val manager = manager(project)
        // Вкладку тулвиндоу закрываем, если файлом редактора она не закрылась: после
        // closeFile её Content уже снят, и второй вызов был бы лишним.
        if (tab != null && manager != null && (file == null || isOpen(project, handle))) {
            manager.javaClass.methods
                .firstOrNull { it.name == "closeTab" && it.parameterCount == 1 }
                ?.also { it.isAccessible = true }
                ?.invoke(manager, tab)
        }
        file != null || tab != null
    } catch (e: Throwable) {
        log.warn("MetaJetCore: не удалось закрыть вкладку '${handle.name}'", e)
        false
    }

    fun focus(handle: TabHandle): Boolean = try {
        val content = handle.content
        if (content != null) content.manager?.setSelectedContent(content, true)
        val component = call(handle.view, "getPreferredFocusableComponent") as? javax.swing.JComponent
        component?.requestFocusInWindow()
        true
    } catch (e: Throwable) {
        log.warn("MetaJetCore: не удалось показать вкладку '${handle.name}'", e)
        false
    }

    /**
     * pid шелла и его командная строка — из `startupOptionsDeferred`, когда он уже завершён.
     *
     * Это авторитетный источник: не настройка IDE и не окружение процесса IDE, а то, что
     * реально запущено в этой вкладке. По pid вкладка сопоставляется с сессией из реестра,
     * по командной строке определяется диалект шелла для строки вычистки окружения.
     */
    fun process(handle: TabHandle): TabProcess? = try {
        // isCompleted проверяется до getCompleted: на незавершённом deferred тот бросает,
        // а процесс вкладки стартует заметно позже самой вкладки.
        val options = call(handle.view, "getStartupOptionsDeferred")
            ?.takeIf { call(it, "isCompleted") == true }
            ?.let { call(it, "getCompleted") }
        if (options == null) {
            null
        } else {
            @Suppress("UNCHECKED_CAST")
            val command = (call(options, "getShellCommand") as? List<String>).orEmpty()
            TabProcess(pid = call(options, "getPid") as? Long, shellCommand = command)
        }
    } catch (e: Throwable) {
        log.debug("MetaJetCore: startupOptions недоступны", e)
        null
    }

    /** Для диагностического действия. */
    fun describe(project: Project?): String = buildString {
        append("terminal API: $TABS_MANAGER — ")
        append(if (loadClass(TABS_MANAGER) != null) "найден" else "НЕ НАЙДЕН")
        append('\n')
        if (project != null) {
            append("менеджер вкладок: ${if (manager(project) != null) "получен" else "не получен"}\n")
            append("вкладок в тулвиндоу: ${tabs(project).size}\n")
            val inEditor = handles(project).filter { it.editorFile != null }
            append("терминалов в editor area: ${inEditor.size}")
            if (inEditor.isNotEmpty()) append(" (${inEditor.joinToString { it.name }})")
            append('\n')
            append("вкладок AIR (Agent Threads): ${airThreadFiles(project).size}\n")
            // Какая вкладка выбрана: спавн агента не должен уводить разработчика с
            // оркестратора, а проверить это иначе нечем.
            append("активная вкладка редактора: ${selectedEditorTab(project) ?: "нет"}\n")
        }
    }

    // ------------------------------------------------------------------ рефлексия

    private fun manager(project: Project): Any? = try {
        val cls = loadClass(TABS_MANAGER)
        cls?.methods
            ?.firstOrNull { it.name == "getInstance" && it.parameterCount == 1 }
            ?.invoke(null, project)
    } catch (e: Throwable) {
        log.warn("MetaJetCore: $TABS_MANAGER недоступен — переходим в ручной режим", e)
        null
    }

    private fun loadClass(name: String): Class<*>? = try {
        Class.forName(name, false, javaClass.classLoader)
    } catch (_: Throwable) {
        null
    }

    private fun call(target: Any, name: String): Any? = try {
        target.javaClass.methods
            .firstOrNull { it.name == name && it.parameterCount == 0 }
            ?.also { it.isAccessible = true }
            ?.invoke(target)
    } catch (e: Throwable) {
        log.debug("MetaJetCore: $name() на ${target.javaClass.name} не вызвался", e)
        null
    }

    private fun staticCall(className: String, name: String, argument: Any): Any? = try {
        loadClass(className)?.methods
            ?.firstOrNull { it.name == name && it.parameterCount == 1 }
            ?.also { it.isAccessible = true }
            ?.invoke(null, argument)
    } catch (e: Throwable) {
        log.debug("MetaJetCore: $className.$name() не вызвался", e)
        null
    }

    /** Вызов метода билдера, который возвращает сам билдер. Промах не фатален. */
    private fun chain(builder: Any, name: String, type: Class<*>, value: Any?) {
        try {
            val method = builder.javaClass.methods.firstOrNull {
                it.name == name && it.parameterCount == 1 &&
                    (it.parameterTypes[0] == type || it.parameterTypes[0].isAssignableFrom(type))
            }
            if (method == null) {
                log.info("MetaJetCore: билдер вкладок не знает $name(), настройка пропущена")
                return
            }
            method.isAccessible = true
            method.invoke(builder, value)
        } catch (e: Throwable) {
            // Отсутствующая настройка билдера — не повод отменять создание вкладки:
            // вкладка без имени полезнее, чем её отсутствие.
            log.info("MetaJetCore: $name() билдера не применился: ${e.message}")
        }
    }

    /** TerminalOffset.minus(TerminalOffset): Long — расстояние между позициями в буфере. */
    private fun minus(left: Any, right: Any): Long? = try {
        left.javaClass.methods
            .firstOrNull { it.name == "minus" && it.parameterCount == 1 && it.returnType == java.lang.Long.TYPE }
            ?.also { it.isAccessible = true }
            ?.invoke(left, right) as? Long
    } catch (_: Throwable) {
        null
    }

    /** TerminalOffset.minus(Long): TerminalOffset — сдвиг позиции. */
    private fun shift(offset: Any, delta: Long): Any? = try {
        val name = if (delta < 0) "minus" else "plus"
        offset.javaClass.methods
            .firstOrNull {
                it.name == name && it.parameterCount == 1 && it.parameterTypes[0] == java.lang.Long.TYPE
            }
            ?.also { it.isAccessible = true }
            ?.invoke(offset, if (delta < 0) -delta else delta)
    } catch (_: Throwable) {
        null
    }
}
