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
    fun `every dialect can exit the shell after the agent`() {
        for (dialect in ShellDialect.entries) {
            val tail = dialect.exitWhenDone()
            // Хвост приписывается к готовой команде, поэтому обязан начинаться с
            // разделителя: без него он склеился бы с последним аргументом запуска.
            assertTrue("$dialect: $tail", tail.startsWith(";") || tail.startsWith(" &"))
            assertTrue("$dialect: $tail", tail.endsWith("exit"))
        }
    }

    @Test
    fun `cmd separates commands with an ampersand`() {
        assertEquals("; exit", ShellDialect.POSIX.exitWhenDone())
        assertEquals("; exit", ShellDialect.POWERSHELL.exitWhenDone())
        assertEquals("; exit", ShellDialect.FISH.exitWhenDone())
        assertEquals(" & exit", ShellDialect.CMD.exitWhenDone())
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
