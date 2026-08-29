package dev.metajetcore

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import dev.metajetcore.registry.SessionRegistry
import dev.metajetcore.settings.MjcSettings
import dev.metajetcore.shell.ShellDialect
import dev.metajetcore.terminal.Terminal
import java.nio.file.Paths

/**
 * Общая обвязка тестов, которым нужна настоящая вкладка терминала.
 *
 * Вынесена отдельно, потому что нужна и лёгкой фикстуре, и тяжёлой: размещение в editor area
 * проверяется только во второй — в лёгкой у платформы нет настоящих окон редактора.
 */
object FlowSupport {

    private const val TERMINAL_TOOL_WINDOW = "Terminal"

    /**
     * В тестовой IDE тулвиндоу терминала никто не создаёт, а менеджер вкладок без него
     * отказывается работать: `IllegalStateException: No terminal tool window found`.
     * Регистрируем пустой — тестам нужен не вид, а место, куда класть вкладки.
     */
    fun registerTerminalToolWindow(project: Project, disposable: Disposable) {
        val manager = ToolWindowManager.getInstance(project)
        if (manager.getToolWindow(TERMINAL_TOOL_WINDOW) != null) return
        @Suppress("DEPRECATION")
        manager.registerToolWindow(TERMINAL_TOOL_WINDOW, true, ToolWindowAnchor.BOTTOM, disposable, true)
    }

    /**
     * Диалект шелла, который IDE откроет во вкладке. Узнаём его так же, как продовый код, —
     * у фактически запущенного процесса: настройка терминала обычно пуста, а окружение
     * процесса IDE описывает другой шелл.
     */
    fun detectTabShell(project: Project): ShellDialect {
        val probe = Terminal.openTab(
            project = project,
            name = "mjc-probe",
            workingDirectory = Paths.get(System.getProperty("java.io.tmpdir")),
            env = emptyMap(),
        ) ?: throw AssertionError("вкладка терминала не создалась")

        awaitPumpingEdt("шелл во вкладке поднялся") { Terminal.isRunning(probe) }
        val shell = Terminal.process(probe)?.shellCommand?.firstOrNull()
        Terminal.closeTab(project, probe)
        return ShellDialect.detect(shell)
    }

    /** Ставит подставного агента и настраивает плагин на него. */
    fun useFakeAgent(project: Project): FakeAgent.Installed {
        val agent = FakeAgent.install(detectTabShell(project))

        SessionRegistry.directoryOverride = agent.registryDirectory
        // pid у подставного агента настоящий, но процесс мог уже завершиться к моменту
        // проверки — живость тут не предмет теста.
        SessionRegistry.isProcessAlive = { true }

        val settings = MjcSettings.getInstance()
        settings.launchCommand = agent.command
        settings.namePrefix = "mjc"
        settings.permissionMode = "auto"
        settings.extraEnvRaw = ""
        return agent
    }

    /** Возвращает настройки и реестр в исходное состояние. */
    fun reset() {
        SessionRegistry.directoryOverride = null
        SessionRegistry.isProcessAlive = SessionRegistry.defaultLivenessCheck
        MjcSettings.getInstance().launchCommand = "claude"
    }
}
