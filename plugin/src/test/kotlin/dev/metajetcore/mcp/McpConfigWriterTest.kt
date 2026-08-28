package dev.metajetcore.mcp

import dev.metajetcore.util.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Плагин пишет в `~/.claude.json` — файл, принадлежащий Claude Code. Главное требование
 * поэтому не «записать», а «не сломать чужое»: сохранить все посторонние ключи, не тронуть
 * соседние проекты и не наплодить дубликатов при повторных запусках.
 */
class McpConfigWriterTest {

    private lateinit var home: Path
    private var previousHome: String? = null

    private val projectA = "C:/Users/dev/Projects/Alpha"
    private val projectB = "C:/Users/dev/Projects/Beta"

    @Before
    fun setUp() {
        home = Files.createTempDirectory("mjc-config")
        previousHome = System.getProperty("user.home")
        System.setProperty("user.home", home.toString())
    }

    @After
    fun tearDown() {
        previousHome?.let { System.setProperty("user.home", it) }
        home.toFile().deleteRecursively()
    }

    private fun config(): Json = Json.parse(Files.readString(McpConfigWriter.configPath()))

    private fun urlFor(project: String): String? =
        config()["projects"]?.get(project)?.get("mcpServers")?.get("metajetcore")?.get("url")?.asString

    @Test
    fun writesServerUnderProjectKey() {
        assertTrue(McpConfigWriter.write(projectA, "http://127.0.0.1:43979/mcp"))
        assertEquals("http://127.0.0.1:43979/mcp", urlFor(projectA))
        assertEquals(
            "http",
            config()["projects"]?.get(projectA)?.get("mcpServers")?.get("metajetcore")?.get("type")?.asString,
        )
    }

    @Test
    fun preservesForeignKeys() {
        // Файл принадлежит Claude Code: всё, что не наше, обязано пережить запись.
        Files.writeString(
            McpConfigWriter.configPath(),
            """{"numStartups":42,"tipsHistory":{"a":1},"projects":{"$projectA":{"allowedTools":["Read"]}}}""",
        )
        assertTrue(McpConfigWriter.write(projectA, "http://127.0.0.1:1/mcp"))

        val root = config()
        assertEquals(42, root["numStartups"]?.asInt)
        assertNotNull(root["tipsHistory"]?.get("a"))
        // Настройки самого проекта тоже не должны пропасть.
        assertNotNull(root["projects"]?.get(projectA)?.get("allowedTools"))
        assertEquals("http://127.0.0.1:1/mcp", urlFor(projectA))
    }

    @Test
    fun doesNotTouchOtherProjects() {
        // Два окна IDE с разными проектами не должны мешать друг другу.
        assertTrue(McpConfigWriter.write(projectA, "http://127.0.0.1:43979/mcp"))
        assertTrue(McpConfigWriter.write(projectB, "http://127.0.0.1:42904/mcp"))

        assertEquals("http://127.0.0.1:43979/mcp", urlFor(projectA))
        assertEquals("http://127.0.0.1:42904/mcp", urlFor(projectB))
    }

    @Test
    fun rewritesInPlaceInsteadOfAccumulating() {
        // Повторные запуски не должны плодить записи: ключ один и тот же.
        McpConfigWriter.write(projectA, "http://127.0.0.1:1/mcp")
        McpConfigWriter.write(projectA, "http://127.0.0.1:2/mcp")
        McpConfigWriter.write(projectA, "http://127.0.0.1:3/mcp")

        assertEquals("http://127.0.0.1:3/mcp", urlFor(projectA))
        assertEquals(
            1,
            config()["projects"]?.get(projectA)?.get("mcpServers")?.asMap?.size,
        )
    }

    @Test
    fun keepsOtherMcpServersOfSameProject() {
        Files.writeString(
            McpConfigWriter.configPath(),
            """{"projects":{"$projectA":{"mcpServers":{"other":{"type":"http","url":"http://x/"}}}}}""",
        )
        McpConfigWriter.write(projectA, "http://127.0.0.1:1/mcp")

        val servers = config()["projects"]?.get(projectA)?.get("mcpServers")?.asMap
        assertEquals(setOf("other", "metajetcore"), servers?.keys)
    }

    @Test
    fun isUpToDateOnlyForMatchingUrl() {
        assertFalse(McpConfigWriter.isUpToDate(projectA, "http://127.0.0.1:1/mcp"))
        McpConfigWriter.write(projectA, "http://127.0.0.1:1/mcp")
        assertTrue(McpConfigWriter.isUpToDate(projectA, "http://127.0.0.1:1/mcp"))
        // Порт сменился — запись устарела, её надо обновить.
        assertFalse(McpConfigWriter.isUpToDate(projectA, "http://127.0.0.1:2/mcp"))
    }

    @Test
    fun normalizesPathSeparators() {
        val backslash = 92.toChar()
        val windowsStyle = "C:${backslash}Users${backslash}dev${backslash}Projects${backslash}Alpha"
        McpConfigWriter.write(windowsStyle, "http://127.0.0.1:1/mcp")
        // Claude Code хранит пути с прямыми слэшами — иначе получим второй ключ на тот же проект.
        assertEquals("http://127.0.0.1:1/mcp", urlFor(projectA))
    }

    @Test
    fun survivesCorruptedConfig() {
        // Битый файл не повод падать: перезапишем своим, это лучше, чем не запуститься.
        Files.writeString(McpConfigWriter.configPath(), "{ это не json")
        assertTrue(McpConfigWriter.write(projectA, "http://127.0.0.1:1/mcp"))
        assertEquals("http://127.0.0.1:1/mcp", urlFor(projectA))
    }
}
