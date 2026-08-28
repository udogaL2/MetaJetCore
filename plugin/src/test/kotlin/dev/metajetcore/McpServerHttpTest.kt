package dev.metajetcore

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.metajetcore.mcp.McpServerService
import dev.metajetcore.util.Json
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * Полный раунд-трип по HTTP: поднимаем MCP-сервер и разговариваем с ним так же, как это
 * делает Claude Code. Запуск IDE для этого не нужен, поэтому проверка гоняется в CI.
 */
class McpServerHttpTest : BasePlatformTestCase() {

    private lateinit var service: McpServerService
    private lateinit var endpoint: String

    override fun setUp() {
        super.setUp()
        service = project.getService(McpServerService::class.java)
        service.start()
        endpoint = service.endpoint()
            ?: throw AssertionError("MCP-сервер не поднялся")
    }

    fun testEndpointIsLoopbackOnly() {
        assertTrue(endpoint, endpoint.startsWith("http://127.0.0.1:"))
        assertTrue(endpoint, endpoint.endsWith("/mcp"))
        assertTrue(service.boundPort > 0)
    }

    fun testInitializeHandshake() {
        val response = rpc("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")
        assertEquals("2.0", response["jsonrpc"]?.asString)
        assertEquals(1, response["id"]?.asInt)

        val result = response["result"]
        assertNotNull(result)
        assertNotNull(result!!["protocolVersion"]?.asString)
        assertNotNull(result["capabilities"]?.get("tools"))
        assertEquals("metajetcore", result["serverInfo"]?.get("name")?.asString)
        // Инструкции объясняют оркестратору, что агенты — не субагенты.
        assertTrue(result["instructions"]?.asString?.contains("не субагент") == true)
    }

    fun testToolsListOverHttp() {
        val response = rpc("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
        val tools = response["result"]?.get("tools")?.asList
        assertNotNull(tools)
        val names = tools!!.mapNotNull { it["name"]?.asString }
        assertTrue(names.toString(), names.contains("spawn_agent"))
        assertTrue(names.toString(), names.contains("diagnostics"))
    }

    fun testToolsCallOverHttp() {
        val response = rpc(
            """{"jsonrpc":"2.0","id":3,"method":"tools/call",
                "params":{"name":"diagnostics","arguments":{}}}""",
        )
        val content = response["result"]?.get("content")?.asList
        assertNotNull(content)
        val text = content!!.first()["text"]?.asString.orEmpty()
        assertTrue(text, text.contains("backend:"))
    }

    fun testUnknownMethodReturnsJsonRpcError() {
        val response = rpc("""{"jsonrpc":"2.0","id":4,"method":"nope"}""")
        assertNotNull(response["error"])
        assertEquals(-32601, response["error"]?.get("code")?.asInt)
    }

    fun testMalformedBodyDoesNotKillTheServer() {
        val (status, _) = raw("POST", "not json at all")
        assertEquals(400, status)
        // Сервер обязан пережить мусор: упавший MCP выглядит для оркестратора
        // как пропавший инструмент.
        val response = rpc("""{"jsonrpc":"2.0","id":5,"method":"ping"}""")
        assertNotNull(response["result"])
    }

    fun testNotificationGetsAccepted() {
        val (status, body) = raw("POST", """{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(202, status)
        assertTrue(body.isEmpty())
    }

    fun testGetIsRejected() {
        val (status, _) = raw("GET", null)
        assertEquals(405, status)
    }

    fun testMcpAddCommandIsUsable() {
        val command = service.claudeMcpAddCommand()
        assertTrue(command, command.startsWith("claude mcp add --transport http metajetcore http://127.0.0.1:"))
    }

    // ------------------------------------------------------------------ http

    private fun rpc(body: String): Json {
        val (status, text) = raw("POST", body)
        assertEquals("HTTP $status, тело: $text", 200, status)
        return Json.parseOrNull(text) ?: throw AssertionError("невалидный JSON в ответе: $text")
    }

    private fun raw(method: String, body: String?): Pair<Int, String> {
        val connection = URI(endpoint).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5_000
        connection.readTimeout = 30_000
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
        }
        val status = connection.responseCode
        val stream = if (status < 400) connection.inputStream else connection.errorStream
        val text = stream?.readBytes()?.toString(StandardCharsets.UTF_8).orEmpty()
        connection.disconnect()
        return status to text
    }
}
