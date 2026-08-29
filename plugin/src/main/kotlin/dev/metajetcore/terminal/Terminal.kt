package dev.metajetcore.terminal

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
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

    /** Тот же терминал под именем сессии, которую в нём нашли. */
    fun renamed(newName: String): TabHandle =
        TabHandle(newName, tab, view, content).also { it.editorFile = editorFile }
}

/** Что известно про процесс во вкладке: pid шелла и командная строка запуска. */
data class TabProcess(val pid: Long?, val shellCommand: List<String>)

/**
 * Терминал IDE, начиная с 2026.2 (переработанный движок).
 *
 * Работаем через `com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager` —
 * это и есть API нового терминала. Поколения G1/G2 (JediTerm-виджеты `ShellTerminalWidget`,
 * `TerminalToolWindowManager.createLocalShellWidget`) выброшены: в 2026.2 первый путь бросает
 * `toShellJediTermWidgetOrThrow`, а второй отдаёт виджет, в который нечем печатать.
 *
 * Почему рефлексия, если плагин и так собирается против 2026.2: классы модуля помечены
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

    /** Сколько символов экрана отдаём наружу: больше оркестратору не нужно, а контекст дорог. */
    private const val SCREEN_LIMIT = 4_000L

    fun isAvailable(project: Project): Boolean = manager(project) != null

    /**
     * Новая вкладка с шеллом.
     *
     * Переменные окружения уезжают через API билдера, а не внутри печатаемой строки: так
     * отпадают экранирование, кавычки и разница диалектов шеллов. В командной строке остаётся
     * только то, что окружением задать нельзя, — роль и режим прав.
     */
    fun openTab(
        project: Project,
        name: String,
        workingDirectory: Path,
        env: Map<String, String>,
        requestFocus: Boolean = false,
    ): TabHandle? {
        val manager = manager(project) ?: return null
        return try {
            val builder = call(manager, "createTabBuilder") ?: return null
            chain(builder, "tabName", String::class.java, name)
            chain(builder, "workingDirectory", String::class.java, workingDirectory.toString())
            chain(builder, "envVariables", Map::class.java, env)
            chain(builder, "requestFocus", java.lang.Boolean.TYPE, requestFocus)
            // Сессия должна стартовать сразу: мы ждём появления записи в реестре, а вкладка
            // может быть не показана, если разработчик смотрит в другую.
            chain(builder, "deferSessionStartUntilUiShown", java.lang.Boolean.TYPE, false)
            // Вкладка остаётся после выхода claude: иначе при падении агента исчезнет и текст
            // ошибки, а именно он нужен для разбора.
            chain(builder, "closeOnProcessTermination", java.lang.Boolean.TYPE, false)

            val tab = call(builder, "createTab") ?: return null
            handleOf(name, tab)
        } catch (e: Throwable) {
            log.warn("MetaJetCore: не удалось открыть вкладку терминала", e)
            null
        }
    }

    /** Хендл по объекту вкладки платформы. */
    fun handleOf(name: String, tab: Any): TabHandle? {
        val view = call(tab, "getView") ?: return null
        val content = call(tab, "getContent") as? Content
        return TabHandle(name = name, tab = tab, view = view, content = content)
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
     * Терминал в редакторе — это `TerminalViewVirtualFile` с публичным `getTerminalView()`,
     * поэтому перечисляем открытые файлы и забираем view оттуда.
     */
    fun handles(project: Project): List<TabHandle> {
        val result = ArrayList<TabHandle>()
        for (tab in tabs(project)) {
            handleOf(titleOf(tab).orEmpty(), tab)?.let { result.add(it) }
        }
        for ((file, view) in editorViews(project)) {
            val handle = TabHandle(
                name = file.name,
                tab = null,
                view = view,
                content = null,
            )
            handle.editorFile = file
            result.add(handle)
        }
        return result
    }

    /** Имя выбранной сейчас вкладки редактора — для диагностики. */
    private fun selectedEditorTab(project: Project): String? = try {
        com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project)
            .currentWindow
            ?.selectedComposite
            ?.file
            ?.name
    } catch (_: Throwable) {
        null
    }

    private fun editorViews(project: Project): List<Pair<VirtualFile, Any>> {
        val files = try {
            FileEditorManager.getInstance(project).openFiles
        } catch (e: Throwable) {
            log.warn("MetaJetCore: не удалось перечислить открытые файлы", e)
            return emptyList()
        }
        return files.mapNotNull { file ->
            if (file.javaClass.name != TERMINAL_VIEW_FILE) {
                null
            } else {
                call(file, "getTerminalView")?.let { view -> file to view }
            }
        }
    }

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

    fun closeTab(project: Project, handle: TabHandle): Boolean = try {
        val tab = handle.tab
        val file = handle.editorFile
        val manager = manager(project)
        val close = manager?.javaClass?.methods?.firstOrNull {
            it.name == "closeTab" && it.parameterCount == 1
        }
        when {
            tab != null && close != null -> {
                close.isAccessible = true
                close.invoke(manager, tab)
                true
            }
            // Терминал в editor area — это открытый файл, и закрывается он как файл.
            file != null -> {
                FileEditorManager.getInstance(project).closeFile(file)
                true
            }
            else -> false
        }
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
        val deferred = call(handle.view, "getStartupOptionsDeferred")
        val completed = deferred?.let { call(it, "isCompleted") as? Boolean } ?: false
        if (!completed) {
            null
        } else {
            val options = call(deferred!!, "getCompleted")
            if (options == null) {
                null
            } else {
                @Suppress("UNCHECKED_CAST")
                val command = (call(options, "getShellCommand") as? List<String>).orEmpty()
                TabProcess(pid = call(options, "getPid") as? Long, shellCommand = command)
            }
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
