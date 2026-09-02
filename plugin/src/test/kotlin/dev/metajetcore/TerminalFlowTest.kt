package dev.metajetcore

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.metajetcore.agents.AgentManager
import dev.metajetcore.agents.CloseResult
import dev.metajetcore.agents.SpawnResult
import dev.metajetcore.registry.SessionRegistry
import dev.metajetcore.roles.Role
import dev.metajetcore.settings.MjcSettings
import dev.metajetcore.terminal.Terminal
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
        assertTrue("reset не прошёл", onPooledThreadPumpingEdt { manager.reset(name) })

        // Не-ASCII в терминал не отправляем — он его искажает (docs/ARCHITECTURE.md §2.7.1).
        assertTrue(
            "кириллица должна отклоняться",
            onPooledThreadPumpingEdt { manager.brief(name, "Проверь модуль") }
                is dev.metajetcore.agents.BriefResult.NotAscii,
        )

        // Экран вкладки читается — это единственный способ увидеть зависшего агента.
        assertNotNull("экран не прочитан", onPooledThreadPumpingEdt { manager.readScreen(name) })

        // Закрытие: /exit доезжает, агент убирает свою запись, шелл гаснет вместе с ним,
        // вкладка закрывается без модального вопроса про запущенный процесс.
        assertEquals(
            "close не прошёл",
            CloseResult.Closed,
            onPooledThreadPumpingEdt { manager.close(name) },
        )
        assertNull("запись осталась в реестре", SessionRegistry.findByName(name))
        assertFalse("вкладка осталась под управлением", manager.knownTabs().contains(name))
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
        // Вкладка, которую плагин не запускал: хвостового выхода из шелла в ней нет, и
        // процесс переживает /exit. Закрывать такую нельзя — платформа спросит
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

    fun testFakeAgentWritesWhereWeExpect() {
        // Проверка самого стенда: если подставной агент перестанет писать запись, остальные
        // тесты начнут падать по таймауту, и причина будет неочевидна.
        assertTrue(Files.isDirectory(agent.registryDirectory))
        assertTrue(agent.command, agent.command.contains("agent."))
    }
}
