# Эксперименты: b24-cowork-code-cli как харнес и кириллица в терминале

Проверено 2026-11-09 на macOS, b24-cowork-code-cli 0.26.7 (форк Qwen Code), PyCharm 2026.3 RC
(263.7307.101). Цель — понять, можно ли вести b24 из плагина так же, как Claude Code и Codex:
полноценная сессия во вкладке IDE, задачи от оркестратора, ответы обратно.

## 1. Канал «оркестратор → b24»: `--input-file`

Флаги из документации пакета (`dist/bundled/qc-helper/docs/features/dual-output.md`):

- `--input-file <path>` — TUI опрашивает файл (`fs.watchFile`, 500 мс) и исполняет JSONL-команды:
  `{"type":"submit","text":"…"}` и `{"type":"confirmation_response","request_id":"…","allowed":true}`.
  Файл — обычный, не FIFO (смотрится `stat.size`).
- `--json-file <path>` — параллельно с TUI пишет события в формате `stream-json`. Работает под
  pty, в отличие от `--json-fd` (pty закрывает дескрипторы ≥ 3).

Прогон: TUI под pty (python `pty.fork`), `--approval-mode plan`, оба файла в рабочем каталоге.

| Что проверяли | Результат |
|---|---|
| Кириллица в `submit` | доезжает без потерь, ответ модели тоже кириллицей |
| `submit` в простое | отдельный ход: событие `user`, затем `assistant`, затем `stream_event/message_stop` |
| `submit` во время ответа | **вливается в текущий ход**: своего события `user` нет, один `message_stop` на оба ответа |
| Событие `result` | в интерактивном режиме **не приходит**; конец хода — `message_stop` |
| Первое событие | `system/session_start` с `session_id`, `protocol_version: 2`, `supported_events` |

Поведение во время ответа расходится с документацией («submit ставится в очередь до простоя»).
В бандле (`chunk-A7FCDM5E.js`, `startInteractiveUI-*.js`) видно почему: обработчик подключён как
`setSubmitFn(text => { addMessage(text); return true })` — всегда `true`, поэтому ветка «TUI занят,
повторить позже» в `RemoteInputWatcher.processQueue` не срабатывает никогда, а `addMessage` — тот
же путь, что ввод с клавиатуры посреди ответа.

Выводы для адаптера:

- задачи слать через `--input-file`, а не в терминал; ожидать простоя по `message_stop`, если
  нужно, чтобы сообщение было отдельным ходом (иначе оно молча вливается в текущий);
- ответы читать из `--json-file`: текстовые блоки `assistant` до `message_stop` — это и есть
  `last_agent_message` в терминах Codex. Отдельный MCP-инструмент `report` для ответа не нужен;
- вопросы о правах приходят `control_request` и закрываются `confirmation_response` из того же
  файла — оркестратор может решать их сам либо оставить человеку в TUI.

Попутно: у b24 есть собственный межсессионный обмен (`peerMessaging`, сокет-inbox и реестр
сессий, `chunk-XYH55I4Y.js`) — аналог `SendMessage`. Не исследован; если окажется доступным
снаружи, может заменить файловый канал.

## 2. Кириллица, напечатанная плагином в терминал

Прежний вывод (`ARCHITECTURE.md` §2.6, §2.7.1): кириллица во вкладку приезжает битой, bracketed
paste не помогает, поэтому `brief_agent` отклоняет не-ASCII. Судя по §2.6 (`cmd /c`, `--%`,
`windows-1251` в опциях JVM), те прогоны шли на Windows.

На macOS проверено платформенным тестом: вкладка с `cat > файл`, затем `Terminal.send` строки
`Привет ёжик` обычным вводом и с `useBracketedPasteMode()`. В файле в обоих случаях корректный
UTF-8 (`d0 9f d1 80 …`). У JVM тестовой IDE `file.encoding`, `sun.jnu.encoding` и
`native.encoding` — `UTF-8`.

Значит, порча — свойство кодировки JVM, а не терминала. Варианты, не реализованы:

- разрешать не-ASCII в `brief_agent`, когда `sun.jnu.encoding`/`native.encoding` — UTF-8;
- для b24 вопрос снимается сам: задачи идут через `--input-file`, мимо pty.

