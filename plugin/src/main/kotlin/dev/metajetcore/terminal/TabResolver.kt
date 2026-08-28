package dev.metajetcore.terminal

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.metajetcore.registry.SessionRegistry
import java.util.UUID

/**
 * Находит вкладку терминала, в которой живёт сессия Claude Code с заданным именем — даже если
 * эту вкладку плагин не создавал.
 *
 * Зачем: запуск оркестратора должен быть ровно `/orchestrate` в любой вкладке. Без этого
 * разработчику пришлось бы каждый раз открывать вкладку через действие плагина, иначе плагин
 * не знал бы, рядом с чем размещать агентов. Требовать лишний клик там, где договорились
 * обойтись одной командой, — плохой обмен.
 *
 * Как: сопоставлением по дереву процессов. Реестр сессий даёт pid процесса `claude`, а
 * терминальный виджет — pid своего шелла. Если шелл оказался среди предков claude, значит
 * сессия живёт в этой вкладке.
 *
 * Работа с процессами — чистый JDK (`ProcessHandle`), от версии IDE не зависит. Рефлексия
 * нужна только чтобы достать pid шелла из виджета, и как везде в этом слое она мягкая:
 * не получилось — вернём null, вызывающий обойдётся без размещения.
 */
object TabResolver {
    private val log = Logger.getInstance(TabResolver::class.java)

    /** Глубина подъёма по предкам: claude может быть внуком шелла, если запущен через обёртку. */
    private const val MAX_ANCESTOR_DEPTH = 8

    fun findTabForSession(project: Project, sessionName: String): TabHandle? {
        val record = SessionRegistry.findByName(sessionName) ?: run {
            log.info("MetaJetCore: сессии '$sessionName' в реестре нет, вкладку искать не по чему")
            return null
        }

        val ancestors = ancestorPids(record.pid.toLong())
        if (ancestors.isEmpty()) {
            log.info("MetaJetCore: у процесса ${record.pid} не видно предков")
            return null
        }

        for (widget in terminalWidgets(project)) {
            val shellPid = shellPid(widget) ?: continue
            if (shellPid in ancestors) {
                log.info(
                    "MetaJetCore: сессия '$sessionName' (pid ${record.pid}) живёт во вкладке " +
                        "с шеллом $shellPid",
                )
                return TabHandle(
                    id = UUID.randomUUID().toString(),
                    displayName = sessionName,
                    widget = widget,
                    backendId = TerminalBackends.resolve().id,
                )
            }
        }

        log.info("MetaJetCore: вкладку для '$sessionName' сопоставить не удалось")
        return null
    }

    /** Цепочка предков процесса, включая его самого: claude, обёртка, шелл. */
    private fun ancestorPids(pid: Long): Set<Long> {
        val result = LinkedHashSet<Long>()
        var handle = try {
            ProcessHandle.of(pid).orElse(null)
        } catch (_: Throwable) {
            null
        }
        var depth = 0
        while (handle != null && depth < MAX_ANCESTOR_DEPTH) {
            result.add(handle.pid())
            handle = try {
                handle.parent().orElse(null)
            } catch (_: Throwable) {
                null
            }
            depth++
        }
        return result
    }

    private fun terminalWidgets(project: Project): List<Any> = try {
        val cls = Class.forName(
            "org.jetbrains.plugins.terminal.TerminalToolWindowManager",
            false,
            javaClass.classLoader,
        )
        val instance = cls.methods
            .firstOrNull { it.name == "getInstance" && it.parameterCount == 1 }
            ?.invoke(null, project)
        sequenceOf("getWidgets", "getTerminalWidgets")
            .mapNotNull { name ->
                instance?.javaClass?.methods
                    ?.firstOrNull { it.name == name && it.parameterCount == 0 }
                    ?.invoke(instance) as? Collection<*>
            }
            .flatMap { it.asSequence() }
            .filterNotNull()
            .distinct()
            .toList()
    } catch (e: Throwable) {
        log.warn("MetaJetCore: не удалось перечислить вкладки терминала", e)
        emptyList()
    }

    /**
     * pid процесса шелла, запущенного во вкладке.
     *
     * Путь: виджет -> TtyConnector -> ProcessTtyConnector.getProcess() -> Process.pid().
     * Оба звена стабильны: первое — JediTerm, второе — JDK начиная с девятой версии.
     */
    private fun shellPid(widget: Any): Long? {
        for (candidate in listOf(widget) + unwrapped(widget)) {
            for (accessor in listOf("getProcessTtyConnector", "getTtyConnector")) {
                try {
                    val connector = candidate.javaClass.methods
                        .firstOrNull { it.name == accessor && it.parameterCount == 0 }
                        ?.invoke(candidate) ?: continue
                    val process = connector.javaClass.methods
                        .firstOrNull { it.name == "getProcess" && it.parameterCount == 0 }
                        ?.invoke(connector) as? Process ?: continue
                    return process.pid()
                } catch (_: Throwable) {
                    // следующий способ
                }
            }
        }
        return null
    }

    private fun unwrapped(widget: Any): List<Any> = try {
        sequenceOf("asNewWidget", "getJBTerminalWidget", "getTerminalWidget")
            .mapNotNull { name ->
                widget.javaClass.methods
                    .firstOrNull { it.name == name && it.parameterCount == 0 }
                    ?.invoke(widget)
            }
            .filter { it !== widget }
            .distinct()
            .toList()
    } catch (_: Throwable) {
        emptyList()
    }
}
