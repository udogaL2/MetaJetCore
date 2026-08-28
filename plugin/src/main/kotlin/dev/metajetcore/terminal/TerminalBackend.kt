package dev.metajetcore.terminal

import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * Ссылка на открытую вкладку терминала. Хранит платформенный объект как Any: конкретный
 * тип виджета отличается между версиями IDE, и знать его здесь мы не хотим.
 */
class TabHandle(
    val id: String,
    val displayName: String,
    internal val widget: Any?,
    internal val backendId: String,
)

data class OpenTabRequest(
    val tabName: String,
    val workingDirectory: Path,
    /** Вкладка, рядом с которой открыть новую. null — куда получится. */
    val nearTab: TabHandle?,
    val requestFocus: Boolean = true,
)

/**
 * Минимальная поверхность контакта с терминалом IDE: создать вкладку и напечатать строку.
 *
 * Всё остальное — переменные окружения, флаги, роль — едет внутри печатаемой строки
 * (см. shell/ShellDialect). Это сделано намеренно: Terminal API помечен экспериментальным
 * с 2025.3, и чем меньше методов мы от него хотим, тем меньше шансов, что апгрейд IDE
 * сломает плагин.
 */
interface TerminalBackend {
    /** Для диагностики: какое поколение API удалось разрешить. */
    val id: String

    fun isAvailable(): Boolean

    fun openTab(project: Project, request: OpenTabRequest): TabHandle?

    /**
     * Поднялся ли шелл во вкладке.
     *
     * Между созданием виджета и стартом процесса шелла проходит заметное время, и строка,
     * напечатанная в этот промежуток, теряется молча. Без этой проверки спавн выглядит как
     * «вкладка открылась, но ничего не запустилось».
     */
    fun isReady(handle: TabHandle): Boolean

    fun sendLine(handle: TabHandle, text: String): Boolean

    /**
     * Текст, который сейчас на экране вкладки.
     *
     * Нужен для разбора ситуаций «агент запустился, но не отвечает»: без этого видно только,
     * что процесс жив, а что он спрашивает — нет. Оркестратору тоже полезно: если агент
     * упёрся в интерактивный вопрос, единственный способ узнать какой — прочитать экран.
     */
    fun readScreen(handle: TabHandle): String?

    fun closeTab(handle: TabHandle): Boolean

    fun focusTab(handle: TabHandle): Boolean
}

/**
 * Заглушка на случай, когда терминальное API недоступно или изменилось до неузнаваемости.
 *
 * Не бросает и не ломает плагин: AgentManager, получив её, отдаёт оркестратору готовую
 * командную строку с пометкой «попроси разработчика выполнить это в новой вкладке».
 * Деградация вместо падения — основное требование к плагину.
 */
object UnavailableTerminalBackend : TerminalBackend {
    override val id: String = "unavailable"
    override fun isAvailable(): Boolean = false
    override fun openTab(project: Project, request: OpenTabRequest): TabHandle? = null
    override fun isReady(handle: TabHandle): Boolean = false
    override fun sendLine(handle: TabHandle, text: String): Boolean = false
    override fun readScreen(handle: TabHandle): String? = null
    override fun closeTab(handle: TabHandle): Boolean = false
    override fun focusTab(handle: TabHandle): Boolean = false
}
