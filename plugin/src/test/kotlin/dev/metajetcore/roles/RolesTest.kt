package dev.metajetcore.roles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RolesTest {

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
    fun rolesResolveById() {
        assertEquals(Role.IMPLEMENTER, Role.fromId("implementer"))
        assertEquals(Role.REVIEWER, Role.fromId("REVIEWER"))
        assertEquals(null, Role.fromId("nope"))
    }
}
