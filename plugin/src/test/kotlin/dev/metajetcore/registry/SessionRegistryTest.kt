package dev.metajetcore.registry

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class SessionRegistryTest {

    private lateinit var dir: Path

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("mjc-registry")
        SessionRegistry.directoryOverride = dir
        // Записи в тестах синтетические, их pid никому не принадлежат.
        SessionRegistry.isProcessAlive = { true }
    }

    @After
    fun tearDown() {
        SessionRegistry.directoryOverride = null
        SessionRegistry.isProcessAlive = SessionRegistry.defaultLivenessCheck
        dir.toFile().deleteRecursively()
    }

    private fun write(fileName: String, json: String) {
        Files.writeString(dir.resolve(fileName), json)
    }

    /** Реальная запись, снятая с работающей сессии Claude Code 2.1.250 на Windows. */
    private val realRecord = """
        {"pid":16404,"sessionId":"eaee88b1-3dd1-4c6a-8e10-347a3072db59",
         "cwd":"C:\\Users\\shatu\\PycharmProjects\\MetaJetCore","startedAt":1787917354462,
         "version":"2.1.250","peerProtocol":1,"peerFeatures":["notify_idle","artifact_yield"],
         "kind":"interactive","entrypoint":"sdk-cli","pidDomain":"win32:laptop-ckesjrop",
         "messagingSocketPath":"\\\\\\\\.\\\\pipe\\\\LOCAL\\\\cc-msg-3a4262713a32975a8a2a19e718f326bc",
         "name":"mjc-envtest3","nameSince":1787917354462,"agent":"Explore","status":"idle"}
    """.trimIndent()

    @Test
    fun parsesRealRegistryRecord() {
        write("16404.json", realRecord)
        val record = SessionRegistry.findByName("mjc-envtest3")
        assertNotNull(record)
        assertEquals(16404, record!!.pid)
        assertEquals("eaee88b1-3dd1-4c6a-8e10-347a3072db59", record.sessionId)
        assertEquals("Explore", record.agent)
        assertEquals("interactive", record.kind)
        assertTrue(record.isIdle)
        assertFalse(record.isBusy)
    }

    @Test
    fun skipsMalformedFilesInsteadOfFailing() {
        // Файл могут переписать прямо во время чтения — это не повод терять остальные.
        write("1.json", "{ this is not json")
        write("2.json", realRecord)
        assertEquals(1, SessionRegistry.all().size)
    }

    @Test
    fun requiresPidAndSessionId() {
        write("3.json", """{"name":"no-pid","sessionId":"x"}""")
        write("4.json", """{"pid":5,"name":"no-session-id"}""")
        assertTrue(SessionRegistry.all().isEmpty())
    }

    @Test
    fun matchesWorkingDirectoryCaseAndSeparatorInsensitively() {
        write("16404.json", realRecord)
        val matches = SessionRegistry.inDirectory(
            Path.of("C:/users/shatu/PycharmProjects/MetaJetCore"),
        )
        assertEquals(1, matches.size)
    }

    @Test
    fun reportsNameAvailability() {
        write("16404.json", realRecord)
        assertTrue(SessionRegistry.isNameTaken("mjc-envtest3"))
        assertFalse(SessionRegistry.isNameTaken("mjc-impl-be"))
    }

    @Test
    fun toleratesMissingDirectory() {
        SessionRegistry.directoryOverride = dir.resolve("does-not-exist")
        assertTrue(SessionRegistry.all().isEmpty())
        assertNull(SessionRegistry.findByName("anything"))
        assertFalse(SessionRegistry.isNameTaken("anything"))
    }

    @Test
    fun hidesRecordsWhoseProcessIsGone() {
        // Запись удаляет сама сессия при выходе, поэтому после падения файл остаётся.
        // Такой призрак и в list_agents выглядит живым, и навсегда занимает имя — а имена
        // сессий глобальны на машину.
        write("16404.json", realRecord)
        SessionRegistry.isProcessAlive = { false }
        assertTrue(SessionRegistry.all().isEmpty())
        assertFalse(SessionRegistry.isNameTaken("mjc-envtest3"))
        assertNull(SessionRegistry.findByName("mjc-envtest3"))
    }

    @Test
    fun ignoresNonJsonFiles() {
        write("16404.json", realRecord)
        Files.writeString(dir.resolve("16404.key"), "not a session")
        assertEquals(1, SessionRegistry.all().size)
    }
}
