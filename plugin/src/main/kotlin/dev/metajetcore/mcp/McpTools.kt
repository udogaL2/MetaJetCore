package dev.metajetcore.mcp

import com.intellij.openapi.project.Project
import dev.metajetcore.agents.AgentInfo
import dev.metajetcore.agents.AgentManager
import dev.metajetcore.agents.BriefResult
import dev.metajetcore.agents.CloseResult
import dev.metajetcore.agents.DeliveryResult
import dev.metajetcore.agents.ResetResult
import dev.metajetcore.harness.Harness
import dev.metajetcore.agents.SpawnResult
import dev.metajetcore.roles.Role
import dev.metajetcore.roles.RoleInstaller
import dev.metajetcore.settings.MjcSettings
import dev.metajetcore.util.Json

/**
 * Определения и диспетчер MCP-инструментов.
 *
 * Контракт инструментов держим стабильным: он — единственное, что видит оркестратор.
 * Всё, что может меняться между версиями IDE, спрятано за AgentManager и Terminal,
 * так что смена терминального API не должна менять эти сигнатуры.
 */
class McpTools(private val project: Project) {

    private val manager: AgentManager get() = project.getService(AgentManager::class.java)

    fun definitions(): List<Json> = listOf(
        tool(
            name = "spawn_agent",
            description =
                "Открыть новую вкладку с полноценной сессией Claude Code в этой IDE. Это НЕ " +
                    "субагент: отдельный процесс, свой контекст, свой адрес для SendMessage. " +
                    "СНАЧАЛА посмотри list_agents: если агент нужной роли уже работает над " +
                    "этим же участком, новый не нужен — отдай задачу ему через SendMessage, " +
                    "у него уже набран контекст. Новый заводи, когда участок другой. " +
                    "Задачу инструмент НЕ передаёт — после успешного спавна отправь её сам " +
                    "через SendMessage на возвращённое имя.",
            properties = Json.obj(
                "role" to schema(
                    "string",
                    "Роль агента",
                    enumValues = Role.entries.map { it.id },
                ),
                "task" to schema("string", "Брифинг: что сделать, и файловый домен агента"),
                "parent" to schema(
                    "string",
                    "Имя ТВОЕЙ сессии (первая строка вывода ListAgents). " +
                        "Нужно, чтобы вкладка открылась рядом с тобой, а не в общей куче.",
                ),
                "name" to schema("string", "Переопределить имя сессии; обычно не нужно"),
                "model" to schema("string", "Переопределить модель роли"),
                "domain" to schema(
                    "string",
                    "Короткий суффикс имени: участок агента — be, fe, p1, tests, sec. Различает агентов одной роли. " +
                        "reviewer + review — итоговое ревью: откроется весь состав ревьюверов из настроек " +
                        "плагина (например Claude и Codex), ответ назовёт всех и скажет, кто сводит отчёты.",
                ),
                "harness" to schema(
                    "string",
                    "Чем запустить агента; обычно не нужно — харнес роли задан в настройках " +
                        "плагина. Агент Codex или b24 получает задачу сразу при старте, отвечает через " +
                        "await_messages, новые задачи — через send_to_agent.",
                    enumValues = Harness.entries.map { it.key },
                ),
            ),
            required = listOf("role", "task", "parent"),
        ),
        tool(
            name = "send_to_agent",
            description = "Отправить сообщение агенту НЕ-Claude (Codex, b24): новая задача, " +
                "«Продолжай: фаза P+1», правки после гейта. Сообщение встаёт в его очередь " +
                "новым ходом, текст доезжает как есть. Агентам Claude Code пиши SendMessage — " +
                "этот инструмент им не доставляет. Ответ забирай через await_messages.",
            properties = Json.obj(
                "name" to schema("string", "Имя агента"),
                "text" to schema("string", "Текст сообщения, целиком"),
            ),
            required = listOf("name", "text"),
        ),
        tool(
            name = "await_messages",
            description = "Дождаться ответов агентов НЕ-Claude (Codex, b24): плагин читает их " +
                "ленты и отдаёт завершённые ходы. С names — ждёт, пока ответит каждый (волна " +
                "tempo), без — первый ответ любого. По таймауту возвращает что есть и кто ещё " +
                "в работе: вызови снова. Ответы агентов Claude Code приходят сами, через " +
                "SendMessage, их здесь не ждут.",
            properties = Json.obj(
                "names" to Json.obj(
                    "type" to Json.of("array"),
                    "items" to Json.obj("type" to Json.of("string")),
                    "description" to Json.of("Имена агентов, чьи ответы ждём; пусто — любой"),
                ),
                "timeout_s" to schema("integer", "Сколько ждать, секунд (по умолчанию 45, максимум 120)"),
            ),
            required = emptyList(),
        ),
        tool(
            name = "list_agents",
            description = "Живые сессии Claude Code в директории этого проекта с их статусом.",
            properties = Json.obj(),
            required = emptyList(),
        ),
        tool(
            name = "brief_agent",
            description = "АВАРИЙНЫЙ канал: впечатать текст во вкладку агента как ввод " +
                "пользователя. Только ASCII — терминал искажает остальное, и такой вызов " +
                "будет отклонён. Любая содержательная переписка идёт через SendMessage; это нужно " +
                "только когда агент перестал отвечать на сообщения.",
            properties = Json.obj(
                "name" to schema("string", "Имя агента"),
                "text" to schema("string", "Текст, только ASCII"),
            ),
            required = listOf("name", "text"),
        ),
        tool(
            name = "reset_agent",
            description = "Сбросить контекст агента: печатает /clear в его вкладке. " +
                "Применять при переходе между этапами, НЕ внутри этапа: сохранённый контекст " +
                "внутри этапа — главная экономия схемы. Новую задачу после сброса отправь " +
                "сам через SendMessage.",
            properties = Json.obj("name" to schema("string", "Имя агента")),
            required = listOf("name"),
        ),
        tool(
            name = "close_agent",
            description = "Мягко завершить агента (/exit) и закрыть его вкладку.",
            properties = Json.obj("name" to schema("string", "Имя агента")),
            required = listOf("name"),
        ),
        tool(
            name = "focus_agent",
            description = "Показать вкладку агента разработчику.",
            properties = Json.obj("name" to schema("string", "Имя агента")),
            required = listOf("name"),
        ),
        tool(
            name = "read_tab",
            description = "Прочитать, что сейчас на экране вкладки агента. Применять, когда " +
                "агент запустился, но не отвечает: скорее всего он упёрся в интерактивный " +
                "вопрос, и увидеть его можно только так.",
            properties = Json.obj("name" to schema("string", "Имя агента")),
            required = listOf("name"),
        ),
        tool(
            name = "diagnostics",
            description = "Состояние плагина: нашлось ли терминальное API, команда запуска, " +
                "что вычищается из окружения. Для разбора проблем.",
            properties = Json.obj(),
            required = emptyList(),
        ),
    )

