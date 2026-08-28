package dev.metajetcore

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.metajetcore.agents.AgentManager
import dev.metajetcore.mcp.McpTools
import dev.metajetcore.roles.Role
import dev.metajetcore.settings.MjcSettings
import dev.metajetcore.util.Json

/**
 * Интеграционные проверки на настоящем Project из платформенной фикстуры.
 *
 * Терминал здесь не участвует: он требует живого UI. Проверяется всё, что до него —
 * сборка командной строки, схемы MCP-инструментов и поведение при ошибках.
 */
class McpIntegrationTest : BasePlatformTestCase() {

    private lateinit var settings: MjcSettings
    private lateinit var manager: AgentManager
    private lateinit var tools: McpTools

    override fun setUp() {
        super.setUp()
        settings = MjcSettings.getInstance()
        settings.launchCommand = "claude"
        settings.stripApiKeys = true
        settings.shellDialect = "posix"
        settings.namePrefix = "mjc"
        settings.extraEnvRaw = ""
        manager = project.getService(AgentManager::class.java)
        tools = McpTools(project)
    }

    fun testCommandCarriesEverything() {
        val line = manager.buildCommandLine(Role.IMPLEMENTER, "mjc-impl-be", "opus")

        // Вычистка идёт по префиксу, поэтому в строке не имена, а перечисление окружения.
        assertTrue(line, line.contains("unset \$(env |"))
        assertTrue(line, line.contains("CLAUDE|ANTHROPIC_"))
        assertTrue(line, line.contains("CLAUDE_CODE_SESSION_NAME='mjc-impl-be'"))
        assertTrue(line, line.contains("ANTHROPIC_MODEL='opus'"))
        // Роль — одним флагом из файла в пользовательском скоупе.
        assertTrue(line, line.contains("--agent mjc-implementer"))
        // Инлайнового JSON в строке быть не должно: он ломался под PowerShell и портился
        // кодировкой терминала. См. docs/ARCHITECTURE.md §2.6.
        assertFalse(line, line.contains("--agents"))
        // Режим прав обязателен: без него агент встанет в manual mode в вкладке,
        // которую никто не читает.
        assertTrue(line, line.contains("--permission-mode auto"))

        // Ключевой инвариант: вся набираемая строка — ASCII. Всё остальное портит
        // кодировка терминала IDE.
        val nonAscii = line.filter { it.code >= 128 }
        assertTrue("в командной строке не-ASCII: '$nonAscii'", nonAscii.isEmpty())
    }

    fun testPurgeCanBeDisabled() {
        settings.stripApiKeys = false
        settings.stripInheritedClaudeMarkers = false
        val line = manager.buildCommandLine(Role.IMPLEMENTER, "mjc-impl", "opus")
        // Исчезнуть должна именно вычистка. ANTHROPIC_MODEL мы задаём сами — он остаётся.
        assertFalse(line, line.contains("unset "))
        assertTrue(line, line.contains("ANTHROPIC_MODEL="))
    }

    fun testPurgeCoversBothFamiliesByPrefix() {
        settings.stripApiKeys = true
        settings.stripInheritedClaudeMarkers = true
        val line = manager.buildCommandLine(Role.IMPLEMENTER, "mjc-impl", "opus")
        // Префиксы, а не имена: список имён устаревал бы с каждой новой версией Claude Code.
        assertTrue(line, line.contains("CLAUDE|ANTHROPIC_"))
        assertFalse("вычистка не должна перечислять имена", line.contains("unset ANTHROPIC_API_KEY"))
    }

    fun testCustomLaunchCommandIsUsedVerbatim() {
        settings.launchCommand = "/usr/local/bin/my-claude"
        val line = manager.buildCommandLine(Role.IMPLEMENTER, "mjc-impl", "opus")
        assertTrue(line, line.contains("/usr/local/bin/my-claude"))
    }

