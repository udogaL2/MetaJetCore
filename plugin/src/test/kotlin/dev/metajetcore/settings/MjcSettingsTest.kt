package dev.metajetcore.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MjcSettingsTest {

    @Test
    fun derivesPrefixFromCamelCase() {
        assertEquals("mjc", MjcSettings.derivePrefix("MetaJetCore"))
        assertEquals("rpa", MjcSettings.derivePrefix("RoomPlanAID"))
    }

    @Test
    fun derivesPrefixFromSeparators() {
        assertEquals("rpa", MjcSettings.derivePrefix("room-plan-aid"))
        assertEquals("mas", MjcSettings.derivePrefix("my_awesome_service"))
    }

    @Test
    fun fallsBackForSingleWordNames() {
        // Одно слово не даёт инициалов — берём первые буквы, лишь бы префикс существовал.
        val prefix = MjcSettings.derivePrefix("backend")
        assertTrue(prefix, prefix.isNotBlank())
        assertTrue(prefix, prefix.all { it.isLetterOrDigit() })
    }

    @Test
    fun neverReturnsEmptyPrefix() {
        // Пустой префикс сделает имена сессий неразличимыми между проектами,
        // а они глобальны на машину.
        for (name in listOf("", "   ", "!!!", "1", "X")) {
            assertTrue("имя '$name'", MjcSettings.derivePrefix(name).isNotBlank())
        }
    }

    @Test
    fun prefixStaysShort() {
        val prefix = MjcSettings.derivePrefix("Some Very Long Project Name With Many Words Indeed")
        assertTrue(prefix, prefix.length <= 5)
    }

    @Test
    fun parsesExtraEnv() {
        val settings = MjcSettings()
        settings.extraEnvRaw = """
            FOO=bar
            # комментарий

            BAZ = qux
            broken-line-without-equals
        """.trimIndent()
        val env = settings.extraEnv()
        assertEquals(mapOf("FOO" to "bar", "BAZ" to "qux"), env)
    }

    @Test
    fun defaultsAreSafe() {
        val settings = MjcSettings()
        // Вычистка ключей включена по умолчанию: иначе агент молча уезжает
        // с подписки на API-биллинг.
        assertTrue(settings.stripApiKeys)
        // Наследуемые маркеры Claude Code тоже вычищаются по умолчанию: иначе агент
        // считает себя вложенным процессом и не регистрируется в реестре сессий.
        assertTrue(settings.stripInheritedClaudeMarkers)
        // Режим прав задан: без него агент стартует в manual mode.
        assertEquals("auto", settings.permissionMode)
        assertEquals("claude", settings.launchCommand)
        // Ожидание должно быть заметно меньше таймаута MCP-вызова на стороне оркестратора.
        assertTrue(settings.readyTimeoutSeconds <= 60)
    }
}
