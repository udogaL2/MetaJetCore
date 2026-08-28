# MetaJetCore plugin — контракт

Дополнение к `docs/ARCHITECTURE.md`. Здесь только то, что нужно для реализации.

Плагин для JetBrains IDE 2026.1+, Kotlin. Поднимает локальный MCP-сервер, к которому
подключается сессия-оркестратор.

Каждый агент — **полноценная отдельная сессия Claude Code** в своей вкладке. Субагенты
(инструмент `Agent`) в схеме не участвуют.

```xml
<depends>com.intellij.modules.platform</depends>
<depends>org.jetbrains.plugins.terminal</depends>
```

---

## 1. MCP-инструменты

### `spawn_agent`

```
spawn_agent(
    role:    "implementer" | "researcher" | "reviewer" | "orchestrator",
    parent:  string,          # имя сессии-оркестратора (первая строка ListAgents)
    task:    string,          # брифинг, впечатывается после старта
    name?:   string,          # по умолчанию <prefix>-<role-короткое>
    model?:  string,          # по умолчанию из таблицы ролей
    split?:  "right" | "down" # по умолчанию "right"
) -> { name, sessionId, tabId, pid }
```

Последовательность — `ARCHITECTURE.md §4`. Существенное:

1. Найти `EditorWindow` родителя по `parent` (см. §3 ниже).
2. Создать вкладку терминала в сплите от этого окна.
3. Окружение вкладки:
   ```
   CLAUDE_CODE_SESSION_NAME = <name>
   ANTHROPIC_MODEL          = <model>
   MJC_TAB                  = <uuid>
   CLAUDE_CODE_AGENT        = <role>   # только метка в реестре, роль НЕ задаёт
   ```
   и **удалить** `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN` из унаследованного env.
4. `sendText(<launch>, shouldExecute = true)`, где `<launch>` зависит от `roleDelivery`:
   - `inline` (по умолчанию) — `<launchCommand> --agents '<json>' --agent <role>`.
     JSON ролей плагин держит у себя, целевой проект не трогается вообще.
   - `flag` — `<launchCommand> --agent <role>`, определения берутся из `.claude/agents/`.
   - `message` — `<launchCommand>` без флагов; роль уходит первой строкой на шаге 6,
     ограничение `tools` при этом **не применяется**.

   Роль — единственное, что нельзя передать окружением (`ARCHITECTURE.md §2.2.1`).
5. Ждать появления `~/.claude/sessions/*.json` с `name == <name>`, таймаут из настроек.
   Из файла взять `pid` и `sessionId`. **Фактическое имя брать оттуда же** — при коллизии
   платформа переименует сессию в вариант.
6. `sendText(task, shouldExecute = true)`. В режиме `message` перед задачей добавить строку:
   `Прочитай .claude/agents/<role>.md — это твоя роль на всю сессию, следуй ей.`

Ошибки: таймаут готовности, родитель не найден, вкладка не создалась — возвращать текстом,
не бросать.

### `close_agent(name) -> { closed: bool }`

`sendText("/exit", execute)` → ждать исчезновения записи из реестра → закрыть вкладку.
Никогда не убивать процесс: останется запись в реестре и мёртвый пайп, `ListAgents` будет
показывать призрака.

### `reset_agent(name, task) -> { ok }`

`sendText("/clear")` → `sendText(task)`. Для перехода между этапами: контекст предыдущего
этапа больше не нужен, а вкладка и сессия остаются.

### `restart_agent(name, task) -> { name, sessionId, tabId, pid }`

`close_agent` + `spawn_agent` с теми же ролью и родителем. Для сломанных сессий.

### `list_agents() -> [{ name, role, sessionId, pid, status, cwd, tabId }]`

Чтение реестра с фильтром по `cwd` проекта, обогащённое картой вкладок.
`role` — из поля `agent` реестра.

### `focus_agent(name) -> { ok }`

Активировать вкладку агента.

---

## 2. Настройки плагина

| Ключ | По умолчанию | Назначение |
|---|---|---|
| `launchCommand` | `claude` | обёртка пользователя |
| `roleDelivery` | `inline` | `inline` / `flag` / `message` — см. `ARCHITECTURE.md §4.0` |
| `roleDefinitions` | встроенные | JSON для `--agents`, редактируется в настройках плагина |
| `envOverrides` | `{}` | дополнительные переменные |
| `namePrefix` | аббревиатура проекта | `MetaJetCore` → `mjc` |
| `readyTimeoutSec` | `120` | ожидание записи в реестре |
| `stripApiKeys` | `true` | вычищать `ANTHROPIC_API_KEY` / `ANTHROPIC_AUTH_TOKEN` |

---

## 3. Карта вкладок

Терминал живёт в editor area: `LightVirtualFile` + собственный `FileEditorProvider` с
терминальным виджетом внутри. В Terminal tool window публичного API для сплита нет.

```kotlin
val fem = FileEditorManagerEx.getInstanceEx(project)
val parentWindow = fem.windows.first { it.getFileList().contains(parentFile) }
parentWindow.split(SwingConstants.VERTICAL, true, agentFile, true)
```

Сопоставление «имя сессии → вкладка»:

1. **Основной путь.** Вкладки, созданные плагином, помечены `MJC_TAB`; плагин ведёт карту
   `MJC_TAB → EditorWindow` и `name → MJC_TAB`.
2. **Fallback** для сессий, запущенных руками: терминальный виджет знает pid своего shell,
   `pid` из реестра даёт дочерний `claude` — сопоставить по дереву процессов.

Рекомендуется действие **New orchestrator here**, чтобы оркестраторы тоже попадали в карту
детерминированно.

---

## 4. Наблюдение за реестром

Плагин следит за `~/.claude/sessions/` (watch на директорию):

- запись появилась → сессия готова, разблокировать ожидающий `spawn_agent`;
- запись исчезла → агент умер, пометить вкладку;
- `status` сменился на ожидание ввода → агент, возможно, завис на пермишен-промпте.

В последнем случае — уведомление в UI. Впечатывать строку в терминал оркестратора
(`sendText`) только если пользователь включил это в настройках: каждая инъекция стоит
оркестратору полного хода с полным контекстом.

---

## 5. Порядок реализации

1. `spawn_agent` + `list_agents` + сплит от родителя. Этого достаточно, чтобы проверить схему.
2. `close_agent`, `reset_agent`, `focus_agent`.
3. Watch реестра и индикация состояния во вкладках.
4. `restart_agent` и восстановление раскладки после рестарта IDE.
