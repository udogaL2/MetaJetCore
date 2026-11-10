package dev.metajetcore.harness

import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import dev.metajetcore.settings.MjcSettings
import dev.metajetcore.shell.ShellDialect
import dev.metajetcore.util.Json
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Какие модели знает харнес — спрашиваем у самого CLI.
 *
 * Справочника моделей у IDE нет: `TerminalAgent` описывает только бинарь и иконку, а выбор
 * модели в агентском чате IDE (модуль `air-plugin`) тоже опрашивает CLI — ровно этими двумя
 * запросами (проверено по jar-ам 2026.3). Брать список у `air-plugin` рефлексией нельзя: это
 * внутренний код отдельного плагина, которого в IDE может и не быть. Свой зашитый список
 * устарел бы с первым же релизом моделей.
 *
 * - Claude Code: управляющий запрос `list_models` в режиме `-p --input-format stream-json`.
 * - Codex: `codex app-server`, JSON-RPC `model/list`.
 *
 * Оба запроса не трогают модель и не тратят токены — отвечают за 1–3 секунды. Запускаем через
 * шелл и команду из настроек, как агентов: у разработчика может быть обёртка.
 *
 * Не ответил — не беда: экран настроек остаётся на запасном списке и ручном вводе.
 */
object ModelCatalog {
    private val log = Logger.getInstance(ModelCatalog::class.java)

    data class Model(val id: String, val displayName: String, val description: String = "")

    /** Пока CLI не ответил или не ответил вовсе. У Codex списка без CLI нет: модели у него свои. */
    fun fallback(harness: Harness): List<Model> = when (harness) {
        Harness.CLAUDE -> listOf(
            Model("opus", "Opus"),
            Model("sonnet", "Sonnet"),
            Model("haiku", "Haiku"),
            Model("fable", "Fable"),
        )
        Harness.CODEX, Harness.B24 -> emptyList()
    }

    private val cache = HashMap<String, List<Model>>()
    private val inFlight = HashMap<String, CompletableFuture<List<Model>?>>()

    /** Ключ включает команду: сменили обёртку — список могли бы отдать другой. */
    private fun key(harness: Harness, command: String) = harness.key + "\u0000" + command

    @Synchronized
    fun cached(harness: Harness, command: String): List<Model>? = cache[key(harness, command)]

    /**
     * Список моделей; null — CLI не ответил. Повторные вызовы до ответа получают тот же future,
     * удачный ответ живёт до перезапуска IDE, неудачный не кэшируется — следующее открытие
     * настроек спросит снова.
     */
    @Synchronized
    fun load(harness: Harness, command: String, shellPath: String): CompletableFuture<List<Model>?> {
        val key = key(harness, command)
        cache[key]?.let { return CompletableFuture.completedFuture(it) }
        inFlight[key]?.let { return it }
        val future = CompletableFuture<List<Model>?>()
        inFlight[key] = future
        AppExecutorUtil.getAppExecutorService().execute {
            val models = try {
                probe(harness, command, shellPath)
            } catch (e: Throwable) {
                log.info("MetaJetCore: список моделей ${harness.displayName} получить не вышло", e)
                null
            }
            synchronized(this) {
                if (!models.isNullOrEmpty()) cache[key] = models
                inFlight.remove(key)
            }
            future.complete(models?.takeIf { it.isNotEmpty() })
        }
        return future
    }

    private fun probe(harness: Harness, command: String, shellPath: String): List<Model>? {
        val line = when (harness) {
            Harness.CLAUDE -> "$command -p --input-format stream-json --output-format stream-json --verbose"
            Harness.CODEX -> "$command app-server"
            // Модели b24 перечислены в его settings.json — запускать CLI ради списка незачем.
            Harness.B24 -> return B24.models()
        }
        val argv = ShellDialect.detect(shellPath).argv(shellPath, "", line)
        val builder = ProcessBuilder(argv)
            // Домашний каталог, а не проект: запрос к проекту отношения не имеет, а сессия
            // Claude в каталоге проекта попала бы в list_agents оркестратора.
            .directory(File(System.getProperty("user.home")))
            .redirectError(ProcessBuilder.Redirect.DISCARD)
        stripInherited(builder.environment())
        val process = builder.start()
        try {
            val result = CompletableFuture<List<Model>?>()
            thread(isDaemon = true, name = "MetaJetCore model probe") {
                try {
                    process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                        for (text in lines) {
                            parse(harness, text)?.let { result.complete(it); return@useLines }
                        }
                    }
                } catch (_: Exception) {
                    // Процесс убит по таймауту — поток закрылся под ногами, это штатно.
                }
                result.complete(null)
            }
            // stdin не закрываем: `claude -p`, получив EOF без сообщения, выходит раньше, чем
            // ответит на управляющий запрос.
            process.outputStream.bufferedWriter(StandardCharsets.UTF_8).apply {
                for (request in requests(harness)) write(request + "\n")
                flush()
            }
            return result.get(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            log.info("MetaJetCore: ${harness.displayName} не отдал список моделей за $PROBE_TIMEOUT_S с")
            return null
        } finally {
            process.descendants().forEach { it.destroy() }
            process.destroy()
        }
    }

    /** То же, что снимаем у агентов: чужая сессия Claude в окружении IDE и ключи API. */
    private fun stripInherited(env: MutableMap<String, String>) {
        val settings = MjcSettings.getInstance()
        val prefixes = buildList {
            if (settings.stripInheritedClaudeMarkers) add("CLAUDE")
            if (settings.stripApiKeys) add("ANTHROPIC_")
        }
        env.keys.removeIf { key -> prefixes.any { key.startsWith(it) } }
    }

    private fun requests(harness: Harness): List<String> = when (harness) {
        Harness.CLAUDE -> listOf(
            """{"type":"control_request","request_id":"mjc-init","request":{"subtype":"initialize"}}""",
            """{"type":"control_request","request_id":"mjc-models","request":{"subtype":"list_models"}}""",
        )
        Harness.CODEX -> listOf(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"clientInfo":{"name":"metajetcore","version":"1"}}}""",
            """{"jsonrpc":"2.0","method":"initialized"}""",
            """{"jsonrpc":"2.0","id":2,"method":"model/list","params":{}}""",
        )
        Harness.B24 -> emptyList()
    }

    /** Строка ответа CLI → список моделей; null — строка не та (шелл может напечатать что угодно). */
    internal fun parse(harness: Harness, line: String): List<Model>? {
        val event = Json.parseOrNull(line.trim()) ?: return null
        return when (harness) {
            Harness.CLAUDE -> {
                if (event["type"]?.asString != "control_response") return null
                // Список моделей отдаёт и ответ на initialize, и на list_models — берём первый.
                val models = event["response"]?.get("response")?.get("models")?.asList ?: return null
                models.mapNotNull { m ->
                    val id = m["value"]?.asString?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    Model(id, m["displayName"]?.asString ?: id, m["description"]?.asString.orEmpty())
                }
            }
            Harness.CODEX -> {
                if (event["id"]?.render() != "2") return null
                val result = event["result"] ?: return emptyList()
                val models = (result["data"] ?: result["models"])?.asList ?: return emptyList()
                models.mapNotNull { m ->
                    if (m["hidden"]?.asBoolean == true) return@mapNotNull null
                    val id = (m["id"] ?: m["model"])?.asString?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val isDefault = m["isDefault"]?.asBoolean == true
                    Model(id, m["displayName"]?.asString ?: id, if (isDefault) "по умолчанию в Codex" else "")
                }
            }
            Harness.B24 -> null
        }
    }

    private const val PROBE_TIMEOUT_S = 20L
}
