package dev.metajetcore

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.metajetcore.agents.AgentManager
import dev.metajetcore.agents.TeamMember
import dev.metajetcore.agents.TeamStore
import dev.metajetcore.harness.Harness
import dev.metajetcore.mcp.McpTools
import dev.metajetcore.registry.SessionRecord
import dev.metajetcore.registry.SessionRegistry
import dev.metajetcore.roles.Role
import dev.metajetcore.settings.MjcSettings
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
        manager.useTeamFile(registryDir.resolve("team.json"))
        tools = McpTools(project)
        // Вызывающий по умолчанию — оркестратор A; вызовы здесь идут не по сети.
        orcA = registerLiveSession(9001, "orc", "mjc-orchestrator", sessionId = "orc-a")
        orcB = registerLiveSession(9002, "orc", "mjc-orchestrator", sessionId = "orc-b")
        caller = orcA
        tools.resolveCaller = { caller }
    }

    private lateinit var orcA: SessionRecord
    private lateinit var orcB: SessionRecord
    private var caller: SessionRecord? = null

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
    private fun registerLiveSession(
        pid: Int,
        name: String,
        agent: String,
        sessionId: String = "s-$pid",
        procStart: String = "start-$pid",
    ): SessionRecord {
        val cwd = manager.projectDirectory().toString().replace("\\", "\\\\")
        Files.writeString(
            registryDir.resolve("$pid.json"),
            """{"pid":$pid,"sessionId":"$sessionId","cwd":"$cwd",
               "kind":"interactive","name":"$name","agent":"$agent","status":"idle",
               "procStart":"$procStart","messagingSocketPath":"/tmp/cc-socks/$pid.sock"}""",
        )
        return SessionRegistry.all().first { it.pid == pid }
    }

    /**
     * Claude-агент, запущенный оркестратором [owner], — как его оставляет spawn: запись в
     * реестре и в группе. Сам спавн требует терминала, а он здесь не участвует.
     */
    private fun adopt(owner: SessionRecord, pid: Int, name: String, role: Role): SessionRecord {
        val record = registerLiveSession(pid, name, "mjc-${role.id}")
        manager.team.add(
            TeamMember(
                name = name,
                owner = manager.team.ownerKey(owner),
                harness = Harness.CLAUDE,
                role = role,
                model = "opus",
                pid = pid.toLong(),
                procStart = record.procStart,
            ),
        )
        return record
    }

    private fun isError(tool: String, vararg arguments: Pair<String, Json>): Boolean =
        tools.call(Json.obj("name" to Json.of(tool), "arguments" to Json.obj(*arguments)))["isError"]?.asBoolean == true

    private fun callText(tool: String, vararg arguments: Pair<String, Json>): String {
        val result = tools.call(Json.obj("name" to Json.of(tool), "arguments" to Json.obj(*arguments)))
        return result["content"]!!.asList!!.first()["text"]!!.asString!!
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
        adopt(orcA, 4242, "mjc-impl-be", Role.IMPLEMENTER)
        adopt(orcA, 4243, "mjc-impl-fe", Role.IMPLEMENTER)
        // Имплементер другого оркестратора и личная сессия с ролью — не свои.
        adopt(orcB, 4244, "mjc-impl-other", Role.IMPLEMENTER)
        registerLiveSession(4245, "mjc-impl-personal", "mjc-implementer")

        val names = manager.reusable(Role.IMPLEMENTER, orcA).map { it.name }.toSet()
        assertEquals(setOf("mjc-impl-be", "mjc-impl-fe"), names)
        assertEquals(setOf("mjc-impl-other"), manager.reusable(Role.IMPLEMENTER, orcB).map { it.name }.toSet())
    }

    fun testAgentsOfOtherRolesAreNotCountedAsSiblings() {
        adopt(orcA, 4242, "mjc-rsrch", Role.RESEARCHER)
        assertTrue(manager.reusable(Role.IMPLEMENTER, orcA).isEmpty())
        // Оркестратор не заменяем и в список никогда не попадает.
        assertTrue(manager.reusable(Role.ORCHESTRATOR, orcA).isEmpty())
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

    fun testCodexLaunchLineHasNoUserText() {
        // Задача, доверие и модель едут через env вкладки; в строке шелла — только ссылки.
        val line = manager.codexArgv(Role.REVIEWER, "gpt-x").last()
        assertTrue(line, line.contains("-p mjc-reviewer"))
        assertTrue(line, line.contains("--disable hooks"))
        assertTrue(line, line.contains("-a never"))
        assertTrue(line, line.contains("-m \"${'$'}MJC_MODEL\""))
        assertTrue(line, line.trimEnd().endsWith("\"${'$'}MJC_TASK\""))
        assertTrue(line, line.contains("exec "))
        // Само значение модели в строку не попадает — только ссылка.
        assertFalse(line, line.contains("gpt-x"))

        val env = manager.codexEnv(Role.REVIEWER, "mjc-rev-x", "gpt-x", null, "задача \"в кавычках\"")
        assertEquals("задача \"в кавычках\"", env["MJC_TASK"])
        assertEquals("gpt-x", env["MJC_MODEL"])
        assertTrue(env["MJC_CODEX_TRUST"]!!.startsWith("projects={"))
        // Пустая модель — флаг не ставится, Codex возьмёт свою из конфига.
        assertFalse(manager.codexArgv(Role.REVIEWER, "").last().contains(" -m "))
    }

    fun testModelIsChosenPerHarness() {
        assertEquals("opus", manager.modelFor(Role.IMPLEMENTER, Harness.CLAUDE, null))
        assertEquals("haiku", manager.modelFor(Role.IMPLEMENTER, Harness.CLAUDE, "haiku"))
        // tempo пишет модели в терминах Claude; Codex на них упал бы.
        assertEquals("", manager.modelFor(Role.IMPLEMENTER, Harness.CODEX, "opus"))
        assertEquals("gpt-x", manager.modelFor(Role.IMPLEMENTER, Harness.CODEX, "gpt-x"))
    }

    fun testSendToClaudeAgentPointsToSendMessageWithItsAddress() {
        adopt(orcA, 4343, "mjc-impl-cl", Role.IMPLEMENTER)
        val text = callText("send_to_agent", "name" to Json.of("mjc-impl-cl"), "text" to Json.of("x"))
        assertTrue(text, text.contains("SendMessage(to=\"uds:/tmp/cc-socks/4343.sock\")"))
    }

    fun testListAgentsShowsOnlyTheCallersGroup() {
        // Случай 16.11: оркестратор видел все сессии каталога и писал личной сессии
        // разработчика. Теперь — только свои, у каждого точный адрес.
        adopt(orcA, 4401, "mjc-impl-a", Role.IMPLEMENTER)
        adopt(orcB, 4402, "mjc-impl-b", Role.IMPLEMENTER)
        registerLiveSession(4403, "моя сессия", "")

        val a = callText("list_agents")
        assertTrue(a, a.contains("mjc-impl-a") && a.contains("адрес=uds:/tmp/cc-socks/4401.sock"))
        assertFalse(a, a.contains("mjc-impl-b"))
        assertFalse(a, a.contains("моя сессия"))
        // Оркестраторы — не агенты группы, даже тёзки.
        assertFalse(a, a.contains("9002"))

        caller = orcB
        val b = callText("list_agents")
        assertTrue(b, b.contains("mjc-impl-b"))
        assertFalse(b, b.contains("mjc-impl-a"))
    }

    fun testForeignAgentCannotBeTouched() {
        // Чужого агента не закрыть, не сбросить, не показать, не прочитать и не впечатать
        // ему текст — ответ тот же, что для несуществующего.
        adopt(orcB, 4501, "mjc-impl-b", Role.IMPLEMENTER)
        registerLiveSession(4502, "моя сессия", "")
        for (target in listOf("mjc-impl-b", "моя сессия")) {
            val name = "name" to Json.of(target)
            for (tool in listOf("close_agent", "reset_agent", "focus_agent", "read_tab")) {
                assertTrue("$tool $target", isError(tool, name))
                assertTrue(callText(tool, name), callText(tool, name).contains("в твоей группе нет агента"))
            }
            assertTrue(isError("brief_agent", name, "text" to Json.of("/exit")))
            val send = callText("send_to_agent", name, "text" to Json.of("x"))
            assertTrue(send, send.contains("в твоей группе нет агента"))
            assertFalse(send, send.contains("uds:"))
        }
        // Агент оркестратора B на месте.
        caller = orcB
        assertTrue(callText("list_agents").contains("mjc-impl-b"))
    }

    fun testWithoutKnownCallerAgentToolsRefuse() {
        // Вызывающего не определили — группу угадывать нельзя: «по всему проекту» означало
        // бы чужих агентов.
        adopt(orcA, 4601, "mjc-impl-a", Role.IMPLEMENTER)
        caller = null
        for (tool in McpTools.AGENT_TOOLS) {
            val result = tools.call(Json.obj("name" to Json.of(tool), "arguments" to Json.obj("name" to Json.of("mjc-impl-a"))))
            assertEquals(tool, true, result["isError"]?.asBoolean)
            assertTrue(tool, result["content"]!!.asList!!.first()["text"]!!.asString!!.contains("не удалось определить"))
        }
        assertEquals(false, tools.call(Json.obj("name" to Json.of("diagnostics")))["isError"]?.asBoolean)
    }

    fun testGroupSurvivesClearOfTheOrchestrator() {
        // /clear меняет sessionId оркестратора, процесс тот же — группа переезжает.
        adopt(orcA, 4701, "mjc-impl-a", Role.IMPLEMENTER)
        caller = registerLiveSession(9001, "orc", "mjc-orchestrator", sessionId = "orc-a-cleared")
        assertTrue(callText("list_agents").contains("mjc-impl-a"))
        // А новый процесс с тем же sessionId-подобным именем и другим pid — нет.
        caller = registerLiveSession(9003, "orc", "mjc-orchestrator", sessionId = "orc-c")
        assertFalse(callText("list_agents").contains("mjc-impl-a"))
    }

    fun testAgentStaysInGroupAfterItsClearAndRename() {
        // reset_agent шлёт агенту /clear: sessionId меняется, процесс тот же. /rename меняет имя.
        adopt(orcA, 4801, "mjc-impl-a", Role.IMPLEMENTER)
        registerLiveSession(4801, "переименован", "mjc-implementer", sessionId = "new-sid")
        val text = callText("list_agents")
        assertTrue(text, text.contains("переименован"))
        assertTrue(text, text.contains("uds:/tmp/cc-socks/4801.sock"))
    }

    fun testReusedPidIsNotTheAgent() {
        // pid переиспользован чужим процессом (другое время старта) — агента больше нет,
        // и чужой процесс в группу не попадает.
        adopt(orcA, 4901, "mjc-impl-a", Role.IMPLEMENTER)
        registerLiveSession(4901, "чужой", "", procStart = "later")
        assertFalse(callText("list_agents").contains("4901"))
        assertTrue(manager.team.all().none { it.name == "mjc-impl-a" })
    }

    fun testGroupIsReadBackFromDisk() {
        // Перезагрузка плагина: память пуста, агенты живы — группа читается с диска.
        adopt(orcA, 5001, "mjc-impl-a", Role.IMPLEMENTER)
        val again = TeamStore(registryDir.resolve("team.json"))
        val key = again.ownerKey(orcA)
        assertEquals(listOf("mjc-impl-a"), again.members(key).map { it.name })
        assertTrue(again.members(again.ownerKey(orcB)).isEmpty())
    }

    fun testAwaitMessagesAnswersWithoutCodexAgents() {
        val text = callText("await_messages", "timeout_s" to Json.of(0))
        assertTrue(text, text.contains("Новых ответов нет"))
    }

    fun testLaunchLineCarriesOnlyWhatEnvironmentCannot() {
        val line = manager.launchArgv(Role.IMPLEMENTER).last()

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
        // Хвостового выхода из шелла больше нет: шелл с -c завершается вместе с агентом.
        assertFalse(line, line.trimEnd().endsWith("exit"))

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

    private val orc = AgentManager.Parent(name = "слитие мастера", sessionId = "0ba88519", address = "uds:/tmp/cc-socks/9001.sock")

    fun testEnvironmentCarriesTheParentAddress() {
        // Куда отвечать — точный адрес процесса оркестратора: имя не уникально, и ответ по
        // нему уходил первому совпавшему. sessionId — ключ для наблюдателей, имя — справочно.
        val env = manager.agentEnv(Role.IMPLEMENTER, "mjc-impl-be", "opus", orc)
        assertEquals("uds:/tmp/cc-socks/9001.sock", env["MJC_PARENT_ADDRESS"])
        assertEquals("0ba88519", env["MJC_PARENT_SESSION"])
        assertEquals("слитие мастера", env["MJC_PARENT"])
        // Codex и b24 получают то же самое.
        assertEquals("uds:/tmp/cc-socks/9001.sock", manager.codexEnv(Role.REVIEWER, "r", "", orc, "t")["MJC_PARENT_ADDRESS"])
    }

    fun testAgentStartsInTheRequestedWorkdir() {
        // 16.11: оркестратор в основном checkout вёл фичу в клоне `bx clone` рядом, агента
        // плагин запускал в checkout — Codex не смог писать в клон. Каталог — от оркестратора.
        val clone = Files.createTempDirectory("modules-feature").toRealPath()
        assertEquals(clone, manager.workDirFor(clone, orcA))
        // Без workdir — каталог вызывающего, если он есть на диске, иначе каталог проекта.
        val callerDir = Files.createTempDirectory("orc-cwd").toRealPath()
        assertEquals(callerDir, manager.workDirFor(null, orcA.copy(cwd = callerDir.toString())))
        assertEquals(manager.projectDirectory(), manager.workDirFor(null, orcA.copy(cwd = "/нет/такого")))
        // Доверие Codex — тому же каталогу, иначе sandbox не пустит писать.
        val env = manager.codexEnv(Role.IMPLEMENTER, "i", "", null, "t", clone)
        assertTrue(env["MJC_CODEX_TRUST"], env["MJC_CODEX_TRUST"]!!.contains(clone.toString()))
    }

    fun testSpawnRejectsBadWorkdir() {
        for (bad in listOf("relative/dir", "/нет/такого/каталога")) {
            val text = spawnCall("workdir" to Json.of(bad))
            assertTrue(text, text.contains("не абсолютный путь существующего каталога"))
        }
    }

    fun testEnvironmentOmitsParentWhenThereIsNone() {
        // Именно отсутствие ключа, а не пустая строка: пустое значение получатель принял бы
        // за настоящий адрес.
        val env = manager.agentEnv(Role.IMPLEMENTER, "mjc-impl-be", "opus", null)
        assertFalse(env.toString(), env.keys.any { it.startsWith("MJC_PARENT") })
        val blank = manager.agentEnv(Role.IMPLEMENTER, "mjc-impl", "opus", AgentManager.Parent("  ", null, " "))
        assertFalse(blank.toString(), blank.keys.any { it.startsWith("MJC_PARENT") })
    }

    fun testParentNameIsTrimmedToTheLengthReceiverExpects() {
        val env = manager.agentEnv(Role.IMPLEMENTER, "mjc-impl", "opus", AgentManager.Parent("o".repeat(120), null, null))
        assertEquals(64, env["MJC_PARENT"]?.length)
    }

    fun testManualCommandCarriesTheParent() {
        val command = manager.manualCommand(Role.IMPLEMENTER, "mjc-impl-be", "opus", orc)
        assertTrue(command, command.contains("MJC_PARENT_SESSION='0ba88519'"))
        assertTrue(command, command.contains("MJC_PARENT_ADDRESS='uds:/tmp/cc-socks/9001.sock'"))
        assertFalse(manager.manualCommand(Role.IMPLEMENTER, "mjc-impl-be", "opus", null).contains("MJC_PARENT"))
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
        assertFalse(manager.launchArgv(Role.IMPLEMENTER).last().contains("unset "))
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
        val line = manager.launchArgv(Role.IMPLEMENTER).last()
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
        assertTrue(text, text.contains("в твоей группе нет агента"))
    }

    fun testToolSchemasAreWellFormed() {
        val definitions = tools.definitions()
        assertTrue(definitions.isNotEmpty())

        val names = definitions.mapNotNull { it["name"]?.asString }.toSet()
        assertEquals(
            setOf(
                "spawn_agent", "send_to_agent", "await_messages", "list_agents", "brief_agent", "reset_agent",
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
