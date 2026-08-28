package dev.metajetcore.terminal

import com.intellij.openapi.diagnostic.Logger
import dev.metajetcore.shell.ShellDialect

/**
 * Какой шелл на самом деле запускается во вкладке терминала IDE.
 *
 * Переменные `SHELL` и `ComSpec` описывают шелл процесса IDE, а не тот, который она
 * открывает во вкладке: пользователь задаёт его в Settings | Tools | Terminal | Shell path.
 * На Windows расхождение почти гарантировано — `ComSpec` указывает на cmd.exe, а во вкладке
 * обычно PowerShell. Ошибка в диалекте означает порванную командную строку и агента,
 * стартовавшего без роли.
 *
 * Читаем настройку рефлексией: `TerminalOptionsProvider` живёт в плагине терминала, а
 * компиляционной зависимости на него у нас намеренно нет.
 */
object TerminalShell {
    private val log = Logger.getInstance(TerminalShell::class.java)

    /**
     * Путь к шеллу из настроек IDE.
     *
     * Настройка живёт в двух местах: `TerminalOptionsProvider` (application) и
     * `TerminalProjectOptionsProvider` (project, там же вычисляется умолчание, когда
     * пользователь ничего не задавал). Спрашиваем оба — application-уровень у большинства
     * пуст, и раньше именно поэтому определение проваливалось.
     */
    fun configuredShellPath(project: com.intellij.openapi.project.Project? = null): String? {
        applicationShellPath()?.let { return it }
        if (project != null) projectShellPath(project)?.let { return it }
        return null
    }

    private fun applicationShellPath(): String? = shellPathFrom(
        className = "org.jetbrains.plugins.terminal.TerminalOptionsProvider",
        instanceArg = null,
    )

    private fun projectShellPath(project: com.intellij.openapi.project.Project): String? = shellPathFrom(
        className = "org.jetbrains.plugins.terminal.TerminalProjectOptionsProvider",
        instanceArg = project,
    )

    private fun shellPathFrom(className: String, instanceArg: Any?): String? = try {
        val cls = Class.forName(className, false, javaClass.classLoader)
        val getInstance = cls.methods.firstOrNull {
            it.name == "getInstance" && it.parameterCount == (if (instanceArg == null) 0 else 1)
        }
        val instance = if (instanceArg == null) {
            getInstance?.invoke(null)
        } else {
            getInstance?.invoke(null, instanceArg)
        }

        if (instance == null) {
            null
        } else {
            // Имя метода менялось между версиями; берём первый подошедший.
            sequenceOf("getShellPath", "getShellPathOrDefault", "getDefaultShellPath")
                .mapNotNull { name ->
                    instance.javaClass.methods
                        .firstOrNull { it.name == name && it.parameterCount == 0 }
                        ?.invoke(instance) as? String
                }
                .firstOrNull { it.isNotBlank() }
        }
    } catch (e: Throwable) {
        log.debug("MetaJetCore: cannot read shell path from $className", e)
        null
    }

    /**
     * Диалект по ФАКТИЧЕСКОМУ процессу шелла в уже открытой вкладке.
     *
     * Самый надёжный источник: спрашиваем не настройку и не окружение, а сам запущенный
     * процесс. `ProcessHandle.info().command()` — чистый JDK, от версии IDE не зависит.
     *
     * Это исправление реальной поломки: при пустом `getShellPath()` код падал на `ComSpec`,
     * то есть на шелл ПРОЦЕССА IDE, и во вкладку с PowerShell уезжала команда в синтаксисе
     * cmd. PowerShell отвечал «Амперсанд (&) не разрешен».
     */
    fun detectDialectForTab(shellPid: Long?): ShellDialect? {
        val pid = shellPid ?: return null
        val command = try {
            ProcessHandle.of(pid).orElse(null)?.info()?.command()?.orElse(null)
        } catch (_: Throwable) {
            null
        }
        if (command.isNullOrBlank()) return null
        val dialect = ShellDialect.detect(command)
        log.info("MetaJetCore: процесс шелла во вкладке '$command' -> $dialect")
        return dialect
    }

    /**
     * Диалект, когда фактического процесса ещё нет.
     *
     * Порядок: настройка терминала в IDE (application, затем project), переменная SHELL,
     * умолчание платформы.
     *
     * `ComSpec` здесь намеренно НЕ используется: он описывает шелл процесса IDE, а вкладку
     * IDE открывает своим — на Windows это почти всегда PowerShell, тогда как ComSpec
     * указывает на cmd. Ровно эта подмена и ломала команду запуска.
     */
    fun detectDialect(project: com.intellij.openapi.project.Project? = null): ShellDialect {
        configuredShellPath(project)?.let { path ->
            val dialect = ShellDialect.detect(path)
            log.info("MetaJetCore: shell path из настроек IDE '$path' -> $dialect")
            return dialect
        }
        System.getenv("SHELL")?.takeIf { it.isNotBlank() }?.let { return ShellDialect.detect(it) }
        return if (ShellDialect.isWindows()) ShellDialect.POWERSHELL else ShellDialect.POSIX
    }
}
