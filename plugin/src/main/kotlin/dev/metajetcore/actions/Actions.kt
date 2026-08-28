package dev.metajetcore.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import dev.metajetcore.agents.AgentManager
import dev.metajetcore.mcp.McpServerService
import dev.metajetcore.registry.SessionRegistry
import dev.metajetcore.settings.MjcSettings
import dev.metajetcore.shell.ShellDialect
import dev.metajetcore.skill.SkillInstaller
import dev.metajetcore.terminal.OpenTabRequest
import dev.metajetcore.terminal.TerminalBackends
import java.awt.datatransfer.StringSelection

private fun notify(project: Project?, text: String, type: NotificationType = NotificationType.INFORMATION) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup("MetaJetCore")
        .createNotification("MetaJetCore", text, type)
        .notify(project)
}

/** Отдаёт команду подключения оркестратора к MCP-серверу плагина. */
class CopyMcpConfigAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.getData(CommonDataKeys.PROJECT) ?: return
        val command = project.getService(McpServerService::class.java).claudeMcpAddCommand()
        CopyPasteManager.getInstance().setContents(StringSelection(command))
        notify(project, "Скопировано:<br/><code>$command</code>")
    }
}

/**
 * Открывает вкладку с обычной сессией Claude Code, помеченной как оркестратор.
 *
 * Роль здесь НЕ навязывается: оркестратором сессия становится по вызову /orchestrate.
 * Смысл действия — в том, чтобы вкладка попала под управление плагина и он знал, где она,
 * когда придёт spawn_agent(parent=...).
 */
class NewOrchestratorAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.getData(CommonDataKeys.PROJECT) ?: return
        val manager = project.getService(AgentManager::class.java)
        val settings = MjcSettings.getInstance()

        val base = "${manager.prefix()}-orc"
        val name = generateSequence(0) { it + 1 }
            .map { if (it == 0) base else "$base-${it + 1}" }
            .first { !SessionRegistry.isNameTaken(it) }

        val backend = TerminalBackends.resolve()
        val shell = ShellDialect.detect(System.getenv("SHELL") ?: System.getenv("ComSpec"))
        val command = shell.unsetPrefix(
            if (settings.stripApiKeys) listOf("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN") else emptyList(),
        ) + shell.composeCommand(
            mapOf("CLAUDE_CODE_SESSION_NAME" to name),
            settings.launchCommand,
        )

        val handle = backend.openTab(
            project,
            OpenTabRequest(tabName = name, workingDirectory = manager.projectDirectory(), nearTab = null),
        )

        if (handle == null) {
            CopyPasteManager.getInstance().setContents(StringSelection(command))
            notify(
                project,
                "Вкладку открыть не удалось (backend=${backend.id}). " +
                    "Команда скопирована — запусти вручную.",
                NotificationType.WARNING,
            )
            return
        }

        backend.sendLine(handle, command)
        notify(project, "Оркестратор <b>$name</b> запущен. Вызови в нём <code>/orchestrate</code>.")
    }
}

/** Диагностика: какое терминальное API разрешилось и что вообще происходит. */
class DiagnosticsAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.getData(CommonDataKeys.PROJECT)
        TerminalBackends.invalidate()
        val settings = MjcSettings.getInstance()
        val endpoint = project?.getService(McpServerService::class.java)?.endpoint() ?: "не запущен"
        val text = buildString {
            append("MCP: $endpoint<br/>")
            append("режим роли: ${settings.roleDelivery}<br/>")
            append("команда запуска: ${settings.launchCommand}<br/>")
            append("реестр сессий: ${SessionRegistry.directory()}<br/>")
            append("живых сессий: ${SessionRegistry.all().size}<br/>")
            append("<pre>${TerminalBackends.describe()}</pre>")
        }
        notify(project, text)
    }
}

/** Переустановить скилл оркестратора в ~/.claude/skills/orchestrate/. */
class InstallSkillAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.getData(CommonDataKeys.PROJECT)
        val changed = SkillInstaller.installIfChanged()
        notify(
            project,
            if (changed) "Скилл обновлён: ${SkillInstaller.skillDirectory()}"
            else "Скилл уже актуален: ${SkillInstaller.skillDirectory()}",
        )
    }
}
