package dev.metajetcore.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import dev.metajetcore.harness.Harness
import dev.metajetcore.roles.Role
import dev.metajetcore.terminal.Terminal

class MjcConfigurable : BoundConfigurable("MetaJetCore") {

    private val settings = MjcSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        group("Запуск агентов") {
            row("Команда запуска:") {
                textField()
                    .bindText(settings::launchCommand)
                    .columns(40)
                    .comment(
                        "Ваша обёртка вокруг claude. Она обязана пробрасывать \"\$@\": иначе до " +
                            "агента не доедут --agent и --permission-mode, и он стартует " +
                            "безролевым в manual mode. Проверить: scripts/check-wrapper.sh",
                    )
            }
            row("Префикс имён:") {
                textField()
                    .bindText(settings::namePrefix)
                    .columns(10)
                    .comment("Пусто — вывести из имени проекта. Имена сессий глобальны на машину.")
            }
            row("Режим прав агентов:") {
                comboBox(listOf("auto", "acceptEdits", "plan", "bypassPermissions", ""))
                    .bindItem(settings::permissionMode.toNullableProperty())
                    .comment(
                        "Уезжает флагом --permission-mode. Задавать обязательно: агент по " +
                            "умолчанию стартует в manual mode и встанет на первом запросе прав " +
                            "в вкладке, которую никто не читает. Настройкой проекта это не " +
                            "лечится — repo-level defaultMode Claude Code игнорирует.",
                    )
            }
            row {
                checkBox("Вычищать наследуемые маркеры Claude Code")
                    .bindSelected(settings::stripInheritedClaudeMarkers)
                    .comment(
                        "CLAUDE_CODE_CHILD_SESSION и соседние. Без вычистки агент, запущенный " +
                            "из IDE, которая сама стартовала внутри сессии Claude Code, считает " +
                            "себя вложенным процессом и не регистрируется в ~/.claude/sessions.",
                    )
            }
            row("Ожидание готовности, с:") {
                intTextField(1..600).bindIntText(settings::readyTimeoutSeconds).columns(6)
                    .comment("Сколько ждать появления сессии в ~/.claude/sessions.")
            }
            row {
                checkBox("Вычищать ANTHROPIC_API_KEY и ANTHROPIC_AUTH_TOKEN")
                    .bindSelected(settings::stripApiKeys)
                    .comment(
                        "Иначе агент молча уезжает с подписки на API-биллинг. " +
                            "Выключать только осознанно.",
                    )
            }
            row("Доп. переменные:") {
                textArea()
                    .bindText(settings::extraEnvRaw)
                    .columns(40)
                    // rows() — расширение, которое живёт не во всех версиях UI DSL;
                    // applyToComponent есть всегда и делает то же самое.
                    .applyToComponent { rows = 4 }
                    .comment("По одной на строку, в формате KEY=VALUE.")
            }
        }

        group("Харнесы ролей") {
            // Список — у IDE (агенты терминала, 263+), а не свой: что установлено и включено,
            // знает она. Показываем только то, для чего в плагине есть адаптер.
            val available = Terminal.terminalAgentKeys()
            val choices = Harness.entries.filter { it == Harness.CLAUDE || it.key in available }
            for (role in Role.entries - Role.ORCHESTRATOR) {
                row("${role.id}:") {
                    comboBox(choices.map { it.key })
                        .bindItem(
                            { settings.harnessFor(role).key },
                            { key -> settings.roleHarness[role.id] = key ?: Harness.CLAUDE.key },
                        )
                    textField()
                        .bindText(
                            { settings.roleModel[role.id].orEmpty() },
                            { model -> settings.roleModel[role.id] = model.trim() },
                        )
                        .columns(14)
                        .comment("модель; пусто — по умолчанию")
                }
            }
            row {
                comment(
                    "Оркестратор — всегда Claude Code. Харнесы IDE без адаптера плагина " +
                        "(${(available - Harness.entries.map { it.key }.toSet()).ifEmpty { listOf("нет") }.joinToString()}) " +
                        "назначить нельзя." +
                        if (available.isEmpty()) " Список агентов IDE недоступен — доступен только Claude Code." else "",
                )
            }
            row("Команда Codex:") {
                textField().bindText(settings::codexLaunchCommand).columns(30)
            }
            row("Аргументы Codex:") {
                textField().bindText(settings::codexArgs).columns(40)
                    .comment(
                        "-a never: подтверждения во вкладке, которую никто не читает, — вечная " +
                            "блокировка. --disable hooks: иначе Codex спросит «Review hooks» на старте.",
                    )
            }
        }

        group("MCP-сервер") {
            row("Порт:") {
                intTextField(0..65535).bindIntText(settings::mcpPort).columns(6)
                    .comment("0 — выбрать свободный автоматически. Изменение требует перезапуска IDE.")
            }
        }
    }
}
