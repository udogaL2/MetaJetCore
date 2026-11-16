package dev.metajetcore.terminal

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.metajetcore.registry.SessionRecord

/**
 * Находит вкладку, в которой живёт конкретная сессия Claude Code, — даже если эту вкладку
 * плагин не создавал (оркестратор запускается просто `/orchestrate` в любой вкладке).
 *
 * Как: реестр сессий даёт pid процесса `claude`, а вкладка — pid своего шелла
 * (`startupOptionsDeferred.pid`). Если шелл оказался среди предков claude, значит сессия
 * живёт в этой вкладке. Работа с процессами — чистый JDK, от версии IDE не зависит.
 *
 * **Только по процессу, никогда по имени или заголовку вкладки.** Имя не уникально и
 * меняется: поиск по нему находил вкладку тёзки — чужой сессии, — и плагин действовал в ней
 * (docs/PLAN-TEAM.md). Отсюда поиск по имени убран совсем.
 */
/** Вкладка сессии и запись реестра, если её удалось определить однозначно. */
data class SessionTab(val handle: TabHandle, val record: SessionRecord?)

object TabResolver {
    private val log = Logger.getInstance(TabResolver::class.java)

    /** Глубина подъёма по предкам: claude может быть внуком шелла, если запущен через обёртку. */
    private const val MAX_ANCESTOR_DEPTH = 8

    /**
     * Вкладка терминала конкретной сессии — по её процессу. Для вызывающей сессии, которую
     * определили по соединению (docs/PLAN-CALLER.md): имя здесь не нужно и не годится.
     * null — сессия не в терминале IDE (например, во вкладке AIR) или вкладка не нашлась.
     */
    fun resolveRecord(project: Project, record: SessionRecord): SessionTab? {
        val handles = Terminal.handles(project)
        if (handles.isEmpty()) return null
        return match(handles, record, record.name ?: record.sessionId)
    }

    private fun match(handles: List<TabHandle>, record: SessionRecord, name: String): SessionTab? {
        val ancestors = ancestorPids(record.pid.toLong())
        for (handle in handles) {
            val pid = Terminal.process(handle)?.pid ?: continue
            if (pid in ancestors) {
                log.info("MetaJetCore: '$name' (pid ${record.pid}) живёт во вкладке с шеллом $pid")
                return SessionTab(handle.renamed(name), record)
            }
        }
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
}