    fun call(params: Json?): Json {
        val name = params?.get("name")?.asString
            ?: return textResult("ошибка: не указано имя инструмента", isError = true)
        val args = params["arguments"] ?: Json.obj()

        return try {
            when (name) {
                "spawn_agent" -> spawnAgent(args)
                "send_to_agent" -> sendToAgent(args)
                "await_messages" -> awaitMessages(args)
                "list_agents" -> listAgents()
                "brief_agent" -> briefAgent(args)
                "reset_agent" -> resetAgent(args)
                "close_agent" -> closeAgent(args)
                "focus_agent" -> focusAgent(args)
                "read_tab" -> readTab(args)
                "diagnostics" -> diagnostics()
                else -> textResult("неизвестный инструмент: $name", isError = true)
            }
        } catch (e: Throwable) {
            // Инструмент, бросивший исключение, для оркестратора выглядит как обрыв связи.
            // Возвращаем текстом, чтобы он мог принять решение сам.
            textResult("инструмент '$name' упал: ${e.message ?: e.javaClass.simpleName}", isError = true)
        }
    }

    // ---------------------------------------------------------------- actions

    private fun spawnAgent(args: Json): Json {
        val roleId = args["role"]?.asString
            ?: return textResult("ошибка: не указана role", isError = true)
        val role = Role.fromId(roleId)
            ?: return textResult(
                "неизвестная роль '$roleId'; доступны: ${Role.entries.joinToString { it.id }}",
                isError = true,
            )
        val task = args["task"]?.asString.orEmpty()
        val parent = args["parent"]?.asString
        val harnessKey = args["harness"]?.asString?.takeIf { it.isNotBlank() }
        val harness = harnessKey?.let {
            Harness.fromKey(it) ?: return textResult(
                "неизвестный харнес '$it'; доступны: ${Harness.entries.joinToString { h -> h.key }}",
                isError = true,
            )
        }

        val requestedName = args["name"]?.asString
        val requestedModel = args["model"]?.asString
        val domain = args["domain"]?.asString
        val panel = MjcSettings.getInstance().reviewPanelMembers()
        // Итоговое ревью разворачивается в состав из настроек одним вызовом: оркестратору не
        // нужно знать настройки, и второго ревьювера не «забыть». Явный harness или name —
        // просьба об одном конкретном агенте, её уважаем.
        if (role == Role.REVIEWER && harness == null && requestedName.isNullOrBlank() &&
            domain?.trim().equals(REVIEW_DOMAIN, ignoreCase = true) && panel.isNotEmpty()
        ) {
            return spawnReviewPanel(panel, task, parent, requestedModel)
        }

        return when (
            val result = manager.spawn(
                role = role,
                task = task,
                parentName = parent,
                requestedName = requestedName,
                requestedModel = requestedModel,
                domain = domain,
                requestedHarness = harness,
            )
        ) {
            is SpawnResult.Started -> textResult("Агент запущен.\n" + describeStarted(result))

            is SpawnResult.Manual -> textResult(
                buildString {
                    append("Автоматически открыть вкладку не вышло: ${result.reason}.\n")
                    append("Попроси разработчика выполнить в новой вкладке:\n\n")
                    append(result.command)
                    append("\n\nПосле запуска агент появится в ListAgents.")
                },
            )

            is SpawnResult.Failed -> textResult("не удалось: ${result.reason}", isError = true)
        }
    }

