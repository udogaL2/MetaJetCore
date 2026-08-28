package dev.metajetcore.mcp

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.metajetcore.settings.MjcSettings
import dev.metajetcore.util.Json
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * MCP-сервер плагина на `com.sun.net.httpserver` из JDK.
 *
 * Почему не HTTP-обвязка платформы: она внутренняя и меняется. HttpServer из JDK не менялся
 * с восьмой Java и не может конфликтовать ни с чем в IDE. Нагрузка тут — единицы запросов
 * в минуту, производительность роли не играет.
 *
 * Транспорт: JSON-RPC поверх POST (streamable HTTP). Отвечаем обычным JSON; SSE не нужен,
 * потому что у нас нет серверных нотификаций.
 */
@Service(Service.Level.PROJECT)
class McpServerService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(McpServerService::class.java)
    private val tools = McpTools(project)

    @Volatile
    private var server: HttpServer? = null

    @Volatile
    var boundPort: Int = -1
        private set

    fun start() {
        if (server != null) return
        val settings = MjcSettings.getInstance()
        val address = if (settings.mcpBindLoopbackOnly) {
            InetSocketAddress(InetAddress.getLoopbackAddress(), settings.mcpPort)
        } else {
            InetSocketAddress(settings.mcpPort)
        }

        try {
            val httpServer = HttpServer.create(address, BACKLOG)
            httpServer.createContext(PATH) { exchange -> handleSafely(exchange) }
            // Небольшой пул: запросы редкие, но spawn может держать поток десятки секунд.
            httpServer.executor = Executors.newFixedThreadPool(4) { runnable ->
                Thread(runnable, "MetaJetCore-MCP").apply { isDaemon = true }
            }
            httpServer.start()
            server = httpServer
            boundPort = httpServer.address.port
            log.info("MetaJetCore MCP server on http://127.0.0.1:$boundPort$PATH")
        } catch (e: Exception) {
            log.warn("MetaJetCore: cannot start MCP server", e)
        }
    }

    fun endpoint(): String? =
        if (boundPort > 0) "http://127.0.0.1:$boundPort$PATH" else null

    /** Команда, которой оркестратор подключается к плагину. */
    fun claudeMcpAddCommand(): String {
        val url = endpoint() ?: return "MCP-сервер не запущен"
        return "claude mcp add --transport http metajetcore $url"
    }

    override fun dispose() {
        server?.stop(0)
        (server?.executor as? java.util.concurrent.ExecutorService)?.let { pool ->
            pool.shutdownNow()
            runCatching { pool.awaitTermination(2, TimeUnit.SECONDS) }
        }
        server = null
        boundPort = -1
    }

    // ------------------------------------------------------------------ HTTP

    private fun handleSafely(exchange: HttpExchange) {
        try {
            handle(exchange)
        } catch (e: Throwable) {
            // Из обработчика никогда не должно вылетать: упавший MCP-сервер выглядит для
            // оркестратора как пропавший инструмент, и он молча теряет способность спавнить.
            log.warn("MetaJetCore: MCP handler failed", e)
            runCatching { respond(exchange, 500, errorResponse(null, -32603, "internal error")) }
        } finally {
            exchange.close()
        }
    }

    private fun handle(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            respond(exchange, 405, errorResponse(null, -32600, "POST expected"))
            return
        }

        val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        val request = Json.parseOrNull(body)
        if (request !is Json.Obj) {
            respond(exchange, 400, errorResponse(null, -32700, "parse error"))
            return
        }

        val id = request["id"]
        val method = request["method"]?.asString

        // Нотификации (без id) подтверждаем пустым 202 и ничего не отвечаем по протоколу.
        if (id == null) {
            respond(exchange, 202, "")
            return
        }

        val result = when (method) {
            "initialize" -> initializeResult()
            "ping" -> Json.obj()
            "tools/list" -> Json.obj("tools" to Json.arr(tools.definitions()))
            "tools/call" -> tools.call(request["params"])
            else -> {
                respond(exchange, 200, errorResponse(id, -32601, "unknown method: $method"))
                return
            }
        }

        respond(
            exchange, 200,
            Json.obj(
                "jsonrpc" to Json.of("2.0"),
                "id" to id,
                "result" to result,
            ).render(),
        )
    }

    private fun initializeResult(): Json = Json.obj(
        "protocolVersion" to Json.of(PROTOCOL_VERSION),
        "capabilities" to Json.obj("tools" to Json.obj()),
        "serverInfo" to Json.obj(
            "name" to Json.of("metajetcore"),
            "version" to Json.of("0.1.0"),
        ),
        "instructions" to Json.of(
            "Управление вкладками с сессиями Claude Code в этой IDE. " +
                "Каждый агент — отдельная полноценная сессия, не субагент. " +
                "Передавай parent = имя своей сессии (первая строка ListAgents), " +
                "чтобы вкладка открылась рядом с тобой.",
        ),
    )

    private fun errorResponse(id: Json?, code: Int, message: String): String = Json.obj(
        "jsonrpc" to Json.of("2.0"),
        "id" to (id ?: Json.Null),
        "error" to Json.obj("code" to Json.of(code), "message" to Json.of(message)),
    ).render()

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
    }

    private companion object {
        const val PATH = "/mcp"
        const val BACKLOG = 8
        const val PROTOCOL_VERSION = "2024-11-05"
    }
}
