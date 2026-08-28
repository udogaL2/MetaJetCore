package dev.metajetcore.terminal

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.lang.reflect.Method
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Доступ к терминалу IDE строго через рефлексию.
 *
 * Почему не обычная компиляционная зависимость: Terminal API помечен экспериментальным
 * (`TerminalToolWindowTabsManager`, `TerminalView` — с 2025.3), а до него было ещё два
 * поколения. Обычная зависимость означала бы NoClassDefFoundError и мёртвый плагин после
 * очередного апгрейда IDE. Рефлексия даёт три попытки и мягкую деградацию.
 *
 * Поколения, в порядке предпочтения:
 *   G3  2025.3+  org.jetbrains.plugins.terminal.TerminalToolWindowTabsManager
 *   G2  2023.2+  org.jetbrains.plugins.terminal.TerminalToolWindowManager
 *   G1  legacy   org.jetbrains.plugins.terminal.TerminalView
 *
 * Ни одно из поколений не гарантировано: если не разрешилось ни одно, возвращаем
 * UnavailableTerminalBackend и работаем в ручном режиме.
 */
object TerminalBackends {
    private val log = Logger.getInstance(TerminalBackends::class.java)
    private val cached = AtomicReference<TerminalBackend?>(null)

    fun resolve(): TerminalBackend {
        cached.get()?.let { return it }
        val backend = probe()
        cached.set(backend)
        log.info("MetaJetCore terminal backend: ${backend.id}")
        return backend
    }

    /** Для диагностического действия: перепроверить, не переехало ли API. */
    fun invalidate() = cached.set(null)

    /** Что именно удалось найти — показывается в диагностике. */
    fun describe(): String {
        val backend = resolve()
        return buildString {
            append("backend: ${backend.id}\n")
            append("available: ${backend.isAvailable()}\n")
            for (generation in Generation.entries) {
                append("  ${generation.name} (${generation.className}): ")
                append(if (generation.load() != null) "found" else "absent")
                append('\n')
            }
        }
    }

    private fun probe(): TerminalBackend {
        for (generation in Generation.entries) {
            val cls = generation.load() ?: continue
            return try {
                GenerationBackend(generation, cls)
            } catch (e: Throwable) {
                log.warn("MetaJetCore: generation ${generation.name} failed to initialise", e)
                continue
            }
        }
        log.warn("MetaJetCore: no terminal API generation resolved; falling back to manual mode")
        return UnavailableTerminalBackend
    }

    enum class Generation(val className: String) {
        G3("org.jetbrains.plugins.terminal.TerminalToolWindowTabsManager"),
        G2("org.jetbrains.plugins.terminal.TerminalToolWindowManager"),
        G1("org.jetbrains.plugins.terminal.TerminalView"),
        ;

        fun load(): Class<*>? = try {
            Class.forName(className, false, javaClass.classLoader)
        } catch (_: Throwable) {
            null
        }
    }
}

