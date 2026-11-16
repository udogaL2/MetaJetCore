package dev.metajetcore.util

import java.util.concurrent.TimeUnit

/**
 * Запуск системных утилит (`lsof`, `ss`, `netstat`) с таймаутом.
 *
 * Нужны там, где JDK бессилен: узнать владельца чужого сокета или файлы, открытые чужим
 * процессом. Любой сбой — null, исключение наружу не летит.
 */
object Commands {
    private const val TIMEOUT_S = 3L

    /** Есть ли `lsof` — на macOS всегда, на Linux почти всегда, на Windows нет. */
    val lsof: String? by lazy {
        listOf("lsof", "/usr/sbin/lsof", "/usr/bin/lsof").firstOrNull { run(listOf(it, "-v")) != null }
    }

    /**
     * Вывод — в файл, а не чтением потока: чтение заблокировало бы поток до конца процесса, и
     * таймаут не сработал бы, а повисший lsof (сетевая ФС) держал бы спавн оркестратора.
     */
    fun run(command: List<String>): String? {
        val out = java.io.File.createTempFile("mjc-cmd", ".txt")
        return try {
            val process = ProcessBuilder(command)
                .redirectOutput(out)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            if (!process.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                null
            } else {
                out.readText()
            }
        } catch (_: Exception) {
            // Нет такой утилиты.
            null
        } finally {
            out.delete()
        }
    }

    /**
     * Файлы, открытые процессами [pids] (`lsof -Fn`). null — узнать нечем (нет lsof);
     * пустой список — узнали, открытых файлов нет.
     */
    fun openFiles(pids: Collection<Long>): List<String>? {
        if (pids.isEmpty()) return emptyList()
        val lsof = lsof ?: return null
        // lsof выходит с 1, если часть pid уже нет, — вывод по остальным при этом полный.
        val out = run(listOf(lsof, "-nP", "-Fn", "-p", pids.joinToString(","))) ?: return null
        return out.lineSequence().filter { it.startsWith("n/") }.map { it.substring(1) }.distinct().toList()
    }
}
