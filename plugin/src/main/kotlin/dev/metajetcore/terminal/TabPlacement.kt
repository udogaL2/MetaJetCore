package dev.metajetcore.terminal

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Размещение новой вкладки рядом с родительской — там же, где живёт родитель.
 *
 * Разработчик держит сессии Claude Code не в терминальном тулвиндоу внизу, а перетаскивает их
 * в editor area, туда, где обычно код. Значит размещение обязано работать в обоих местах, и
 * правило простое: **новая вкладка появляется там, где находится вкладка родителя.**
 *
 * Все три механизма найдены в поставке IDE (проверено по jar-ам 2025.1 и 2026.1):
 *
 *  * `TerminalToolWindowManager.isInTerminalToolWindow(widget)` — статический метод, отвечает,
 *    где сейчас родитель;
 *  * `JBTerminalWidgetListener.split(vertically)` — тот самый путь, которым сплитит UI.
 *    `TerminalSplitAction` — не платформенный AnAction с id, а JediTerm-экшен, делегирующий
 *    в этот listener, поэтому дёргать надо listener, а не ActionManager;
 *  * действие `Terminal.MoveToEditor` — то, что разработчик делает руками. Свой
 *    `FileEditorProvider` писать не нужно: в плагине терминала он уже есть
 *    (`terminal-view-editor`, `classic-terminal-session-editor`).
 *
 * **Размещение — best-effort и никогда не фатально.** Если что-то из этого не сработало,
 * вкладка всё равно создана и агент всё равно поднимется, просто окажется не там, где хотелось.
 * Ронять спавн из-за косметики нельзя.
 */
object TabPlacement {
    private val log = Logger.getInstance(TabPlacement::class.java)

    /** Где живёт вкладка. */
    enum class Location { TOOL_WINDOW, EDITOR, UNKNOWN }

    fun locate(widget: Any?): Location {
        if (widget == null) return Location.UNKNOWN
        return try {
            val cls = Class.forName(
                "org.jetbrains.plugins.terminal.TerminalToolWindowManager",
                false,
                javaClass.classLoader,
            )
            val method = cls.methods.firstOrNull {
                it.name == "isInTerminalToolWindow" && it.parameterCount == 1
            }
            if (method == null) {
                log.warn("MetaJetCore: isInTerminalToolWindow не найден, размещение отключено")
                return Location.UNKNOWN
            }
            // Метод ждёт JBTerminalWidget, а у нас на руках может быть и он, и обёртка
            // TerminalWidget. Подбираем по типу параметра, а не гадаем: слепой unwrap
            // подсовывал обёртку и invoke падал на несовпадении типов.
            val target = coerce(widget, method.parameterTypes[0])
            if (target == null) {
                log.warn(
                    "MetaJetCore: ${widget.javaClass.name} не приводится к " +
                        "${method.parameterTypes[0].name}, размещение недоступно",
                )
                return Location.UNKNOWN
            }
            val inToolWindow = method.invoke(null, target) as? Boolean
            val result = when (inToolWindow) {
                true -> Location.TOOL_WINDOW
                false -> Location.EDITOR
                null -> Location.UNKNOWN
            }
            log.info(
                "MetaJetCore: родитель ${target.javaClass.name} -> $result " +
                    "(ожидался параметр ${method.parameterTypes[0].name})",
            )
            result
        } catch (e: Throwable) {
            log.warn("MetaJetCore: cannot locate terminal widget ${widget.javaClass.name}", e)
            Location.UNKNOWN
        }
    }

