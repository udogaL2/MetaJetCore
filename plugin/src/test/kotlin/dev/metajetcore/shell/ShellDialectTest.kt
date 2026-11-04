package dev.metajetcore.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellDialectTest {

    @Test
    fun `nothing to unset means nothing is printed`() {
        for (dialect in ShellDialect.entries) {
            assertEquals(dialect.name, "", dialect.unsetNames(emptyList()))
        }
    }

    @Test
    fun `every dialect can unset by name`() {
        val names = listOf("CLAUDE_CODE_MESSAGING_SOCKET", "ANTHROPIC_API_KEY")
        for (dialect in ShellDialect.entries) {
            val line = dialect.unsetNames(names)
            for (name in names) {
                assertTrue("$dialect: $line", line.contains(name))
            }
            // Команда обязана заканчиваться разделителем: сразу за ней печатается запуск.
            assertTrue("$dialect: $line", line.trimEnd().endsWith(";") || line.trimEnd().endsWith("&"))
        }
    }

    @Test
    fun `posix uses unset`() {
        assertEquals(
            "unset CLAUDECODE CLAUDE_PID; ",
            ShellDialect.POSIX.unsetNames(listOf("CLAUDECODE", "CLAUDE_PID")),
        )
    }

    @Test
    fun `powershell removes environment items`() {
        val line = ShellDialect.POWERSHELL.unsetNames(listOf("CLAUDECODE"))
        assertEquals("Remove-Item Env:CLAUDECODE -ErrorAction SilentlyContinue; ", line)
    }

    @Test
    fun `posix runs a simple command through exec in a login shell`() {
        assertEquals(
            listOf("/bin/zsh", "-l", "-i", "-c", "unset A; exec claude --agent mjc-impl"),
            ShellDialect.POSIX.argv("/bin/zsh", "unset A; ", "claude --agent mjc-impl"),
        )
    }

    @Test
    fun `compound command is not prefixed with exec`() {
        // exec приклеился бы к первой части, и `claude` не запустился бы вовсе.
        val argv = ShellDialect.POSIX.argv("/bin/bash", "", "nvm use 20 && claude")
        assertEquals("nvm use 20 && claude", argv.last())
    }

    @Test
    fun `powershell and cmd get their own switches`() {
        assertEquals(
            listOf("pwsh", "-NoLogo", "-Command", "claude"),
            ShellDialect.POWERSHELL.argv("pwsh", "", "claude"),
        )
        assertEquals(
            listOf("cmd.exe", "/c", "set A= & claude"),
            ShellDialect.CMD.argv("cmd.exe", "set A= & ", "claude"),
        )
    }

    @Test
    fun `simple command detection is conservative`() {
        assertTrue(ShellDialect.isSimple("claude --agent mjc-impl --permission-mode acceptEdits"))
        for (compound in listOf("a; b", "a && b", "a | b", "\$HOME/bin/claude", "a > log", "")) {
            assertTrue(compound, !ShellDialect.isSimple(compound))
        }
    }

    @Test
    fun `detects dialect from shell path`() {
        assertEquals(ShellDialect.POSIX, ShellDialect.detect("/bin/bash"))
        assertEquals(ShellDialect.POSIX, ShellDialect.detect("/usr/bin/zsh"))
        assertEquals(ShellDialect.POSIX, ShellDialect.detect("C:\\Program Files\\Git\\usr\\bin\\bash.exe"))
        assertEquals(ShellDialect.FISH, ShellDialect.detect("/usr/bin/fish"))
        assertEquals(ShellDialect.POWERSHELL, ShellDialect.detect("C:\\...\\powershell.exe"))
        assertEquals(ShellDialect.POWERSHELL, ShellDialect.detect("/usr/bin/pwsh"))
        assertEquals(ShellDialect.CMD, ShellDialect.detect("C:\\Windows\\system32\\cmd.exe"))
    }

    @Test
    fun `unknown shell falls back to the platform default`() {
        val expected = if (ShellDialect.isWindows()) ShellDialect.POWERSHELL else ShellDialect.POSIX
        assertEquals(expected, ShellDialect.detect(null))
        assertEquals(expected, ShellDialect.detect(""))
    }
}
