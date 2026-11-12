package dev.metajetcore.registry

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class CallerResolverTest {

    @After
    fun tearDown() {
        SessionRegistry.directoryOverride = null
        SessionRegistry.isProcessAlive = SessionRegistry.defaultLivenessCheck
    }

    @Test
    fun lsofKeepsOnlyTheClientSideOfTheConnection() {
        // Вывод lsof -Fpn, снятый на macOS: под -iTCP:<порт> попадает и сама IDE — у неё этот
        // порт удалённый. Клиент — тот, у кого он локальный, а удалённый — наш сервер.
        val output = """
            p8156
            f509
            n127.0.0.1:43745->127.0.0.1:50418
            p66324
            f6
            n127.0.0.1:50418->127.0.0.1:43745
        """.trimIndent()
        assertEquals(listOf(66324L), CallerResolver.parseLsof(output, clientPort = 50418, serverPort = 43745))
        // Соединение того же порта, но к чужому серверу — не наше.
        assertEquals(emptyList<Long>(), CallerResolver.parseLsof(output, clientPort = 50418, serverPort = 1))
    }

    @Test
    fun netstatOnWindowsGivesThePidOfTheClient() {
        val output = """
            Active Connections

              Proto  Local Address          Foreign Address        State           PID
              TCP    127.0.0.1:43745        127.0.0.1:50418        ESTABLISHED     8156
              TCP    127.0.0.1:50418        127.0.0.1:43745        ESTABLISHED     66324
        """.trimIndent()
        assertEquals(listOf(66324L), CallerResolver.parseNetstat(output, 50418, 43745))
    }

    @Test
    fun ssOnLinuxGivesThePidOfTheClient() {
        val output = """
            0 0 127.0.0.1:43745 127.0.0.1:50418 users:(("java",pid=8156,fd=509))
            0 0 127.0.0.1:50418 127.0.0.1:43745 users:(("claude",pid=66324,fd=6))
        """.trimIndent()
        assertEquals(listOf(66324L), CallerResolver.parseSs(output, 50418, 43745))
    }

    @Test
    fun recordIsFoundForTheProcessOrItsAncestor() {
        // MCP-клиент может жить в дочернем процессе claude — запись ищется и по предкам.
        val dir = Files.createTempDirectory("mjc-registry")
        SessionRegistry.directoryOverride = dir
        val parent = ProcessHandle.current().parent().orElseThrow().pid()
        Files.writeString(
            dir.resolve("$parent.json"),
            """{"pid":$parent,"sessionId":"s-parent","name":"инт html","cwd":"/tmp"}""",
        )
        val found = CallerResolver.recordForPids(listOf(ProcessHandle.current().pid()))
        assertEquals("s-parent", found?.sessionId)
        assertEquals("инт html", found?.name)
    }

    @Test
    fun differentSessionsMeanNoCaller() {
        // Связь наугад хуже отсутствия связи.
        val dir = Files.createTempDirectory("mjc-registry")
        SessionRegistry.directoryOverride = dir
        SessionRegistry.isProcessAlive = { true }
        Files.writeString(dir.resolve("101.json"), """{"pid":101,"sessionId":"a","name":"x"}""")
        Files.writeString(dir.resolve("102.json"), """{"pid":102,"sessionId":"b","name":"x"}""")
        assertNull(CallerResolver.recordForPids(listOf(101L, 102L)))
        assertEquals("a", CallerResolver.recordForPids(listOf(101L))?.sessionId)
    }

    @Test
    fun clientPidIsFoundByItsPortOnARealConnection() {
        // Настоящее соединение с другим процессом — то, что делает claude, когда зовёт инструмент.
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val client = ProcessBuilder(
                "python3", "-c",
                "import socket,time; s=socket.create_connection(('127.0.0.1', ${server.localPort})); time.sleep(20)",
            ).start()
            try {
                server.soTimeout = 10_000
                server.accept().use { socket ->
                    val pids = CallerResolver.pidsOnPort(socket.port, server.localPort)
                        .filter { it != ProcessHandle.current().pid() }
                    assertEquals(listOf(client.pid()), pids)
                }
            } finally {
                client.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            }
        }
    }
}