    private fun describeStarted(result: SpawnResult.Started, skipSiblings: Set<String> = emptySet()): String =
        buildString {
            append(describe(result.agent))
            // Дублирование роли не запрещаем: несколько имплементеров по разным
            // доменам — штатная схема. Но назвать уже живых обязаны: оркестратор
            // сам на них не смотрит (живой прогон: четыре имплементера подряд), а
            // после спавна ещё не поздно отдать задачу тому, кто в теме.
            val siblings = result.siblings.filter { it.name !in skipSiblings }
            if (siblings.isNotEmpty()) {
                append("\n\nВНИМАНИЕ: этой же роли в проекте уже работают ")
                append(siblings.joinToString(", ") { it.name })
                append(". Если задача про их участок — отдай её им через SendMessage, ")
                append("а этого закрой через close_agent: у них набран контекст, ")
                append("новый агент входит в задачу с нуля. Если участок другой — ")
                append("всё верно, продолжай.")
            }
            if (result.taskDelivered) {
                // Codex и b24 получают задачу аргументом при старте (у Codex без первого
                // сообщения сессии нет вовсе). Повторно слать её нельзя — будет два хода.
                append("\n\nЗадача УЖЕ передана агенту при старте — повторно не отправляй. ")
                append("Это агент ${result.agent.harness.displayName}: SendMessage ему не ")
                append("доставляет. Ответ забери через await_messages(names=[\"${result.agent.name}\"]), ")
                append("следующие задачи — через send_to_agent.")
            } else if (result.agent.harness != Harness.CLAUDE) {
                append("\n\nЭто агент ${result.agent.harness.displayName}: SendMessage ему не доставляет. ")
                append("Задачу отправь через send_to_agent(name=\"${result.agent.name}\"), ")
                append("ответ забери через await_messages(names=[\"${result.agent.name}\"]).")
            } else {
                append("\n\nСЛЕДУЮЩИЙ ШАГ, ОБЯЗАТЕЛЬНО: отправь агенту задачу через ")
                append("SendMessage(to=\"${result.agent.name}\"). ")
                append("Плагин задачу не передаёт намеренно: терминал искажает не-ASCII ")
                append("(проверено живым прогоном), а сообщения доставляются как есть. ")
                append("Отправь ему это:\n\n")
                append(result.pendingBriefing)
            }
        }