    /**
     * Сплитит терминал родителя, создавая рядом новую сессию, и возвращает её виджет.
     *
     * `split()` сам создаёт новый терминал, поэтому «создать вкладку и подвинуть» не выйдет —
     * порядок обратный: сначала сплит, потом находим появившийся виджет разницей множеств
     * `getWidgets()` до и после. Другого способа получить хендл нет: `split()` возвращает void.
     */
    fun splitFromParent(project: Project, parentWidget: Any, vertically: Boolean): Any? {
        val listener = listenerOf(parentWidget)
        if (listener == null) {
            log.warn(
                "MetaJetCore: у ${unwrap(parentWidget).javaClass.name} нет getListener(), " +
                    "сплит невозможен",
            )
            return null
        }
        log.info("MetaJetCore: listener сплита = ${listener.javaClass.name}")

        if (!canSplit(listener, vertically)) {
            log.info("MetaJetCore: родитель сплитить не даёт, вкладка откроется обычным способом")
            return null
        }

        val before = widgetSet(project)
        return try {
            val split = listenerMethod("split") ?: return null
            split.invoke(listener, vertically)

            val after = widgetSet(project)
            val created = after.filterNot { existing -> before.any { it === existing } }
            log.info("MetaJetCore: виджетов до сплита ${before.size}, после ${after.size}")
            if (created.size == 1) {
                created.first()
            } else {
                log.warn("MetaJetCore: после сплита появилось ${created.size} виджетов, хендл не определён")
                null
            }
        } catch (e: Throwable) {
            log.warn("MetaJetCore: split from parent failed", e)
            null
        }
    }

