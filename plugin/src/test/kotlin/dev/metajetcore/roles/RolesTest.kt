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
    fun readOnlyRolesCannotEditExistingCode() {
        // Edit нет намеренно: их дело исследовать и вычитывать, менять код — имплементер.
        for (role in listOf(Role.RESEARCHER, Role.REVIEWER)) {
            assertFalse("${role.id} умеет Edit", Roles.toolsFor(role).contains("Edit"))
        }
    }

    @Test
    fun readOnlyRolesCanWriteReports() {
        // Write выдан осознанно: без него отчёт пишется Bash-heredoc'ом и рвётся на длине
        // команды — поймано живым прогоном. Гарантии это не рушит, Bash у них всё равно есть.
        for (role in listOf(Role.RESEARCHER, Role.REVIEWER)) {
            assertTrue("${role.id} не умеет Write", Roles.toolsFor(role).contains("Write"))
            assertTrue(
                "${role.id}: в промпте не сказано, куда класть отчёт",
                Roles.promptFor(role).contains("MJC_REPORTS_DIR"),
            )
        }
    }

    @Test
    fun onlyOrchestratorIsTrulyRestricted() {
        // Единственная роль, у которой запрет на изменение файлов — настоящий.
        val tools = Roles.toolsFor(Role.ORCHESTRATOR)
        assertFalse(tools.toString(), tools.contains("Write"))
        assertFalse(tools.toString(), tools.contains("Edit"))
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
