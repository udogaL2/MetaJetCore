package dev.metajetcore.harness

import com.intellij.openapi.diagnostic.Logger
import dev.metajetcore.roles.Role
import dev.metajetcore.roles.RoleInstaller
import dev.metajetcore.roles.Roles
import dev.metajetcore.util.Json
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.name

/**
 * Всё, что плагин знает о файлах Codex: профили ролей и ленты сессий (rollout).
 *
 * Проверено на Codex 0.166 (docs/PLAN-0.4.md §1.4, §4.6). Форматы ниже — не публичный
 * контракт Codex, поэтому разбор терпимый: неизвестная строка пропускается, а не роняет
 * чтение. Худшее последствие поломки формата — агент «молчит», а не падает плагин.
 */
object Codex {
    private val log = Logger.getInstance(Codex::class.java)

    /** Для тестов: подставной каталог вместо настоящего ~/.codex. */
    @Volatile
    internal var homeOverride: Path? = null

    fun home(): Path = homeOverride
        ?: System.getenv("CODEX_HOME")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
        ?: Paths.get(System.getProperty("user.home"), ".codex")

    fun sessionsDirectory(): Path = home().resolve("sessions")

    // ------------------------------------------------------------- profiles

    /**
     * Имя профиля роли: `codex -p mjc-reviewer` читает `~/.codex/mjc-reviewer.config.toml`.
     *
     * Профиль, а не `-c developer_instructions=…` в командной строке: промпт роли длинный,
     * многострочный и на русском, и пропускать его через строку шелла значило бы вернуть
     * весь зоопарк экранирования, от которого мы ушли (PowerShell и кавычки). Файл Codex
     * читает сам, как UTF-8, — ровно как Claude читает `~/.claude/agents`.
     */
    fun profileName(role: Role): String = RoleInstaller.PREFIX + role.id

    fun profilePath(role: Role): Path = home().resolve("${profileName(role)}.config.toml")

    internal fun profileText(role: Role): String = buildString {
        append("# Роль MetaJetCore. Файл переписывается плагином при старте IDE — правки потеряются.\n")
        append("developer_instructions = ")
        append(tomlString(Roles.promptFor(role, Harness.CODEX)))
        append("\n")
    }

    /** Возвращает число переписанных файлов. */
    fun installProfiles(): Int {
        var changed = 0
        for (role in Role.entries - Role.ORCHESTRATOR) {
            val path = profilePath(role)
            try {
                val desired = profileText(role)
                val current = if (Files.exists(path)) Files.readString(path, StandardCharsets.UTF_8) else null
                if (current != desired) {
                    Files.createDirectories(path.parent)
                    Files.write(path, desired.toByteArray(StandardCharsets.UTF_8))
                    changed++
                }
            } catch (e: Exception) {
                log.warn("MetaJetCore: cannot write $path", e)
            }
        }
        return changed
    }

    /**
     * Базовая строка TOML с экранированием. Не литерал `'''…'''`: тот нельзя экранировать, и
     * промпт с тремя апострофами подряд молча сломал бы файл.
     */
    internal fun tomlString(text: String): String = buildString {
        append('"')
        for (ch in text) {
            when {
                ch == '"' -> append("\\\"")
                ch == BACKSLASH -> append("\\\\")
                ch == '\n' -> append("\\n")
                ch == '\t' -> append("\\t")
                ch == '\r' -> append("\\r")
                ch.code < 0x20 || ch.code == 0x7f -> append("\\u%04x".format(ch.code))
                else -> append(ch)
            }
        }
        append('"')
    }

    /**
     * Значение для `-c`, которое помечает каталог доверенным на один запуск.
     *
     * Inline-таблица, а не `projects."<путь>".trust_level`: ключ `-c` Codex режет по точкам,
     * а в пути они бывают (`/Users/e.shatura/...`), и вопрос о доверии оставался — проверено.
     * В `~/.codex/config.toml` ничего не пишем: это файл пользователя.
     */
    fun trustOverride(directory: Path): String =
        "projects={${tomlString(directory.toString())}={trust_level=\"trusted\"}}"

    // ------------------------------------------------------------- rollouts

    /** Сессия Codex: UUID треда и файл его ленты. */
    data class Session(val id: String, val rollout: Path)

