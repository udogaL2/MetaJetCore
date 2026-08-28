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

    /** Путь к шеллу из настроек IDE, либо null если API недоступен или путь не задан. */
    fun configuredShellPath(): String? = try {
        val cls = Class.forName(
            "org.jetbrains.plugins.terminal.TerminalOptionsProvider",
            false,
            javaClass.classLoader,
        )
        val instance = cls.methods
            .firstOrNull { it.name == "getInstance" && it.parameterCount == 0 }
            ?.invoke(null)

        if (instance == null) {
            null
        } else {
            // getShellPath() в одних версиях, getShellPathOrDefault() в других.
            val value = sequenceOf("getShellPath", "getShellPathOrDefault", "getDefaultShellPath")
                .mapNotNull { name ->
                    instance.javaClass.methods
                        .firstOrNull { it.name == name && it.parameterCount == 0 }
                        ?.invoke(instance)
                }
                .firstOrNull()
            (value as? String)?.takeIf { it.isNotBlank() }
        }
    } catch (e: Throwable) {
        log.debug("MetaJetCore: cannot read terminal shell path", e)
        null
    }

    /**
     * Диалект для вкладок, которые откроет плагин.
     *
     * Приоритет: настройка терминала IDE → окружение процесса → умолчание платформы.
     */
    fun detectDialect(): ShellDialect {
        configuredShellPath()?.let { path ->
            val dialect = ShellDialect.detect(path)
            log.info("MetaJetCore: terminal shell '$path' -> $dialect")
            return dialect
        }
        return ShellDialect.detect(System.getenv("SHELL") ?: System.getenv("ComSpec"))
    }
}
