package dev.metajetcore.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellDialectTest {

    @Test
    fun `posix puts env inline before the command`() {
        val line = ShellDialect.POSIX.composeCommand(
            linkedMapOf("A" to "1", "B" to "two"),
            "claude --agent implementer",
        )
        assertEquals("A='1' B='two' claude --agent implementer", line)
    }

    @Test
    fun `posix quoting survives embedded single quotes`() {
        // Через это проходит JSON ролей, в котором кавычки встречаются постоянно.
        val quoted = ShellDialect.POSIX.quoteArgument("""{"a":"it's"}""")
        assertTrue(quoted, quoted.startsWith("'") && quoted.endsWith("'"))
        assertTrue(quoted, quoted.contains("""'\''"""))
    }

    @Test
    fun `powershell uses env prefix syntax`() {
        val line = ShellDialect.POWERSHELL.composeCommand(linkedMapOf("A" to "1"), "claude")
        assertEquals("\$env:A='1'; claude", line)
    }

    @Test
    fun `powershell doubles single quotes`() {
        assertEquals("'it''s'", ShellDialect.POWERSHELL.quoteArgument("it's"))
    }

    @Test
    fun `cmd uses set`() {
        val line = ShellDialect.CMD.composeCommand(linkedMapOf("A" to "1"), "claude")
        assertEquals("set \"A=1\" & claude", line)
    }

    @Test
    fun `fish exports before the command`() {
        val line = ShellDialect.FISH.composeCommand(linkedMapOf("A" to "1"), "claude")
        assertEquals("set -x A '1'; claude", line)
    }

    @Test
    fun `empty env leaves the command untouched`() {
        for (dialect in ShellDialect.entries) {
            assertEquals(dialect.name, "claude", dialect.composeCommand(emptyMap(), "claude"))
        }
    }

    @Test
    fun `unset prefix is empty when nothing to unset`() {
        for (dialect in ShellDialect.entries) {
            assertEquals(dialect.name, "", dialect.unsetPrefix(emptyList()))
        }
    }

    @Test
    fun `unset prefix names every variable`() {
        val prefix = ShellDialect.POSIX.unsetPrefix(listOf("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN"))
        assertTrue(prefix, prefix.contains("ANTHROPIC_API_KEY"))
        assertTrue(prefix, prefix.contains("ANTHROPIC_AUTH_TOKEN"))
        assertTrue(prefix, prefix.trimEnd().endsWith(";"))
    }

    @Test
    fun `detects dialect from shell path`() {
        assertEquals(ShellDialect.POSIX, ShellDialect.detect("/bin/bash"))
        assertEquals(ShellDialect.POSIX, ShellDialect.detect("/usr/bin/zsh"))
        assertEquals(ShellDialect.FISH, ShellDialect.detect("/usr/bin/fish"))
        assertEquals(ShellDialect.POWERSHELL, ShellDialect.detect("C:\\...\\powershell.exe"))
        assertEquals(ShellDialect.POWERSHELL, ShellDialect.detect("/usr/bin/pwsh"))
        assertEquals(ShellDialect.CMD, ShellDialect.detect("C:\\Windows\\system32\\cmd.exe"))
    }

    @Test
    fun `composed command never leaks unquoted json`() {
        // Регрессия: неэкранированный JSON ломает командную строку и агент стартует без роли.
        val json = """{"implementer":{"prompt":"a b \"c\"","tools":["Read"]}}"""
        val line = ShellDialect.POSIX.composeCommand(
            linkedMapOf("CLAUDE_CODE_SESSION_NAME" to "mjc-impl"),
            "claude --agents ${ShellDialect.POSIX.quoteArgument(json)} --agent implementer",
        )
        assertTrue(line, line.contains("--agents '"))
        assertFalse("двойные кавычки не должны требовать экранирования в posix-строке", line.contains("\\\""))
    }
}
