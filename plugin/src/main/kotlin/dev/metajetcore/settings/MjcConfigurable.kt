package dev.metajetcore.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import dev.metajetcore.harness.Harness
import dev.metajetcore.roles.Role
import dev.metajetcore.terminal.Terminal

/**
 * Экран `Settings | Tools | MetaJetCore`.
 *
 * Устроен от частого к редкому: команда запуска и режим прав, затем таблица ролей, затем Codex
 * (раскрыт, только если он кому-то назначен) и свёрнутое «Дополнительно». Почему у каждой
 * опции такое значение по умолчанию — в подсказке «?», а не в абзаце под полем: абзацы под
 * каждой строкой превращали экран в стену текста, за которой не видно самих настроек.
 */
class MjcConfigurable : BoundConfigurable("MetaJetCore") {

    private val settings = MjcSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        group("Claude Code") {
            row("Команда запуска:") {
                textField()
                    .bindText(settings::launchCommand)
                    .columns(30)
                    .comment("claude или ваша обёртка вокруг него")
                contextHelp(
                    "Обёртка обязана пробрасывать аргументы (\"\$@\"): иначе до агента не дойдут " +
                        "--agent и --permission-mode, и он стартует без роли в ручном режиме прав. " +
                        "Проверить: scripts/check-wrapper.sh.",
                )
            }
            row("Режим прав агентов:") {
                comboBox(PERMISSION_MODES.keys.toList(), SimpleListCellRenderer.create("") { PERMISSION_MODES[it] ?: it })
                    .bindItem(settings::permissionMode.toNullableProperty())
                contextHelp(
                    "Агента без режима прав никто не разблокирует: его вкладку не читают, и он " +
                        "встанет на первом же запросе. Настройкой проекта это не задать — " +
                        "repo-level defaultMode Claude Code игнорирует, поэтому режим едет флагом.",
                )
            }
            row("Префикс имён сессий:") {
                textField()
                    .bindText(settings::namePrefix)
                    .columns(8)
                    .applyToComponent { emptyText.text = "по проекту" }
                    .comment("Пусто — аббревиатура проекта: MetaJetCore → mjc-impl-be")
            }
        }

        group("Роли") {
            // Список — у IDE (агенты терминала, 263+), а не свой: что установлено и включено,
            // знает она. Назначить можно только харнес, для которого в плагине есть адаптер.
            val available = Terminal.terminalAgentKeys()
            val offered = Harness.entries.filter { it == Harness.CLAUDE || it.key in available }

            row("") {
                label("Харнес").bold()
                label("Модель").bold()
            }.layout(RowLayout.PARENT_GRID)

            for (role in Role.entries) {
                row(ROLE_TITLES.getValue(role) + ":") {
                    // Уже назначенный харнес показываем, даже если IDE его сейчас не видит:
                    // иначе комбобокс молча подменил бы его Claude Code при первом Apply.
                    val choices = (offered + settings.harnessFor(role)).distinct()
                    val harness = comboBox(choices, harnessRenderer())
                        .bindItem(
                            { settings.harnessFor(role) },
                            { picked -> settings.roleHarness[role.id] = (picked ?: Harness.CLAUDE).key },
                        )
                        // Оркестратор — всегда Claude Code: на нём держится SendMessage.
                        .enabled(role != Role.ORCHESTRATOR && choices.size > 1)
                    val model = textField()
                        .bindText(
                            { settings.roleModel[role.id].orEmpty() },
                            { value -> settings.roleModel[role.id] = value.trim() },
                        )
                        .columns(12)
                        .validationOnInput { field -> modelWarning(field.text, harness.component.item) }
                    // Подсказка в пустом поле — модель, которая реально поедет, а не «по умолчанию»:
                    // так видно, что оставить пусто — тоже осознанный выбор.
                    fun refreshPlaceholder() = placeholder(model.component, role, harness.component)
                    refreshPlaceholder()
                    harness.component.addActionListener { refreshPlaceholder() }
                    comment(ROLE_HINTS.getValue(role))
                }.layout(RowLayout.PARENT_GRID)
            }
            row {
                comment(
                    if (Harness.CODEX in offered) "Пустая модель — модель роли для Claude Code, модель из ~/.codex/config.toml для Codex."
                    else "Codex появится в списке, когда IDE увидит его среди агентов терминала.",
                )
            }
        }