    /**
     * Итоговое ревью составом: по агенту на участника, каждый — со своим файлом отчёта.
     *
     * Отказ одного участника не отменяет остальных: ревью одним харнесом лучше, чем никакого,
     * а оркестратор увидит, кого не хватает. Сводит отчёты первый Claude-участник, а не
     * оркестратор: тому писать файлы нельзя (ARCHITECTURE §5.1), а у ревьювера уже есть
     * контекст ветки.
     */
    private fun spawnReviewPanel(
        members: List<MjcSettings.PanelMember>,
        task: String,
        parent: String?,
        requestedModel: String?,
    ): Json {
        val started = mutableListOf<SpawnResult.Started>()
        val sections = mutableListOf<String>()
        for (member in members) {
            val memberTask = if (members.size > 1) task + panelNote(members.size, member.slot) else task
            val title = "${member.harness.displayName} (${member.slot})"
            sections += when (
                val result = manager.spawn(
                    role = Role.REVIEWER,
                    task = memberTask,
                    parentName = parent,
                    requestedName = null,
                    requestedModel = member.model ?: requestedModel,
                    domain = member.slot,
                    requestedHarness = member.harness,
                )
            ) {
                is SpawnResult.Started -> {
                    started += result
                    "— $title\n" + describeStarted(result, skipSiblings = started.map { it.agent.name }.toSet())
                }
                is SpawnResult.Manual ->
                    "— $title: открыть вкладку не вышло (${result.reason}). Команда для ручного запуска:\n${result.command}"
                is SpawnResult.Failed -> "— $title: не удалось — ${result.reason}"
            }
        }
        val text = buildString {
            append("Итоговое ревью — состав из настроек плагина: ${members.size}, запущено: ${started.size}.\n\n")
            append(sections.joinToString("\n\n"))
            if (started.size > 1) {
                val merger = started.firstOrNull { it.agent.harness == Harness.CLAUDE } ?: started.first()
                val channel = if (merger.agent.harness == Harness.CLAUDE) "SendMessage" else "send_to_agent"
                append("\n\nСВЕДЕНИЕ. Каждый пишет свой файл отчёта (суффикс слота к пути из задачи). ")
                append("Когда ответят все, отправь ${merger.agent.name} ($channel) пути отчётов остальных и ")
                append("попроси свести их в итоговый файл по исходному пути из задачи: находки объединить, ")
                append("дубли схлопнуть, расхождения между ревьюверами отметить, STATUS: FAIL, если FAIL хотя ")
                append("бы у одного. Повторное ревью дельты — всем участникам, тем же агентам.")
            }
        }
        return textResult(text, isError = started.isEmpty())
    }

    private fun listAgents(): Json {
        val agents = manager.list()
        if (agents.isEmpty()) return textResult("В этом проекте нет живых сессий Claude Code.")
        return textResult(
            agents.joinToString("\n") { describe(it) } +
                "\n\nАгент с ролью — это готовая к работе сессия с набранным контекстом. " +
                "Задачу по участку, который агент уже вёл, отправляй ему через SendMessage: " +
                "новый агент на то же место входит в задачу с нуля.",
        )
    }

    private fun briefAgent(args: Json): Json {
        val name = args["name"]?.asString ?: return textResult("ошибка: нет name", isError = true)
        val text = args["text"]?.asString ?: return textResult("ошибка: нет text", isError = true)
        return when (val result = manager.brief(name, text)) {
            is BriefResult.Sent -> textResult("отправлено в '$name'")

            is BriefResult.NotAscii -> textResult(
                "не отправлено: терминал искажает не-ASCII, а в тексте есть " +
                    "'${result.offending}'. Отправь это через SendMessage — там кодировка " +
                    "не портится. brief_agent годится только для коротких ASCII-команд.",
                isError = true,
            )

            is BriefResult.UnknownTab -> textResult(
                "вкладка '$name' плагину неизвестна; используй SendMessage",
                isError = true,
            )
        }
    }

