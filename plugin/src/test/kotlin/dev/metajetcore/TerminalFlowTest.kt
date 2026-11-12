package dev.metajetcore

import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.metajetcore.agents.AgentManager
import dev.metajetcore.agents.CloseResult
import dev.metajetcore.agents.DeliveryResult
import dev.metajetcore.agents.SpawnResult
import dev.metajetcore.harness.B24
import dev.metajetcore.harness.Codex
import dev.metajetcore.harness.Harness
import dev.metajetcore.mcp.McpTools
import dev.metajetcore.registry.SessionRegistry
import dev.metajetcore.roles.Role
import dev.metajetcore.settings.MjcSettings
import dev.metajetcore.terminal.Terminal
import dev.metajetcore.util.Json
import java.nio.file.Files

/**
 * Весь цикл работы с агентом на НАСТОЯЩЕЙ вкладке терминала.
 *
 * До этого теста терминальный слой проверялся только руками в песочнице, и каждый такой
 * прогон вскрывал очередную поломку, которую автотесты не видели: `/exit`, оседающий в поле
 * ввода; вкладки в editor area, невидимые для плагина; угон фокуса у оркестратора. Всё это —
 * ровно то, что здесь и проверяется.
 *
 * Вместо `claude` запускается подставной агент ([FakeAgent]): он пишет запись в реестр и
 * убирает её по `/exit`. Значит тест не ходит в сеть, не тратит лимиты подписки и не зависит
 * от версии CLI — но проверяет именно наш цикл целиком.
 *
 * Про потоки. Тело платформенного теста выполняется в EDT. Вызовы `Terminal` делаются прямо
 * (они EDT и требуют), а блокирующие операции `AgentManager` уходят на посторонний поток —
 * иначе они встанут, ожидая EDT, который держит сам тест. См. [onPooledThreadPumpingEdt].
 */
class TerminalFlowTest : BasePlatformTestCase() {

    private lateinit var settings: MjcSettings
    private lateinit var manager: AgentManager
    private lateinit var agent: FakeAgent.Installed

    override fun setUp() {
        super.setUp()
        settings = MjcSettings.getInstance()
        manager = project.getService(AgentManager::class.java)

        FlowSupport.registerTerminalToolWindow(project, testRootDisposable)
        // Вкладки с шеллом (проба, «руками запущенная», «застрявшая») тест закрывает при
        // живом шелле. Если у шелла есть фоновый потомок — у zsh с плагинами это обычное
        // дело, — платформа спрашивает «завершить процесс?», а тестовый диалог по умолчанию
        // на это бросает. Отвечаем так, как ответил бы человек. Код плагина этот диалог не
        // встречает: вкладку с живым чужим процессом он не закрывает (см. TabLeftOpen).
        TestDialogManager.setTestDialog(TestDialog.OK)
        agent = FlowSupport.useFakeAgent(project)
    }

    override fun tearDown() {
        try {
            // Вкладки закрыты телом теста, но их редакторы освобождаются следующими
            // событиями EDT — см. awaitEditorsReleased.
            awaitEditorsReleased()
            SessionRegistry.directoryOverride = null
            SessionRegistry.isProcessAlive = SessionRegistry.defaultLivenessCheck
            settings.launchCommand = "claude"
            settings.codexLaunchCommand = "codex"
            Codex.homeOverride = null
            settings.b24LaunchCommand = "b24-cowork-code-cli"
            B24.channelsOverride = null
            settings.reviewPanel = mutableMapOf()
            settings.reviewPanelModel = mutableMapOf()
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        } finally {
            super.tearDown()
        }
    }

