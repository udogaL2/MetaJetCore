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

    /** Флаг в команде запуска: после `/exit` процесс не завершается (только POSIX). */
    const val LINGER_FLAG = "--fake-linger"

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
        # mjcParent* — не поля реестра Claude Code: так тест видит, какую связь с родителем
        # агент получил в окружении.
        printf '{"pid":%s,"sessionId":"11111111-2222-4333-8444-555555555555","cwd":"%s","name":"%s","agent":"%s","status":"idle","version":"fake","mjcParent":"%s","mjcParentSession":"%s"}' \
            "${'$'}${'$'}" "${'$'}(pwd)" "${'$'}CLAUDE_CODE_SESSION_NAME" "${'$'}CLAUDE_CODE_AGENT" "${'$'}MJC_PARENT" "${'$'}MJC_PARENT_SESSION" > "${'$'}FILE"
        while IFS= read -r LINE; do
            case "${'$'}LINE" in
                */exit*) break ;;
            esac
        done
        rm -f "${'$'}FILE"
        # Режим «завис после выхода»: сессия из реестра ушла, а процесс остался — так
        # выглядит агент, оставивший живого потомка. Плагин обязан добить такое дерево сам.
        for ARG in "${'$'}@"; do
            if [ "${'$'}ARG" = "$LINGER_FLAG" ]; then sleep 600; fi
        done
    """.trimIndent()
}

/**
 * Подставной `codex`: пишет ленту сессии так, как её пишет Codex 0.166 (`session_meta`, затем
 * `task_started` / `task_complete` на каждый ход) и отвечает на `queue` новым ходом. Сам не
 * выходит: плагин завершает Codex сигналом, как и настоящий. Ответ хода — `ECHO: <текст>`, чтобы тест видел, что доехало именно его сообщение.
 *
 * Только POSIX: проверяется не Codex, а наш цикл — поиск ленты, ящик, доставка, закрытие.
 */
object FakeCodex {

    class Installed(val command: String, val home: Path)

    fun install(): Installed {
        val home = Files.createTempDirectory("mjc-fake-codex")
        Files.createDirectories(home.resolve("sessions/2026/11/04"))
        val script = home.resolve("codex.sh")
        Files.write(script, script().toByteArray())
        return Installed("bash $script $home", home)
    }

    private fun script(): String = """
        #!/usr/bin/env bash
        HOME_DIR="${'$'}1"; shift
        DIR="${'$'}HOME_DIR/sessions/2026/11/04"
        esc() { printf '%s' "${'$'}1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | tr '\n' ' '; }
        turn() {
            printf '{"type":"event_msg","payload":{"type":"task_started"}}\n' >> "${'$'}1"
            printf '{"type":"event_msg","payload":{"type":"task_complete","last_agent_message":"ECHO: %s"}}\n' "${'$'}(esc "${'$'}2")" >> "${'$'}1"
        }
        if [ "${'$'}1" = "queue" ]; then
            # codex queue --thread <id> --message <text>
            ID="${'$'}3"; MSG="${'$'}5"
            turn "${'$'}(ls "${'$'}DIR"/rollout-*-"${'$'}ID".jsonl)" "${'$'}MSG"
            exit 0
        fi
        for ARG in "${'$'}@"; do TASK="${'$'}ARG"; done
        ID="fake-${'$'}${'$'}"
        FILE="${'$'}DIR/rollout-2026-11-04T00-00-00-${'$'}ID.jsonl"
        printf '{"type":"session_meta","payload":{"id":"%s","cwd":"%s"}}\n' "${'$'}ID" "${'$'}(esc "${'$'}(pwd)")" > "${'$'}FILE"
        turn "${'$'}FILE" "${'$'}TASK"
        while IFS= read -r LINE; do :; done
    """.trimIndent()
}

/**
 * Подставной `b24-cowork-code-cli`: пишет ленту так, как её пишет b24 0.26.7
 * (EXPERIMENT-B24-CLI.md §4) — `session_start`, затем на каждое сообщение ход
 * `user` → `message_start` → `assistant` → `message_stop`, — и опрашивает input-файл.
 *
 * Ответ хода — `ECHO: <текст>`. Сообщение со словом «медленно» идёт ходом с инструментом: первое
 * сообщение кончается `tool_use`, а ответ приходит вторым через 2 с — так тест видит, что плагин
 * не считает ход законченным раньше времени и держит очередь. `/clear` событий не даёт, как у
 * настоящего, но следующий ответ помечается. Что пришло в argv, фейк кладёт в `seen.json`
 * рядом с лентой. Сам не выходит: плагин завершает b24 сигналом.
 *
 * python3, а не bash: фейку надо разбирать JSON команд. Только POSIX.
 */
object FakeB24 {

    class Installed(val command: String, val channels: Path)

    fun install(): Installed {
        val dir = Files.createTempDirectory("mjc-fake-b24")
        val script = dir.resolve("b24.py")
        Files.write(script, SCRIPT.toByteArray())
        return Installed("python3 $script", Files.createDirectories(dir.resolve("channels")))
    }

    private val SCRIPT = """
        import json, os, sys, time
        args = sys.argv[1:]
        def opt(name):
            return args[args.index(name) + 1] if name in args else None
        events, inp = opt("--json-file"), opt("--input-file")
        task, sid = opt("-i"), opt("--session-id")
        with open(os.path.join(os.path.dirname(events), "seen.json"), "w") as f:
            json.dump({"model": opt("-m"), "approval": opt("--approval-mode"),
                       "prompt": opt("--append-system-prompt") or ""}, f, ensure_ascii=False)
        def emit(obj):
            with open(events, "a", encoding="utf-8") as f:
                f.write(json.dumps(obj, ensure_ascii=False) + "\n")
        def stream(kind):
            emit({"type": "stream_event", "event": {"type": kind}})
        def say(text, stop_reason=None):
            emit({"type": "assistant", "message": {"content": [{"type": "text", "text": text}], "stop_reason": stop_reason}})
        cleared = False
        def turn(text):
            global cleared
            emit({"type": "user", "message": {"content": [{"type": "text", "text": text}]}})
            reply = ("ECHO(после /clear): " if cleared else "ECHO: ") + text
            cleared = False
            if "медленно" in text:
                stream("message_start")
                say("Делаю.")
                emit({"type": "assistant", "message": {"content": [{"type": "tool_use", "id": "t1", "name": "write_file", "input": {}}], "stop_reason": "tool_use"}})
                stream("message_stop")
                time.sleep(2)
                emit({"type": "user", "message": {"content": [{"type": "tool_result", "tool_use_id": "t1", "content": "ok"}]}})
            stream("message_start")
            say(reply)
            stream("message_stop")
        emit({"type": "system", "subtype": "session_start", "session_id": sid, "data": {"session_id": sid}})
        if task:
            turn(task)
        offset = os.path.getsize(inp)
        while True:
            time.sleep(0.2)
            with open(inp, "rb") as f:
                f.seek(offset)
                chunk = f.read()
            end = chunk.rfind(b"\n")
            if end < 0:
                continue
            offset += end + 1
            for line in chunk[:end].decode("utf-8").splitlines():
                cmd = json.loads(line)
                if cmd.get("type") != "submit":
                    continue
                if cmd["text"] == "/clear":
                    cleared = True
                else:
                    turn(cmd["text"])
    """.trimIndent()
}
