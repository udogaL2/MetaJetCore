package dev.metajetcore.harness

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import dev.metajetcore.util.Json
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * Всё, что плагин знает о b24-cowork-code-cli (форк Qwen Code): канал «dual output» и модели.
 *
 * Проверено на 0.26.7-b24.12 (docs/EXPERIMENT-B24-CLI.md). Канал — два файла, которые CLI
 * держит рядом со своим TUI: в `--input-file` мы дописываем JSONL-команды, из `--json-file`
 * читаем события в формате `stream-json`. Терминал для содержательного обмена не нужен вовсе,
 * поэтому нет и ограничения «только ASCII».
 *
 * Формат событий — не публичный контракт внутренней сборки, поэтому разбор терпимый:
 * неизвестное событие пропускается. Худшее последствие поломки — агент «молчит».
 */
object B24 {
    private val log = Logger.getInstance(B24::class.java)

    /** Для тестов: подставной каталог каналов и конфиг вместо настоящих. */
    @Volatile
    internal var channelsOverride: Path? = null

    @Volatile
    internal var homeOverride: Path? = null

    /**
     * Каталог каналов — системный каталог IDE, а не проект и не `~/.b24-cowork-code`: файлы
     * живут ровно столько, сколько агент, в git им не место, а каталог CLI — не наш.
     */
    fun channelsRoot(): Path = channelsOverride
        ?: Paths.get(PathManager.getSystemPath(), "metajetcore", "b24")

    /** Файлы канала одного агента. Каталог по UUID сессии: имя агента не уникально во времени. */
    class Channel(val dir: Path) {
        val input: Path = dir.resolve("input.jsonl")
        val events: Path = dir.resolve("events.jsonl")

        fun create() {
            Files.createDirectories(dir)
            // Оба файла — до старта CLI: input он только опрашивает и пустым его не создаст,
            // а events мы начинаем читать сразу, ещё до первой записи.
            Files.write(input, ByteArray(0))
            Files.write(events, ByteArray(0))
        }

        /** Целиком, а не два своих файла: CLI или обёртка вправе положить рядом что-то своё. */
        fun delete() {
            try {
                if (Files.exists(dir)) {
                    Files.walk(dir).use { paths ->
                        paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                    }
                }
            } catch (e: Exception) {
                log.info("MetaJetCore: канал b24 $dir не удалён: ${e.message}")
            }
        }

        /**
         * Дописать команду. Целой строкой одним вызовом: CLI читает файл до последнего
         * перевода строки, и недописанная строка просто подождёт следующего опроса.
         */
        fun submit(text: String) = append(Json.obj("type" to Json.of("submit"), "text" to Json.of(text)))

        private fun append(command: Json) {
            Files.write(
                input,
                (command.render() + "\n").toByteArray(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND,
            )
        }
    }

    fun channel(sessionId: String): Channel = Channel(channelsRoot().resolve(sessionId))

    /**
     * Режим прав плагина → `--approval-mode` b24. null — флаг не передаём.
     *
     * Соответствие по смыслу, а не по имени: `acceptEdits` у b24 называется `auto-edit`,
     * `bypassPermissions` — `yolo`.
     */
    fun approvalMode(permissionMode: String): String? = when (permissionMode.trim()) {
        "auto" -> "auto"
        "acceptEdits" -> "auto-edit"
        "plan" -> "plan"
        "bypassPermissions" -> "yolo"
        "default" -> "default"
        else -> null
    }

    // ------------------------------------------------------------- models

    fun home(): Path = homeOverride ?: Paths.get(System.getProperty("user.home"), ".b24-cowork-code")

