package dev.metajetcore.roles

import dev.metajetcore.skill.SkillInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RolesTest {

    private val workers = Role.entries - Role.ORCHESTRATOR

    @Test
    fun definitionsHaveNoToolWhitelist() {
        // Белый список tools отрезал Skill и MCP, а гейты tempo работают через скиллы
        // (docs/PLAN-0.4.md §1.6). Вернуть его — сломать tempo, поэтому сторожим формат.
        for (role in Role.entries) {
            val text = RoleInstaller.definitionText(role)
            val frontmatter = text.substringAfter("---\n").substringBefore("\n---")
            assertFalse("${role.id}: $frontmatter", frontmatter.lines().any { it.startsWith("tools:") })
            assertTrue(frontmatter, frontmatter.contains("name: mjc-${role.id}"))
            assertTrue(frontmatter, frontmatter.contains("model: ${role.defaultModel}"))
            assertTrue(frontmatter, frontmatter.contains("НЕ использовать как субагента"))
        }
    }

    @Test
    fun orchestratorSkillDoesNotStripTools() {
        // disallowed-tools мешал оркестратору под /tempo вести PROGRESS и state.
        val frontmatter = SkillInstaller.skillText().substringAfter("---\n").substringBefore("\n---")
        assertFalse(frontmatter, frontmatter.contains("disallowed-tools"))
    }

    @Test
    fun skillMapsEveryTempoRoleToASession() {
        // Мост для tempo: без таблицы оркестратор не знает, какой ролью заменить субагента.
        val skill = SkillInstaller.skillText()
        for (tempoRole in listOf("planner", "auditor", "implementer", "fixer", "gate", "review")) {
            assertTrue("нет роли tempo $tempoRole", skill.contains("`$tempoRole`"))
        }
        assertTrue(skill.contains("дословно"))
        assertTrue(skill.contains("Agent"))
    }

    @Test
    fun workersAnswerInTempoFormat() {
        for (role in workers) {
            val prompt = Roles.promptFor(role)
            assertTrue("${role.id}: нет STATUS", prompt.contains("STATUS: DONE | DONE_WITH_CONCERNS | BLOCKED"))
            assertTrue("${role.id}: нет PASS/FAIL", prompt.contains("STATUS: PASS | FAIL"))
            assertTrue("${role.id}: не сказано не коммитить", prompt.contains("Не коммить"))
            assertTrue("${role.id}: нет MJC_REPORTS_DIR", prompt.contains("MJC_REPORTS_DIR"))
        }
    }

    @Test
    fun readOnlyRolesAreToldNotToChangeCode() {
        // Инструментального запрета больше нет — значит запрет обязан быть словами.
        for (role in listOf(Role.PLANNER, Role.RESEARCHER, Role.REVIEWER)) {
            assertTrue(role.id, Roles.promptFor(role).contains("Код проекта не меняй") ||
                Roles.promptFor(role).contains("Код не меняй"))
        }
    }

    @Test
    fun everyRoleKnowsItsSupervisorIsNotHuman() {
        // Без этого агенты задают вопросы в пустоту: их вкладку никто не читает.
        for (role in workers) {
            val prompt = Roles.promptFor(role)
            assertTrue("${role.id}: нет упоминания SendMessage", prompt.contains("SendMessage"))
            assertTrue("${role.id}: не сказано, что вкладку не читают", prompt.contains("не читает"))
        }
    }

    @Test
    fun orchestratorIsToldToCheckLiveAgentsBeforeSpawning() {
        // Единственный рычаг против «новая задача — новый агент»: плагин отличить дубль от
        // штатной параллельной работы не может, он видит роль и не видит участок
        // (docs/ARCHITECTURE.md §8.1). Значит правило живёт только здесь, и молча пропасть
        // при следующей правке промпта оно не должно.
        val prompt = Roles.promptFor(Role.ORCHESTRATOR)
        assertTrue(prompt, prompt.contains("list_agents"))
        assertTrue(prompt, prompt.contains("участок"))
    }

    @Test
    fun rolesResolveById() {
        assertEquals(Role.IMPLEMENTER, Role.fromId("implementer"))
        assertEquals(Role.REVIEWER, Role.fromId("REVIEWER"))
        assertEquals(Role.PLANNER, Role.fromId("mjc-planner"))
        assertEquals(null, Role.fromId("nope"))
        // В реестре сессий лежит то, что реально применилось, а применяется флаг
        // --agent mjc-researcher, а не CLAUDE_CODE_AGENT=researcher (живой прогон, 2.1.251).
        assertEquals(Role.RESEARCHER, Role.fromId("mjc-researcher"))
    }
}
