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
    fun liveSessionOfTheUserIsNotTakenForTheNewAgent() {
        // Случай 16.11: пользователь работает в своём Codex в том же каталоге, его лента
        // дописывается прямо во время спавна. По mtime она свежее — но начата раньше.
        val root = Files.createTempDirectory("sessions")
        val day = Files.createDirectories(root.resolve("2026/11/16"))
        val project = Files.createTempDirectory("project").toRealPath()
        val spawnAt = System.currentTimeMillis()
        fun meta(id: String, startedAt: Long) =
            """{"type":"session_meta","payload":{"id":"$id","cwd":"$project","timestamp":"${java.time.Instant.ofEpochMilli(startedAt)}"}}"""

        val users = day.resolve("rollout-2026-11-16T13-23-54-users.jsonl")
        Files.writeString(users, meta("users", spawnAt - 180_000) + "\n" + started + "\n")
        assertNull(Codex.findNewSession(project, spawnAt, emptySet(), root))

        val agent = day.resolve("rollout-2026-11-16T13-26-51-agent.jsonl")
        Files.writeString(agent, meta("agent", spawnAt + 1_000) + "\n")
        // Лента пользователя изменена позже ленты агента — всё равно берётся агент.
        Files.writeString(users, complete("ещё ход") + "\n", java.nio.file.StandardOpenOption.APPEND)
        Files.setLastModifiedTime(users, java.nio.file.attribute.FileTime.fromMillis(spawnAt + 5_000))
        assertEquals("agent", Codex.findNewSession(project, spawnAt, emptySet(), root)?.id)
    }

    @Test
    fun sessionIsBoundToTheProcessThatHoldsItsRollout() {
        // Привязка «агент → сессия» — по файлу ленты, открытому процессом вкладки агента.
        // Рядом в том же каталоге — живая сессия пользователя, свежее по всем часам: не наша.
        org.junit.Assume.assumeTrue(dev.metajetcore.util.Commands.lsof != null)
        val root = Files.createTempDirectory("sessions").toRealPath()
        val day = Files.createDirectories(root.resolve("2026/11/16"))
        fun write(name: String, id: String, source: String) = day.resolve(name).also {
            Files.writeString(it, """{"type":"session_meta","payload":{"id":"$id","cwd":"/p","source":$source}}""" + "\n")
        }
        val agentFile = write("rollout-2026-11-16T13-26-53-agent.jsonl", "agent", "\"cli\"")
        val guardian = write("rollout-2026-11-16T13-28-20-guard.jsonl", "guard", """{"subagent":{"other":"guardian"}}""")
        val usersFile = write("rollout-2026-11-16T13-27-00-users.jsonl", "users", "\"vscode\"")
        val stray = Files.createTempFile("not-a-session", ".txt")

        fun holder(vararg files: Path) = ProcessBuilder(
            "python3", "-c",
            "import sys,time; fs=[open(p,'a') for p in sys.argv[1:]]; print('ok', flush=True); time.sleep(30)",
            *files.map { it.toString() }.toTypedArray(),
        ).start().also { it.inputStream.bufferedReader().readLine() }

        // Агент — внук процесса вкладки: шелл → обёртка → codex.
        val tab = ProcessBuilder(
            "bash", "-c",
            "python3 -c \"import sys,time; fs=[open(p,'a') for p in sys.argv[1:]]; print('ok', flush=True); time.sleep(30)\" '$agentFile' '$guardian' '$stray'",
        ).start().also { it.inputStream.bufferedReader().readLine() }
        val user = holder(usersFile)
        try {
            val found = Codex.sessionOfProcess(tab.pid(), emptySet(), root)
            assertEquals("agent", ((found as Codex.Lookup.Found).session)?.id)
            // Уже занятая — не достаётся второму агенту.
            assertEquals(Codex.Lookup.Found(null), Codex.sessionOfProcess(tab.pid(), setOf("agent"), root))
            // Процесс пользователя к агенту не относится.
            assertEquals("users", (Codex.sessionOfProcess(user.pid(), emptySet(), root) as Codex.Lookup.Found).session?.id)
        } finally {
            tab.descendants().forEach { it.destroyForcibly() }
            tab.destroyForcibly()
            user.destroyForcibly()
        }
    }

    @Test
    fun startTimeFallsBackToTheFileName() {
        val expected = java.time.LocalDateTime.parse("2026-11-16T13:23:54")
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, Codex.startedFromFileName("rollout-2026-11-16T13-23-54-01a1e419-6f46.jsonl"))
        assertNull(Codex.startedFromFileName("rollout-broken.jsonl"))
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
