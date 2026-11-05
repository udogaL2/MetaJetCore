package dev.metajetcore.terminal

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.project.Project
import javax.swing.SwingUtilities

/**
 * Размещение вкладок: перенос в editor area и открытие агента рядом с оркестратором.
 *
 * Разработчик держит сессии Claude Code не в терминальном тулвиндоу внизу, а в editor area,
 * где под них есть вертикальное место. С 2026.2 это штатная операция: у платформы есть
 * `ToolWindowInEditorSupport`, а у терминала — его реализация `TerminalInEditorSupport` с
 * методом `openInEditor(content, editorWindow)`. Ровно её дёргает пункт меню
 * «Move to Editor», так что мы делаем то же самое, что разработчик руками.
 *
 * Размещение — best-effort и никогда не фатально: не вышло попасть рядом — вкладка просто
 * останется в тулвиндоу. Ронять спавн из-за косметики нельзя.
 */
object TabPlacement {
    private val log = Logger.getInstance(TabPlacement::class.java)

    private const val IN_EDITOR_SUPPORT = "com.intellij.terminal.frontend.toolwindow.impl.TerminalInEditorSupport"

    /**
     * Переносит вкладку в editor area. Возвращает true, если получилось.
     *
     * Запоминает файл, под которым вкладка там открылась: по нему потом находится окно
     * родителя, когда рядом надо открыть агента.
     */
    fun moveToEditor(project: Project, handle: TabHandle, targetWindow: EditorWindow? = null): Boolean {
        val content = handle.content ?: return false
        val support = support() ?: return false
        val editors = FileEditorManagerEx.getInstanceEx(project)
        // currentWindow пуст, когда фокус не в редакторе — например, разработчик сейчас в
        // терминале, что для нас обычное дело. Тогда берём любое открытое окно: перенести
        // вкладку в редактор всё равно лучше, чем оставить её в тулвиндоу.
        val window = targetWindow ?: editors.currentWindow ?: editors.windows.firstOrNull()
        if (window == null) {
            // Окна редактора может не быть вовсе — например, в проекте не открыт ни один
            // файл. Переносить тогда некуда, и это не поломка.
            log.info("MetaJetCore: в editor area нет окна, вкладка '${handle.name}' осталась в тулвиндоу")
            return false
        }

        val method = support.javaClass.methods.firstOrNull {
            it.name == "openInEditor" && it.parameterCount == 2
        } ?: return false
        // Порядок штатного «Move to Editor»: сначала снять с тулвиндоу, потом открыть.
        // Иначе внизу остаётся пустая вкладка агента (см. Terminal.detachFromToolWindow).
        val detachedFrom = Terminal.detachFromToolWindow(project, handle)
        return try {
            method.isAccessible = true
            method.invoke(support, content, window)
            // Файл ищем по самой вкладке, а не по выбранному в окне редактору: выбор мы сразу
            // возвращаем на оркестратора, и «выбранный» файл оказался бы чужим.
            handle.editorFile = Terminal.editorFileOf(project, handle)
            log.info("MetaJetCore: вкладка '${handle.name}' перенесена в editor area (${handle.editorFile?.name})")
            true
        } catch (e: Throwable) {
            // Именно с исключением: у обёрток рефлексии message пустой, и без причины
            // разбирать такое бесполезно — один раз уже потратили на это прогон.
            log.info("MetaJetCore: перенести '${handle.name}' в editor area не вышло", unwrap(e))
            // Вкладку уже сняли — без возврата агент остался бы живым, но без окна.
            detachedFrom?.let { Terminal.reattachToToolWindow(project, handle, it) }
            false
        }
    }

    /** Рефлексия заворачивает настоящую ошибку в InvocationTargetException с пустым message. */
    private fun unwrap(e: Throwable): Throwable =
        if (e is java.lang.reflect.InvocationTargetException) e.targetException ?: e else e

    /**
     * Открывает вкладку агента рядом с вкладкой родителя — соседней вкладкой в его окне.
     *
     * Именно вкладкой, а не сплитом. Сплит на каждого агента звучит нагляднее, но при трёх-
     * четырёх ролях окно превращается в набор узких полосок, и разделить его пополам
     * разработчик всегда может сам. Группировка при этом сохраняется: агенты лежат в том же
     * окне, что и их оркестратор, а не в общей куче.
     *
     * Родитель не найден или живёт в тулвиндоу — агент всё равно уходит в editor area
     * (текущее окно или первое): ради места под длинный вывод плагин и задуман, а внизу
     * вкладка агента теряется среди обычных терминалов. В тулвиндоу агент остаётся, только
     * если окон редактора нет вовсе.
     */
    fun placeNear(project: Project, parent: TabHandle?, child: TabHandle) {
        val parentWindow = parent?.let { windowOf(project, it) }
        if (parentWindow == null) {
            if (parent != null) {
                log.info("MetaJetCore: родитель '${parent.name}' не в editor area, агент — в текущее окно редактора")
            }
            val editors = FileEditorManagerEx.getInstanceEx(project)
            val window = editors.currentWindow ?: editors.windows.firstOrNull()
            val selected = window?.selectedComposite
            moveToEditor(project, child, window)
            restoreSelection(window, selected, parent?.name)
            return
        }

        // Что было выбрано до спавна — туда и вернём. Открытие файла в редакторе выделяет
        // его вкладку, то есть спавн агента уводил разработчика с вкладки оркестратора,
        // а именно в неё он и смотрит. Агент при этом не требует внимания: он поднялся и
        // ждёт задачу по SendMessage.
        val selected = parentWindow.selectedComposite

        moveToEditor(project, child, parentWindow)
        restoreSelection(parentWindow, selected, parent.name)
    }

    private fun restoreSelection(window: EditorWindow?, selected: com.intellij.openapi.fileEditor.impl.EditorComposite?, who: String?) {
        if (window == null || selected == null) return
        try {
            window.setSelectedComposite(selected, true)
        } catch (e: Throwable) {
            log.info("MetaJetCore: вернуть выбор на '${who ?: selected.file.name}' не вышло: ${e.message}")
        }
    }

    /**
     * Окно editor area, в котором сейчас показан родитель, или null, если он в тулвиндоу.
     *
     * Ищем по компоненту вкладки, а не по своей записи о переносе: вкладку в editor area
     * мог утащить и сам разработчик — мышью или через Move to Editor. Полагаться на
     * собственную бухгалтерию значило бы размещать агента мимо всякий раз, когда оркестратор
     * оказался там не нашими руками, а это как раз обычный случай.
     */
    private fun windowOf(project: Project, handle: TabHandle): EditorWindow? {
        val component = Terminal.componentOf(handle) ?: return null
        for (window in FileEditorManagerEx.getInstanceEx(project).windows) {
            for (composite in window.allComposites) {
                if (SwingUtilities.isDescendingFrom(component, composite.component)) {
                    // Заодно запоминаем файл: по нему вкладку можно найти дешевле.
                    handle.editorFile = composite.file
                    return window
                }
            }
        }
        return null
    }

    private fun support(): Any? = try {
        // Реализация EP без состояния и с публичным конструктором — создаём свою.
        Class.forName(IN_EDITOR_SUPPORT, false, javaClass.classLoader)
            .getDeclaredConstructor()
            .newInstance()
    } catch (e: Throwable) {
        log.info("MetaJetCore: $IN_EDITOR_SUPPORT недоступен, editor area не используется")
        null
    }
}
