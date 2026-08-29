package dev.metajetcore.registry

import dev.metajetcore.util.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Одна запись из ~/.claude/sessions/<pid>.json.
 *
 * Это единственный документированный на практике способ узнать, какие сессии Claude Code
 * живы, как их зовут и готовы ли они принимать ввод. Мы читаем реестр, но никогда в него
 * не пишем: он принадлежит Claude Code.
 */
data class SessionRecord(
    val pid: Int,
    val sessionId: String,
    val name: String?,
    val cwd: String?,
    val kind: String?,
    val agent: String?,
    val status: String?,
    val version: String?,
    val startedAt: Long?,
) {
    val isIdle: Boolean get() = status == "idle"
    val isBusy: Boolean get() = status == "busy"

    companion object {
        fun parse(text: String): SessionRecord? {
            val root = Json.parseOrNull(text) as? Json.Obj ?: return null
            val pid = root["pid"]?.asInt ?: return null
            val sessionId = root["sessionId"]?.asString ?: return null
            return SessionRecord(
                pid = pid,
                sessionId = sessionId,
                name = root["name"]?.asString,
                cwd = root["cwd"]?.asString,
                kind = root["kind"]?.asString,
                agent = root["agent"]?.asString,
                status = root["status"]?.asString,
                version = root["version"]?.asString,
                startedAt = root["startedAt"]?.asLong,
            )
        }
    }
}

/**
 * Чтение реестра сессий Claude Code.
 *
 * Никакого watch-механизма платформы: обычный опрос директории. Реестр — это несколько
 * маленьких файлов, опрос раз в секунду стоит ничего, а зависимости от VirtualFileSystem
 * и её изменений между версиями IDE у нас не появляется.
 */
object SessionRegistry {

    /** Переопределяется в тестах; в проде — ~/.claude/sessions. */
    var directoryOverride: Path? = null

    /**
     * Жив ли процесс сессии. Переопределяется в тестах.
     *
     * Запись из реестра удаляет сама сессия при выходе, поэтому после падения (kill, обрыв
     * питания, зависший процесс) файл остаётся. Такой «призрак» и в `list_agents` выглядит
     * как рабочий агент, и навсегда занимает имя в [isNameTaken] — а имена глобальны на
     * машину. Дешевле всего проверить pid: ProcessHandle — чистый JDK.
     */
    val defaultLivenessCheck: (Int) -> Boolean = { pid ->
        try {
            ProcessHandle.of(pid.toLong()).map { it.isAlive }.orElse(false)
        } catch (_: Throwable) {
            // Не смогли проверить — считаем живой: потерять живого агента хуже, чем
            // показать мёртвого.
            true
        }
    }

    var isProcessAlive: (Int) -> Boolean = defaultLivenessCheck

    fun directory(): Path {
        directoryOverride?.let { return it }
        val home = System.getenv("CLAUDE_CONFIG_DIR")?.takeIf { it.isNotBlank() }
            ?.let { Paths.get(it) }
            ?: Paths.get(System.getProperty("user.home"), ".claude")
        return home.resolve("sessions")
    }

    fun all(): List<SessionRecord> {
        val dir = directory()
        if (!Files.isDirectory(dir)) return emptyList()
        return try {
            Files.list(dir).use { stream ->
                stream.filter { it.isRegularFile() && it.extension == "json" }
                    .map { path -> readRecord(path) }
                    .filter { it != null && isProcessAlive(it.pid) }
                    .map { it!! }
                    .toList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Сессии, работающие в указанной директории. Сравнение путей нормализованное. */
    fun inDirectory(cwd: Path): List<SessionRecord> {
        val target = normalize(cwd.toString())
        return all().filter { record -> record.cwd?.let { normalize(it) } == target }
    }

    fun byName(name: String): List<SessionRecord> = all().filter { it.name == name }

    fun findByName(name: String): SessionRecord? {
        val matches = byName(name)
        return matches.singleOrNull() ?: matches.firstOrNull()
    }

    /** Занято ли имя прямо сейчас. Имена сессий глобальны на машину. */
    fun isNameTaken(name: String): Boolean = byName(name).isNotEmpty()

    private fun readRecord(path: Path): SessionRecord? = try {
        // Файл могут переписать прямо во время чтения — тогда просто пропускаем его
        // до следующего опроса, а не роняем весь список.
        //
        // BOM снимаем: JSON его не допускает, а записать файл с ним может кто угодно —
        // например, PowerShell при `Set-Content -Encoding utf8`. Нераспарсенная запись
        // означает невидимого агента, и разбираться в такой пропаже крайне неприятно.
        SessionRecord.parse(Files.readString(path).removePrefix("﻿"))
    } catch (_: Exception) {
        null
    }

    private fun normalize(path: String): String =
        path.replace('\\', '/').trimEnd('/').lowercase()

    /** Диагностика: имя файла реестра для pid, если такой есть. */
    fun fileNameFor(pid: Int): String? {
        val dir = directory()
        if (!Files.isDirectory(dir)) return null
        return try {
            Files.list(dir).use { stream ->
                stream.filter { it.name == "$pid.json" }.findFirst().orElse(null)?.name
            }
        } catch (_: Exception) {
            null
        }
    }
}
