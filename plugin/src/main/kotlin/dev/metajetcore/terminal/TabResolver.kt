package dev.metajetcore.terminal

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.metajetcore.registry.SessionRecord
import dev.metajetcore.registry.SessionRegistry

/**
 * Находит вкладку, в которой живёт сессия Claude Code с заданным именем, — даже если эту
 * вкладку плагин не создавал.
 *
 * Зачем: запуск оркестратора должен оставаться просто `/orchestrate` в любой вкладке. Иначе
 * разработчику пришлось бы каждый раз открывать вкладку действием плагина, только чтобы тот
 * знал, рядом с чем размещать агентов.
 *
 * Как: реестр сессий даёт pid процесса `claude`, а вкладка — pid своего шелла
 * (`startupOptionsDeferred.pid`). Если шелл оказался среди предков claude, значит сессия
 * живёт в этой вкладке. Работа с процессами — чистый JDK, от версии IDE не зависит.
 *
 * Запасной путь — сравнение с заголовком вкладки: он совпадает с именем сессии у вкладок,
 * которые плагин создал сам, и у тех, что переименовал разработчик.
 */
/** Вкладка сессии и запись реестра, если её удалось определить однозначно. */
data class SessionTab(val handle: TabHandle, val record: SessionRecord?)

object TabResolver {
    private val log = Logger.getInstance(TabResolver::class.java)

    /** Глубина подъёма по предкам: claude может быть внуком шелла, если запущен через обёртку. */
    private const val MAX_ANCESTOR_DEPTH = 8

    fun findTabForSession(project: Project, sessionName: String): TabHandle? =
        resolve(project, sessionName)?.handle

    /**
     * Вкладка сессии и запись реестра, по которой она нашлась.
     *
     * Запись здесь важнее вкладки. Имя сессии задаёт человек и оно **не уникально** — «слитие
     * мастера» бывает сразу в трёх вкладках, — а `sessionId` уникален и переживает resume.
     * Сопоставление по дереву процессов это единственное место, где тёзок можно развести:
     * pid из записи ведёт к конкретному процессу `claude`, а тот — к конкретной вкладке.
     * Раз уж мы всё равно это вычисляем, отдаём наружу и запись — по ней строится связь
     * «агент → его оркестратор», которую по имени построить нельзя в принципе.
     */
    fun resolve(project: Project, sessionName: String): SessionTab? {
        // Терминалы и тулвиндоу, и editor area: оркестратор чаще всего именно во втором,
        // а getTabs() его не показывает (см. Terminal.handles).
        val handles = Terminal.handles(project)
        if (handles.isEmpty()) return null

        // Перебираем ВСЕ записи с этим именем: взять первую значило бы промахнуться мимо
        // вкладки ровно в тех случаях, ради которых поиск и написан.
        for (record in SessionRegistry.byName(sessionName)) {
            val ancestors = ancestorPids(record.pid.toLong())
            for (handle in handles) {
                val pid = Terminal.process(handle)?.pid ?: continue
                if (pid in ancestors) {
                    log.info("MetaJetCore: '$sessionName' (pid ${record.pid}) живёт во вкладке с шеллом $pid")
                    return SessionTab(handle.renamed(sessionName), record)
                }
            }
        }

        val byTitle = handles.firstOrNull { it.name == sessionName }
        if (byTitle != null) {
            // Заголовок вкладки к процессу не привязан, поэтому запись отсюда взять нельзя:
            // тёзок он не разводит, а связать агента не с тем оркестратором хуже, чем не
            // связать вовсе.
            log.info("MetaJetCore: '$sessionName' сопоставлен по заголовку вкладки, запись реестра неизвестна")
            return SessionTab(byTitle, null)
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
}
