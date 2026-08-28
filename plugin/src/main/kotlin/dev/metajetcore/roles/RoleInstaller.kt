package dev.metajetcore.roles

import com.intellij.openapi.diagnostic.Logger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Установка определений ролей в пользовательский скоуп: ~/.claude/agents/mjc-<role>.md.
 *
 * Зачем не передавать роли инлайном через `--agents '<json>'`:
 *
 *  * промпты ролей на русском, а плагин печатает команду в терминал IDE, который пишет в pty
 *    в кодировке JVM. В песочнице это оказалась windows-1251, и кириллица приезжала
 *    кракозябрами — то есть агент получил бы искажённый системный промпт;
 *  * Windows PowerShell вообще не может передать нативному exe аргумент, в котором есть и
 *    кавычки, и пробелы (проверено живыми запусками).
 *
 * Отсюда правило: **в терминал плагин печатает только ASCII**. Роль уезжает флагом
 * `--agent mjc-<role>`, а её текст читает сам claude — из файла, как UTF-8.
 *
 * Пользовательский скоуп, а не проектный, по той же причине, что и у скилла: работает во всех
 * проектах сразу, и в целевой проект копировать нечего.
 *
 * Побочный эффект: роли появятся в списке типов субагентов. Отписаться от этого нельзя,
 * поэтому описание каждой начинается с «НЕ использовать как субагента» — Claude выбирает
 * субагента именно по описанию.
 */
object RoleInstaller {
    private val log = Logger.getInstance(RoleInstaller::class.java)

    /** Префикс, чтобы не столкнуться с собственными агентами пользователя. */
    const val PREFIX = "mjc-"

    fun agentName(role: Role): String = PREFIX + role.id

    fun agentsDirectory(): Path {
        val home = System.getenv("CLAUDE_CONFIG_DIR")?.takeIf { it.isNotBlank() }
            ?.let { Paths.get(it) }
            ?: Paths.get(System.getProperty("user.home"), ".claude")
        return home.resolve("agents")
    }

    fun definitionPath(role: Role): Path = agentsDirectory().resolve("${agentName(role)}.md")

    /** Возвращает число переписанных файлов. */
    fun installIfChanged(): Int {
        var changed = 0
        try {
            Files.createDirectories(agentsDirectory())
        } catch (e: Exception) {
            log.warn("MetaJetCore: cannot create ${agentsDirectory()}", e)
            return 0
        }

        for (role in Role.entries) {
            val path = definitionPath(role)
            val desired = definitionText(role)
            try {
                val current = if (Files.exists(path)) {
                    Files.readString(path, StandardCharsets.UTF_8)
                } else {
                    null
                }
                if (current != desired) {
                    // Явно UTF-8: дефолтная кодировка JVM у IDE может быть какой угодно,
                    // и именно на этом мы уже один раз обожглись.
                    Files.write(path, desired.toByteArray(StandardCharsets.UTF_8))
                    changed++
                }
            } catch (e: Exception) {
                log.warn("MetaJetCore: cannot write $path", e)
            }
        }
        if (changed > 0) log.info("MetaJetCore: installed $changed role definitions into ${agentsDirectory()}")
        return changed
    }

    private fun definitionText(role: Role): String {
        val tools = Roles.toolsFor(role).joinToString(", ")
        return buildString {
            append("---\n")
            append("name: ${agentName(role)}\n")
            append("description: Роль целой сессии MetaJetCore. НЕ использовать как субагента. ")
            append(descriptionFor(role))
            append("\n")
            append("tools: $tools\n")
            append("model: ${role.defaultModel}\n")
            append("---\n\n")
            append(Roles.promptFor(role))
            append("\n")
        }
    }

    private fun descriptionFor(role: Role): String = when (role) {
        Role.ORCHESTRATOR -> "Координирует остальные роли, сам код не пишет."
        Role.IMPLEMENTER -> "Пишет код в рамках выданного файлового домена."
        Role.RESEARCHER -> "Исследует кодовую базу и внешние источники, только чтение."
        Role.REVIEWER -> "Вычитывает готовый код через заданную линзу, только чтение."
    }
}
