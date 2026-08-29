package dev.metajetcore

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.metajetcore.terminal.Terminal
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Диагностический тест: выясняет, можно ли вообще управлять шеллом во вкладке из headless-теста.
 *
 * Не проверка поведения плагина, а замер среды. Держим отдельно и с говорящими сообщениями:
 * если стенд однажды перестанет работать, разбираться придётся именно здесь.
 */
class TerminalProbeTest : BasePlatformTestCase() {

    fun testShellAcceptsALongCommand() {
        // Гипотеза: ломается длина. Наша команда запуска несёт снятие унаследованных
        // маркеров, и на десяти переменных в синтаксисе PowerShell это ~900 символов.
        registerTerminalToolWindow()
        val marker = Files.createTempDirectory("mjc-probe-long").resolve("marker.txt")
        val handle = Terminal.openTab(
            project = project,
            name = "mjc-probe-long",
            workingDirectory = Paths.get(System.getProperty("java.io.tmpdir")),
            env = emptyMap(),
        ) ?: throw AssertionError("вкладка не создалась")
        awaitPumpingEdt("шелл поднялся") { Terminal.isRunning(handle) }

        val noise = (1..10).joinToString("") {
            "Remove-Item Env:MJC_NO_SUCH_VARIABLE_$it -ErrorAction SilentlyContinue; "
        }
        val path = marker.toString().replace('\\', '/')
        val command = noise + "echo mjc-ok > $path"
        Terminal.send(handle, command, execute = true)

        val appeared = try {
            awaitPumpingEdt("маркерный файл появился", timeoutMs = 20_000) { Files.exists(marker) }
            true
        } catch (_: AssertionError) {
            false
        }
        val screen = Terminal.readScreen(handle)
        Terminal.closeTab(project, handle)

        assertTrue(
            "команда длиной ${command.length} не выполнилась.\nscreen:\n$screen",
            appeared,
        )
    }

    private fun registerTerminalToolWindow() {
        val manager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
        if (manager.getToolWindow("Terminal") != null) return
        manager.registerToolWindow(
            "Terminal",
            true,
            com.intellij.openapi.wm.ToolWindowAnchor.BOTTOM,
            testRootDisposable,
            true,
        )
    }

    fun testShellAcceptsACommand() {
        com.intellij.openapi.wm.ToolWindowManager.getInstance(project).registerToolWindow(
            "Terminal",
            true,
            com.intellij.openapi.wm.ToolWindowAnchor.BOTTOM,
            testRootDisposable,
            true,
        )

        val marker = Files.createTempDirectory("mjc-probe").resolve("marker.txt")
        val handle = Terminal.openTab(
            project = project,
            name = "mjc-probe",
            workingDirectory = Paths.get(System.getProperty("java.io.tmpdir")),
            env = emptyMap(),
        ) ?: throw AssertionError("вкладка не создалась")

        awaitPumpingEdt("шелл поднялся") { Terminal.isRunning(handle) }

        val process = Terminal.process(handle)
        val grid = Terminal.gridSizeForDiagnostics(handle)
        val integration = Terminal.isShellIntegrationReady(handle)

        // Простейшая команда, результат которой виден на файловой системе, а не на экране:
        // экран в headless-режиме может врать, файл — нет.
        val path = marker.toString().replace('\\', '/')
        Terminal.send(handle, "echo mjc-ok > $path", execute = true)

        val appeared = try {
            awaitPumpingEdt("маркерный файл появился", timeoutMs = 20_000) { Files.exists(marker) }
            true
        } catch (_: AssertionError) {
            false
        }

        val report = buildString {
            append("shell=").append(process?.shellCommand).append('\n')
            append("pid=").append(process?.pid).append('\n')
            append("grid=").append(grid).append('\n')
            append("shellIntegration=").append(integration).append('\n')
            append("marker=").append(appeared).append('\n')
            append("screen:\n").append(Terminal.readScreen(handle))
        }
        Terminal.closeTab(project, handle)

        assertTrue("шелл не выполнил команду.\n$report", appeared)
    }
}
