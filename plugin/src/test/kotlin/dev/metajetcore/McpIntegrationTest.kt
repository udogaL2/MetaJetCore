package dev.metajetcore

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.metajetcore.agents.AgentManager
import dev.metajetcore.mcp.McpTools
import dev.metajetcore.roles.Role
import dev.metajetcore.settings.MjcSettings
import dev.metajetcore.shell.ShellDialect
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
        settings.stripInheritedClaudeMarkers = true
        settings.permissionMode = "auto"
        settings.namePrefix = "mjc"
        settings.extraEnvRaw = ""
        manager = project.getService(AgentManager::class.java)
        tools = McpTools(project)
    }

    fun testLaunchLineCarriesOnlyWhatEnvironmentCannot() {
        val line = manager.launchLine(Role.IMPLEMENTER, ShellDialect.POSIX)

        // Роль — одним флагом из файла в пользовательском скоупе: через окружение она
        // пишется в реестр, но не применяется (docs/ARCHITECTURE.md §2.2.1).
        assertTrue(line, line.contains("--agent mjc-implementer"))
        // Режим прав обязателен: без него агент встанет в manual mode в вкладке,
        // которую никто не читает.
        assertTrue(line, line.contains("--permission-mode auto"))
        // Окружение теперь уезжает через API вкладки, в строке его быть не должно —
        // именно это убирает экранирование и разницу диалектов шеллов.
        assertFalse(line, line.contains("CLAUDE_CODE_SESSION_NAME"))
        assertFalse(line, line.contains("ANTHROPIC_MODEL"))
        assertFalse(line, line.contains("--agents"))
        // Хвостовой выход из шелла: без него процесс вкладки переживает /exit, и её
        // закрытие упирается в модальный вопрос платформы (docs/ARCHITECTURE.md §2).
        assertTrue(line, line.trimEnd().endsWith("; exit"))

        // Ключевой инвариант: вся набираемая строка — ASCII.
        val nonAscii = line.filter { it.code >= 128 }
        assertTrue("в командной строке не-ASCII: '$nonAscii'", nonAscii.isEmpty())
    }

    fun testEnvironmentCarriesAddressAndModel() {
        val env = manager.agentEnv(Role.IMPLEMENTER, "mjc-impl-be", "opus")
        // Имя сессии — это адрес для SendMessage, без него агентов не различить.
        assertEquals("mjc-impl-be", env["CLAUDE_CODE_SESSION_NAME"])
        assertEquals("opus", env["ANTHROPIC_MODEL"])
        assertEquals("implementer", env["CLAUDE_CODE_AGENT"])
        // Отчёты — вне репозитория, иначе в каждом проекте нужна строка в .gitignore.
        assertTrue(env.containsKey("MJC_REPORTS_DIR"))
    }

    fun testEnvironmentCarriesTheOrchestratorName() {
        // Связь «агент → его оркестратор» знает только плагин и только в момент спавна:
        // в реестре Claude Code такого поля нет. Сторонние наблюдатели иначе вынуждены
        // угадывать её по общему префиксу имени, а это разваливается на двух командах
        // в одном проекте.
        val env = manager.agentEnv(Role.IMPLEMENTER, "mjc-impl-be", "opus", "mjc-orc")
        assertEquals("mjc-orc", env["MJC_PARENT"])
    }

    fun testEnvironmentOmitsParentWhenThereIsNone() {
        // Именно отсутствие ключа, а не пустая строка: пустое значение получатель принял бы
        // за настоящее имя и построил бы дерево с несуществующим родителем.
        val env = manager.agentEnv(Role.IMPLEMENTER, "mjc-impl-be", "opus", null)
        assertFalse(env.toString(), env.containsKey("MJC_PARENT"))
        assertFalse(manager.agentEnv(Role.IMPLEMENTER, "mjc-impl", "opus", "  ").containsKey("MJC_PARENT"))
    }

    fun testParentNameIsTrimmedToTheLengthReceiverExpects() {
        val long = "o".repeat(120)
        val env = manager.agentEnv(Role.IMPLEMENTER, "mjc-impl", "opus", long)
        assertEquals(64, env["MJC_PARENT"]?.length)
    }

    fun testManualCommandCarriesTheParent() {
        // Ручной режим — деградация, но дерево команды и в нём должно получаться верным.
        val command = manager.manualCommand(Role.IMPLEMENTER, "mjc-impl-be", "opus", "mjc-orc")
        assertTrue(command, command.contains("MJC_PARENT='mjc-orc'"))
        assertFalse(
            manager.manualCommand(Role.IMPLEMENTER, "mjc-impl-be", "opus", null).contains("MJC_PARENT"),
        )
    }

    fun testOrchestratorTabIsMarkedWithItsRole() {
        // Роль этой переменной не задаётся — она метка (§2.2.1). Нужна, чтобы оркестратора
        // было видно снаружи: иначе он опознаётся только по имени, а имя произвольное.
        val env = manager.orchestratorEnv("mjc-orc")
        assertEquals("mjc-orc", env["CLAUDE_CODE_SESSION_NAME"])
        assertEquals("orchestrator", env["CLAUDE_CODE_AGENT"])
    }

    fun testPurgeCanBeDisabled() {
        settings.stripApiKeys = false
        settings.stripInheritedClaudeMarkers = false
        assertTrue(manager.inheritedMarkers().isEmpty())
        assertFalse(manager.launchLine(Role.IMPLEMENTER, ShellDialect.POSIX).contains("unset "))
    }

    fun testPurgeNamesOnlyWhatIsActuallyInherited() {
        // Имена берутся из окружения самой IDE: агент наследует именно его, поэтому
        // зашитый список был бы одновременно и неполным, и устаревающим.
        val marker = "CLAUDE_CODE_MESSAGING_SOCKET"
        val inherited = manager.inheritedMarkers()
        for (name in inherited) {
            assertTrue(name, name.startsWith("CLAUDE") || name.startsWith("ANTHROPIC_"))
            assertTrue(name, System.getenv().containsKey(name))
        }
        if (System.getenv().containsKey(marker)) {
            assertTrue(inherited.toString(), inherited.contains(marker))
        }
    }

    fun testCustomLaunchCommandIsUsedVerbatim() {
        settings.launchCommand = "/usr/local/bin/my-claude"
        val line = manager.launchLine(Role.IMPLEMENTER, ShellDialect.POSIX)
        assertTrue(line, line.contains("/usr/local/bin/my-claude"))
    }

    fun testExtraEnvIsIncluded() {
        settings.extraEnvRaw = "MJC_EXTRA=yes"
        assertEquals("yes", manager.agentEnv(Role.IMPLEMENTER, "mjc-impl", "opus")["MJC_EXTRA"])
    }

    fun testBriefRejectsNonAsciiInsteadOfCorruptingIt() {
        // Живой прогон на 2026.2: «Ответь ровно одним словом: ПРОБА-OK» приехало в TUI как
        // «❯ ������ ����� ����� ������: �����-OK», и агент ответил на выдуманный текст.
        // Молчаливое искажение выглядит как выполненная команда — отказ честнее.
        val result = tools.call(
            Json.obj(
                "name" to Json.of("brief_agent"),
                "arguments" to Json.obj(
                    "name" to Json.of("mjc-rsrch"),
                    "text" to Json.of("Ответь ровно: ПРОБА"),
                ),
            ),
        )
        assertEquals(true, result["isError"]?.asBoolean)
        val text = result["content"]!!.asList!!.first()["text"]!!.asString!!
        assertTrue(text, text.contains("SendMessage"))
    }

    fun testBriefAcceptsAsciiAndFailsOnlyOnUnknownTab() {
        // ASCII проходит проверку кодировки и упирается уже в отсутствие вкладки —
        // значит отказ выше был именно про кодировку, а не про что-то ещё.
        val result = tools.call(
            Json.obj(
                "name" to Json.of("brief_agent"),
                "arguments" to Json.obj(
                    "name" to Json.of("mjc-rsrch"),
                    "text" to Json.of("/exit"),
                ),
            ),
        )
        assertEquals(true, result["isError"]?.asBoolean)
        val text = result["content"]!!.asList!!.first()["text"]!!.asString!!
        assertTrue(text, text.contains("неизвестна"))
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
        assertTrue(text, text.contains("terminal API"))
        assertTrue(text, text.contains("команда запуска:"))
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