    fun testSpawnBriefResetAndClose() {
        val result = onPooledThreadPumpingEdt {
            manager.spawn(
                role = Role.RESEARCHER,
                task = "разобраться в задаче",
                parentName = null,
                requestedName = null,
                requestedModel = null,
                domain = "flow",
            )
        }

        val started = result as? SpawnResult.Started
            // Экран вкладки — единственный способ понять, почему подставной агент не
            // поднялся: команду мы напечатали, а дальше отвечает шелл.
            ?: throw AssertionError(
                "агент не поднялся: $result\nошибка агента: ${agentError()}\n" +
                    "реестр:\n${registryDump()}\nэкран:\n${screenOf("mjc-rsrch-flow")}",
            )

        val name = started.agent.name
        assertEquals("mjc-rsrch-flow", name)
        // Имя в записи реестра взято из CLAUDE_CODE_SESSION_NAME — значит окружение доехало
        // через API вкладки, а не потерялось по дороге.
        assertNotNull("сессии нет в реестре", SessionRegistry.findByName(name))
        // Задачу плагин не доставляет: содержательный канал — SendMessage.
        assertEquals("разобраться в задаче", started.pendingBriefing)

        // /clear доходит до уже запущенного процесса. Раньше он оседал в поле ввода:
        // в pty уходил \n, а Enter в TUI — это \r.
        assertEquals(
            "reset не прошёл",
            dev.metajetcore.agents.ResetResult.Done,
            onPooledThreadPumpingEdt { manager.reset(name) },
        )

        // Не-ASCII в терминал не отправляем — он его искажает (docs/ARCHITECTURE.md §2.7.1).
        assertTrue(
            "кириллица должна отклоняться",
            onPooledThreadPumpingEdt { manager.brief(name, "Проверь модуль") }
                is dev.metajetcore.agents.BriefResult.NotAscii,
        )

        // Экран вкладки читается — это единственный способ увидеть зависшего агента.
        assertNotNull("экран не прочитан", onPooledThreadPumpingEdt { manager.readScreen(name) })

        // Закрытие: /exit доезжает, агент убирает свою запись и завершается, вкладка
        // закрывается вместе с ним без модального вопроса про запущенный процесс.
        assertEquals(
            "close не прошёл",
            CloseResult.Closed,
            onPooledThreadPumpingEdt { manager.close(name) },
        )
        assertNull("запись осталась в реестре", SessionRegistry.findByName(name))
        assertFalse("вкладка осталась под управлением", manager.knownTabs().contains(name))
    }

    fun testTabClosesWhenTheAgentExitsByItself() {
        // Агент вышел сам — по /exit изнутри или упал. Процесс вкладки и есть агент, поэтому
        // вкладка обязана закрыться без close_agent, а плагин — забыть её: иначе на экране
        // висит окно с мёртвым терминалом, а list_agents врёт про «управляемого» агента.
        val name = spawnResearcher("self")
        val handle = Terminal.handles(project).firstOrNull { it.name == name }
        // Выход «изнутри»: строка уходит прямо во вкладку, мимо close_agent.
        assertEquals(
            dev.metajetcore.agents.BriefResult.Sent,
            onPooledThreadPumpingEdt { manager.brief(name, "/exit") },
        )
        awaitPumpingEdt("вкладка закрылась сама") { !manager.knownTabs().contains(name) }
        if (handle != null) assertFalse("вкладка осталась на экране", Terminal.isOpen(project, handle))
    }

    fun testHungAgentIsKilledAndItsTabClosed() {
        if (dev.metajetcore.shell.ShellDialect.isWindows()) return
        // Сессия ушла из реестра, а процесс остался — висящее окно, ради которого всё и
        // затевалось. Процесс запустил плагин, значит плагин его и добивает.
        settings.launchCommand = agent.command + " " + FakeAgent.LINGER_FLAG
        val name = spawnResearcher("hung")
        val timeout = manager.tabDeadTimeoutMs
        manager.tabDeadTimeoutMs = 2_000L
        try {
            assertEquals(CloseResult.Closed, onPooledThreadPumpingEdt { manager.close(name) })
            assertFalse("вкладка осталась под управлением", manager.knownTabs().contains(name))
        } finally {
            manager.tabDeadTimeoutMs = timeout
        }
    }

    private fun spawnResearcher(domain: String): String {
        val result = onPooledThreadPumpingEdt {
            manager.spawn(Role.RESEARCHER, "задача", null, null, null, domain)
        }
        return (result as? SpawnResult.Started)?.agent?.name
            ?: throw AssertionError("агент не поднялся: $result\nошибка агента: ${agentError()}\nреестр:\n${registryDump()}")
    }