    /**
     * Новая сессия Codex в каталоге [cwd], начатая не раньше [sinceMillis] и ещё никем не
     * занятая.
     *
     * Адрес Codex-агента — UUID, а имя треда выдумывает модель, поэтому опознать «свою»
     * сессию можно только так: новый файл ленты, тот же каталог, после момента спавна.
     * Два одновременных старта в одном каталоге так не развести — поэтому спавны Codex
     * сериализуются вызывающим.
     */
    fun findNewSession(cwd: Path, sinceMillis: Long, claimed: Set<String>, root: Path = sessionsDirectory()): Session? {
        if (!Files.isDirectory(root)) return null
        val target = normalize(cwd)
        val candidates = try {
            Files.walk(root, 4).use { stream ->
                stream.filter { it.name.startsWith("rollout-") && it.name.endsWith(".jsonl") }
                    .filter { runCatching { Files.getLastModifiedTime(it).toMillis() >= sinceMillis - CLOCK_SLACK_MS }.getOrDefault(false) }
                    .toList()
            }
        } catch (e: Exception) {
            return null
        }
        return candidates.sortedByDescending { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
            .firstNotNullOfOrNull { path ->
                val meta = readMeta(path) ?: return@firstNotNullOfOrNull null
                val (id, metaCwd) = meta
                if (id in claimed || normalize(Paths.get(metaCwd)) != target) null else Session(id, path)
            }
    }

    /** Первая строка ленты — `session_meta` с `id` и `cwd`. */
    internal fun readMeta(path: Path): Pair<String, String>? = try {
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
            val first = reader.readLine() ?: return null
            val payload = Json.parseOrNull(first)?.takeIf { it["type"]?.asString == "session_meta" }?.get("payload")
            val id = payload?.get("id")?.asString ?: return null
            val cwd = payload["cwd"]?.asString ?: return null
            id to cwd
        }
    } catch (e: Exception) {
        null
    }

    private fun normalize(path: Path): String =
        runCatching { path.toRealPath().toString() }.getOrDefault(path.toAbsolutePath().normalize().toString())

    /**
     * Чтение ленты с запомненного места: каждый вызов отдаёт только новое.
     *
     * Читаем до последнего перевода строки, а не до конца файла: Codex дописывает строку не
     * атомарно, и недописанный JSON в середине разбора дал бы пропавшее событие — смещение
     * уже ушло бы за него.
     */
    class RolloutReader(val path: Path) {
        private var offset = 0L

        /** Ход сейчас идёт: последним было начало хода, а не его завершение. */
        @Volatile
        var busy: Boolean = true
            private set

        /** Ответы завершённых ходов (`task_complete.last_agent_message`) с прошлого вызова. */
        @Synchronized
        fun readCompletedTurns(): List<String> {
            val chunk = readChunk() ?: return emptyList()
            val replies = mutableListOf<String>()
            for (line in chunk.lineSequence()) {
                if (line.isBlank()) continue
                val event = Json.parseOrNull(line) ?: continue
                if (event["type"]?.asString != "event_msg") continue
                val payload = event["payload"] ?: continue
                when (payload["type"]?.asString) {
                    "task_started" -> busy = true
                    "task_complete" -> {
                        busy = false
                        // Ход без текста (агент только вызывал инструменты) — тоже ответ: иначе
                        // оркестратор ждал бы сообщения, которого не будет.
                        replies += payload["last_agent_message"]?.asString ?: ""
                    }
                    "turn_aborted" -> {
                        busy = false
                        replies += "(ход прерван)"
                    }
                }
            }
            return replies
        }

        private fun readChunk(): String? = try {
            RandomAccessFile(path.toFile(), "r").use { file ->
                val length = file.length()
                if (length <= offset) return null
                val bytes = ByteArray((length - offset).toInt())
                file.seek(offset)
                file.readFully(bytes)
                val lastNewline = bytes.lastIndexOf('\n'.code.toByte())
                if (lastNewline < 0) return null
                offset += lastNewline + 1
                String(bytes, 0, lastNewline + 1, StandardCharsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }
    }

    private val BACKSLASH: Char = 92.toChar()

    /** Время файла и часы JVM могут расходиться на доли секунды. */
    private const val CLOCK_SLACK_MS = 2_000L
}
