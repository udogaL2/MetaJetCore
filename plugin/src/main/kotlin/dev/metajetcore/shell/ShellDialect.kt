package dev.metajetcore.shell

/**
 * Синтаксис того немногого, что плагину всё ещё нужно от шелла: снять унаследованные
 * переменные окружения перед запуском агента и собрать argv, которым вкладка его запустит.
 *
 * Всё остальное окружение задаётся через API терминала (`TerminalToolWindowTabBuilder.
 * envVariables`), поэтому ни составления `KEY=value`, ни экранирования кавычек здесь больше
 * нет — вместе с ними ушёл целый класс поломок вида «PowerShell не принимает аргумент с
 * кавычками и пробелами».
 *
 * Почему вычистку нельзя сделать тем же API: `envVariables` умеет только добавить или
 * переопределить переменную, а нам нужно, чтобы её НЕ БЫЛО. Пустое значение не подходит:
 * Claude Code проверяет часть маркеров на `undefined`, а не на пустоту, — например
 * `CLAUDE_CODE_MESSAGING_SOCKET` (проверено по бинарнику 2.1.251: `r === void 0 ? ... : ...`).
 * Пустая строка прошла бы проверку и агент принял бы инбокс родителя за свой.
 */
enum class ShellDialect {
    /** bash, zsh, sh, dash. */
    POSIX,

    /** PowerShell / pwsh. */
    POWERSHELL,

    /** cmd.exe. */
    CMD,

    /** fish: собственный синтаксис. */
    FISH,
    ;

    /**
     * Снять перечисленные переменные. Имена берутся из окружения самой IDE (см.
     * `AgentManager.inheritedMarkers`), поэтому список не может устареть: мы вычищаем ровно
     * то, что реально протекло, а не то, что знали на момент написания кода.
     */
    fun unsetNames(names: List<String>): String = when {
        names.isEmpty() -> ""
        this == POSIX -> "unset ${names.joinToString(" ")}; "
        this == FISH -> names.joinToString("") { "set -e $it; " }
        this == POWERSHELL ->
            names.joinToString("") { "Remove-Item Env:$it -ErrorAction SilentlyContinue; " }
        else -> names.joinToString("") { "set $it= & " }
    }

    /**
     * argv процесса вкладки агента: шелл, который выполнит [line] и завершится вместе с ним.
     *
     * Зачем шелл, а не сразу бинарник, как делает сама IDE: команда запуска берётся из
     * настроек и бывает составной (`nvm use 20 && claude`), алиасом или функцией из профиля.
     * Поэтому POSIX-шелл стартует login + interactive (`-l -i`) — ровно с тем окружением, что
     * и вкладка, в которой разработчик набрал бы команду сам.
     *
     * Шелл живёт ровно столько, сколько команда: `-c`/`-Command`/`/c` выходит после неё, и
     * вкладка с `closeOnProcessTermination` закрывается. Прежний хвост `; exit`, печатаемый в
     * интерактивный шелл, больше не нужен.
     *
     * `exec` — только для простой команды: тогда процесс вкладки и есть агент, и сигналы
     * при закрытии идут прямо ему. К составной строке `exec` приклеился бы лишь к первой
     * части и молча сломал бы запуск.
     */
    fun argv(
        shellPath: String,
        unset: String,
        command: String,
        execable: Boolean = isSimple(command),
    ): List<String> = when (this) {
        POSIX, FISH -> {
            val body = if (execable) "exec $command" else command
            listOf(shellPath, "-l", "-i", "-c", unset + body)
        }
        POWERSHELL -> listOf(shellPath, "-NoLogo", "-Command", unset + command)
        CMD -> listOf(shellPath, "/c", unset + command)
    }

    /**
     * Значение переменной окружения одним аргументом команды.
     *
     * Так в строку запуска попадает пользовательский текст (задача Codex-агенту, путь
     * проекта): само значение едет через API вкладки, а в строке — только имя переменной.
     * Кавычки, `$` и переводы строки в значении шелл при такой подстановке не трогает —
     * проверено с задачей `привет, "кавычки" и ${'$'}доллар`. Имя обязано быть безобидным, его
     * проверяет вызывающий.
     *
     * Известные исключения — Windows. cmd раскрывает `%VAR%` до разбора строки, поэтому
     * кавычки внутри значения рвут аргумент. Windows PowerShell 5.1 портит кавычки в
     * аргументах нативных программ (pwsh 7.3+ — нет). На живом Windows это не проверялось;
     * худшее последствие — искажённая задача, и агент ответит на неё вопросом.
     */
    fun envRef(name: String): String = when (this) {
        POSIX, FISH -> "\"${'$'}$name\""
        POWERSHELL -> "\"${'$'}env:$name\""
        CMD -> "\"%$name%\""
    }

    companion object {
        /**
         * Диалект по командной строке шелла.
         *
         * Источник этой строки — сама вкладка (`startupOptionsDeferred.shellCommand`), то есть
         * фактически запущенный процесс, а не настройка и не окружение процесса IDE. Раньше
         * диалект брали из `ComSpec`, и во вкладку с PowerShell уезжал синтаксис cmd —
         * PowerShell отвечал «Амперсанд (&) не разрешен».
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

        /**
         * Команда без операторов шелла — одна программа с аргументами. Консервативно: всё
         * сомнительное считаем составным, худшее последствие — лишний процесс шелла.
         */
        fun isSimple(command: String): Boolean =
            command.isNotBlank() && command.none { it in ";&|<>()`$\n{}" }

        /**
         * Шелл по умолчанию, если в настройках терминала IDE он не задан. На Windows IDE по
         * умолчанию открывает PowerShell, а не `ComSpec` (cmd) — повторяем её выбор.
         */
        fun defaultShell(): String =
            if (isWindows()) "powershell.exe"
            else System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/sh"

        fun isWindows(): Boolean =
            System.getProperty("os.name").orEmpty().lowercase().contains("win")
    }
}
