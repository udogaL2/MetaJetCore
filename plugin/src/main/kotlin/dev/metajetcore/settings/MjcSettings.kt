package dev.metajetcore.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.metajetcore.harness.Harness
import dev.metajetcore.roles.Role

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

    /**
     * Порт MCP-сервера. `0` — вывести из пути проекта (см. `MjcSettings.derivePort`).
     *
     * Порт входит в URL, а URL — в сохранённую конфигурацию MCP, поэтому он обязан быть
     * стабильным между запусками IDE: иначе `claude mcp add` пришлось бы переделывать после
     * каждого рестарта. Случайный порт здесь недопустим — сохранённый конфиг стал бы
     * указывать в пустоту, а оркестратор увидел бы просто «инструментов нет».
     *
     * Вывод из пути проекта даёт и стабильность, и разные порты для разных проектов, так что
     * два окна IDE не столкнутся.
     */
    var mcpPort: Int = 0

    /** Слушать только на localhost. Менять незачем; поле оставлено для отладки. */
    var mcpBindLoopbackOnly: Boolean = true

    /**
     * Харнес каждой роли: id роли -> ключ харнеса (`claude_code`, `codex`).
     *
     * Ключи — те же, что у агентов терминала IDE, поэтому список выбора в настройках берётся
     * у IDE. Нет записи — Claude Code: так ведут себя все установки до 0.4.
     */
    var roleHarness: MutableMap<String, String> = mutableMapOf()

    /** Модель каждой роли: id роли -> имя модели. Пусто — модель роли по умолчанию у харнеса. */
    var roleModel: MutableMap<String, String> = mutableMapOf()

    /** Команда запуска Codex. Как и у Claude, сюда вписывается обёртка. */
    var codexLaunchCommand: String = "codex"

    /**
     * Аргументы Codex-агентов сверх роли, доверия к каталогу и модели.
     *
     * `-a never`: вопрос о подтверждении во вкладке, которую никто не читает, — вечная
     * блокировка. `--disable hooks`: иначе при недоверенных хуках пользователя Codex
     * спрашивает «Review hooks» на старте, а флаг `--dangerously-bypass-hook-trust`
     * запустил бы непроверенный код (docs/PLAN-0.4.md §4.6). Строка видна в настройках
     * целиком, чтобы это решение можно было пересмотреть, не трогая код.
     */
    var codexArgs: String = "-s workspace-write -a never --disable hooks"

    /**
     * Состав итогового ревью: ключ харнеса -> сколько ревьюверов (0–3).
     *
     * Только итоговое ревью (`domain=review`): гейты в фиче идут десятками, и умножать каждый
     * на размер состава дорого. Пусто или все нули — один ревьювер на харнесе роли, как до
     * появления состава (docs/PLAN-REVIEW-PANEL.md).
     */
    var reviewPanel: MutableMap<String, Int> = mutableMapOf()

    /** Модель ревьюверов состава по харнесу; пусто — как у роли ревьювера. */
    var reviewPanelModel: MutableMap<String, String> = mutableMapOf()

    /** Участник состава: харнес, модель и слот — хвост имени (`review-codex`, `review-claude-2`). */
    data class PanelMember(val harness: Harness, val model: String?, val slot: String)

    fun reviewPanelMembers(): List<PanelMember> = Harness.entries.flatMap { harness ->
        val count = (reviewPanel[harness.key] ?: 0).coerceIn(0, MAX_PANEL_PER_HARNESS)
        val model = reviewPanelModel[harness.key]?.trim()?.takeIf { it.isNotEmpty() }
        (1..count).map { index ->
            val slot = "review-" + harness.shortName + if (index > 1) "-$index" else ""
            PanelMember(harness, model, slot)
        }
    }

    fun harnessFor(role: Role): Harness =
        if (role == Role.ORCHESTRATOR) Harness.CLAUDE
        else Harness.fromKey(roleHarness[role.id]) ?: Harness.CLAUDE

    fun modelFor(role: Role): String? = roleModel[role.id]?.trim()?.takeIf { it.isNotEmpty() }

    /** Дополнительные переменные окружения агентов, в формате KEY=VALUE, по одной в строке. */
    var extraEnvRaw: String = ""


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
        const val MAX_PANEL_PER_HARNESS = 3

        /** Обратный слэш кодом, чтобы не плодить экранирование в исходнике. */
        private val WINDOWS_SEPARATOR: Char = 92.toChar()

        fun getInstance(): MjcSettings =
            ApplicationManager.getApplication().getService(MjcSettings::class.java)

        /**
         * Порт, выведенный из пути проекта: стабильный между запусками, разный для разных
         * проектов.
         *
         * Диапазон 40000–44999 выбран намеренно: ниже него плотно сидят зарегистрированные
         * службы, а с 49152 начинается эфемерный диапазон, откуда порты раздаёт сама ОС —
         * там наш порт рано или поздно займут.
         */
        fun derivePort(projectPath: String): Int {
            var hash = 0
            // Нормализуем форму записи пути: один и тот же проект не должен получать разные
            // порты из-за разделителя или регистра.
            for (ch in projectPath.replace(WINDOWS_SEPARATOR, '/').lowercase()) {
                hash = hash * 31 + ch.code
            }
            return 40_000 + (hash.toLong() and 0x7fffffffL).toInt() % 5_000
        }

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