Живой проверки с Claude Code TUI на macOS не было: байты в pty те же, но вставка многострочного
текста в TUI отдельно не проверялась.

## 3. Запуск так, как его сделал бы плагин

Прогон в каталоге проекта: `zsh -l -c 'exec b24-cowork-code-cli --session-id <uuid>
--approval-mode plan --json-file … --input-file … --append-system-prompt "$MJC_ROLE_PROMPT"
-i "$MJC_TASK"'`, промпт роли и задача — в окружении.

- `session_start` пришёл с переданным `session_id`.
- Промпт роли (многострочный, кириллица, обе кавычки, `$HOME`) применился: каждый ответ
  начинался с требуемого маркера.
- Задача из `-i` выполнена первым ходом.
- `submit "/clear"` сбросил контекст (следующий вопрос о сказанном ранее — «НЕ ЗНАЮ»);
  событий в ленте `/clear` не даёт.
- SIGTERM — выход за 0,3 с; `session_end` не записан.
- Вопроса о доверии к каталогу не было.

## 4. Этап 0 плана: b24 в NON_SHELL-вкладке IDE

Платформенный тест (PyCharm 263.7307.101, b24 0.26.7-b24.12): вкладка открыта `Terminal.openTab`
с `command = zsh -l -i -c 'exec b24-cowork-code-cli … --approval-mode default
-m bitrix/bitrixgpt-6.5-agent …'`, задача — создать файл.

- TUI рисуется во вкладке нормально, `readScreen` отдаёт осмысленный экран: диалог «Apply this
  change?» во время запроса, итог хода и строка статуса с моделью после.
- `-m bitrix/bitrixgpt-6.5-agent` применился (`message_start.message.model`). Список моделей —
  `~/.b24-cowork-code/settings.json` → `modelProviders.openai[].id`, текущая — `model.name`.
- `--approval-mode default` на запись файла даёт `control_request` (`tool_name: write_file`).
  `confirmation_response` из input-файла закрывает диалог в TUI, приходит `control_response`
  `success`, файл создан.
- SIGTERM дереву процессов — вкладка закрылась за 0,2–0,3 с.

**`message_stop` — конец сообщения модели, а не хода.** Ход с инструментом:

```
user(text) → message_start → assistant(thinking) → assistant(text) → assistant(tool_use, stop_reason=tool_use)
→ message_stop → control_request → control_response → user(tool_result)
→ message_start → assistant(thinking) → assistant(text, stop_reason=null) → message_stop
```

Значит, ход закончен на `message_stop`, если в сообщении не было `tool_use` (у такого
`assistant` `stop_reason = "tool_use"`). Ответ хода — `text`-блоки последнего сообщения.
Прогоны §1–3 этого не показали: там ходы были без инструментов, из одного сообщения.

## 5. Этап 4: живой прогон через MCP-инструменты плагина

Платформенный тест (PyCharm 263.7307.101, b24 0.26.7-b24.12, модель
`bitrix/bitrixgpt-6.5-agent`, режим прав по умолчанию плагина — `auto`) вызывал инструменты так,
как их вызывает оркестратор:

| Шаг | Результат |
|---|---|
| `spawn_agent(harness=b24)` с задачей «столица Франции одним словом» | вкладка открыта, «задача уже передана» |
| `await_messages` | `Париж` |
| `list_agents` | `статус=idle` |
| `send_to_agent` «повтори: ёжик в тумане» | `отправлено`; `await_messages` → `ёжик в тумане` |
| `reset_agent` | `контекст сброшен` |
| `send_to_agent` «какой город ты называл?» | в очереди (пауза после `/clear`); `await_messages` → `НЕ ЗНАЮ` — контекст пуст |
| `read_tab` | экран TUI с вопросом и ответом |
| `close_agent` | вкладка закрыта, процесса нет, каталог канала удалён |

Найдено и исправлено: ответ `send_to_agent` для сообщения в очереди обещал «ответ на текущий
ход и на это сообщение», а после сброса идущего хода нет и `/clear` ответа не даёт —
оркестратор ждал бы лишний ответ. Формулировка теперь не обещает ответа на текущий ход.
