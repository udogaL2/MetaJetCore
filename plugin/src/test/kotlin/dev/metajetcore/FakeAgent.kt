package dev.metajetcore

import dev.metajetcore.shell.ShellDialect
import java.nio.file.Files
import java.nio.file.Path

/**
 * Подставной `claude` для тестов: пишет запись в реестр сессий и убирает её по `/exit`.
 *
 * Зачем не настоящий Claude Code: тест не должен ходить в сеть, тратить лимиты подписки и
 * зависеть от версии CLI. А проверить надо не его, а наш цикл — открылась ли вкладка, доехала
 * ли команда, доехали ли переменные окружения, дожимается ли Enter, доходят ли `/clear` и
 * `/exit` до уже запущенного процесса.
 *
 * Две вещи, на которых стенд уже один раз обжёгся и которые поэтому обязательны:
 *
 *  * запись содержит **настоящий pid** процесса-агента. Сопоставление вкладки идёт по дереву
 *    процессов, и с выдуманным pid эта ветка не проверяется вовсе;
 *  * имя берётся из `CLAUDE_CODE_SESSION_NAME`, то есть заодно проверяется, что окружение
 *    доехало через API вкладки: не доехало бы — запись появилась бы без имени, и спавн
 *    отвалился бы по таймауту.
 */
object FakeAgent {

    /** Готовый к печати в терминал вызов подставного агента. */
    class Installed(val command: String, val registryDirectory: Path)

    fun install(dialect: ShellDialect): Installed {
        val home = Files.createTempDirectory("mjc-fake-agent")
        val registry = home.resolve("sessions").also { Files.createDirectories(it) }
        val slashes = { path: Path -> path.toString().replace('\\', '/') }

        return if (dialect == ShellDialect.CMD || dialect == ShellDialect.POWERSHELL) {
            val script = home.resolve("agent.ps1")
            Files.write(script, powershellScript().toByteArray())
            // PowerShell, а не .cmd: в батнике не узнать собственный pid, а он тут нужен.
            // Прямые слэши — их принимают и PowerShell, и bash, в отличие от обратных.
            Installed(
                "powershell -NoProfile -ExecutionPolicy Bypass -File ${slashes(script)} ${slashes(registry)}",
                registry,
            )
        } else {
            val script = home.resolve("agent.sh")
            Files.write(script, posixScript().toByteArray())
            script.toFile().setExecutable(true)
            // Через `bash <путь>`, и путь с прямыми слэшами: на Windows терминал IDE
            // открывает Git Bash, где `C:\Users\...` рассыпается на экранирования, а бит
            // исполняемости не выставляется.
            Installed("bash ${slashes(script)} ${slashes(registry)}", registry)
        }
    }

    /**
     * Пути в JSON — с прямыми слэшами: обратный слэш там начинает escape-последовательность,
     * и запись просто не распарсилась бы.
     */
    private fun powershellScript(): String = """
        # Хвост вроде `--agent mjc-researcher --permission-mode auto` дописывает плагин, и
        # настоящий claude его понимает. Скрипту он не нужен, но принять его обязан: иначе
        # PowerShell отвергает вызов целиком, а выглядит это как «агент молча не поднялся».
        param([string]${'$'}Registry, [Parameter(ValueFromRemainingArguments = ${'$'}true)]${'$'}Rest)
        trap { ${'$'}_ | Out-File -FilePath (Join-Path ${'$'}Registry "error.txt") -Encoding utf8; break }
        ${'$'}file = Join-Path ${'$'}Registry "${'$'}PID.json"
        ${'$'}record = @{
            pid = ${'$'}PID
            sessionId = "11111111-2222-4333-8444-555555555555"
            cwd = (Get-Location).Path.Replace('\', '/')
            name = ${'$'}env:CLAUDE_CODE_SESSION_NAME
            agent = ${'$'}env:CLAUDE_CODE_AGENT
            status = "idle"
            version = "fake"
        } | ConvertTo-Json -Compress
        # Без BOM: `Set-Content -Encoding utf8` в PowerShell 5.1 его добавляет, а настоящий
        # Claude Code пишет реестр чистым UTF-8 — стенд должен вести себя так же.
        [System.IO.File]::WriteAllText(${'$'}file, ${'$'}record, (New-Object System.Text.UTF8Encoding ${'$'}false))
        while (${'$'}true) {
            ${'$'}line = [Console]::In.ReadLine()
            if (${'$'}line -eq ${'$'}null) { break }
            if (${'$'}line.Trim() -eq "/exit") { break }
        }
        Remove-Item -Path ${'$'}file -ErrorAction SilentlyContinue
    """.trimIndent().replace("\n", "\r\n")

    private fun posixScript(): String = """
        #!/usr/bin/env bash
        REG="${'$'}1"
        FILE="${'$'}REG/${'$'}${'$'}.json"
        printf '{"pid":%s,"sessionId":"11111111-2222-4333-8444-555555555555","cwd":"%s","name":"%s","agent":"%s","status":"idle","version":"fake"}' \
            "${'$'}${'$'}" "${'$'}(pwd)" "${'$'}CLAUDE_CODE_SESSION_NAME" "${'$'}CLAUDE_CODE_AGENT" > "${'$'}FILE"
        while IFS= read -r LINE; do
            case "${'$'}LINE" in
                */exit*) break ;;
            esac
        done
        rm -f "${'$'}FILE"
    """.trimIndent()
}
