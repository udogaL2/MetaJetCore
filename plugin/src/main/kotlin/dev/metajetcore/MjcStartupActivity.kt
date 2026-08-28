package dev.metajetcore

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.metajetcore.mcp.McpServerService
import dev.metajetcore.skill.SkillInstaller

class MjcStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.getService(McpServerService::class.java).start()
        // Скилл оркестратора обновляем при каждом старте: он описывает инструменты плагина,
        // поэтому его версия обязана совпадать с версией плагина.
        SkillInstaller.installIfChanged()
    }
}
