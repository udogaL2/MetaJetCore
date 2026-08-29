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
import dev.metajetcore.roles.RoleInstaller
import dev.metajetcore.settings.MjcSettings
import dev.metajetcore.shell.ShellDialect
import dev.metajetcore.skill.SkillInstaller
import dev.metajetcore.terminal.TabPlacement
import dev.metajetcore.terminal.Terminal
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
 * Смысл действия в другом — вкладка сразу попадает под управление плагина и переезжает в
 * editor area, поэтому агенты потом открываются рядом с ней. Запущенную руками вкладку
 * плагин тоже находит (по дереву процессов), но разместить рядом с ней уже некуда: она в
 * тулвиндоу.
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

        val handle = Terminal.openTab(
            project = project,
            name = name,
            workingDirectory = manager.projectDirectory(),
            env = mapOf("CLAUDE_CODE_SESSION_NAME" to name),
            requestFocus = true,
        )

        if (handle == null) {
            val shell = ShellDialect.detect(System.getenv("SHELL"))
            val command = shell.unsetNames(manager.inheritedMarkers()) +
                "CLAUDE_CODE_SESSION_NAME=$name ${settings.launchCommand}"
            CopyPasteManager.getInstance().setContents(StringSelection(command))
            notify(
                project,
                "Вкладку открыть не удалось. Команда скопирована — запусти вручную.",
                NotificationType.WARNING,
            )
            return
        }

        manager.register(name, handle)
        TabPlacement.moveToEditor(project, handle)
        manager.startPlainSession(handle)
        notify(project, "Оркестратор <b>$name</b> запускается. Вызови в нём <code>/orchestrate</code>.")
    }
}

/** Диагностика: что разрешилось из терминального API и что вообще происходит. */
class DiagnosticsAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.getData(CommonDataKeys.PROJECT)
        val settings = MjcSettings.getInstance()
        val endpoint = project?.getService(McpServerService::class.java)?.endpoint() ?: "не запущен"
        val text = buildString {
            append("MCP: $endpoint<br/>")
            append("команда запуска: ${settings.launchCommand}<br/>")
            append("реестр сессий: ${SessionRegistry.directory()}<br/>")
            append("живых сессий: ${SessionRegistry.all().size}<br/>")
            append("<pre>${Terminal.describe(project)}</pre>")
        }
        notify(project, text)
    }
}

/** Переустановить скилл оркестратора в ~/.claude/skills/orchestrate/. */
class InstallSkillAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.getData(CommonDataKeys.PROJECT)
        val skill = SkillInstaller.installIfChanged()
        val roles = RoleInstaller.installIfChanged()
        notify(
            project,
            "Скилл: ${if (skill) "обновлён" else "актуален"} (${SkillInstaller.skillDirectory()})<br/>" +
                "Роли: переписано $roles (${RoleInstaller.agentsDirectory()})",
        )
    }
}
