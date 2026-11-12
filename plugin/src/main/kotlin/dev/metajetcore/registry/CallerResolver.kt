package dev.metajetcore.registry

import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.TimeUnit

/**
 * Какая сессия Claude Code вызвала инструмент — по TCP-соединению запроса, без имени.
 *
 * Имя, которое оркестратор присылает в `parent`, для связи «агент → оркестратор» ненадёжно:
 * после `/rename` модель продолжает передавать старое, и сессию по нему уже не найти
 * (docs/PLAN-CALLER.md). Процесс же не врёт. MCP-сервер — HTTP внутри IDE, общий на все сессии,
 * поэтому подняться к вызывающему по дереву процессов нельзя; зато у запроса есть порт клиента,
 * и, пока запрос обрабатывается, соединение открыто: системная утилита назовёт pid его
 * владельца. Проверено (2.1.322, macOS): клиентская сторона — сам процесс `claude`, pid
 * совпадает с именем файла реестра.
 *
 * Утилиты, а не JDK: в JDK нет способа узнать владельца чужого сокета. Любой сбой — null и
 * запасной путь по имени; исключение наружу не летит.
 */
object CallerResolver {
    private val log = Logger.getInstance(CallerResolver::class.java)

    /** claude может держать MCP-клиент в дочернем процессе или за обёрткой — поднимаемся. */
    private const val MAX_ANCESTOR_DEPTH = 6
    private const val COMMAND_TIMEOUT_S = 3L

    /**
     * Запись реестра сессии, открывшей соединение с порта [clientPort] к серверу на
     * [serverPort]. null — не определили однозначно.
     */
    fun recordFor(clientPort: Int, serverPort: Int): SessionRecord? = try {
        val self = ProcessHandle.current().pid()
        val pids = pidsOnPort(clientPort, serverPort).filter { it != self }.distinct()
        recordForPids(pids).also {
            log.info("MetaJetCore: вызывающий по порту $clientPort: pid $pids → ${it?.let { r -> "${r.name} (${r.sessionId})" } ?: "не определён"}")
        }
    } catch (e: Throwable) {
        log.info("MetaJetCore: вызывающего по порту $clientPort определить не вышло: ${e.message}")
        null
    }

    /**
     * Запись реестра по pid процессов соединения: сам процесс или его предок. Несколько разных
     * сессий — null: связь наугад хуже отсутствия связи.
     */
    internal fun recordForPids(pids: List<Long>): SessionRecord? {
        if (pids.isEmpty()) return null
        val records = SessionRegistry.all().associateBy { it.pid.toLong() }
        val found = pids.mapNotNull { pid -> ancestors(pid).firstNotNullOfOrNull { records[it] } }
        return found.distinctBy { it.sessionId }.singleOrNull()
    }

    private fun ancestors(pid: Long): Sequence<Long> = sequence {
        var handle = try {
            ProcessHandle.of(pid).orElse(null)
        } catch (_: Throwable) {
            null
        }
        var depth = 0
        // Сам pid — первым, даже если процесс уже не виден: запись реестра могла остаться.
        yield(pid)
        handle = handle?.let { runCatching { it.parent().orElse(null) }.getOrNull() }
        while (handle != null && depth < MAX_ANCESTOR_DEPTH) {
            yield(handle.pid())
            handle = runCatching { handle!!.parent().orElse(null) }.getOrNull()
            depth++
        }
    }

    /** Процессы, у которых открыт сокет с локальным портом [clientPort]. */
    internal fun pidsOnPort(clientPort: Int, serverPort: Int): List<Long> {
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
        if (windows) {
            return run(listOf("netstat", "-ano", "-p", "TCP"))?.let { parseNetstat(it, clientPort, serverPort) }.orEmpty()
        }
        // lsof есть на macOS всегда, на Linux — почти всегда; ss — запасной путь Linux.
        // Полные пути — на случай урезанного PATH у процесса IDE.
        for (lsof in listOf("lsof", "/usr/sbin/lsof", "/usr/bin/lsof")) {
            val out = run(listOf(lsof, "-nP", "-iTCP:$clientPort", "-sTCP:ESTABLISHED", "-Fpn")) ?: continue
            return parseLsof(out, clientPort, serverPort)
        }
        return run(listOf("ss", "-tnpH", "state", "established"))?.let { parseSs(it, clientPort, serverPort) }.orEmpty()
    }

    /**
     * `lsof -Fpn`: строки `p<pid>`, за ними `n<локальный>-><удалённый>` по каждому сокету.
     * Берём процессы, у которых локальный порт — клиентский, а удалённый — наш сервер: под
     * `-iTCP:<порт>` попадает и сама IDE (у неё этот порт удалённый).
     */
    internal fun parseLsof(output: String, clientPort: Int, serverPort: Int): List<Long> {
        val result = ArrayList<Long>()
        var pid: Long? = null
        for (line in output.lineSequence()) {
            when {
                line.startsWith("p") -> pid = line.substring(1).trim().toLongOrNull()
                line.startsWith("n") && pid != null -> {
                    val (local, remote) = line.substring(1).split("->").takeIf { it.size == 2 } ?: continue
                    if (portOf(local) == clientPort && portOf(remote) == serverPort) result += pid!!
                }
            }
        }
        return result.distinct()
    }

    /** `netstat -ano`: `TCP  127.0.0.1:50418  127.0.0.1:43745  ESTABLISHED  1234`. */
    internal fun parseNetstat(output: String, clientPort: Int, serverPort: Int): List<Long> =
        output.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 5 || !parts[0].equals("TCP", ignoreCase = true)) return@mapNotNull null
            if (portOf(parts[1]) == clientPort && portOf(parts[2]) == serverPort) parts.last().toLongOrNull() else null
        }.distinct().toList()

    /** `ss -tnpH`: `0 0 127.0.0.1:50418 127.0.0.1:43745 users:(("claude",pid=123,fd=6))`. */
    internal fun parseSs(output: String, clientPort: Int, serverPort: Int): List<Long> =
        output.lineSequence().flatMap { line ->
            val parts = line.trim().split(Regex("\\s+"))
            val local = parts.indexOfFirst { portOf(it) == clientPort }
            if (local < 0 || parts.getOrNull(local + 1)?.let(::portOf) != serverPort) emptySequence()
            else Regex("pid=(\\d+)").findAll(line).mapNotNull { it.groupValues[1].toLongOrNull() }
        }.distinct().toList()

    /** Порт из `127.0.0.1:50418`, `[::1]:50418`, `*:50418`. */
    private fun portOf(endpoint: String): Int? = endpoint.substringAfterLast(':').trim().toIntOrNull()

    /**
     * Вывод — в файл, а не чтением потока: чтение заблокировало бы поток до конца процесса, и
     * таймаут не сработал бы, а повисший lsof (сетевая ФС) держал бы спавн оркестратора.
     */
    private fun run(command: List<String>): String? {
        val out = java.io.File.createTempFile("mjc-caller", ".txt")
        return try {
            val process = ProcessBuilder(command)
                .redirectOutput(out)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            if (!process.waitFor(COMMAND_TIMEOUT_S, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                null
            } else {
                out.readText()
            }
        } catch (_: Exception) {
            // Нет такой утилиты — пробуем следующую.
            null
        } finally {
            out.delete()
        }
    }
}