    /**
     * Модели из `settings.json` CLI: `modelProviders.<провайдер>[].id`. Файл только читаем — он
     * принадлежит CLI. Запускать CLI ради списка не нужно: модели Vibecode перечислены там.
     */
    fun models(): List<ModelCatalog.Model> = try {
        val file = home().resolve("settings.json")
        if (!Files.exists(file)) {
            emptyList()
        } else {
            val root = Json.parseOrNull(Files.readString(file, StandardCharsets.UTF_8))
            val providers = root?.get("modelProviders")?.asMap.orEmpty()
            providers.values.flatMap { it.asList.orEmpty() }.mapNotNull { entry ->
                val id = entry["id"]?.asString?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ModelCatalog.Model(id, entry["name"]?.asString ?: id)
            }.distinctBy { it.id }
        }
    } catch (e: Exception) {
        log.info("MetaJetCore: модели b24 не прочитаны: ${e.message}")
        emptyList()
    }

    // ------------------------------------------------------------- events

    /**
     * Чтение ленты событий с запомненного места.
     *
     * Конец хода — НЕ просто `message_stop`: так заканчивается каждое обращение к модели, а ход
     * с инструментом состоит из нескольких (проверено, EXPERIMENT-B24-CLI.md §4). Сообщение с
     * вызовом инструмента несёт блок `tool_use`, после его `message_stop` ход продолжается.
     * Ход закончен на `message_stop` сообщения без `tool_use`, и ответ хода — текст этого
     * последнего сообщения (то же, что `last_agent_message` у Codex).
     */
    class EventsReader(val path: Path, startsBusy: Boolean) {
        private var offset = 0L
        private val text = StringBuilder()
        private var hasToolUse = false

        @Volatile
        var started: Boolean = false
            private set

        /** Ход идёт. Выставляется и снаружи — в момент отправки, см. [markSubmitted]. */
        @Volatile
        var busy: Boolean = startsBusy
            private set

        /** Открытые запросы разрешений: id → инструмент. Агент стоит, пока их не закроют. */
        private val permissions = LinkedHashMap<String, String>()

        @Synchronized
        fun pendingPermissions(): List<String> = permissions.values.toList()

        /**
         * Сообщение только что записано в input-файл. CLI опрашивает его раз в 500 мс, и до
         * первого события нового хода лента выглядела бы как простой — следующее сообщение
         * ушло бы в тот же момент и влилось бы в этот ход.
         */
        @Synchronized
        fun markSubmitted() {
            busy = true
        }

        /** Ответы завершённых ходов с прошлого вызова. */
        @Synchronized
        fun readCompletedTurns(): List<String> {
            val chunk = readChunk() ?: return emptyList()
            val replies = mutableListOf<String>()
            for (line in chunk.lineSequence()) {
                if (line.isBlank()) continue
                val event = Json.parseOrNull(line) ?: continue
                when (event["type"]?.asString) {
                    "system" -> if (event["subtype"]?.asString == "session_start") started = true
                    "user" -> if (isUserText(event)) busy = true
                    "assistant" -> {
                        for (block in event["message"]?.get("content")?.asList.orEmpty()) {
                            when (block["type"]?.asString) {
                                "text" -> block["text"]?.asString?.let {
                                    if (text.isNotEmpty()) text.append('\n')
                                    text.append(it)
                                }
                                "tool_use" -> hasToolUse = true
                            }
                        }
                    }
                    "stream_event" -> when (event["event"]?.get("type")?.asString) {
                        "message_start" -> {
                            busy = true
                            text.setLength(0)
                            hasToolUse = false
                        }
                        "message_stop" -> if (!hasToolUse) {
                            busy = false
                            replies += text.toString().trim()
                            text.setLength(0)
                        }
                    }
                    "control_request" -> {
                        val id = event["request_id"]?.asString
                        if (id != null) permissions[id] = event["request"]?.get("tool_name")?.asString ?: "?"
                    }
                    "control_response" -> event["response"]?.get("request_id")?.asString?.let { permissions.remove(it) }
                }
            }
            return replies
        }

        /** Сообщение пользователя с текстом; `tool_result` тоже приходит как `user`, но ход не начинает. */
        private fun isUserText(event: Json): Boolean =
            event["message"]?.get("content")?.asList.orEmpty().any { it["type"]?.asString == "text" }

        /** До последнего перевода строки: недописанный JSON в середине разбора дал бы пропавшее событие. */
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
}
