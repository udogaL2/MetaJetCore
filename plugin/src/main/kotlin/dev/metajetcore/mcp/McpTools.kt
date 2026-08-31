package dev.metajetcore.mcp

import com.intellij.openapi.project.Project
import dev.metajetcore.agents.AgentInfo
import dev.metajetcore.agents.AgentManager
import dev.metajetcore.agents.BriefResult
import dev.metajetcore.agents.CloseResult
import dev.metajetcore.agents.SpawnResult
import dev.metajetcore.roles.Role
import dev.metajetcore.roles.RoleInstaller
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
                    "Короткий суффикс имени: be, fe, sec, arch. Различает агентов одной роли.",
                ),
            ),
            required = listOf("role", "task", "parent"),
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

        return when (
            val result = manager.spawn(
                role = role,
                task = task,
                parentName = parent,
                requestedName = args["name"]?.asString,
                requestedModel = args["model"]?.asString,
                domain = args["domain"]?.asString,
            )
        ) {
            is SpawnResult.Started -> textResult(
                buildString {
                    append("Агент запущен.\n")
                    append(describe(result.agent))
                    append("\n\nСЛЕДУЮЩИЙ ШАГ, ОБЯЗАТЕЛЬНО: отправь агенту задачу через ")
                    append("SendMessage(to=\"${result.agent.name}\"). ")
                    append("Плагин задачу не передаёт намеренно: терминал искажает не-ASCII ")
                    append("(проверено живым прогоном), а сообщения доставляются как есть. ")
                    append("Отправь ему это:\n\n")
                    append(result.pendingBriefing)
                },
            )

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

    private fun listAgents(): Json {
        val agents = manager.list()
        if (agents.isEmpty()) return textResult("В этом проекте нет живых сессий Claude Code.")
        return textResult(agents.joinToString("\n") { describe(it) })
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
        return if (manager.reset(name)) {
            textResult("контекст '$name' сброшен; новую задачу отправь через SendMessage")
        } else {
            textResult("вкладка '$name' плагину неизвестна", isError = true)
        }
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
}