        val codexUsed = Role.entries.any { settings.harnessFor(it) == Harness.CODEX }
        collapsibleGroup("Codex") {
            row("Команда:") {
                textField().bindText(settings::codexLaunchCommand).columns(30)
            }
            row("Аргументы:") {
                textField().bindText(settings::codexArgs).columns(40)
                contextHelp(
                    "-a never: вопрос о подтверждении во вкладке, которую никто не читает, — вечная " +
                        "блокировка. --disable hooks: иначе Codex спросит «Review hooks» на старте. " +
                        "Роль, доверие к каталогу и модель плагин добавит сам.",
                )
            }
        }.expanded = codexUsed

        collapsibleGroup("Дополнительно") {
            row("Ожидание старта агента:") {
                intTextField(1..600).bindIntText(settings::readyTimeoutSeconds).columns(5)
                label("с")
                contextHelp(
                    "Сколько ждать, пока сессия появится в ~/.claude/sessions. Спавн держит MCP-вызов " +
                        "оркестратора всё это время, поэтому значение должно быть ниже его MCP_TIMEOUT.",
                )
            }
            row {
                checkBox("Не передавать агентам ANTHROPIC_API_KEY и ANTHROPIC_AUTH_TOKEN")
                    .bindSelected(settings::stripApiKeys)
                contextHelp("Иначе агент молча уходит с подписки на оплату по API.")
            }
            row {
                checkBox("Снимать унаследованные переменные Claude Code")
                    .bindSelected(settings::stripInheritedClaudeMarkers)
                contextHelp(
                    "Если IDE запущена из сессии Claude Code, агент унаследует её маркеры, сочтёт " +
                        "себя вложенным процессом и не зарегистрируется в ~/.claude/sessions.",
                )
            }
            row("Переменные окружения агентов:") {
                textArea()
                    .bindText(settings::extraEnvRaw)
                    .align(AlignX.FILL)
                    // rows() — расширение, которое живёт не во всех версиях UI DSL;
                    // applyToComponent есть всегда и делает то же самое.
                    .applyToComponent { rows = 3 }
                    .comment("KEY=VALUE, по одной на строку")
            }
            row("Порт MCP-сервера:") {
                intTextField(0..65535).bindIntText(settings::mcpPort).columns(6)
                    .comment("0 — вычислить по пути проекта. Применится после перезапуска IDE.")
            }
        }
    }

    private fun harnessRenderer() = SimpleListCellRenderer.create<Harness?> { label, value, _ ->
        label.text = value?.displayName.orEmpty()
        label.icon = value?.let { Terminal.agentIcon(it.key) }
    }

    private fun placeholder(field: JBTextField, role: Role, harness: ComboBox<Harness>) {
        field.emptyText.text = when (harness.item) {
            Harness.CODEX -> "из конфига Codex"
            else -> role.defaultModel
        }
        field.repaint()
    }

    /**
     * Модель от другого харнеса — не ошибка ввода, а частый недосмотр после смены харнеса
     * роли. Сохранить даём (у Codex такая модель просто отбросится), но предупреждаем.
     */
    private fun modelWarning(text: String, harness: Harness?) = with(text.trim()) {
        when {
            isEmpty() -> null
            harness == Harness.CODEX && Harness.isClaudeModel(this) ->
                com.intellij.openapi.ui.ValidationInfo("Codex не знает модели Claude — возьмётся модель из его конфига").asWarning()
            harness == Harness.CLAUDE && !Harness.isClaudeModel(this) ->
                com.intellij.openapi.ui.ValidationInfo("Не похоже на модель Claude").asWarning()
            else -> null
        }
    }

    private companion object {
        val PERMISSION_MODES = linkedMapOf(
            "auto" to "auto — агент решает сам (рекомендуется)",
            "acceptEdits" to "acceptEdits — правки без вопросов",
            "plan" to "plan — только планирование",
            "bypassPermissions" to "bypassPermissions — без проверок",
            "" to "не передавать флаг",
        )

        val ROLE_TITLES = mapOf(
            Role.ORCHESTRATOR to "Оркестратор",
            Role.PLANNER to "Планировщик",
            Role.IMPLEMENTER to "Имплементер",
            Role.RESEARCHER to "Ресерчер",
            Role.REVIEWER to "Ревьювер",
        )

        val ROLE_HINTS = mapOf(
            Role.ORCHESTRATOR to "ведёт задачу; всегда Claude Code",
            Role.PLANNER to "ADR, SDD, план",
            Role.IMPLEMENTER to "код фаз, исправления",
            Role.RESEARCHER to "исследование, аудит",
            Role.REVIEWER to "гейты и ревью",
        )
    }
}
