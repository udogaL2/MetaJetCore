package dev.metajetcore.harness

/**
 * Харнес — программа, которой запускается агент: Claude Code, Codex.
 *
 * Ключи совпадают с `agentKey` агентов терминала IDE (`TerminalAgent`, 263+), и это
 * намеренно: список харнесов, которые видит разработчик в настройках, берётся у IDE, а иконка
 * вкладки — по тому же ключу. Свой справочник плагин не ведёт.
 *
 * Здесь перечислены только харнесы, для которых в плагине есть адаптер. Остальные IDE тоже
 * покажет (Junie, сторонние провайдеры), но назначить их роли нельзя: чем такого агента
 * запустить, как узнать, что он готов, и как до него достучаться, — разное у каждого.
 */
enum class Harness(val key: String, val displayName: String) {
    CLAUDE("claude_code", "Claude Code"),
    CODEX("codex", "Codex"),
    ;

    companion object {
        fun fromKey(key: String?): Harness? =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) }

        /**
         * Похоже ли имя модели на модель Claude.
         *
         * tempo задаёт модель каждому запуску субагента в терминах Claude (`opus`, `sonnet`), и
         * оркестратор передаёт её в `spawn_agent` как есть. Codex такую модель не знает и
         * упал бы на старте, поэтому для него это имя отбрасываем — берётся модель из
         * настроек роли или из конфига Codex.
         */
        fun isClaudeModel(model: String): Boolean {
            val lower = model.trim().lowercase()
            return lower in CLAUDE_ALIASES || lower.startsWith("claude")
        }

        private val CLAUDE_ALIASES = setOf("opus", "sonnet", "haiku", "fable", "opusplan", "default")
    }
}
