package dev.metajetcore.shell

/**
 * Как записать «переменные окружения + команда» одной строкой для конкретного шелла.
 *
 * Это ключевое решение по стабильности: плагин НЕ задаёт окружение через API терминала.
 * Вместо этого env едет прямо в набираемой командной строке. Благодаря этому вся работа
 * с терминалом сводится к двум операциям — создать вкладку и напечатать строку, — а
 * значит от нестабильного Terminal API мы зависим по минимуму.
 */
enum class ShellDialect {
    /** bash, zsh, sh, dash — основная цель проекта. */
    POSIX,

    /** PowerShell / pwsh. */
    POWERSHELL,

    /** cmd.exe. */
    CMD,

    /** fish: собственный синтаксис для env. */
    FISH,
    ;

    fun composeCommand(env: Map<String, String>, command: String): String = when (this) {
        POSIX -> {
            val assignments = env.entries.joinToString(" ") { (k, v) -> "$k=${quotePosix(v)}" }
            if (assignments.isEmpty()) command else "$assignments $command"
        }

        FISH -> {
            val prefix = env.entries.joinToString("; ") { (k, v) -> "set -x $k ${quotePosix(v)}" }
            if (prefix.isEmpty()) command else "$prefix; $command"
        }

        POWERSHELL -> {
            val prefix = env.entries.joinToString("; ") { (k, v) -> "\$env:$k=${quotePowerShell(v)}" }
            if (prefix.isEmpty()) command else "$prefix; $command"
        }

        CMD -> {
            val prefix = env.entries.joinToString(" & ") { (k, v) -> "set \"$k=$v\"" }
            if (prefix.isEmpty()) command else "$prefix & $command"
        }
    }

    /** Как убрать переменную, если она унаследована и мешает (например, ANTHROPIC_API_KEY). */
    fun unsetPrefix(names: List<String>): String = when {
        names.isEmpty() -> ""
        this == POSIX -> "unset ${names.joinToString(" ")}; "
        this == FISH -> names.joinToString("") { "set -e $it; " }
        this == POWERSHELL ->
            names.joinToString("") { "Remove-Item Env:$it -ErrorAction SilentlyContinue; " }
        this == CMD -> names.joinToString("") { "set $it= & " }
        else -> ""
    }

    /** Экранирование одного аргумента (например, JSON для --agents). */
    fun quoteArgument(value: String): String = when (this) {
        POSIX, FISH -> quotePosix(value)
        POWERSHELL -> quotePowerShell(value)
        CMD -> "\"" + value.replace("\"", "\\\"") + "\""
    }

    private fun quotePosix(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun quotePowerShell(value: String): String =
        "'" + value.replace("'", "''") + "'"

    companion object {
        /**
         * Определение диалекта по пути к шеллу. Намеренно консервативно: при любых
         * сомнениях на Windows берём PowerShell, иначе POSIX — это дефолты соответствующих
         * платформ, и промах виден сразу по первой же набранной команде.
         */
        fun detect(shellPath: String?): ShellDialect {
            val lower = shellPath?.lowercase().orEmpty()
            return when {
                lower.contains("powershell") || lower.contains("pwsh") -> POWERSHELL
                lower.endsWith("cmd.exe") || lower.endsWith("cmd") -> CMD
                lower.contains("fish") -> FISH
                lower.contains("bash") || lower.contains("zsh") ||
                    lower.contains("/sh") || lower.endsWith("sh") -> POSIX
                isWindows() -> POWERSHELL
                else -> POSIX
            }
        }

        fun isWindows(): Boolean =
            System.getProperty("os.name").orEmpty().lowercase().contains("win")
    }
}