    /**
     * Переносит вкладку в editor area тем же действием, которым это делает разработчик.
     *
     * Действие ждёт виджет в DataContext. Ключ данных у разных версий разный, поэтому
     * пробуем известные, и если ни один не подошёл — просто возвращаем false: вкладка
     * останется в тулвиндоу, агент от этого не страдает.
     */
    fun moveToEditor(project: Project, widget: Any): Boolean {
        val action = ActionManager.getInstance().getAction(MOVE_TO_EDITOR) ?: run {
            log.info("MetaJetCore: действия $MOVE_TO_EDITOR в этой IDE нет")
            return false
        }

        for (keyName in WIDGET_DATA_KEYS) {
            val context = dataContext(project, keyName, widget) ?: continue
            try {
                val event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN, context)
                action.update(event)
                if (!event.presentation.isEnabled) continue
                action.actionPerformed(event)
                log.info("MetaJetCore: вкладка перенесена в editor area (ключ $keyName)")
                return true
            } catch (e: Throwable) {
                log.debug("MetaJetCore: moveToEditor via $keyName failed", e)
            }
        }
        log.info("MetaJetCore: перенести вкладку в editor area не удалось, останется в тулвиндоу")
        return false
    }

    // ------------------------------------------------------------------ internals

    private fun dataContext(project: Project, keyName: String, widget: Any): DataContext? = try {
        val key = dataKey(keyName) ?: return null
        SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(key, widget)
            .build()
    } catch (_: Throwable) {
        null
    }

    @Suppress("UNCHECKED_CAST")
    private fun dataKey(qualified: String): com.intellij.openapi.actionSystem.DataKey<Any>? = try {
        val separator = qualified.lastIndexOf('.')
        val cls = Class.forName(qualified.substring(0, separator), false, javaClass.classLoader)
        val fieldName = qualified.substring(separator + 1)
        val field = cls.fields.firstOrNull { it.name == fieldName }
            ?: cls.getDeclaredField(fieldName)
        field.isAccessible = true
        field.get(null) as? com.intellij.openapi.actionSystem.DataKey<Any>
    } catch (_: Throwable) {
        null
    }

    /**
     * Ищет getListener() и на самом виджете, и на его развёрнутой форме: в одних версиях
     * метод есть у JBTerminalWidget, в других — у обёртки.
     */
    private fun listenerOf(widget: Any): Any? {
        for (candidate in candidates(widget)) {
            try {
                val method = candidate.javaClass.methods
                    .firstOrNull { it.name == "getListener" && it.parameterCount == 0 }
                    ?: continue
                method.invoke(candidate)?.let { return it }
            } catch (_: Throwable) {
                // пробуем следующую форму
            }
        }
        return null
    }

    /** Первая из форм виджета, подходящая под требуемый тип. */
    private fun coerce(widget: Any, required: Class<*>): Any? =
        candidates(widget).firstOrNull { required.isInstance(it) }

    /** Виджет как есть плюс его развёрнутая форма, если она отличается. */
    private fun candidates(widget: Any): List<Any> {
        val unwrapped = unwrap(widget)
        return if (unwrapped === widget) listOf(widget) else listOf(widget, unwrapped)
    }

    private fun canSplit(listener: Any, vertically: Boolean): Boolean = try {
        // default-ный метод интерфейса; если не нашли — пробуем сплитить и смотрим на результат
        val method = listenerMethod("canSplit") ?: return true
        (method.invoke(listener, vertically) as? Boolean) ?: true
    } catch (_: Throwable) {
        true
    }

    /**
     * Метод listener-а, взятый с ИНТЕРФЕЙСА, а не с класса реализации.
     *
     * Реализация — анонимный внутренний класс `TerminalToolWindowManager$3`, он
     * package-private, и вызов его публичного метода через рефлексию падает с
     * IllegalAccessException. Через интерфейс `JBTerminalWidgetListener` тот же вызов
     * проходит — проверено живым прогоном.
     */
    private fun listenerMethod(name: String): java.lang.reflect.Method? = try {
        val iface = Class.forName(
            "com.intellij.terminal.JBTerminalWidgetListener",
            false,
            javaClass.classLoader,
        )
        iface.methods.firstOrNull { it.name == name && it.parameterCount == 1 }
    } catch (e: Throwable) {
        log.warn("MetaJetCore: интерфейс JBTerminalWidgetListener недоступен", e)
        null
    }

    private fun widgetSet(project: Project): List<Any> = try {
        val cls = Class.forName(
            "org.jetbrains.plugins.terminal.TerminalToolWindowManager",
            false,
            javaClass.classLoader,
        )
        val instance = cls.methods
            .firstOrNull { it.name == "getInstance" && it.parameterCount == 1 }
            ?.invoke(null, project)
        // getWidgets() отдаёт JBTerminalWidget, getTerminalWidgets() — новый интерфейс
        // TerminalWidget. Берём тот, что есть, лишь бы сравнить «до» и «после».
        val set = sequenceOf("getWidgets", "getTerminalWidgets")
            .mapNotNull { name ->
                instance?.javaClass?.methods
                    ?.firstOrNull { it.name == name && it.parameterCount == 0 }
                    ?.invoke(instance)
            }
            .firstOrNull()
        (set as? Collection<*>)?.filterNotNull()?.toList() ?: emptyList()
    } catch (e: Throwable) {
        log.debug("MetaJetCore: cannot enumerate terminal widgets", e)
        emptyList()
    }

    /**
     * У новых версий вкладка представлена оболочкой `TerminalWidget`, а listener и статические
     * проверки ждут `JBTerminalWidget`. Если у объекта есть способ отдать внутренний виджет,
     * пользуемся им.
     */
    private fun unwrap(widget: Any): Any = try {
        sequenceOf("asNewWidget", "getJBTerminalWidget", "getTerminalWidget")
            .mapNotNull { name ->
                widget.javaClass.methods
                    .firstOrNull { it.name == name && it.parameterCount == 0 }
                    ?.invoke(widget)
            }
            .firstOrNull { it !== widget } ?: widget
    } catch (_: Throwable) {
        widget
    }

    private const val MOVE_TO_EDITOR = "Terminal.MoveToEditor"

    /** Известные ключи данных, под которыми действия терминала ищут виджет. */
    private val WIDGET_DATA_KEYS = listOf(
        "com.intellij.terminal.ui.TerminalWidget.DATA_KEY",
        "com.intellij.terminal.JBTerminalWidget.TERMINAL_DATA_KEY",
        "org.jetbrains.plugins.terminal.TerminalView.DATA_KEY",
    )
}