    fun testSpawnSurvivesWhenThereIsNoEditorArea() {
        // Размещение рядом с родителем автотестом не проверить: платформа в тестах ставит
        // TestEditorManagerImpl, у которого окон редактора нет вовсе (проверено замером:
        // `окон=0` при открытом файле). Проверяем то, что здесь и важно, — размещение это
        // косметика, и её отсутствие не должно мешать агенту подняться.
        val parent = Terminal.openTab(
            project = project,
            name = "mjc-orc",
            workingDirectory = manager.projectDirectory(),
            env = emptyMap(),
        ) ?: throw AssertionError("вкладка родителя не создалась")
        awaitPumpingEdt("шелл родителя поднялся") { Terminal.isRunning(parent) }
        manager.register("mjc-orc", parent)

        val started = onPooledThreadPumpingEdt {
            manager.spawn(
                role = Role.REVIEWER,
                task = "ревью",
                parentName = "mjc-orc",
                requestedName = null,
                requestedModel = null,
                domain = "near",
            )
        } as? SpawnResult.Started ?: throw AssertionError("агент не поднялся без editor area")

        assertEquals("mjc-rev-near", started.agent.name)
        onPooledThreadPumpingEdt { manager.close(started.agent.name) }
        Terminal.closeTab(project, parent)
    }