    private fun resetAgent(args: Json): Json {
        val name = args["name"]?.asString ?: return textResult("ошибка: нет name", isError = true)
        return when (val result = manager.reset(name)) {
            ResetResult.Done -> textResult(
                "контекст '$name' сброшен; новую задачу отправь через " +
                    if (manager.isExternal(name)) "send_to_agent" else "SendMessage",
            )
            ResetResult.UnknownTab -> textResult("вкладка '$name' плагину неизвестна", isError = true)
            is ResetResult.Unsupported -> textResult(result.reason, isError = true)
        }
    }

    private fun sendToAgent(args: Json): Json {
        val name = args["name"]?.asString ?: return textResult("ошибка: нет name", isError = true)
        val text = args["text"]?.asString ?: return textResult("ошибка: нет text", isError = true)
        return when (val result = manager.deliver(name, text)) {
            DeliveryResult.Sent ->
                textResult("отправлено '$name'; ответ забери через await_messages(names=[\"$name\"])")
            is DeliveryResult.Queued -> textResult(
                // Очередь бывает и без идущего хода — короткая пауза после сброса (/clear ответа не
                // даёт). Поэтому не обещаем «ответ на текущий ход»: оркестратор ждал бы лишний.
                "'$name' сейчас не принимает сообщение: оно в очереди плагина (позиция " +
                    "${result.position}) и уйдёт отдельным ходом, как только агент освободится. " +
                    "Ответ на него придёт через await_messages(names=[\"$name\"]) после ответов на " +
                    "то, что ты отправил раньше.",
            )
            DeliveryResult.UseSendMessage -> textResult(
                "'$name' — сессия Claude Code: пиши ей SendMessage. Плагин второй канал не " +
                    "заводит — у двух очередей не было бы порядка между собой.",
                isError = true,
            )
            DeliveryResult.UnknownAgent ->
                textResult("агент '$name' плагину неизвестен; см. list_agents", isError = true)
            is DeliveryResult.Failed -> textResult("не доставлено: ${result.reason}", isError = true)
        }
    }

    private fun awaitMessages(args: Json): Json {
        val names = args["names"]?.asList?.mapNotNull { it.asString }?.takeIf { it.isNotEmpty() }
        val timeout = (args["timeout_s"]?.asLong ?: DEFAULT_AWAIT_S).coerceIn(0, MAX_AWAIT_S)
        val result = manager.awaitMessages(names, timeout * 1000)
        return textResult(
            buildString {
                if (result.messages.isEmpty()) append("Новых ответов нет.\n")
                for (message in result.messages) {
                    append("=== от ${message.from} ===\n")
                    append(message.text.ifBlank { "(ход без текста)" })
                    append("\n\n")
                }
                if (result.stillWorking.isNotEmpty()) {
                    append("Ещё в работе: ${result.stillWorking.joinToString()}. Вызови await_messages снова.\n")
                }
                if (result.notInMailbox.isNotEmpty()) {
                    append(
                        "Не ждём: ${result.notInMailbox.joinToString()} — это сессии Claude Code " +
                            "или неизвестные имена; ответы Claude-агентов приходят через SendMessage.\n",
                    )
                }
            }.trimEnd(),
        )
    }

    private fun closeAgent(args: Json): Json {
        val name = args["name"]?.asString ?: return textResult("ошибка: нет name", isError = true)
        return when (val result = manager.close(name)) {
            is CloseResult.Closed -> textResult("'$name' завершён, вкладка закрыта")
            is CloseResult.UnknownTab ->
                textResult("вкладка '$name' плагину неизвестна", isError = true)
            // Не ошибка: агент завершён, то есть инструмент своё дело сделал. Ошибкой это
            // выглядело бы как «закрыть не удалось», и оркестратор пошёл бы повторять вызов
            // на уже мёртвой сессии.
            is CloseResult.TabLeftOpen -> textResult(result.reason)
        }
    }

    private fun focusAgent(args: Json): Json {
        val name = args["name"]?.asString ?: return textResult("ошибка: нет name", isError = true)
        return if (manager.focus(name)) textResult("вкладка '$name' показана")
        else textResult("вкладка '$name' плагину неизвестна", isError = true)
    }

