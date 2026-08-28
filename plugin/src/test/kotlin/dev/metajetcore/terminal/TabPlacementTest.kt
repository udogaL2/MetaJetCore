package dev.metajetcore.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Тест на деградацию, а не на функциональность.
 *
 * Размещение вкладки целиком построено на рефлексии по внутренним классам плагина терминала:
 * `TerminalToolWindowManager.isInTerminalToolWindow`, `JBTerminalWidgetListener.split`,
 * действие `Terminal.MoveToEditor`. Ни одно из них не является публичным API, и любое может
 * исчезнуть или переехать в следующей версии IDE.
 *
 * Требование к коду поэтому не «работает», а **«не ломает спавн, когда перестало работать»**:
 * агент обязан подняться даже если вкладка окажется не там, где хотелось. Здесь проверяется
 * именно это — что на посторонних и отсутствующих объектах всё возвращает пустой результат
 * и не бросает.
 */
class TabPlacementTest {

    @Test
    fun locateReturnsUnknownForNull() {
        assertEquals(TabPlacement.Location.UNKNOWN, TabPlacement.locate(null))
    }

    @Test
    fun locateReturnsUnknownForForeignObject() {
        // Посторонний объект — то же, что исчезнувший API: ответ «не знаю», а не исключение.
        assertEquals(TabPlacement.Location.UNKNOWN, TabPlacement.locate("не виджет"))
        assertEquals(TabPlacement.Location.UNKNOWN, TabPlacement.locate(Any()))
        assertEquals(TabPlacement.Location.UNKNOWN, TabPlacement.locate(42))
    }

    @Test
    fun locateNeverThrows() {
        // Объект, у которого есть похоже названные методы, но с другими типами —
        // самый вредный случай: рефлексия найдёт метод и упадёт на вызове.
        val trap = object {
            @Suppress("unused") fun asNewWidget(): String = "подмена"
            @Suppress("unused") fun getListener(): Int = 0
            @Suppress("unused") fun getTtyConnector(): Any? = null
        }
        assertEquals(TabPlacement.Location.UNKNOWN, TabPlacement.locate(trap))
    }

    @Test
    fun splitFromParentIsNullWithoutProject() {
        // Без живого Project и без плагина терминала сплит невозможен — обязан вернуть null,
        // чтобы вызывающий откатился на обычное создание вкладки.
        assertNull(TabPlacement.splitFromParent(FakeProject, Any(), true))
    }

    @Test
    fun locationEnumCoversAllCases() {
        // Если добавится новое место, вызывающий код обязан быть пересмотрен: он ветвится
        // по этому перечислению.
        assertEquals(3, TabPlacement.Location.entries.size)
    }

    @Test
    fun unavailableBackendDegradesInsteadOfThrowing() {
        // Заглушка терминала — это то, во что превращается плагин, когда API переехало.
        // Она обязана отвечать «нет» на всё и не бросать.
        val handle = TabHandle("id", "имя", widget = null, backendId = "test")
        assertFalse(UnavailableTerminalBackend.isAvailable())
        assertFalse(UnavailableTerminalBackend.isReady(handle))
        assertFalse(UnavailableTerminalBackend.sendLine(handle, "текст"))
        assertFalse(UnavailableTerminalBackend.closeTab(handle))
        assertFalse(UnavailableTerminalBackend.focusTab(handle))
        assertNull(UnavailableTerminalBackend.readScreen(handle))
    }
}

/**
 * Project нужен только как аргумент: до его использования код не доходит, потому что
 * плагина терминала в тестовом окружении нет. Полноценная фикстура здесь была бы лишней.
 */
private object FakeProject : com.intellij.openapi.project.Project by NoProject

private val NoProject: com.intellij.openapi.project.Project =
    java.lang.reflect.Proxy.newProxyInstance(
        com.intellij.openapi.project.Project::class.java.classLoader,
        arrayOf(com.intellij.openapi.project.Project::class.java),
    ) { _, _, _ -> null } as com.intellij.openapi.project.Project
