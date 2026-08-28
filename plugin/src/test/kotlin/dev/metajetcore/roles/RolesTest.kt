package dev.metajetcore.roles

import dev.metajetcore.util.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RolesTest {

    @Test
    fun agentsJsonIsValidAndCarriesEveryField() {
        for (role in Role.entries) {
            val rendered = Roles.agentsJson(role, "opus")
            val parsed = Json.parseOrNull(rendered)
            assertNotNull("роль ${role.id}: невалидный JSON", parsed)

            val definition = parsed!![role.id]
            assertNotNull("роль ${role.id}: нет ключа с именем роли", definition)
            assertTrue(definition!!["description"]?.asString?.isNotBlank() == true)
            assertTrue(definition["prompt"]?.asString?.isNotBlank() == true)
            assertEquals("opus", definition["model"]?.asString)
            assertTrue(definition["tools"]?.asList?.isNotEmpty() == true)
        }
    }

    @Test
    fun agentsJsonContainsOnlyTheRequestedRole() {
        // Передаём одну роль за спавн, чтобы строка оставалась около килобайта.
        val parsed = Json.parse(Roles.agentsJson(Role.IMPLEMENTER, "opus"))
        assertEquals(setOf("implementer"), parsed.asMap?.keys)
    }

    @Test
    fun orchestratorCannotWrite() {
        // Ключевая гарантия: оркестратор не должен уметь делать работу имплементера сам.
        val tools = Roles.toolsFor(Role.ORCHESTRATOR)
        assertFalse(tools.toString(), tools.contains("Edit"))
        assertFalse(tools.toString(), tools.contains("Write"))
        assertTrue(tools.contains("Read"))
        assertTrue(tools.contains("SendMessage"))
        assertTrue(tools.contains("ListAgents"))
    }

    @Test
    fun readOnlyRolesCannotWrite() {
        for (role in listOf(Role.RESEARCHER, Role.REVIEWER)) {
            val tools = Roles.toolsFor(role)
            assertFalse("${role.id} умеет Edit", tools.contains("Edit"))
            assertFalse("${role.id} умеет Write", tools.contains("Write"))
        }
    }

    @Test
    fun implementerCanWrite() {
        val tools = Roles.toolsFor(Role.IMPLEMENTER)
        assertTrue(tools.contains("Edit"))
        assertTrue(tools.contains("Write"))
    }

    @Test
    fun everyRoleKnowsItsSupervisorIsNotHuman() {
        // Без этого агенты задают вопросы в пустоту: их вкладку никто не читает.
        for (role in listOf(Role.IMPLEMENTER, Role.RESEARCHER, Role.REVIEWER)) {
            val prompt = Roles.promptFor(role)
            assertTrue("${role.id}: нет упоминания SendMessage", prompt.contains("SendMessage"))
            assertTrue("${role.id}: не сказано, что вкладку не читают", prompt.contains("не читает"))
        }
    }

    @Test
    fun jsonSurvivesShellQuoting() {
        // Промпты содержат кавычки и переводы строк; они не должны ломать командную строку.
        val json = Roles.agentsJson(Role.REVIEWER, "sonnet")
        val quoted = dev.metajetcore.shell.ShellDialect.POSIX.quoteArgument(json)
        val unquoted = quoted.removeSurrounding("'").replace("""'\''""", "'")
        assertEquals(json, unquoted)
    }

    @Test
    fun rolesResolveById() {
        assertEquals(Role.IMPLEMENTER, Role.fromId("implementer"))
        assertEquals(Role.REVIEWER, Role.fromId("REVIEWER"))
        assertEquals(null, Role.fromId("nope"))
    }
}
