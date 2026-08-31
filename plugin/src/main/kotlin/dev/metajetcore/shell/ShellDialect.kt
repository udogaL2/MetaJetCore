package dev.metajetcore.shell

/**
 * Синтаксис того немногого, что плагину всё ещё приходится печатать в шелл: снять
 * унаследованные переменные окружения перед запуском агента и выйти из шелла после его
 * завершения.
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
     * Хвост, который гасит сам шелл, когда агент завершился.
     *
     * Зачем: процесс вкладки — это шелл, а `/exit` убивает только claude. Шелл после этого
     * возвращается к приглашению и остаётся жив, поэтому закрытие вкладки упирается в
     * модальный вопрос платформы «в терминале что-то запущено, точно закрыть?». Зовём мы
     * закрытие под `invokeAndWait`, то есть этот вопрос блокирует поток MCP-запроса:
     * оркестратор ждёт не IDE, а человека, а вкладка остаётся открытой, если человек
     * ответил «нет» или просто не заметил диалог. С хвостовым выходом процесса во вкладке
     * не остаётся вовсе и спрашивать платформе не о чем.
     *
     * Почему хвост, а не `exec claude`: команда запуска берётся из настроек и может быть
     * составной (`nvm use 20 && claude`, обёртка-скрипт). `exec` приклеился бы только к
     * первой её части и молча сломал бы запуск, а `; exit` корректен при любой строке.
     *
     * Вкладка при этом не исчезает: она создаётся с `closeOnProcessTermination = false`,
     * поэтому текст упавшего запуска остаётся на экране и его можно прочитать.
     */
    fun exitWhenDone(): String = if (this == CMD) " & exit" else "; exit"

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

        fun isWindows(): Boolean =
            System.getProperty("os.name").orEmpty().lowercase().contains("win")
    }
}