private class GenerationBackend(
    private val generation: TerminalBackends.Generation,
    private val managerClass: Class<*>,
) : TerminalBackend {

    private val log = Logger.getInstance(GenerationBackend::class.java)

    override val id: String = generation.name

    override fun isAvailable(): Boolean = true

    override fun openTab(project: Project, request: OpenTabRequest): TabHandle? {
        val manager = instance(project) ?: return null
        val widget = when (generation) {
            TerminalBackends.Generation.G3 -> openViaTabBuilder(manager, request)
            TerminalBackends.Generation.G2 -> openViaShellWidget(manager, request)
            TerminalBackends.Generation.G1 -> openViaLocalShellWidget(manager, request)
        } ?: return null

        return TabHandle(
            id = UUID.randomUUID().toString(),
            displayName = request.tabName,
            widget = widget,
            backendId = id,
        )
    }

    /**
     * Шелл считается поднявшимся, когда у виджета появился TtyConnector: до этого писать
     * некуда и строка теряется без следа.
     */
    override fun isReady(handle: TabHandle): Boolean {
        val widget = handle.widget ?: return false
        // getTtyConnector наследуется от JediTermWidget, getProcessTtyConnector объявлен
        // прямо в ShellTerminalWidget — проверено по jar платформы. Хватит любого.
        for (name in listOf("getTtyConnector", "getProcessTtyConnector")) {
            try {
                val method = widget.javaClass.methods
                    .firstOrNull { it.name == name && it.parameterCount == 0 } ?: continue
                if (method.invoke(widget) != null) return true
            } catch (_: Throwable) {
                // Часть реализаций бросает, пока сессия не стартовала — это и есть «не готов».
            }
        }
        return false
    }

    /**
     * Печать строки в терминал.
     *
     * Порядок попыток отражает историю API. Ищем метод по имени и арности, а не по точной
     * сигнатуре: сигнатуры между версиями менялись (добавлялись флаги), имена — нет.
     */
    override fun sendLine(handle: TabHandle, text: String): Boolean {
        val widget = handle.widget ?: return false
        val payload = if (text.endsWith("\n")) text else text + "\n"

        // 1) executeCommand(String) — ShellTerminalWidget, живёт дольше всех
        invokeIfPresent(widget, "executeCommand", arrayOf(String::class.java), text)?.let { return true }

        // 2) sendCommandToExecute(String)
        invokeIfPresent(widget, "sendCommandToExecute", arrayOf(String::class.java), text)?.let { return true }

        // 3) sendText(String) / sendText(String, Boolean) — TerminalView, 2025.3+
        invokeIfPresent(widget, "sendText", arrayOf(String::class.java), payload)?.let { return true }
        invokeIfPresent(
            widget, "sendText",
            arrayOf(String::class.java, java.lang.Boolean.TYPE), payload, true,
        )?.let { return true }

        // 4) писать прямо в поток шелла — самый низкий уровень, но и самый устойчивый
        if (writeToTtyConnector(widget, payload)) return true

        log.warn("MetaJetCore: no way to send text on widget ${widget.javaClass.name}")
        return false
    }

    /**
     * Снимок экрана вкладки через TerminalTextBuffer из JediTerm.
     *
     * Путь: widget.getTerminalTextBuffer() -> getScreenLines(). Оба метода живут в JediTerm
     * и переживали все переделки терминала IDE, так что это самая устойчивая точка чтения.
     */
    override fun readScreen(handle: TabHandle): String? {
        val widget = handle.widget ?: return null
        return try {
            val buffer = widget.javaClass.methods
                .firstOrNull { it.name == "getTerminalTextBuffer" && it.parameterCount == 0 }
                ?.invoke(widget) ?: return null

            sequenceOf("getScreenLines", "getScreenText", "getText")
                .mapNotNull { name ->
                    buffer.javaClass.methods
                        .firstOrNull { it.name == name && it.parameterCount == 0 }
                        ?.invoke(buffer) as? String
                }
                .firstOrNull()
        } catch (e: Throwable) {
            log.warn("MetaJetCore: cannot read terminal screen", e)
            null
        }
    }

    override fun closeTab(handle: TabHandle): Boolean {
        val widget = handle.widget ?: return false
        for (name in listOf("close", "dispose", "closeTab")) {
            invokeIfPresent(widget, name, emptyArray())?.let { return true }
        }
        return false
    }

    override fun focusTab(handle: TabHandle): Boolean {
        val widget = handle.widget ?: return false
        for (name in listOf("requestFocus", "grabFocus", "requestFocusInWindow")) {
            invokeIfPresent(widget, name, emptyArray())?.let { return true }
        }
        return false
    }

    // --- поколения ---

    private fun instance(project: Project): Any? = try {
        val getInstance = managerClass.methods.firstOrNull {
            it.name == "getInstance" && it.parameterCount == 1 &&
                it.parameterTypes[0].isAssignableFrom(Project::class.java)
        }
        getInstance?.invoke(null, project)
    } catch (e: Throwable) {
        log.warn("MetaJetCore: cannot obtain ${managerClass.name} instance", e)
        null
    }

    /** 2025.3+: createTabBuilder().tabName(...).workingDirectory(...).createTab() */
    private fun openViaTabBuilder(manager: Any, request: OpenTabRequest): Any? = try {
        val builder = manager.javaClass.methods
            .firstOrNull { it.name == "createTabBuilder" && it.parameterCount == 0 }
            ?.invoke(manager)

        if (builder == null) {
            null
        } else {
            configureBuilder(builder, "tabName", request.tabName)
            configureBuilder(builder, "workingDirectory", request.workingDirectory.toString())
            configureBuilder(builder, "requestFocus", request.requestFocus)
            // createTab() / create() / build() — имя менялось, пробуем все
            builder.javaClass.methods
                .firstOrNull { it.name in TAB_TERMINALS && it.parameterCount == 0 }
                ?.invoke(builder)
        }
    } catch (e: Throwable) {
        log.warn("MetaJetCore: G3 createTabBuilder path failed", e)
        null
    }

    /**
     * 2023.2+: TerminalToolWindowManager.
     *
     * Порядок не случаен. `createLocalShellWidget` возвращает конкретный
     * `ShellTerminalWidget`, у которого есть `executeCommand(String)` — проверено по jar
     * платформы 2025.1. `createShellWidget` отдаёт интерфейс `TerminalWidget`, и печатать
     * в него нечем. Поэтому конкретный класс пробуем первым, а интерфейс оставляем в запас
     * на случай, если в будущей версии уберут именно `createLocalShellWidget`.
     */
    private fun openViaShellWidget(manager: Any, request: OpenTabRequest): Any? {
        val cwd = request.workingDirectory.toString()

        openViaLocalShellWidget(manager, request)?.let { return it }

        return try {
            manager.javaClass.methods
                .firstOrNull { it.name == "createShellWidget" && it.parameterCount == 4 }
                ?.invoke(manager, cwd, request.tabName, request.requestFocus, false)
        } catch (e: Throwable) {
            log.warn("MetaJetCore: G2 createShellWidget fallback failed", e)
            null
        }
    }

    /**
     * createLocalShellWidget — существует в трёх арностях, от самой полной к самой старой.
     * Возвращает ShellTerminalWidget, у которого есть executeCommand(String).
     */
    private fun openViaLocalShellWidget(manager: Any, request: OpenTabRequest): Any? {
        val cwd = request.workingDirectory.toString()
        val candidates = manager.javaClass.methods
            .filter { it.name == "createLocalShellWidget" && it.parameterCount in 2..4 }
            .sortedByDescending { it.parameterCount }

        for (method in candidates) {
            try {
                val result = when (method.parameterCount) {
                    4 -> method.invoke(manager, cwd, request.tabName, request.requestFocus, false)
                    3 -> method.invoke(manager, cwd, request.tabName, request.requestFocus)
                    else -> method.invoke(manager, cwd, request.tabName)
                }
                if (result != null) return result
            } catch (e: Throwable) {
                log.warn("MetaJetCore: createLocalShellWidget/${method.parameterCount} failed", e)
            }
        }
        return null
    }

    // --- вспомогательное ---

    private fun configureBuilder(builder: Any, name: String, value: Any) {
        try {
            val method = builder.javaClass.methods.firstOrNull {
                it.name == name && it.parameterCount == 1
            } ?: return
            method.invoke(builder, value)
        } catch (_: Throwable) {
            // Отсутствующая настройка билдера — не повод отменять создание вкладки:
            // без имени или без cwd она всё равно полезнее, чем ничего.
        }
    }

    private fun invokeIfPresent(
        target: Any,
        name: String,
        parameterTypes: Array<Class<*>>,
        vararg args: Any?,
    ): Any? = try {
        val method: Method? = findMethod(target.javaClass, name, parameterTypes)
        if (method == null) null else {
            method.isAccessible = true
            method.invoke(target, *args) ?: Unit
        }
    } catch (_: Throwable) {
        null
    }

    private fun findMethod(cls: Class<*>, name: String, types: Array<Class<*>>): Method? =
        cls.methods.firstOrNull { candidate ->
            candidate.name == name &&
                candidate.parameterCount == types.size &&
                candidate.parameterTypes.zip(types).all { (declared, wanted) ->
                    declared.isAssignableFrom(wanted) || declared == wanted
                }
        }

    /**
     * Последний рубеж: писать в TtyConnector напрямую. Это самый старый и самый
     * стабильный кусок JediTerm, он переживал все переделки терминала.
     */
    private fun writeToTtyConnector(widget: Any, text: String): Boolean = try {
        val connector = widget.javaClass.methods
            .firstOrNull { it.name == "getTtyConnector" && it.parameterCount == 0 }
            ?.invoke(widget)
        if (connector == null) false else {
            val write = findMethod(connector.javaClass, "write", arrayOf(String::class.java))
            if (write == null) false else {
                write.isAccessible = true
                write.invoke(connector, text)
                true
            }
        }
    } catch (_: Throwable) {
        false
    }

    private companion object {
        val TAB_TERMINALS = setOf("createTab", "create", "build", "createTabAndFocus")
    }
}