    private fun readTab(args: Json): Json {
        val name = args["name"]?.asString ?: return textResult("ошибка: нет name", isError = true)
        val screen = manager.readScreen(name)
        return when {
            screen == null && !manager.knownTabs().contains(name) -> textResult(
                "вкладка '$name' плагину неизвестна; известны: ${manager.knownTabs().joinToString()}",
                isError = true,
            )
            screen == null -> textResult("экран вкладки '$name' прочитать не удалось", isError = true)
            else -> textResult("экран вкладки '$name':\n\n$screen")
        }
    }

    private fun diagnostics(): Json {
        val settings = dev.metajetcore.settings.MjcSettings.getInstance()
        return textResult(
            buildString {
                append("проект: ${project.name}\n")
                append("префикс имён: ${manager.prefix()}\n")
                append("команда запуска: ${settings.launchCommand}\n")
                append("команда Codex: ${settings.codexLaunchCommand} ${settings.codexArgs}\n")
                append("команда b24: ${settings.b24LaunchCommand} ${settings.b24Args}\n")
                append("харнесы ролей: ")
                append(Role.entries.filter { it != Role.ORCHESTRATOR }.joinToString { "${it.id}=${settings.harnessFor(it).key}" })
                append('\n')
                append("агенты терминала IDE: ${dev.metajetcore.terminal.Terminal.terminalAgentKeys().ifEmpty { listOf("(API недоступно)") }}\n")
                append("роли в: ${RoleInstaller.agentsDirectory()}\n")
                append("режим прав агентов: ${settings.permissionMode.ifBlank { "(не задан)" }}\n")
                append("вычищаем из окружения: ${manager.inheritedMarkers().ifEmpty { listOf("(нечего)") }}\n")
                append(manager.describeTerminal())
                append("вкладки под управлением: ${manager.knownTabs().size}\n")
                append("\nпример команды для ручного режима:\n")
                append(manager.manualCommand(Role.IMPLEMENTER, "${manager.prefix()}-impl", "opus"))
            },
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun describe(agent: AgentInfo): String = buildString {
        append("- ${agent.name}")
        append("  роль=${agent.role?.id ?: "неизвестна"}")
        if (agent.harness != Harness.CLAUDE) append("  харнес=${agent.harness.key}")
        if (agent.model.isNotBlank()) append("  модель=${agent.model}")
        agent.status?.let { append("  статус=$it") }
        agent.pid?.let { append("  pid=$it") }
        if (!agent.managed) append("  (вкладка не под управлением плагина)")
    }

    private fun tool(
        name: String,
        description: String,
        properties: Json.Obj,
        required: List<String>,
    ): Json = Json.obj(
        "name" to Json.of(name),
        "description" to Json.of(description),
        "inputSchema" to Json.obj(
            "type" to Json.of("object"),
            "properties" to properties,
            "required" to Json.arr(required.map { Json.of(it) }),
        ),
    )

    private fun schema(type: String, description: String, enumValues: List<String>? = null): Json {
        val fields = linkedMapOf<String, Json>(
            "type" to Json.of(type),
            "description" to Json.of(description),
        )
        if (enumValues != null) fields["enum"] = Json.arr(enumValues.map { Json.of(it) })
        return Json.Obj(fields)
    }


    private fun textResult(text: String, isError: Boolean = false): Json = Json.obj(
        "content" to Json.arr(
            listOf(Json.obj("type" to Json.of("text"), "text" to Json.of(text))),
        ),
        "isError" to Json.of(isError),
    )

    internal companion object {
        const val DEFAULT_AWAIT_S = 45L
        const val MAX_AWAIT_S = 120L
        const val REVIEW_DOMAIN = "review"

        /**
         * Приписка к задаче участника состава. Плагин дописывает её сам, потому что промпт
         * tempo называет один путь отчёта, и без приписки ревьюверы писали бы в один файл;
         * Codex и b24 задачу получают при старте, позже её не поправить.
         */
        fun panelNote(size: Int, slot: String): String =
            "\n\n---\nТы — один из $size ревьюверов этой проверки (твой слот: $slot). Если задача " +
                "называет файл отчёта, пиши в файл с тем же путём и суффиксом `-$slot` перед " +
                "расширением (например review.md → review-$slot.md). Файл по исходному пути " +
                "соберёт один из участников по просьбе оркестратора — сам его не пиши, пока не попросят."
    }
}