    fun testExtraEnvIsIncluded() {
        settings.extraEnvRaw = "MJC_EXTRA=yes"
        val line = manager.buildCommandLine(Role.IMPLEMENTER, "mjc-impl", "opus")
        assertTrue(line, line.contains("MJC_EXTRA='yes'"))
    }

    fun testToolSchemasAreWellFormed() {
        val definitions = tools.definitions()
        assertTrue(definitions.isNotEmpty())

        val names = definitions.mapNotNull { it["name"]?.asString }.toSet()
        assertEquals(
            setOf(
                "spawn_agent", "list_agents", "brief_agent", "reset_agent",
                "close_agent", "focus_agent", "read_tab", "diagnostics",
            ),
            names,
        )

        for (definition in definitions) {
            val name = definition["name"]?.asString
            assertTrue("$name: пустое описание", definition["description"]?.asString?.isNotBlank() == true)
            val schema = definition["inputSchema"]
            assertEquals("$name: схема не object", "object", schema?.get("type")?.asString)
            assertNotNull("$name: нет properties", schema?.get("properties")?.asMap)
            assertNotNull("$name: нет required", schema?.get("required")?.asList)

            // Каждое required-поле обязано быть описано в properties, иначе клиент
            // не сможет собрать корректный вызов.
            val properties = schema!!["properties"]!!.asMap!!.keys
            for (required in schema["required"]!!.asList!!.mapNotNull { it.asString }) {
                assertTrue("$name: required '$required' отсутствует в properties",
                    properties.contains(required))
            }
        }
    }

    fun testSpawnAgentSchemaListsEveryRole() {
        val spawn = tools.definitions().first { it["name"]?.asString == "spawn_agent" }
        val roles = spawn["inputSchema"]?.get("properties")?.get("role")?.get("enum")
            ?.asList?.mapNotNull { it.asString }?.toSet()
        assertEquals(Role.entries.map { it.id }.toSet(), roles)
    }

    fun testUnknownToolReturnsErrorInsteadOfThrowing() {
        // Упавший инструмент для оркестратора выглядит как обрыв связи, поэтому
        // ошибки обязаны возвращаться текстом.
        val result = tools.call(Json.obj("name" to Json.of("no_such_tool")))
        assertEquals(true, result["isError"]?.asBoolean)
        assertTrue(result["content"]?.asList?.isNotEmpty() == true)
    }

    fun testSpawnWithUnknownRoleIsRejectedCleanly() {
        val result = tools.call(
            Json.obj(
                "name" to Json.of("spawn_agent"),
                "arguments" to Json.obj(
                    "role" to Json.of("architect"),
                    "task" to Json.of("x"),
                    "parent" to Json.of("mjc-orc"),
                ),
            ),
        )
        assertEquals(true, result["isError"]?.asBoolean)
        val text = result["content"]?.asList?.firstOrNull()?.get("text")?.asString.orEmpty()
        assertTrue(text, text.contains("architect"))
    }

    fun testMissingToolNameIsHandled() {
        val result = tools.call(Json.obj())
        assertEquals(true, result["isError"]?.asBoolean)
    }

    fun testDiagnosticsAlwaysAnswers() {
        // Диагностика должна работать даже когда терминал недоступен —
        // именно тогда она и нужна.
        val result = tools.call(Json.obj("name" to Json.of("diagnostics")))
        assertEquals(false, result["isError"]?.asBoolean)
        val text = result["content"]!!.asList!!.first()["text"]!!.asString!!
        assertTrue(text, text.contains("backend:"))
        assertTrue(text, text.contains("диалект шелла:"))
    }

    fun testListAgentsAnswersWhenNothingRuns() {
        val result = tools.call(Json.obj("name" to Json.of("list_agents")))
        assertEquals(false, result["isError"]?.asBoolean)
    }

    fun testPrefixFallsBackToProjectName() {
        settings.namePrefix = ""
        assertTrue(manager.prefix().isNotBlank())
    }
}
