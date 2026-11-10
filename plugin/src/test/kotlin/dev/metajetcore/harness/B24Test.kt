package dev.metajetcore.harness

import dev.metajetcore.util.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class B24Test {

    @After
    fun tearDown() {
        B24.homeOverride = null
    }

    private fun events(): Path = Files.createTempFile("b24-events", ".jsonl")

    private fun Path.append(vararg lines: String) =
        Files.writeString(this, lines.joinToString("\n", postfix = "\n"), StandardOpenOption.APPEND)

    private val sessionStart = """{"type":"system","subtype":"session_start","session_id":"s1","data":{}}"""
    private fun userText(text: String) =
        """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"$text"}]}}"""
    private val toolResult =
        """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}}"""
    private val messageStart = """{"type":"stream_event","event":{"type":"message_start","message":{}}}"""
    private val messageStop = """{"type":"stream_event","event":{"type":"message_stop"}}"""
    private fun assistantText(text: String) =
        """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"$text"}],"stop_reason":null}}"""
    private val thinking =
        """{"type":"assistant","message":{"role":"assistant","content":[{"type":"thinking","thinking":"hm"}]}}"""
    private val toolUse =
        """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"write_file","input":{}}],"stop_reason":"tool_use"}}"""

    @Test
    fun turnWithToolEndsOnlyAfterTheFinalMessage() {
        // Последовательность — снятая с живого b24 0.26.7 (EXPERIMENT-B24-CLI.md §4).
        val file = events()
        val reader = B24.EventsReader(file, startsBusy = true)
        file.append(
            sessionStart, userText("создай файл"),
            messageStart, thinking, assistantText("Создаю файл."), toolUse, messageStop,
            """{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"write_file"}}""",
        )
        // Первое сообщение кончилось вызовом инструмента — ход продолжается, ответа нет.
        assertEquals(emptyList<String>(), reader.readCompletedTurns())
        assertTrue(reader.started)
        assertTrue(reader.busy)
        assertEquals(listOf("write_file"), reader.pendingPermissions())

        file.append(
            """{"type":"control_response","response":{"subtype":"success","request_id":"r1","response":{"allowed":true}}}""",
            toolResult, messageStart, thinking, assistantText("Файл создан."), messageStop,
        )
        // Ответ хода — текст последнего сообщения, а не «Создаю файл.».
        assertEquals(listOf("Файл создан."), reader.readCompletedTurns())
        assertFalse(reader.busy)
        assertEquals(emptyList<String>(), reader.pendingPermissions())
        assertEquals(emptyList<String>(), reader.readCompletedTurns())
    }

    @Test
    fun textBlocksOfTheLastMessageAreJoined() {
        val file = events()
        val reader = B24.EventsReader(file, startsBusy = false)
        file.append(userText("q"), messageStart, assistantText("один"), assistantText("два"), messageStop)
        assertEquals(listOf("один\nдва"), reader.readCompletedTurns())
    }

    @Test
    fun turnWithoutTextStillAnswers() {
        // Иначе оркестратор ждал бы ответа, которого не будет.
        val file = events()
        val reader = B24.EventsReader(file, startsBusy = true)
        file.append(userText("q"), messageStart, thinking, messageStop)
        assertEquals(listOf(""), reader.readCompletedTurns())
    }

    @Test
    fun submittedMessageMakesTheAgentBusyBeforeItsFirstEvent() {
        // CLI опрашивает input раз в 500 мс: без отметки следующее сообщение ушло бы в ту же
        // паузу и влилось бы в этот ход.
        val file = events()
        val reader = B24.EventsReader(file, startsBusy = false)
        assertFalse(reader.busy)
        reader.markSubmitted()
        assertTrue(reader.busy)
        file.append(userText("q"), messageStart, assistantText("ок"), messageStop)
        assertEquals(listOf("ок"), reader.readCompletedTurns())
        assertFalse(reader.busy)
    }

    @Test
    fun unfinishedLineWaitsForTheNextRead() {
        val file = events()
        val reader = B24.EventsReader(file, startsBusy = true)
        file.append(userText("q"), messageStart, assistantText("ок"))
        Files.writeString(file, """{"type":"stream_event","event":{"type":"message""", StandardOpenOption.APPEND)
        assertEquals(emptyList<String>(), reader.readCompletedTurns())
        Files.writeString(file, "_stop\"}}\n", StandardOpenOption.APPEND)
        assertEquals(listOf("ок"), reader.readCompletedTurns())
    }

    @Test
    fun garbageLinesAreSkipped() {
        val file = events()
        val reader = B24.EventsReader(file, startsBusy = true)
        file.append("не json", """{"type":"новое_событие"}""", messageStart, assistantText("ок"), messageStop)
        assertEquals(listOf("ок"), reader.readCompletedTurns())
    }

    @Test
    fun channelWritesOneJsonLinePerCommandWithTextIntact() {
        val channel = B24.Channel(Files.createTempDirectory("b24-chan").resolve("s1"))
        channel.create()
        channel.submit("проверь \"кавычки\"\nи \$HOME")
        val lines = Files.readAllLines(channel.input)
        assertEquals(1, lines.size)
        val command = Json.parse(lines.single())
        assertEquals("submit", command["type"]?.asString)
        assertEquals("проверь \"кавычки\"\nи \$HOME", command["text"]?.asString)
        channel.delete()
        assertFalse(Files.exists(channel.dir))
    }

    @Test
    fun onlyKnownApprovalModesReachTheCommandLine() {
        assertEquals("auto", B24.approvalMode(" auto "))
        assertEquals("yolo", B24.approvalMode("yolo"))
        assertNull(B24.approvalMode(""))
        // Режимы Claude Code у CLI не существуют — с ними он бы не стартовал.
        assertNull(B24.approvalMode("acceptEdits"))
    }

    @Test
    fun modelsAreReadFromCliSettings() {
        val home = Files.createTempDirectory("b24-home")
        Files.writeString(
            home.resolve("settings.json"),
            """{"modelProviders":{"openai":[
                {"id":"bitrix/bitrixgpt-6.5-agent","name":"BitrixGPT 6.5"},
                {"id":"bitrix/bitrixgpt-6.0-agent"},
                {"name":"без id"}
            ]},"model":{"name":"bitrix/bitrixgpt-6.5-agent"}}""",
        )
        B24.homeOverride = home
        assertEquals(
            listOf(
                ModelCatalog.Model("bitrix/bitrixgpt-6.5-agent", "BitrixGPT 6.5"),
                ModelCatalog.Model("bitrix/bitrixgpt-6.0-agent", "bitrix/bitrixgpt-6.0-agent"),
            ),
            B24.models(),
        )
    }

    @Test
    fun missingCliSettingsGiveNoModels() {
        B24.homeOverride = Files.createTempDirectory("b24-empty")
        assertEquals(emptyList<ModelCatalog.Model>(), B24.models())
    }
}
