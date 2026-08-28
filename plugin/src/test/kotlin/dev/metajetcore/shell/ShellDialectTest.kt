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
    fun `powershell uses env prefix syntax`() {
        val line = ShellDialect.POWERSHELL.composeCommand(linkedMapOf("A" to "1"), "claude")
        assertEquals("\$env:A='1'; claude", line)
    }

    @Test
    fun `powershell doubles single quotes in env values`() {
        val line = ShellDialect.POWERSHELL.composeCommand(linkedMapOf("A" to "it's"), "claude")
        assertEquals("\$env:A='it''s'; claude", line)
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
    fun `purge is empty when there is nothing to purge`() {
        for (dialect in ShellDialect.entries) {
            assertEquals(dialect.name, "", dialect.purgeByPrefix(emptyList()))
            assertEquals(dialect.name, "", dialect.unsetNames(emptyList()))
        }
    }

    @Test
    fun `posix purge covers unknown variables by prefix`() {
        // Смысл префиксной вычистки: она снимает и те переменные, о которых мы не знаем.
        // Поимённый список устаревал бы с каждой новой версией Claude Code.
        val purge = ShellDialect.POSIX.purgeByPrefix(listOf("CLAUDE", "ANTHROPIC_"))
        assertTrue(purge, purge.startsWith("unset "))
        assertTrue(purge, purge.contains("CLAUDE|ANTHROPIC_"))
        assertTrue(purge, purge.contains("env |"))
        assertTrue(purge, purge.trimEnd().endsWith(";"))
    }

    @Test
    fun `powershell purge enumerates the environment`() {
        val purge = ShellDialect.POWERSHELL.purgeByPrefix(listOf("CLAUDE", "ANTHROPIC_"))
        assertTrue(purge, purge.contains("Get-ChildItem Env:"))
        assertTrue(purge, purge.contains("'CLAUDE*'"))
        assertTrue(purge, purge.contains("'ANTHROPIC_*'"))
        assertTrue(purge, purge.contains("SilentlyContinue"))
    }

    @Test
    fun `cmd cannot purge by prefix and says so`() {
        // cmd.exe не умеет перечислить окружение одной строкой — для него остаётся список имён.
        assertFalse(ShellDialect.CMD.supportsPrefixPurge)
        for (dialect in listOf(ShellDialect.POSIX, ShellDialect.POWERSHELL, ShellDialect.FISH)) {
            assertTrue(dialect.name, dialect.supportsPrefixPurge)
        }
        val named = ShellDialect.CMD.unsetNames(listOf("ANTHROPIC_API_KEY"))
        assertTrue(named, named.contains("ANTHROPIC_API_KEY"))
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

}