    fun testCallerSessionIsTheParentEvenAfterRename() {
        // Оркестратор во вкладке AIR, переименованный после старта: в parent он шлёт старое
        // имя, которого в реестре уже нет (OctoDash, 12.11). Вызывающего плагин знает по
        // соединению — связь и место агента берутся от него, а не от имени.
        val caller = dev.metajetcore.registry.SessionRecord(
            pid = ProcessHandle.current().pid().toInt(),
            sessionId = "8692d369-4c9d-482a-9c44-1b4dec44e0b5",
            name = "инт html",
            cwd = manager.projectDirectory().toString(),
            kind = "interactive",
            agent = null,
            status = "busy",
            version = "fake",
            startedAt = null,
        )
        val thread = object : com.intellij.testFramework.LightVirtualFile("threadView-orc"),
            com.intellij.air.thread.view.AgentThreadViewContentFile {
            override fun getThreadId() = caller.sessionId
        }
        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(thread, false)
        try {
            assertSame("вкладка AIR не нашлась по threadId", thread, Terminal.airThreadFile(project, caller.sessionId))
            assertNull(Terminal.airThreadFile(project, "чужая-сессия"))

            val started = onPooledThreadPumpingEdt {
                manager.spawn(
                    role = Role.REVIEWER,
                    task = "ревью",
                    parentName = "modules-bc",
                    requestedName = null,
                    requestedModel = null,
                    domain = "adr",
                    caller = caller,
                )
            } as? SpawnResult.Started ?: throw AssertionError("агент не поднялся")

            val record = Files.list(agent.registryDirectory).use { files ->
                files.toList().map { Json.parse(Files.readString(it)) }
                    .first { it["name"]?.asString == started.agent.name }
            }
            assertEquals(caller.sessionId, record["mjcParentSession"]?.asString)
            // Имя — текущее, из реестра, а не устаревшее из аргумента.
            assertEquals("инт html", record["mjcParent"]?.asString)
            onPooledThreadPumpingEdt { manager.close(started.agent.name) }
        } finally {
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).closeFile(thread)
        }
    }

    fun testTabIsDetachedFromToolWindowAndCanBeReturned() {
        // Перенос в editor area целиком здесь не проверить (окон редактора в тестах нет, см.
        // выше). Проверяем его половину, из-за которой внизу оставалась пустая вкладка агента:
        // снятие с тулвиндоу без гибели сессии — и возврат, если в редактор не попали.
        val handle = Terminal.openTab(
            project = project,
            name = "mjc-detach",
            workingDirectory = manager.projectDirectory(),
            env = emptyMap(),
        ) ?: throw AssertionError("вкладка не создалась")
        awaitPumpingEdt("шелл поднялся") { Terminal.isRunning(handle) }
        fun inToolWindow() = Terminal.tabs(project).any { Terminal.titleOf(it) == "mjc-detach" }
        assertTrue(inToolWindow())

        val from = Terminal.detachFromToolWindow(project, handle)
            ?: throw AssertionError("detachTab не вызвался — API сменилось?")
        assertFalse("вкладка осталась в тулвиндоу", inToolWindow())
        assertTrue("сессия не должна умирать при снятии", Terminal.isRunning(handle))

        Terminal.reattachToToolWindow(project, handle, from)
        assertTrue("вкладка не вернулась в тулвиндоу", inToolWindow())
        Terminal.closeTab(project, handle)
    }

    fun testManuallyStartedSessionIsMatchedByProcessTree() {
        // Вкладку открываем сами и печатаем команду руками — так делает разработчик, когда
        // запускает оркестратора не действием плагина. Плагин о такой вкладке ничего не
        // знает и обязан найти её по дереву процессов.
        val tab = Terminal.openTab(
            project = project,
            name = "mjc-manual",
            workingDirectory = manager.projectDirectory(),
            env = mapOf("CLAUDE_CODE_SESSION_NAME" to "mjc-manual-session"),
        ) ?: throw AssertionError("вкладка не создалась")
        awaitPumpingEdt("шелл поднялся") { Terminal.isRunning(tab) }
        assertTrue(Terminal.send(tab, settings.launchCommand, execute = true))

        awaitPumpingEdt("сессия появилась в реестре") {
            SessionRegistry.findByName("mjc-manual-session") != null
        }

        val found = dev.metajetcore.terminal.TabResolver
            .findTabForSession(project, "mjc-manual-session")
        assertNotNull("вкладку по дереву процессов не нашли", found)
        assertEquals("mjc-manual-session", found!!.name)

        Terminal.send(tab, "/exit", execute = true)
        awaitPumpingEdt("сессия ушла из реестра") {
            SessionRegistry.findByName("mjc-manual-session") == null
        }
        Terminal.closeTab(project, tab)
    }

    fun testTabWithLivingProcessIsNotClosedBehindAModalDialog() {
        // Вкладка, которую плагин не запускал: её процесс — шелл разработчика, он
        // переживает /exit, и добивать его плагин не вправе. Закрывать такую нельзя — платформа спросит
        // подтверждение модальным диалогом, а зовём мы закрытие под invokeAndWait, то есть
        // диалог подвесит поток MCP-запроса до ответа человека. Ровно на этом close_agent
        // и висел: «сессия закрылась, а вкладка ресерчера осталась».
        val tab = Terminal.openTab(
            project = project,
            name = "mjc-stuck",
            workingDirectory = manager.projectDirectory(),
            env = emptyMap(),
        ) ?: throw AssertionError("вкладка не создалась")
        awaitPumpingEdt("шелл поднялся") { Terminal.isRunning(tab) }
        manager.register("mjc-stuck", tab)

        // Ждать полный продовый таймаут тесту незачем: процесс здесь не умрёт никогда.
        val timeout = manager.tabDeadTimeoutMs
        manager.tabDeadTimeoutMs = 2_000L
        try {
            val result = onPooledThreadPumpingEdt { manager.close("mjc-stuck") }
            assertTrue("ожидался TabLeftOpen, получено $result", result is CloseResult.TabLeftOpen)
            // Вкладка остаётся под управлением: её экран — единственное объяснение того,
            // почему процесс не завершился, и read_tab по ней должен работать.
            assertTrue("вкладка выпала из управления", manager.knownTabs().contains("mjc-stuck"))
            assertFalse("процесс во вкладке не должен был умереть", Terminal.isTerminated(tab))
        } finally {
            manager.tabDeadTimeoutMs = timeout
            Terminal.closeTab(project, tab)
        }
    }

    /** Всё, что лежит в тестовом реестре: имя в записи важнее самого факта её наличия. */
    private fun registryDump(): String {
        val files = Files.list(agent.registryDirectory).use { it.toList() }
        if (files.isEmpty()) return "(пусто)"
        return files.joinToString("\n") { "${it.fileName}: ${Files.readString(it)}" }
    }

    /** Что подставной агент записал о своей собственной ошибке, если она была. */
    private fun agentError(): String {
        val file = agent.registryDirectory.resolve("error.txt")
        return if (Files.exists(file)) Files.readString(file) else "(нет)"
    }

    /** Экран вкладки — то, что видел бы разработчик. Без него отладка стенда слепая. */
    private fun screenOf(name: String): String =
        onPooledThreadPumpingEdt { manager.readScreen(name) } ?: "(экран недоступен)"

    fun testCodexAgentLifecycle() {
        if (dev.metajetcore.shell.ShellDialect.isWindows()) return
        // Весь цикл Codex-агента: задача уходит при старте, ответ хода плагин берёт из ленты,
        // следующая задача — через `codex queue`, сброс честно не поддерживается, закрытие
        // по /quit закрывает и вкладку.
        val codex = FakeCodex.install()
        Codex.homeOverride = codex.home
        settings.codexLaunchCommand = codex.command
        // Сессию Codex плагин опознаёт по каталогу. У лёгкого тестового проекта basePath на
        // диске нет, и терминал стартовал бы в родительском — в жизни каталог проекта есть.
        Files.createDirectories(manager.projectDirectory())

        val result = onPooledThreadPumpingEdt {
            manager.spawn(
                role = Role.REVIEWER,
                task = "проверь \"кавычки\" и ${'$'}HOME",
                parentName = null,
                requestedName = null,
                requestedModel = "opus",
                domain = "cdx",
                requestedHarness = Harness.CODEX,
            )
        }
        val started = result as? SpawnResult.Started
            ?: throw AssertionError("Codex-агент не поднялся: $result\nэкран:\n${screenOf("mjc-rev-cdx")}")
        val name = started.agent.name
        assertTrue("задача должна уйти при старте", started.taskDelivered)
        assertEquals(Harness.CODEX, started.agent.harness)
        // Модель Claude Codex-у не передаётся: он бы на ней упал.
        assertEquals("", started.agent.model)

        // Задача доехала через env дословно: кавычки целы, ${'$'}HOME не раскрыт.
        val first = onPooledThreadPumpingEdt { manager.awaitMessages(listOf(name), 10_000) }
        assertEquals(first.toString(), listOf("ECHO: проверь \"кавычки\" и ${'$'}HOME"), first.messages.map { it.text })

        val listed = manager.list().firstOrNull { it.name == name }
        assertEquals("idle", listed?.status)
        assertEquals(Harness.CODEX, listed?.harness)

        assertEquals(
            dev.metajetcore.agents.DeliveryResult.Sent,
            onPooledThreadPumpingEdt { manager.deliver(name, "вторая задача") },
        )
        val second = onPooledThreadPumpingEdt { manager.awaitMessages(listOf(name), 10_000) }
        assertEquals(second.toString(), listOf("ECHO: вторая задача"), second.messages.map { it.text })

        assertTrue(
            onPooledThreadPumpingEdt { manager.reset(name) } is dev.metajetcore.agents.ResetResult.Unsupported,
        )
        assertEquals(CloseResult.Closed, onPooledThreadPumpingEdt { manager.close(name) })
        assertFalse("вкладка осталась под управлением", manager.knownTabs().contains(name))
        assertNull(manager.list().firstOrNull { it.name == name })
    }

    fun testB24AgentLifecycle() {
        if (dev.metajetcore.shell.ShellDialect.isWindows()) return
        // Весь цикл b24-агента (docs/PLAN-B24.md): задача и роль уходят при старте, ответ —
        // последнее сообщение хода, а не первое; сообщение занятому агенту ждёт в очереди
        // плагина и уходит отдельным ходом; /clear работает; закрытие убирает и канал.
        val fake = FakeB24.install()
        settings.b24LaunchCommand = fake.command
        B24.channelsOverride = fake.channels
        Files.createDirectories(manager.projectDirectory())

        val result = onPooledThreadPumpingEdt {
            manager.spawn(
                role = Role.IMPLEMENTER,
                task = "проверь \"кавычки\" и ${'$'}HOME",
                parentName = null,
                requestedName = null,
                requestedModel = "bitrix/bitrixgpt-6.5-agent",
                domain = "b24",
                requestedHarness = Harness.B24,
            )
        }
        val started = result as? SpawnResult.Started
            ?: throw AssertionError("b24-агент не поднялся: $result\nэкран:\n${screenOf("mjc-impl-b24")}")
        val name = started.agent.name
        assertTrue("задача должна уйти при старте", started.taskDelivered)
        assertEquals(Harness.B24, started.agent.harness)
        assertEquals("bitrix/bitrixgpt-6.5-agent", started.agent.model)

        val first = onPooledThreadPumpingEdt { manager.awaitMessages(listOf(name), 10_000) }
        assertEquals(first.toString(), listOf("ECHO: проверь \"кавычки\" и ${'$'}HOME"), first.messages.map { it.text })

        // Что доехало в argv: модель, режим прав по общей настройке, промпт роли с протоколом.
        val channel = Files.list(fake.channels).use { it.toList() }.single()
        val seen = Json.parse(Files.readString(channel.resolve("seen.json")))
        assertEquals("bitrix/bitrixgpt-6.5-agent", seen["model"]?.asString)
        // Режим прав b24 — свой, по умолчанию auto: агент в default встал бы на вопросе во вкладке.
        assertEquals("auto", seen["approval"]?.asString)
        assertTrue(seen["prompt"]?.asString.orEmpty().contains("Протокол команды"))

        val listed = manager.list().firstOrNull { it.name == name }
        assertEquals("idle", listed?.status)
        assertEquals(Harness.B24, listed?.harness)

        // Ход с инструментом: пока он идёт, второе сообщение ждёт в очереди плагина.
        assertEquals(DeliveryResult.Sent, onPooledThreadPumpingEdt { manager.deliver(name, "медленно раз") })
        assertEquals(DeliveryResult.Queued(1), onPooledThreadPumpingEdt { manager.deliver(name, "два") })
        val replies = mutableListOf<String>()
        repeat(3) {
            if (replies.size < 2) {
                replies += onPooledThreadPumpingEdt { manager.awaitMessages(listOf(name), 10_000) }.messages.map { it.text }
            }
        }
        // Ответ первого хода — итог, а не «Делаю.» из сообщения с инструментом; второе
        // сообщение — отдельный ход, а не влитое в первый.
        assertEquals(listOf("ECHO: медленно раз", "ECHO: два"), replies)

        assertEquals(
            dev.metajetcore.agents.ResetResult.Done,
            onPooledThreadPumpingEdt { manager.reset(name) },
        )
        onPooledThreadPumpingEdt { manager.deliver(name, "три") }
        val afterReset = onPooledThreadPumpingEdt { manager.awaitMessages(listOf(name), 10_000) }
        assertEquals(afterReset.toString(), listOf("ECHO(после /clear): три"), afterReset.messages.map { it.text })

        assertEquals(CloseResult.Closed, onPooledThreadPumpingEdt { manager.close(name) })
        assertFalse("вкладка осталась под управлением", manager.knownTabs().contains(name))
        assertNull(manager.list().firstOrNull { it.name == name })
        assertFalse("канал не удалён", Files.exists(channel))
    }

    fun testFinalReviewOpensTheWholePanel() {
        if (dev.metajetcore.shell.ShellDialect.isWindows()) return
        // Один вызов spawn_agent(reviewer, domain=review) — весь состав из настроек: Claude и
        // Codex, у каждого свой слот и своя приписка про файл отчёта.
        val codex = FakeCodex.install()
        Codex.homeOverride = codex.home
        settings.codexLaunchCommand = codex.command
        settings.reviewPanel = mutableMapOf("claude_code" to 1, "codex" to 1)
        Files.createDirectories(manager.projectDirectory())

        val tools = McpTools(project)
        val result = onPooledThreadPumpingEdt {
            tools.call(
                Json.obj(
                    "name" to Json.of("spawn_agent"),
                    "arguments" to Json.obj(
                        "role" to Json.of("reviewer"),
                        "task" to Json.of("ревью ветки, отчёт в review.md"),
                        "domain" to Json.of("review"),
                    ),
                ),
            )
        }
        val text = result["content"]!!.asList!!.first()["text"]!!.asString!!
        val claudeName = "mjc-rev-review-claude"
        val codexName = "mjc-rev-review-codex"
        try {
            assertTrue(text, text.contains("запущено: 2"))
            assertTrue(text, text.contains("SendMessage(to=\"$claudeName\")"))
            assertTrue(text, text.contains("await_messages(names=[\"$codexName\"])"))
            // Сводит Claude-участник, а не оркестратор.
            assertTrue(text, text.contains("отправь $claudeName (SendMessage)"))
            // Задача Codex-у ушла при старте — вместе с припиской про его файл отчёта.
            val first = onPooledThreadPumpingEdt { manager.awaitMessages(listOf(codexName), 10_000) }
            val echoed = first.messages.singleOrNull()?.text.orEmpty()
            assertTrue(echoed, echoed.startsWith("ECHO: ревью ветки") && echoed.contains("review-review-codex.md"))
        } finally {
            for (name in listOf(claudeName, codexName)) {
                if (manager.knownTabs().contains(name)) onPooledThreadPumpingEdt { manager.close(name) }
            }
        }
        assertFalse(manager.knownTabs().contains(claudeName))
        assertFalse(manager.knownTabs().contains(codexName))
    }

    fun testFakeAgentWritesWhereWeExpect() {
        // Проверка самого стенда: если подставной агент перестанет писать запись, остальные
        // тесты начнут падать по таймауту, и причина будет неочевидна.
        assertTrue(Files.isDirectory(agent.registryDirectory))
        assertTrue(agent.command, agent.command.contains("agent."))
    }
}
