package dev.metajetcore

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.metajetcore.agents.AgentManager
import dev.metajetcore.mcp.McpTools
import dev.metajetcore.registry.SessionRegistry
import dev.metajetcore.roles.Role
import dev.metajetcore.settings.MjcSettings
import dev.metajetcore.shell.ShellDialect
import dev.metajetcore.util.Json
import java.nio.file.Files
import java.nio.file.Path

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

    /**
     * Свой реестр на каждый прогон.
     *
     * Без подмены тесты читают ~/.claude/sessions разработчика: занятость имени, состав
     * агентов и сам факт «в проекте кто-то работает» приезжали бы из чужих живых сессий,
     * а часть проверок молча меняла бы смысл.
     */
    private lateinit var registryDir: Path

    override fun setUp() {
        super.setUp()
        registryDir = Files.createTempDirectory("mjc-mcp-registry")
        SessionRegistry.directoryOverride = registryDir
        SessionRegistry.isProcessAlive = { true }
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

    override fun tearDown() {
        try {
            SessionRegistry.directoryOverride = null
            SessionRegistry.isProcessAlive = SessionRegistry.defaultLivenessCheck
            registryDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    /** Живая сессия в директории этого проекта — как её видит плагин. */
    private fun registerLiveSession(pid: Int, name: String, agent: String, sessionId: String = "s-$pid") {
        val cwd = manager.projectDirectory().toString().replace("\\", "\\\\")
        Files.writeString(
            registryDir.resolve("$pid.json"),
            """{"pid":$pid,"sessionId":"$sessionId","cwd":"$cwd",
               "kind":"interactive","name":"$name","agent":"$agent","status":"idle"}""",
        )
    }

    private fun spawnCall(vararg extra: Pair<String, Json>): String {
        val arguments = Json.obj(
            "role" to Json.of("implementer"),
            "task" to Json.of("правка"),
            "parent" to Json.of("mjc-orc"),
            *extra,
        )
        val result = tools.call(
            Json.obj("name" to Json.of("spawn_agent"), "arguments" to arguments),
        )
        return result["content"]!!.asList!!.first()["text"]!!.asString!!
    }

    fun testLiveAgentsOfTheSameRoleAreListedForTheOrchestrator() {
        // Живой прогон дал четырёх имплементеров подряд: оркестратор на уже работающих не
        // смотрит. Запрещать нельзя — параллельные домены это штатная схема, — поэтому
        // плагин обязан хотя бы назвать их, и список снимается ДО открытия вкладки, иначе
        // в него попал бы сам новичок.
        registerLiveSession(4242, "mjc-impl-be", "mjc-implementer")
        registerLiveSession(4243, "mjc-impl-fe", "mjc-implementer")

        val names = manager.reusable(Role.IMPLEMENTER).map { it.name }.toSet()
        assertEquals(setOf("mjc-impl-be", "mjc-impl-fe"), names)
    }

    fun testAgentsOfOtherRolesAreNotCountedAsSiblings() {
        registerLiveSession(4242, "mjc-rsrch", "mjc-researcher")
        assertTrue(manager.reusable(Role.IMPLEMENTER).isEmpty())
        // Оркестратор не заменяем и в список никогда не попадает.
        registerLiveSession(4244, "mjc-orc", "mjc-orchestrator")
        assertTrue(manager.reusable(Role.ORCHESTRATOR).isEmpty())
    }

    fun testLiveAgentOfTheSameRoleDoesNotBlockSpawn() {
        // Ключевое: несколько имплементеров по разным доменам — нормальная работа, и спавн
        // из-за живого однорольца не отменяется. Имя просим заведомо занятое, чтобы вызов
        // упёрся в проверку имени сразу после места, где раньше стоял отказ, и при этом не
        // открывал настоящую вкладку терминала — она в этом тесте ни при чём.
        registerLiveSession(4242, "mjc-impl-be", "mjc-implementer")

        val text = spawnCall("name" to Json.of("mjc-impl-be"), "domain" to Json.of("fe"))
        assertTrue(text, text.contains("уже занято"))
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

    fun testParentNameLosesTheRefSuffixListAgentsPrints() {
        // ListAgents печатает `слитие мастера [815818]` и велит копировать имя дословно,
        // оркестратор так и делает. Хвост — ref платформы, в реестре его нет; пока он не
        // снимался, вкладка родителя не находилась (агент открывался в тулвиндоу), а
        // MJC_PARENT приезжал наблюдателю несопоставимым — дерево команды не собиралось.
        assertEquals("слитие мастера", manager.canonicalName("слитие мастера [815818]"))
        assertEquals("mjc-orc", manager.canonicalName("mjc-orc [ebe653]"))
        // Пробел перед скобкой не обязателен, а вокруг строки может быть что угодно.
        assertEquals("mjc-orc", manager.canonicalName("  mjc-orc[0f4bf4]  "))
    }

    fun testNamesWithoutRefSuffixArePassedThrough() {
        assertEquals("mjc-orc", manager.canonicalName("mjc-orc"))
        // Скобки не с шестнадцатеричным содержимым — часть имени, а не ref.
        assertEquals("релиз [прод]", manager.canonicalName("релиз [прод]"))
        assertEquals("", manager.canonicalName("   "))
    }

    fun testParentIsIdentifiedBySessionIdNotByName() {
        // Ключ связи «агент → оркестратор» — sessionId, а не имя: имя задаёт человек, оно
        // не уникально и меняется по ходу работы. Имя остаётся рядом, но только справочно.
        registerLiveSession(7001, "слитие мастера", "", sessionId = "0ba88519")

        val env = manager.agentEnv(
            Role.IMPLEMENTER,
            "mjc-impl-be",
            "opus",
            "слитие мастера",
            manager.parentSessionId("слитие мастера"),
        )
        assertEquals("0ba88519", env["MJC_PARENT_SESSION"])
        assertEquals("слитие мастера", env["MJC_PARENT"])
    }

    fun testResumedSessionIsStillOneParent() {
        // Три записи реестра с одним именем и одним sessionId — это одна сессия, поднятая
        // заново (замер на живом стенде: pid 290774/300159/313131, sessionId один).
        // Развязывать тут нечего, связь определена.
        registerLiveSession(290774, "слитие мастера", "", sessionId = "0ba88519")
        registerLiveSession(300159, "слитие мастера", "", sessionId = "0ba88519")
        registerLiveSession(313131, "слитие мастера", "", sessionId = "0ba88519")

        assertEquals("0ba88519", manager.parentSessionId("слитие мастера"))
    }

    fun testAmbiguousParentNameYieldsNoLinkAtAll() {
        // Два РАЗНЫХ оркестратора с одинаковым именем. Выбрать наугад нельзя: наблюдатель
        // покажет чужую команду, и понять это по экрану будет невозможно. Честный ответ —
        // связи нет.
        registerLiveSession(7001, "orc", "", sessionId = "aaa")
        registerLiveSession(7002, "orc", "", sessionId = "bbb")

        assertNull(manager.parentSessionId("orc"))
        val env = manager.agentEnv(Role.IMPLEMENTER, "mjc-impl", "opus", "orc", manager.parentSessionId("orc"))
        assertFalse(env.toString(), env.containsKey("MJC_PARENT_SESSION"))
        // Имя при этом уезжает: человеку оно всё ещё говорит, откуда агент.
        assertEquals("orc", env["MJC_PARENT"])
    }

    fun testMatchedRecordWinsOverAmbiguousName() {
        // Вкладку родителя нашли сопоставлением pid — значит известен конкретный процесс,
        // и тёзки больше не мешают. Эта запись сильнее любого перебора по имени.
        registerLiveSession(7001, "orc", "", sessionId = "aaa")
        registerLiveSession(7002, "orc", "", sessionId = "bbb")

        val matched = SessionRegistry.byName("orc").first { it.pid == 7002 }
        assertEquals("bbb", manager.parentSessionId("orc", matched))
    }

    fun testSpawnUsesTheCanonicalParentNameInEnvironment() {
        // Проверка сквозная: то, что доедет до MJC_PARENT, не должно содержать ref.
        val env = manager.agentEnv(
            Role.IMPLEMENTER,
            "mjc-impl-be",
            "opus",
            manager.canonicalName("слитие мастера [815818]"),
        )
        assertEquals("слитие мастера", env["MJC_PARENT"])
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
        // Ручной режим — деградация, но дерево команды и в нём должно получаться верным,
        // то есть по sessionId, а не по имени.
        val command = manager.manualCommand(Role.IMPLEMENTER, "mjc-impl-be", "opus", "mjc-orc", "sid-1")
        assertTrue(command, command.contains("MJC_PARENT='mjc-orc'"))
        assertTrue(command, command.contains("MJC_PARENT_SESSION='sid-1'"))
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
