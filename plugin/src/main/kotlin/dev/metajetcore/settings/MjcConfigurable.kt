package dev.metajetcore.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.fields.ExtendableTextComponent
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import dev.metajetcore.harness.ModelCatalog
import dev.metajetcore.shell.ShellDialect
import javax.swing.JLabel
import javax.swing.JList
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindIntValue
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
 * Устроен от частого к редкому: таблица агентов (команда, аргументы и режим прав каждого
 * харнеса — строками одной сетки, чтобы всё про запуск было в одном месте), затем роли, ревью и
 * свёрнутое «Дополнительно». Почему у каждой
 * опции такое значение по умолчанию — в подсказке «?», а не в абзаце под полем: абзацы под
 * каждой строкой превращали экран в стену текста, за которой не видно самих настроек.
 */
class MjcConfigurable : BoundConfigurable("MetaJetCore") {

    private val settings = MjcSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        group("Агенты") {
            // Одна сетка на все харнесы: раньше Claude Code был вверху, а Codex и b24 — в
            // свёрнутых группах внизу, и команду запуска приходилось искать по экрану.
            row("") {
                label("Команда запуска").bold()
                label("Аргументы").bold()
                label("Режим прав").bold()
            }.layout(RowLayout.PARENT_GRID)
            row(Harness.CLAUDE.displayName + ":") {
                textField().bindText(settings::launchCommand).columns(COMMAND_COLUMNS)
                    .applyToComponent { emptyText.text = "claude" }
                // Аргументов у Claude Code нет: роль и режим прав плагин передаёт сам, а
                // остальное настраивается в самом Claude Code.
                label("")
                comboBox(PERMISSION_MODES.keys.toList(), textRenderer { PERMISSION_MODES[it] ?: it })
                    .bindItem(settings::permissionMode.toNullableProperty())
                contextHelp(
                    "Команда — claude или ваша обёртка. Обёртка обязана пробрасывать аргументы " +
                        "(\"\$@\"): иначе до агента не дойдут --agent и --permission-mode, и он " +
                        "стартует без роли в ручном режиме прав (проверить: scripts/check-wrapper.sh). " +
                        "Режим прав обязателен: вкладку агента никто не читает, и без режима он встанет " +
                        "на первом же запросе. Настройкой проекта это не задать — repo-level " +
                        "defaultMode Claude Code игнорирует.",
                )
            }.layout(RowLayout.PARENT_GRID)
            row(Harness.CODEX.displayName + ":") {
                textField().bindText(settings::codexLaunchCommand).columns(COMMAND_COLUMNS)
                    .applyToComponent { emptyText.text = "codex" }
                textField().bindText(settings::codexArgs).columns(ARGS_COLUMNS)
                // Режим прав Codex — флагами -s/-a в аргументах: так он задаётся у самого CLI.
                label("в аргументах (-s, -a)")
                contextHelp(
                    "-a never: вопрос о подтверждении во вкладке, которую никто не читает, — вечная " +
                        "блокировка. --disable hooks: иначе Codex спросит «Review hooks» на старте. " +
                        "Роль, доверие к каталогу и модель плагин добавит сам.",
                )
            }.layout(RowLayout.PARENT_GRID)
            row(Harness.B24.displayName + ":") {
                textField().bindText(settings::b24LaunchCommand).columns(COMMAND_COLUMNS)
                    .applyToComponent { emptyText.text = "b24-cowork-code-cli" }
                textField().bindText(settings::b24Args).columns(ARGS_COLUMNS)
                comboBox(B24_APPROVAL_MODES.keys.toList(), textRenderer { B24_APPROVAL_MODES[it] ?: it })
                    .bindItem(settings::b24ApprovalMode.toNullableProperty())
                contextHelp(
                    "Режим — --approval-mode, свой, а не режим Claude Code: в default агент встанет " +
                        "на вопросе во вкладке, ответить на который из оркестратора нельзя. " +
                        "Аргументы — сверх того, что плагин добавит сам: --session-id, канал " +
                        "(--json-file, --input-file), роль (--append-system-prompt), задача (-i), модель (-m).",
                )
            }.layout(RowLayout.PARENT_GRID)
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
            // b24 IDE не знает — его предлагаем всегда, команда запуска — в таблице агентов.
            val available = Terminal.terminalAgentKeys()
            val offered = Harness.entries.filter { it == Harness.CLAUDE || !it.knownToIde || it.key in available }

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
                    val box = ModelField(role)
                    modelBoxes += box to { harness.component.item ?: Harness.CLAUDE }
                    cell(box)
                        .bindText(
                            { settings.roleModel[role.id].orEmpty() },
                            { value -> settings.roleModel[role.id] = value.trim() },
                        )
                        .validationOnInput { modelWarning(it.text, harness.component.item) }
                        .validationOnApply { modelWarning(it.text, harness.component.item) }
                    box.show(harness.component.item, models[harness.component.item].orEmpty())
                    harness.component.addActionListener {
                        val picked = harness.component.item ?: Harness.CLAUDE
                        box.show(picked, models[picked].orEmpty())
                    }
                    comment(ROLE_HINTS.getValue(role))
                }.layout(RowLayout.PARENT_GRID)
            }
            row {
                comment(
                    "Пустая модель — модель роли для Claude Code, модель из конфига CLI для Codex и B24 CoWork Code." +
                        if (Harness.CODEX in offered) "" else " Codex появится в списке, когда IDE увидит его среди агентов терминала.",
                )
            }
            row {
                label("").applyToComponent {
                    foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                    statusLabel = this
                }
            }
            loadModels(offered)
        }

        group("Итоговое ревью") {
            // Те же харнесы, что можно назначить ролям; уже заданный в составе показываем, даже
            // если IDE его сейчас не видит, — иначе Apply молча обнулил бы его.
            val available = Terminal.terminalAgentKeys()
            val panelHarnesses = Harness.entries.filter {
                it == Harness.CLAUDE || !it.knownToIde || it.key in available || (settings.reviewPanel[it.key] ?: 0) > 0
            }
            for (harness in panelHarnesses) {
                row(harness.displayName + ":") {
                    spinner(0..MjcSettings.MAX_PANEL_PER_HARNESS)
                        .bindIntValue(
                            { settings.reviewPanel[harness.key] ?: 0 },
                            { value: Int ->
                                if (value > 0) settings.reviewPanel[harness.key] = value
                                else settings.reviewPanel.remove(harness.key)
                            },
                        )
                    val box = ModelField(Role.REVIEWER)
                    modelBoxes += box to { harness }
                    cell(box)
                        .bindText(
                            { settings.reviewPanelModel[harness.key].orEmpty() },
                            { value ->
                                if (value.isBlank()) settings.reviewPanelModel.remove(harness.key)
                                else settings.reviewPanelModel[harness.key] = value.trim()
                            },
                        )
                        .validationOnInput { modelWarning(it.text, harness) }
                    box.show(harness, models[harness].orEmpty())
                }.layout(RowLayout.PARENT_GRID)
            }
            row {
                comment(
                    "Сколько ревьюверов каждого харнеса открыть на итоговое ревью (tempo review, ревью MR): " +
                        "оркестратор заведёт всех одним вызовом, а отчёты сведёт один из них. Все нули — " +
                        "один ревьювер по настройке роли выше. Гейты (tests, sec, a11y, perf, scen) — всегда по одному.",
                )
            }
        }

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

    // Подкласс, а не фабрика SimpleListCellRenderer.create: фабрики помечены устаревшими и
    // уйдут из платформы, а customize — обычный абстрактный метод, его не тронут.
    private fun harnessRenderer() = object : SimpleListCellRenderer<Harness?>() {
        override fun customize(list: JList<out Harness?>, value: Harness?, index: Int, selected: Boolean, hasFocus: Boolean) {
            text = value?.displayName.orEmpty()
            icon = value?.let { Terminal.agentIcon(it.key) }
        }
    }

    private fun textRenderer(text: (String) -> String) = object : SimpleListCellRenderer<String?>() {
        override fun customize(list: JList<out String?>, value: String?, index: Int, selected: Boolean, hasFocus: Boolean) {
            this.text = value?.let(text).orEmpty()
        }
    }

    // ------------------------------------------------------------- модели

    /** Списки моделей по харнесам: сначала запасные, потом — что ответил CLI. */
    private val models = HashMap<Harness, List<ModelCatalog.Model>>()
    private val modelStatus = LinkedHashMap<Harness, String>()
    private val modelBoxes = mutableListOf<Pair<ModelField, () -> Harness>>()
    private var statusLabel: JLabel? = null

    /**
     * Спрашивает списки у CLI в фоне: экран открывается сразу, списки доезжают, когда доедут.
     * Ответ приходит, пока открыт модальный диалог настроек, поэтому `ModalityState.any()` —
     * с модальностью по умолчанию обновление ждало бы закрытия окна.
     */
    private fun loadModels(harnesses: List<Harness>) {
        // Шелл — тот же, что у вкладок агентов. Настройки общие на IDE, проект берём любой
        // открытый: настройка шелла терминала у них обычно одна.
        val shellPath = ProjectManager.getInstance().openProjects.firstOrNull()
            ?.let { Terminal.configuredShellPath(it) } ?: ShellDialect.defaultShell()
        for (harness in harnesses) {
            val command = commandOf(harness)
            val cached = ModelCatalog.cached(harness, command)
            models[harness] = cached ?: ModelCatalog.fallback(harness)
            if (cached != null) {
                modelStatus[harness] = "${harness.displayName}: ${cached.size} моделей"
                continue
            }
            modelStatus[harness] = "${harness.displayName}: спрашиваю список моделей…"
            ModelCatalog.load(harness, command, shellPath).thenAccept { loaded ->
                ApplicationManager.getApplication().invokeLater({
                    if (loaded != null) models[harness] = loaded
                    modelStatus[harness] = if (loaded != null) "${harness.displayName}: ${loaded.size} моделей"
                    else "${harness.displayName}: список не получен — впишите модель вручную"
                    refreshModels()
                }, ModalityState.any())
            }
        }
        refreshModels()
    }

    private fun refreshModels() {
        for ((box, harness) in modelBoxes) {
            val picked = harness()
            box.show(picked, models[picked].orEmpty())
        }
        statusLabel?.text = modelStatus.values.joinToString("   ·   ")
    }

    private fun commandOf(harness: Harness) = when (harness) {
        Harness.CLAUDE -> settings.launchCommand
        Harness.CODEX -> settings.codexLaunchCommand
        Harness.B24 -> settings.b24LaunchCommand
    }.trim().ifEmpty {
        when (harness) {
            Harness.CLAUDE -> "claude"
            Harness.CODEX -> "codex"
            Harness.B24 -> "b24-cowork-code-cli"
        }
    }

    /**
     * Модель роли: свободный ввод плюс список от харнеса по стрелке.
     *
     * Текстовое поле, а не редактируемый комбобокс: `ComboBox` платформы оборачивает чужой
     * редактор, и редактор с подсказкой в пустом поле в него не встаёт (проверено: компонент
     * редактора не добавлялся, раскладка падала с NPE). Свободный ввод обязателен: CLI может не
     * ответить, а модель — быть новее списка. Хранится короткий id (`sonnet`, `gpt-6-luna`) —
     * то, что уходит в `--model`/`ANTHROPIC_MODEL`; название видно только в списке.
     */
    private class ModelField(private val role: Role) : ExtendableTextField(12) {
        private var models: List<ModelCatalog.Model> = emptyList()

        init {
            addExtension(
                ExtendableTextComponent.Extension.create(
                    AllIcons.General.ArrowDown, AllIcons.General.ArrowDown, "Выбрать модель",
                ) { showPopup() },
            )
        }

        /** Подставить список харнеса, не трогая введённое. */
        fun show(harness: Harness?, list: List<ModelCatalog.Model>) {
            models = list
            // Подсказка — модель, которая реально поедет, а не «по умолчанию»: так видно, что
            // оставить поле пустым — тоже осознанный выбор.
            emptyText.text = when (harness) {
                Harness.CODEX -> "из конфига Codex"
                Harness.B24 -> "из конфига B24"
                else -> role.defaultModel
            }
            repaint()
        }

        private fun showPopup() {
            if (models.isEmpty()) return
            JBPopupFactory.getInstance()
                .createPopupChooserBuilder(models)
                .setRenderer(object : ColoredListCellRenderer<ModelCatalog.Model>() {
                    override fun customizeCellRenderer(
                        list: JList<out ModelCatalog.Model>, value: ModelCatalog.Model?, index: Int,
                        selected: Boolean, focus: Boolean,
                    ) {
                        value ?: return
                        append(value.displayName)
                        if (value.displayName != value.id) append("  " + value.id, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        if (value.description.isNotBlank()) append("  " + value.description, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    }
                })
                .setItemChosenCallback { text = it.id }
                .createPopup()
                .showUnderneathOf(this)
        }
    }

    /**
     * Модель от другого харнеса — не ошибка ввода, а частый недосмотр после смены харнеса
     * роли. Сохранить даём (у Codex такая модель просто отбросится), но предупреждаем.
     */
    private fun modelWarning(text: String, harness: Harness?) = with(text.trim()) {
        when {
            isEmpty() -> null
            harness != null && harness != Harness.CLAUDE && Harness.isClaudeModel(this) ->
                com.intellij.openapi.ui.ValidationInfo("${harness.displayName} не знает модели Claude — возьмётся модель из его конфига").asWarning()
            harness == Harness.CLAUDE && !Harness.isClaudeModel(this) ->
                com.intellij.openapi.ui.ValidationInfo("Не похоже на модель Claude").asWarning()
            else -> null
        }
    }

    private companion object {
        const val COMMAND_COLUMNS = 22
        const val ARGS_COLUMNS = 28

        // Подписи короткие: комбобоксы стоят в одной сетке, и длинная подпись раздвинула бы
        // колонку на весь экран. Что значит каждый режим — в подсказке «?».
        val PERMISSION_MODES = linkedMapOf(
            "auto" to "auto (рекомендуется)",
            "acceptEdits" to "acceptEdits",
            "plan" to "plan",
            "bypassPermissions" to "bypassPermissions",
            "" to "не передавать",
        )

        val B24_APPROVAL_MODES = linkedMapOf(
            "auto" to "auto (рекомендуется)",
            "auto-edit" to "auto-edit",
            "plan" to "plan",
            "yolo" to "yolo",
            "default" to "default — спрашивать",
            "" to "не передавать",
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
