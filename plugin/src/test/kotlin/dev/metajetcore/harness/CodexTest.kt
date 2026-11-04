package dev.metajetcore.harness

import dev.metajetcore.roles.Role
import dev.metajetcore.roles.Roles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class CodexTest {

    private fun rollout(dir: Path, id: String, cwd: String, vararg events: String): Path {
        val file = dir.resolve("rollout-2026-11-04T00-00-00-$id.jsonl")
        val meta = """{"type":"session_meta","payload":{"id":"$id","cwd":"$cwd"}}"""
        Files.writeString(file, (listOf(meta) + events).joinToString("\n", postfix = "\n"))
        return file
    }

    private val started = """{"type":"event_msg","payload":{"type":"task_started"}}"""
    private fun complete(text: String) =
        """{"type":"event_msg","payload":{"type":"task_complete","last_agent_message":"$text"}}"""

    @Test
    fun readerReturnsEachTurnOnceAndTracksBusy() {
        val dir = Files.createTempDirectory("rollout")
        val file = rollout(dir, "a", "/x", started)
        val reader = Codex.RolloutReader(file)
        assertEquals(emptyList<String>(), reader.readCompletedTurns())
        assertTrue(reader.busy)

        Files.writeString(file, complete("один") + "\n", java.nio.file.StandardOpenOption.APPEND)
        assertEquals(listOf("один"), reader.readCompletedTurns())
        assertFalse(reader.busy)
        // Второй вызов отдаёт только новое.
        assertEquals(emptyList<String>(), reader.readCompletedTurns())
    }

    @Test
    fun readerSkipsUnfinishedLine() {
        // Codex дописывает строку не атомарно: половинка JSON не должна потерять событие.
        val dir = Files.createTempDirectory("rollout")
        val file = rollout(dir, "a", "/x")
        val reader = Codex.RolloutReader(file)
        reader.readCompletedTurns()
        val line = complete("целиком")
        Files.writeString(file, line.substring(0, 20), java.nio.file.StandardOpenOption.APPEND)
        assertEquals(emptyList<String>(), reader.readCompletedTurns())
        Files.writeString(file, line.substring(20) + "\n", java.nio.file.StandardOpenOption.APPEND)
        assertEquals(listOf("целиком"), reader.readCompletedTurns())
    }

    @Test
    fun newSessionIsMatchedByDirectoryAndNotReclaimed() {
        val root = Files.createTempDirectory("sessions")
        val day = Files.createDirectories(root.resolve("2026/11/04"))
        val project = Files.createTempDirectory("project").toRealPath()
        rollout(day, "other", "/somewhere/else", started)
        rollout(day, "mine", project.toString(), started)

        val found = Codex.findNewSession(project, 0, emptySet(), root)
        assertEquals("mine", found?.id)
        // Занятая сессия второму агенту не достаётся.
        assertNull(Codex.findNewSession(project, 0, setOf("mine"), root))
        // Старше момента спавна — не наша.
        assertNull(Codex.findNewSession(project, System.currentTimeMillis() + 60_000, emptySet(), root))
    }

    @Test
    fun tomlStringEscapes() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", Codex.tomlString("a\"b\\c\nd"))
    }

    @Test
    fun trustOverrideIsAnInlineTable() {
        // Не dotted-ключ: точки в пути ломают разбор `-c` (проверено на e.shatura).
        assertEquals(
            "projects={\"/Users/e.shatura/p\"={trust_level=\"trusted\"}}",
            Codex.trustOverride(Path.of("/Users/e.shatura/p")),
        )
    }

    @Test
    fun codexProfilesCarryCodexProtocol() {
        for (role in Role.entries - Role.ORCHESTRATOR) {
            val text = Codex.profileText(role)
            assertTrue(text, text.lines().any { it.startsWith("developer_instructions = \"") })
            val prompt = Roles.promptFor(role, Harness.CODEX)
            // У Codex нет SendMessage: упоминание отправило бы его искать несуществующее.
            assertFalse("${role.id}: SendMessage в протоколе Codex", prompt.contains("SendMessage"))
            assertTrue(prompt.contains("STATUS: DONE | DONE_WITH_CONCERNS | BLOCKED"))
            assertTrue(prompt.contains("Не коммить"))
        }
    }

    @Test
    fun claudeModelNamesAreRecognised() {
        for (name in listOf("opus", "Sonnet", "haiku", "claude-opus-5-5")) assertTrue(name, Harness.isClaudeModel(name))
        for (name in listOf("gpt-6.1-sol", "o3", "")) assertFalse(name, Harness.isClaudeModel(name))
    }
}
