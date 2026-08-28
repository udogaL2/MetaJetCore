package dev.metajetcore.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@State(
    name = "MetaJetCoreSettings",
    storages = [Storage("metajetcore.xml")],
)
class MjcSettings : PersistentStateComponent<MjcSettings> {

    /**
     * Команда запуска Claude Code. Сюда вписывается пользовательская обёртка.
     *
     * Важно: режимы INLINE и FLAG требуют, чтобы обёртка пробрасывала "$@".
     * Проверить — scripts/check-wrapper.sh в корне проекта.
     */
    var launchCommand: String = "claude"


    /**
     * Режим прав для спавненных агентов, уезжает флагом `--permission-mode`.
     *
     * Задавать обязательно: агент по умолчанию стартует в manual mode и встанет на первом же
     * запросе прав, а его вкладку никто не читает. Настройкой проекта это не решается —
     * `permissions.defaultMode: auto` из repo-level настроек Claude Code игнорирует, о чём
     * прямо сообщает: «repo-level settings cannot grant it». Пусто — флаг не добавлять.
     */
    var permissionMode: String = "auto"

    /** Пусто — вывести из имени проекта. Имена сессий глобальны на машину. */
    var namePrefix: String = ""

    /**
     * Сколько ждать появления сессии в ~/.claude/sessions, секунд.
     *
     * Спавн блокирует MCP-вызов на это время, а у Claude Code есть свой таймаут на вызов
     * инструмента. Держим заметно ниже него; если обёртка стартует дольше, поднимать надо
     * оба: и это значение, и MCP_TIMEOUT на стороне оркестратора.
     */
    var readyTimeoutSeconds: Int = 45

    /**
     * Вычищать ANTHROPIC_API_KEY и ANTHROPIC_AUTH_TOKEN перед запуском агента.
     * Иначе сессия молча уезжает с подписки на API-биллинг.
     */
    var stripApiKeys: Boolean = true

    /**
     * Вычищать наследуемые маркеры Claude Code (CLAUDE_CODE_CHILD_SESSION и прочие).
     *
     * Без этого агент, запущенный из IDE, которая сама стартовала внутри сессии Claude Code,
     * считает себя вложенным процессом и не регистрируется в ~/.claude/sessions.
     * Выключать только при отладке.
     */
    var stripInheritedClaudeMarkers: Boolean = true

    /** auto | posix | powershell | cmd | fish */
    var shellDialect: String = "auto"

    /** 0 — выбрать свободный порт автоматически. */
    var mcpPort: Int = 0

    /** Слушать только на localhost. Менять незачем; поле оставлено для отладки. */
    var mcpBindLoopbackOnly: Boolean = true

    /** Дополнительные переменные окружения агентов, в формате KEY=VALUE, по одной в строке. */
    var extraEnvRaw: String = ""

    /**
     * Впечатывать ли уведомление в терминал оркестратора, когда агент завис.
     * По умолчанию выключено: каждая инъекция стоит оркестратору полного хода.
     */
    var notifyOrchestratorInTerminal: Boolean = false

    fun extraEnv(): Map<String, String> =
        extraEnvRaw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
            .associate { line ->
                val index = line.indexOf('=')
                line.substring(0, index).trim() to line.substring(index + 1).trim()
            }

    override fun getState(): MjcSettings = this

    override fun loadState(state: MjcSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        fun getInstance(): MjcSettings =
            ApplicationManager.getApplication().getService(MjcSettings::class.java)

        /** MetaJetCore -> mjc, room-plan-aid -> rpa, backend -> be. */
        fun derivePrefix(projectName: String): String {
            val words = projectName.split(Regex("[^A-Za-z0-9]+"))
                .flatMap { chunk -> Regex("[A-Z]?[a-z0-9]+|[A-Z]+(?![a-z])").findAll(chunk).map { it.value } }
                .filter { it.isNotBlank() }
            val initials = words.mapNotNull { it.firstOrNull() }.joinToString("").lowercase()
            val candidate = when {
                initials.length >= 2 -> initials.take(5)
                else -> projectName.lowercase().filter { it.isLetterOrDigit() }.take(4)
            }
            // Имя целиком из пунктуации ("!!!") давало пустой префикс, а он делает имена
            // сессий неразличимыми между проектами — они глобальны на машину.
            return candidate.ifBlank { "mjc" }
        }
    }
}
