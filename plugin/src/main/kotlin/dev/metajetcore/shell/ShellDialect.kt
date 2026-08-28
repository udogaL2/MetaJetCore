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
            val prefix = env.entries.joinToString("; ") { (k, v) -> "\$env:$k=${quotePsLiteral(v)}" }
            if (prefix.isEmpty()) command else "$prefix; $command"
        }

        CMD -> {
            val prefix = env.entries.joinToString(" & ") { (k, v) -> "set \"$k=$v\"" }
            if (prefix.isEmpty()) command else "$prefix & $command"
        }
    }

    /**
     * Стереть ВСЕ унаследованные переменные с указанными префиксами.
     *
     * Именно префиксами, а не списком имён. Список — это денилист: он устаревает молча, стоит
     * Claude Code завести новую переменную, и она снова просочится в агента. А просачивается
     * там существенное: `CLAUDE_CODE_MESSAGING_TOKEN` и `..._SOCKET` — это инбокс РОДИТЕЛЬСКОЙ
     * сессии, `CLAUDE_CODE_CHILD_SESSION` заставляет агента считать себя вложенным процессом
     * и не регистрироваться в реестре, `ANTHROPIC_API_KEY` молча уводит с подписки на
     * API-биллинг.
     *
     * cmd.exe перечислять окружение одной строкой не умеет, поэтому для него остаётся
     * поимённый список — см. [unsetNames].
     */
    fun purgeByPrefix(prefixes: List<String>): String = when {
        prefixes.isEmpty() -> ""

        this == POSIX -> {
            val pattern = prefixes.joinToString("|")
            // unset без аргументов — no-op, поэтому пустой env безопасен.
            "unset \$(env | grep -oE '^($pattern)[A-Za-z0-9_]*' | tr '\\n' ' '); "
        }

        this == FISH -> {
            val pattern = prefixes.joinToString("|")
            "for v in (env | grep -oE '^($pattern)[A-Za-z0-9_]*'); set -e \$v; end; "
        }

        this == POWERSHELL -> {
            val filter = prefixes.joinToString(" -or ") { "\$_.Name -like '$it*'" }
            "Get-ChildItem Env: | Where-Object { $filter } | " +
                "ForEach-Object { Remove-Item \"Env:\$(\$_.Name)\" -ErrorAction SilentlyContinue }; "
        }

        else -> ""
    }

    /** Поимённая вычистка. Нужна только там, где перечислить окружение нельзя. */
    fun unsetNames(names: List<String>): String = when {
        names.isEmpty() -> ""
        this == POSIX -> "unset ${names.joinToString(" ")}; "
        this == FISH -> names.joinToString("") { "set -e $it; " }
        this == POWERSHELL ->
            names.joinToString("") { "Remove-Item Env:$it -ErrorAction SilentlyContinue; " }
        this == CMD -> names.joinToString("") { "set $it= & " }
        else -> ""
    }

    /** Умеет ли шелл стереть переменные по префиксу одной строкой. */
    val supportsPrefixPurge: Boolean get() = this != CMD

    private fun quotePosix(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    /**
     * Значение для присваивания `$env:X=...` внутри самого PowerShell.
     *
     * Здесь второго слоя разбора нет — это не аргумент нативной программы, — поэтому
     * достаточно удвоить одинарные кавычки. Экранировать двойные тут нельзя: они уехали бы
     * в значение переменной как есть.
     */
    private fun quotePsLiteral(value: String): String =
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
