package dev.metajetcore.agents

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.metajetcore.harness.B24
import dev.metajetcore.harness.Codex
import dev.metajetcore.harness.Harness
import dev.metajetcore.registry.SessionRecord
import dev.metajetcore.registry.SessionRegistry
import dev.metajetcore.roles.Role
import dev.metajetcore.roles.RoleInstaller
import dev.metajetcore.settings.MjcSettings
import dev.metajetcore.shell.ShellDialect
import dev.metajetcore.terminal.TabHandle
import dev.metajetcore.terminal.TabPlacement
import dev.metajetcore.terminal.SessionTab
import dev.metajetcore.terminal.TabResolver
import dev.metajetcore.terminal.Terminal
import dev.metajetcore.util.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Что плагин знает про запущенного агента. */
data class AgentInfo(
    val name: String,
    /** null — сессию плагин не заводил и роль из реестра не выводится. */
    val role: Role?,
    val model: String,
    val sessionId: String?,
    val pid: Int?,
    val status: String?,
    val managed: Boolean,
    val harness: Harness = Harness.CLAUDE,
)

/** Ответ агента не-Claude, собранный плагином (для Claude-агентов канал — SendMessage). */
data class AgentMessage(val from: String, val text: String)

/** Результат ожидания ответов: что пришло и кого ещё ждём. */
data class AwaitResult(
    val messages: List<AgentMessage>,
    val stillWorking: List<String>,
    /** Имена, ответы которых через ящик не приходят: Claude-агенты или неизвестные. */
    val notInMailbox: List<String>,
)

/** Результат отправки задачи агенту через плагин. */
sealed interface DeliveryResult {
    data object Sent : DeliveryResult

    /**
     * Агент занят ходом (или стоит пауза после сброса), сообщение ждёт в очереди плагина и
     * уйдёт, когда агент освободится. Так у b24: сообщение посреди хода он вливает в текущий ход (docs/PLAN-B24.md §2.6).
     */
    data class Queued(val position: Int) : DeliveryResult

    /** Агент — сессия Claude Code: ей пишут SendMessage, плагин второй канал не заводит. */
    data object UseSendMessage : DeliveryResult

    data object UnknownAgent : DeliveryResult

    data class Failed(val reason: String) : DeliveryResult
}

sealed interface ResetResult {
    data object Done : ResetResult
    data object UnknownTab : ResetResult
    data class Unsupported(val reason: String) : ResetResult
}

/** Результат печати текста во вкладку. */
sealed interface BriefResult {
    data object Sent : BriefResult

    /** Вкладки с таким именем плагин не знает и сопоставить её не смог. */
    data object UnknownTab : BriefResult

    /** В тексте есть символы, которые терминал исказит. Отправлять такое нельзя. */
    data class NotAscii(val offending: String) : BriefResult
}

/**
 * Результат закрытия агента.
 *
 * Boolean тут не хватало: «вкладку не закрыли» и «вкладки не знаем» — разные новости для
 * оркестратора, и в первом случае агент всё-таки завершён.
 */
sealed interface CloseResult {
    /** Сессия завершена, вкладка закрыта. */
    data object Closed : CloseResult

    /** Вкладки с таким именем плагин не знает. */
    data object UnknownTab : CloseResult

    /**
     * Сессия завершена, но во вкладке остался живой процесс, поэтому вкладку не закрывали:
     * платформа спросила бы подтверждение модальным диалогом.
     */
    data class TabLeftOpen(val reason: String) : CloseResult
}

/** Результат спавна: либо агент поднят, либо команда, которую надо выполнить руками. */
sealed interface SpawnResult {
    /**
     * @param pendingBriefing текст, который оркестратор обязан отправить агенту через
     *   SendMessage. Плагин его не печатает: см. комментарий в [AgentManager.spawn].
     * @param siblings агенты той же роли, которые уже работали в проекте на момент вызова.
     *   Непустой список — не ошибка: несколько имплементеров по непересекающимся доменам
     *   это штатная схема. Но оркестратор обязан увидеть, что он только что продублировал
     *   роль, — сам он этого не проверяет (см. комментарий в [AgentManager.spawn]).
     */
    data class Started(
        val agent: AgentInfo,
        val pendingBriefing: String,
        val siblings: List<AgentInfo> = emptyList(),
        /**
         * Задача уже у агента: Codex и b24 получают её аргументом при старте. Codex — потому
         * что без первого сообщения его сессия не создаётся вовсе (docs/PLAN-0.4.md §4.6),
         * b24 — потому что так задача не ждёт отдельного канала (docs/PLAN-B24.md §2.4).
         */
        val taskDelivered: Boolean = false,
    ) : SpawnResult

    /** Терминал недоступен или сессия не поднялась. Оркестратор попросит разработчика. */
    data class Manual(val command: String, val reason: String) : SpawnResult

    data class Failed(val reason: String) : SpawnResult
}

/**
 * Где родитель на экране: вкладка терминала или файл вкладки AIR (Agent Threads). Оба null —
 * не нашли, агент откроется в текущем окне редактора.
 */
internal data class ParentPlace(val handle: TabHandle?, val file: VirtualFile?)

/**
 * Агент без SendMessage (Codex, b24). Всё, что про него известно, — у плагина: адрес, лента
 * ответов, доставка. Отдельный тип, а не ветки `if codex` по всему менеджеру: каждая операция
 * (список, закрытие, сброс, ожидание) иначе обрастала бы случаем на каждый харнес.
 */
private interface ExternalAgent {
    val harness: Harness
    val sessionId: String

    /** Ход идёт или сообщения ждут отправки — ответ ещё будет. */
    val busy: Boolean

    /** Статус для list_agents. */
    fun status(): String

    /** Ответы завершённых ходов с прошлого вызова. */
    fun readCompletedTurns(): List<String>
    fun deliver(text: String): DeliveryResult
    fun reset(): ResetResult

    /** Агент забыт: убрать то, что плагин заводил под него. */
    fun dispose() {}
}

@Service(Service.Level.PROJECT)
class AgentManager(private val project: Project) : Disposable {

    private val log = Logger.getInstance(AgentManager::class.java)
    private val tabs = ConcurrentHashMap<String, TabHandle>()
    private val spawned = ConcurrentHashMap<String, AgentInfo>()

    /**
     * Агенты не-Claude: их нет в реестре Claude Code, поэтому всё, что про них известно, —
     * только здесь. Адрес — UUID сессии, ответы — из ленты агента.
     */
    private val externalAgents = ConcurrentHashMap<String, ExternalAgent>()

    /** Собранные, но ещё не отданные оркестратору ответы агентов не-Claude. */
    private val pending = ConcurrentHashMap<String, MutableList<String>>()

    /**
     * Свою сессию Codex опознаём как «новая лента в этом каталоге после момента старта».
     * Два одновременных старта так не развести, поэтому спавны Codex идут по одному.
     */
    private val codexSpawnLock = Any()

    private inner class CodexAgent(val session: Codex.Session) : ExternalAgent {
        val reader = Codex.RolloutReader(session.rollout)
        override val harness get() = Harness.CODEX
        override val sessionId get() = session.id
        override val busy get() = reader.busy
        override fun status() = if (reader.busy) "busy" else "idle"
        override fun readCompletedTurns() = reader.readCompletedTurns()
        override fun deliver(text: String) = deliverToCodex(session.id, text)

        // `/new` в Codex заводит новый тред с новым UUID, а его лента появится только со
        // следующим сообщением — плагин потерял бы адрес агента (docs/PLAN-0.4.md §4.6).
        override fun reset() = ResetResult.Unsupported(
            "сброс контекста Codex-агента не поддерживается: закрой его (close_agent) и " +
                "заведи нового (spawn_agent)",
        )
    }

    /**
     * b24-агент. Канал — два файла рядом с его TUI (см. [B24]): команды пишем в input, ответы
     * читаем из ленты событий.
     *
     * Своя очередь исходящих — потому что b24 сообщение посреди хода не ставит в очередь, а
     * вливает в текущий ход (проверено, вопреки его документации): один ответ закрыл бы два
     * ожидания оркестратора. Поэтому пишем в input только в простое, остальное ждёт здесь.
     */
    private inner class B24Agent(
        override val sessionId: String,
        val channel: B24.Channel,
        hasTask: Boolean,
    ) : ExternalAgent {
        val reader = B24.EventsReader(channel.events, startsBusy = hasTask)
        private val outbox = ArrayDeque<String>()

        /** После `/clear` событий нет, поэтому следующую команду придерживаем на время опроса CLI. */
        private var holdUntil = 0L

        override val harness get() = Harness.B24
        override val busy get() = reader.busy || synchronized(outbox) { outbox.isNotEmpty() }

        fun hasOutgoing(): Boolean = synchronized(outbox) { outbox.isNotEmpty() }

        override fun status(): String {
            val asks = reader.pendingPermissions()
            return when {
                asks.isNotEmpty() -> "ждёт разрешения в своей вкладке: " + asks.joinToString()
                busy -> "busy"
                else -> "idle"
            }
        }

        override fun readCompletedTurns(): List<String> {
            val replies = reader.readCompletedTurns()
            flush()
            return replies
        }

        override fun deliver(text: String): DeliveryResult {
            synchronized(outbox) { outbox.addLast(text) }
            flush()
            val left = synchronized(outbox) { outbox.size }
            return if (left == 0) DeliveryResult.Sent else DeliveryResult.Queued(left)
        }

        /** `/clear` через тот же канал: проверено, контекст сбрасывается. Занят — встанет в очередь. */
        override fun reset(): ResetResult = when (val result = deliver(B24_CLEAR)) {
            is DeliveryResult.Failed -> ResetResult.Unsupported("не доставлено: ${result.reason}")
            else -> ResetResult.Done
        }

        fun flush() = synchronized(outbox) {
            while (outbox.isNotEmpty() && !reader.busy && System.currentTimeMillis() >= holdUntil) {
                val next = outbox.first()
                try {
                    channel.submit(next)
                } catch (e: Exception) {
                    log.warn("MetaJetCore: не удалось записать в канал b24 ${channel.input}", e)
                    return@synchronized
                }
                outbox.removeFirst()
                if (next == B24_CLEAR) holdUntil = System.currentTimeMillis() + B24_CLEAR_HOLD_MS
                else reader.markSubmitted()
            }
        }

        override fun dispose() = channel.delete()
    }

    /**
     * Фоновый дочитыватель лент: очередь b24 сбрасывается при чтении, а оркестратор может
     * долго не звать ни await_messages, ни list_agents — например ждёт Claude-агентов.
     * Работает только пока есть что отправлять.
     */
    @Volatile
    private var outboxPump: ScheduledFuture<*>? = null

    @Synchronized
    private fun ensureOutboxPump() {
        if (outboxPump != null) return
        outboxPump = com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService()
            .scheduleWithFixedDelay({
                try {
                    if (externalAgents.values.any { it is B24Agent && it.hasOutgoing() }) collectReplies()
                } catch (e: Throwable) {
                    log.warn("MetaJetCore: фоновое чтение лент упало", e)
                }
            }, OUTBOX_PUMP_MS, OUTBOX_PUMP_MS, TimeUnit.MILLISECONDS)
    }

    override fun dispose() {
        outboxPump?.cancel(false)
    }

    private val settings get() = MjcSettings.getInstance()

    /**
     * Сколько ждать смерти процесса вкладки перед её закрытием.
     *
     * `var` ради теста негативного пути: там процесс не умирает никогда, и с продовым
     * значением тест просто стоял бы полный таймаут.
     */
    internal var tabDeadTimeoutMs: Long = EXIT_TIMEOUT_MS

    fun projectDirectory(): Path =
        project.basePath?.let { Paths.get(it) } ?: Paths.get(System.getProperty("user.dir"))

    /**
     * Куда агенты кладут развёрнутые отчёты: `~/.claude/metajetcore/<проект>/reports`.
     *
     * Вне репозитория намеренно: иначе каждый новый проект требовал бы строки в .gitignore,
     * а следы работы агентов засоряли бы историю.
     */
    fun reportsDirectory(): Path {
        val home = System.getenv("CLAUDE_CONFIG_DIR")?.takeIf { it.isNotBlank() }
            ?.let { Paths.get(it) }
            ?: Paths.get(System.getProperty("user.home"), ".claude")
        val dir = home.resolve("metajetcore").resolve(sanitize(project.name)).resolve("reports")
        runCatching { Files.createDirectories(dir) }
            .onFailure { log.warn("MetaJetCore: не удалось создать $dir", it) }
        return dir
    }

    private fun sanitize(name: String): String =
        name.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
            .joinToString("")
            .trim('-')
            .ifBlank { "project" }

    fun prefix(): String =
        settings.namePrefix.ifBlank { MjcSettings.derivePrefix(project.name) }

    // ------------------------------------------------------------------ spawn

    fun spawn(
        role: Role,
        task: String,
        parentName: String?,
        requestedName: String?,
        requestedModel: String?,
        domain: String?,
        requestedHarness: Harness? = null,
        caller: SessionRecord? = null,
    ): SpawnResult {
        val harness = requestedHarness ?: settings.harnessFor(role)
        if (role == Role.ORCHESTRATOR && harness != Harness.CLAUDE) {
            return SpawnResult.Failed("оркестратор работает только в Claude Code")
        }
        // Имя родителя приводим к тому, как сессия называется в реестре: оркестратор
        // копирует его из ListAgents вместе с ref-хвостом (см. canonicalName).
        val requestedParent = parentName?.takeIf { it.isNotBlank() }?.let { canonicalName(it) }
        // Вызывающая сессия, определённая по соединению, главнее имени из аргумента: имя модель
        // помнит старым после /rename, а процесс не врёт (docs/PLAN-CALLER.md). Имя наружу —
        // текущее, из реестра.
        if (caller != null && requestedParent != null && caller.name != requestedParent) {
            log.info("MetaJetCore: parent='$requestedParent', но вызвала сессия '${caller.name}' (${caller.sessionId}) — берём её")
        }
        val parent = caller?.name?.takeIf { it.isNotBlank() } ?: requestedParent

        // Кто этой роли уже работает — снимаем ДО открытия вкладки, иначе в списке окажется
        // и сам новичок. Спавн этим не отменяется: несколько имплементеров по
        // непересекающимся доменам — штатная схема, и запрещать её значило бы ломать
        // нормальную работу ради борьбы с дублями. Но в ответе список будет: живой прогон
        // дал четырёх имплементеров подряд, то есть сам оркестратор на живых не смотрит.
        val siblings = reusable(role)

        // Родителя разрешаем один раз: нужна и вкладка (чтобы открыть агента рядом), и
        // запись реестра (чтобы отдать наружу sessionId вместо неуникального имени).
        val parentTab = if (caller != null) resolveParent(caller) else parent?.let { resolveParent(it) }
        val parentSession = caller?.sessionId ?: parent?.let { parentSessionId(it, parentTab?.record) }
        // Оркестратор может жить не в терминале, а во вкладке AIR (Agent Threads) — тогда рядом
        // с ним открываем по её файлу.
        val parentFile = if (parentTab?.handle == null) parentSession?.let { runOnEdt { Terminal.airThreadFile(project, it) } } else null
        if (parent != null && parentTab?.handle == null && parentFile == null) {
            // Не отказ: агент нужнее, чем его место на экране. Но в логе это должно быть
            // видно — иначе «вкладка снова упала вниз» разбирается вслепую.
            log.info("MetaJetCore: вкладка родителя '$parent' не найдена, агент откроется в текущем окне")
        }
        val place = ParentPlace(parentTab?.handle, parentFile)

        val name = requestedName?.takeIf { it.isNotBlank() } ?: generateName(role, domain)
        if (SessionRegistry.isNameTaken(name) || externalAgents.containsKey(name)) {
            return SpawnResult.Failed(
                "имя '$name' уже занято живой сессией; передай другое или закрой ту сессию",
            )
        }

        val model = modelFor(role, harness, requestedModel)
        when (harness) {
            Harness.CODEX -> return spawnCodex(role, task, name, model, parent, parentSession, place, siblings)
            Harness.B24 -> return spawnB24(role, task, name, model, parent, parentSession, place, siblings)
            Harness.CLAUDE -> Unit
        }
        val manualCommand = manualCommand(role, name, model, parent, parentSession)

        if (!runOnEdt { Terminal.isAvailable(project) }) {
            return SpawnResult.Manual(manualCommand, "терминальное API IDE недоступно")
        }

        // Агент — сам процесс вкладки, а не строка, напечатанная в шелл (см. Terminal.openTab).
        // Поэтому ни ожидания приглашения шелла, ни печати: argv уходит в API создания
        // процесса, вкладка закроется сама, когда агент завершится.
        val argv = runOnEdt { launchArgv(role) }
        val handle = runOnEdt {
            Terminal.openTab(
                project = project,
                name = name,
                workingDirectory = projectDirectory(),
                env = agentEnv(role, name, model, parent, parentSession),
                command = argv,
            )?.also { child ->
                Terminal.setAgentIcon(child, CLAUDE_AGENT_KEY)
                TabPlacement.placeNear(project, place.handle, child, place.file)
            }
        } ?: return SpawnResult.Manual(manualCommand, "не удалось открыть вкладку терминала")

        tabs[name] = handle

        val record = when (val ready = awaitSession(name, handle)) {
            is Readiness.Ready -> ready.record
            Readiness.Exited -> {
                tabs.remove(name)
                return SpawnResult.Manual(
                    manualCommand,
                    "процесс агента завершился до регистрации сессии; проверь команду запуска " +
                        "в настройках плагина (запускалось: ${argv.joinToString(" ")})",
                )
            }
            Readiness.Timeout -> return SpawnResult.Manual(
                manualCommand,
                "сессия не появилась в реестре за ${settings.readyTimeoutSeconds} c; " +
                    "посмотри экран вкладки (read_tab) и команду запуска в настройках плагина",
            )
        }

        // Имя могло измениться: при коллизии Claude Code переименовывает сессию в вариант.
        val actualName = record.name ?: name
        if (actualName != name) {
            tabs.remove(name)
            tabs[actualName] = handle
        }

        // Задачу в терминал НЕ печатаем, хотя технически теперь можем.
        //
        // Содержательный канал в этой схеме ровно один — межсессионные сообщения: они
        // адресные, UTF-8-безопасные, доставляются в очередь агента и не зависят от того,
        // что сейчас на экране вкладки. Печать в TUI осталась только для управляющих команд
        // (/exit, /clear) и аварийного brief_agent.
        val info = AgentInfo(
            name = actualName,
            role = role,
            model = model,
            sessionId = record.sessionId,
            pid = record.pid,
            status = record.status,
            managed = true,
        )
        spawned[actualName] = info
        return SpawnResult.Started(info, pendingBriefing = task, siblings = siblings)
    }

    /**
     * Модель агента: явная из вызова, затем из настроек роли, затем дефолт роли.
     *
     * Для Codex и b24 имена моделей Claude отбрасываются: tempo задаёт модель в терминах
     * Claude, и `-m opus` уронил бы их на старте. Пустая строка — модель из конфига самого CLI.
     */
    internal fun modelFor(role: Role, harness: Harness, requested: String?): String {
        val explicit = requested?.trim()?.takeIf { it.isNotEmpty() }
        return when (harness) {
            Harness.CLAUDE -> explicit ?: settings.modelFor(role) ?: role.defaultModel
            Harness.CODEX, Harness.B24 -> explicit?.takeUnless { Harness.isClaudeModel(it) }
                ?: settings.modelFor(role)?.takeUnless { Harness.isClaudeModel(it) }
                ?: ""
        }
    }

    /**
     * Спавн Codex-агента. Отличия от Claude — следствия того, как устроен Codex 0.166
     * (docs/PLAN-0.4.md §4.6):
     *
     *  * задача уходит аргументом при старте: без первого сообщения сессия не создаётся, и
     *    адреса для `codex queue` просто нет;
     *  * готовность — появление новой ленты rollout в каталоге проекта, а не запись в
     *    реестре Claude Code: туда Codex не пишет;
     *  * роль — профилем `-p mjc-<role>`, а не флагом `--agent`.
     */
    private fun spawnCodex(
        role: Role,
        task: String,
        name: String,
        model: String,
        parent: String?,
        parentSession: String?,
        place: ParentPlace,
        siblings: List<AgentInfo>,
    ): SpawnResult = synchronized(codexSpawnLock) {
        // Пустую задачу не отправляем пустой: без первого сообщения сессии не будет.
        val firstMessage = task.ifBlank { CODEX_IDLE_TASK }
        val env = codexEnv(role, name, model, parent, parentSession, firstMessage)
        val manual = codexManualCommand(role, model, env)
        if (!runOnEdt { Terminal.isAvailable(project) }) {
            return SpawnResult.Manual(manual, "терминальное API IDE недоступно")
        }
        val since = System.currentTimeMillis()
        val argv = runOnEdt { codexArgv(role, model) }
        val handle = runOnEdt {
            Terminal.openTab(
                project = project,
                name = name,
                workingDirectory = projectDirectory(),
                env = env,
                command = argv,
            )?.also { child ->
                Terminal.setAgentIcon(child, Harness.CODEX.key)
                TabPlacement.placeNear(project, place.handle, child, place.file)
            }
        } ?: return SpawnResult.Manual(manual, "не удалось открыть вкладку терминала")
        tabs[name] = handle

        val session = awaitCodexSession(handle, since)
            ?: run {
                val exited = runOnEdt { Terminal.isTerminated(handle) || !Terminal.isOpen(project, handle) }
                if (exited) tabs.remove(name)
                return SpawnResult.Manual(
                    manual,
                    if (exited) {
                        "процесс Codex завершился до начала сессии; проверь команду запуска " +
                            "Codex в настройках плагина (запускалось: ${argv.joinToString(" ")})"
                    } else {
                        "сессия Codex не появилась за ${settings.readyTimeoutSeconds} c; посмотри " +
                            "экран вкладки (read_tab) — скорее всего Codex задал вопрос на старте"
                    },
                )
            }
        externalAgents[name] = CodexAgent(session)
        val info = AgentInfo(
            name = name,
            role = role,
            model = model,
            sessionId = session.id,
            pid = runOnEdt { Terminal.process(handle) }?.pid?.toInt(),
            status = "busy",
            managed = true,
            harness = Harness.CODEX,
        )
        spawned[name] = info
        SpawnResult.Started(info, pendingBriefing = task, siblings = siblings, taskDelivered = true)
    }

    private fun awaitCodexSession(handle: TabHandle, since: Long): Codex.Session? {
        val claimed = externalAgents.values.map { it.sessionId }.toSet()
        val deadline = System.currentTimeMillis() + settings.readyTimeoutSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            Codex.findNewSession(projectDirectory(), since, claimed)?.let { return it }
            if (runOnEdt { Terminal.isTerminated(handle) || !Terminal.isOpen(project, handle) }) {
                return Codex.findNewSession(projectDirectory(), since, claimed)
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return null
    }

    /**
     * Спавн b24-агента (docs/PLAN-B24.md). Проще Codex:
     *
     *  * адрес задаём сами — `--session-id`, поэтому сессию не ищем и спавны не сериализуем;
     *  * готовность — `session_start` в ленте событий;
     *  * роль — `--append-system-prompt`, задача — `-i`; обе через переменные окружения.
     */
    private fun spawnB24(
        role: Role,
        task: String,
        name: String,
        model: String,
        parent: String?,
        parentSession: String?,
        place: ParentPlace,
        siblings: List<AgentInfo>,
    ): SpawnResult {
        val sessionId = UUID.randomUUID().toString()
        val channel = B24.channel(sessionId)
        val env = b24Env(role, name, model, parent, parentSession, task, channel)
        val manual = b24ManualCommand(sessionId, model, env, task.isNotBlank())
        if (!runOnEdt { Terminal.isAvailable(project) }) {
            return SpawnResult.Manual(manual, "терминальное API IDE недоступно")
        }
        try {
            channel.create()
        } catch (e: Exception) {
            return SpawnResult.Failed("не удалось создать канал b24 в ${channel.dir}: ${e.message}")
        }
        val argv = runOnEdt { b24Argv(sessionId, model, task.isNotBlank()) }
        val handle = runOnEdt {
            Terminal.openTab(
                project = project,
                name = name,
                workingDirectory = projectDirectory(),
                env = env,
                command = argv,
            )?.also { child -> TabPlacement.placeNear(project, place.handle, child, place.file) }
        } ?: run {
            channel.delete()
            return SpawnResult.Manual(manual, "не удалось открыть вкладку терминала")
        }
        tabs[name] = handle

        if (!awaitB24Start(channel, handle)) {
            val exited = runOnEdt { Terminal.isTerminated(handle) || !Terminal.isOpen(project, handle) }
            if (exited) {
                tabs.remove(name)
                channel.delete()
            }
            return SpawnResult.Manual(
                manual,
                if (exited) {
                    "процесс b24 завершился до начала сессии; проверь команду запуска b24 в " +
                        "настройках плагина (запускалось: ${argv.joinToString(" ")})"
                } else {
                    "сессия b24 не началась за ${settings.readyTimeoutSeconds} c; посмотри экран " +
                        "вкладки (read_tab) — скорее всего CLI задал вопрос на старте (вход в Vibecode?)"
                },
            )
        }
        externalAgents[name] = B24Agent(sessionId, channel, hasTask = task.isNotBlank())
        ensureOutboxPump()
        val info = AgentInfo(
            name = name,
            role = role,
            model = model,
            sessionId = sessionId,
            pid = runOnEdt { Terminal.process(handle) }?.pid?.toInt(),
            status = if (task.isNotBlank()) "busy" else "idle",
            managed = true,
            harness = Harness.B24,
        )
        spawned[name] = info
        return SpawnResult.Started(info, pendingBriefing = task, siblings = siblings, taskDelivered = task.isNotBlank())
    }

    /**
     * Ждём `session_start` в ленте — первое событие, которое CLI пишет, подняв TUI. Читаем
     * первую строку, не трогая читателя ленты: его смещение должно начаться с нуля, иначе
     * ответ на задачу из `-i` мог бы проскочить мимо ящика.
     */
    private fun awaitB24Start(channel: B24.Channel, handle: TabHandle): Boolean {
        val deadline = System.currentTimeMillis() + settings.readyTimeoutSeconds * 1000L
        fun started() = try {
            Files.newBufferedReader(channel.events).use { it.readLine() }?.contains("\"session_start\"") == true
        } catch (e: Exception) {
            false
        }
        while (System.currentTimeMillis() < deadline) {
            if (started()) return true
            if (runOnEdt { Terminal.isTerminated(handle) || !Terminal.isOpen(project, handle) }) return started()
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    // ------------------------------------------------------------- lifecycle

    /**
     * Мягкое завершение: `/exit`, дождаться исчезновения из реестра и закрытия вкладки.
     *
     * Вкладка агента, запущенного плагином, закрывается сама: её процесс — сам агент, и
     * вкладка создана с `closeOnProcessTermination` (см. `Terminal.openTab`). Дальше только
     * страховка, и порядок в ней важен:
     *  1. процесс не умер за таймаут — добиваем дерево процессов. Сессия к этому моменту
     *     уже ушла из реестра, значит висит хвост, а не работа;
     *  2. вкладка ещё на экране — закрываем явно, и в editor area, и в тулвиндоу. Процесс
     *     мёртв, поэтому модального «в терминале что-то запущено» не будет: под
     *     `invokeAndWait` такой диалог заблокировал бы поток MCP-запроса.
     *
     * Вкладку, которую плагин не запускал (подхвачена по дереву процессов), не добиваем: там
     * процесс — шелл разработчика. Если он жив, отвечаем [CloseResult.TabLeftOpen].
     */
    fun close(rawName: String): CloseResult {
        // Имя нормализуем один раз здесь: дальше оно уходит и в реестр, и в обе карты,
        // и расхождение между ними означало бы вкладку-сироту.
        val name = canonicalName(rawName)
        val handle = tabFor(name) ?: return CloseResult.UnknownTab
        val external = externalAgents[name]
        if (external == null && runOnEdt { Terminal.isOpen(project, handle) }) {
            runOnEdt { Terminal.send(handle, "/exit", execute = true) }
        }
        if (external == null) awaitSessionGone(name)
        // Последний ответ мог прийти прямо перед выходом — собираем, пока агент ещё известен.
        collectReplies()
        externalAgents.remove(name)?.dispose()
        // Управлять больше нечем: сессии нет. Дальше речь только об уборке вкладки.
        spawned.remove(name)

        // Codex и b24 завершаем сигналом, а не командой в TUI. Живой прогон в IDE: `/quit` у
        // Codex не срабатывал ни слитно с Enter, ни с Enter отдельно после паузы — терминал IDE
        // доставляет ввод в его TUI иначе, чем голый pty. Сигнал — контракт ОС, а не экранной
        // формы: SIGTERM, время на корректный выход, затем принудительно. Ленты пишутся по
        // событию, поэтому ничего не теряется. b24 по SIGTERM выходит за доли секунды.
        if (external != null && handle.ownsProcess) Terminal.killProcessTree(handle, KILL_GRACE_MS)

        var dead = awaitTabDead(handle)
        if (!dead && handle.ownsProcess) {
            log.warn("MetaJetCore: процесс агента '$name' не завершился после /exit — завершаем дерево")
            dead = Terminal.killProcessTree(handle, KILL_GRACE_MS)
        }
        if (!dead) {
            // Вкладку намеренно оставляем под управлением: read_tab по ней ещё работает, а
            // именно её экран объясняет, почему процесс не завершился.
            return CloseResult.TabLeftOpen(
                "сессия '$name' завершена, но во вкладке остался живой процесс, поэтому " +
                    "вкладка не закрыта: IDE спросила бы подтверждение. Посмотри её экран " +
                    "(read_tab) и закрой вкладку руками",
            )
        }

        // Сама вкладка закрывается асинхронно, вслед за смертью процесса. Даём ей это
        // сделать, а если не успела — закрываем сами: процесса уже нет, спрашивать не о чем.
        if (!awaitTabClosed(handle)) runOnEdt { Terminal.closeTab(project, handle) }
        tabs.remove(name)
        return CloseResult.Closed
    }

    /** Сброс контекста между этапами: сессия и вкладка остаются живыми. */
    fun reset(rawName: String): ResetResult {
        val name = canonicalName(rawName)
        externalAgents[name]?.let { agent ->
            // Ответ хода, закончившегося до сброса, ещё должен попасть в ящик.
            collectReplies()
            return agent.reset()
        }
        val handle = tabFor(name) ?: return ResetResult.UnknownTab
        return if (runOnEdt { Terminal.send(handle, "/clear", execute = true) }) ResetResult.Done
        else ResetResult.UnknownTab
    }

    /**
     * Отправить сообщение агенту не-Claude.
     *
     * Codex — через `codex queue`: это его собственный способ доставить сообщение в живую
     * сессию, сообщение встаёт новым ходом (проверено: кириллица доезжает целой). b24 — через
     * input-файл, в простое (см. [B24Agent]). Печатать в TUI не годится по тем же причинам,
     * что и для Claude.
     *
     * Claude-агентам плагин сообщений не возит: у них есть SendMessage, и второй канал
     * означал бы две очереди без порядка между ними.
     */
    fun deliver(rawName: String, text: String): DeliveryResult {
        val name = canonicalName(rawName)
        val agent = externalAgents[name]
            ?: return if (SessionRegistry.findByName(name) != null) DeliveryResult.UseSendMessage
            else DeliveryResult.UnknownAgent
        // Сначала дочитать ленту: занятость агента должна быть свежей, а ответы, прочитанные
        // попутно, — оказаться в ящике, а не потеряться.
        collectReplies()
        return agent.deliver(text)
    }

    private fun deliverToCodex(threadId: String, text: String): DeliveryResult {
        val shellPath = runOnEdt { Terminal.configuredShellPath(project) } ?: ShellDialect.defaultShell()
        val dialect = ShellDialect.detect(shellPath)
        val command = settings.codexLaunchCommand + " queue --thread " + dialect.envRef(ENV_THREAD) +
            " --message " + dialect.envRef(ENV_MESSAGE)
        val argv = dialect.argv(shellPath, dialect.unsetNames(inheritedMarkers()), command, execable = false)
        return try {
            val process = ProcessBuilder(argv)
                .directory(projectDirectory().toFile())
                .redirectErrorStream(true)
                .also { it.environment()[ENV_THREAD] = threadId; it.environment()[ENV_MESSAGE] = text }
                .start()
            process.outputStream.close()
            val finished = process.waitFor(DELIVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val output = process.inputStream.bufferedReader().readText().trim()
            when {
                !finished -> {
                    process.destroyForcibly()
                    DeliveryResult.Failed("codex queue не завершился за ${DELIVERY_TIMEOUT_MS / 1000} c")
                }
                process.exitValue() != 0 ->
                    DeliveryResult.Failed("codex queue: код ${process.exitValue()}: ${output.takeLast(400)}")
                else -> DeliveryResult.Sent
            }
        } catch (e: Exception) {
            DeliveryResult.Failed("не удалось запустить codex queue: ${e.message}")
        }
    }

    /**
     * Дождаться ответов агентов не-Claude.
     *
     * Модель tempo — «запустил волну, дождался всех отчётов». Claude-агенты отвечают сами,
     * через SendMessage, а у Codex и b24 обратного канала в сессию Claude нет. Поэтому
     * оркестратор ждёт их здесь: плагин читает их ленты и отдаёт завершённые ходы.
     *
     * С [names] — ждём, пока ответит каждый из них (или таймаут); без — первый же ответ
     * любого. Возвращаем всё, что накопилось, — по таймауту тоже: частичный результат лучше
     * пустого, оркестратор позовёт снова за остальными.
     */
    fun awaitMessages(names: List<String>?, timeoutMs: Long): AwaitResult {
        val wanted = names?.map { canonicalName(it) }?.filter { it.isNotEmpty() }?.distinct()
        val notInMailbox = wanted.orEmpty().filter { !externalAgents.containsKey(it) && !pending.containsKey(it) }
        val waitFor = wanted?.minus(notInMailbox.toSet())
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            collectReplies()
            val ready = when {
                waitFor == null -> pending.values.any { it.isNotEmpty() }
                waitFor.isEmpty() -> true
                else -> waitFor.all { name ->
                    pending[name]?.isNotEmpty() == true || !externalAgents.containsKey(name)
                }
            }
            if (ready || System.currentTimeMillis() >= deadline) break
            Thread.sleep(POLL_INTERVAL_MS)
        }
        val messages = mutableListOf<AgentMessage>()
        for (name in waitFor ?: pending.keys.toList()) {
            val queue = pending[name] ?: continue
            synchronized(queue) {
                queue.forEach { messages += AgentMessage(name, it) }
                queue.clear()
            }
        }
        val still = (waitFor ?: externalAgents.keys.toList()).filter { name ->
            messages.none { it.from == name } && externalAgents[name]?.busy == true
        }
        return AwaitResult(messages, still, notInMailbox)
    }

    /**
     * Дочитать ленты агентов не-Claude и разложить завершённые ходы по ящикам.
     *
     * Копию пишем журналом в каталог отчётов: ящик живёт в памяти IDE, а оркестратор может
     * перезапуститься посреди волны — тогда ответ хотя бы найдётся глазами.
     */
    private fun collectReplies() {
        for ((name, agent) in externalAgents) {
            val replies = agent.readCompletedTurns()
            if (replies.isEmpty()) continue
            val queue = pending.getOrPut(name) { mutableListOf() }
            synchronized(queue) { queue += replies }
            journal(name, replies)
        }
    }

    private fun journal(name: String, replies: List<String>) {
        try {
            val line = replies.joinToString("") { text ->
                Json.obj(
                    "at" to Json.of(System.currentTimeMillis()),
                    "from" to Json.of(name),
                    "text" to Json.of(text),
                ).render() + "\n"
            }
            Files.writeString(
                reportsDirectory().resolve(MAILBOX_JOURNAL),
                line,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND,
            )
        } catch (e: Exception) {
            log.warn("MetaJetCore: журнал ящика не записан", e)
        }
    }

    /**
     * Впечатать текст во вкладку как ввод пользователя.
     *
     * Аварийный канал: обычная переписка идёт через SendMessage. Текст уходит вставкой
     * (bracketed paste), поэтому многострочный текст не разъезжается по строкам ввода TUI.
     *
     * Только ASCII, и это не перестраховка. Живой прогон на 2026.2: отправили
     * «Ответь ровно одним словом: ПРОБА-OK», в TUI приехало
     * «❯ ������ ����� ����� ������: �����-OK», и агент ответил на выдуманный текст.
     * Терминал пишет в pty не в UTF-8, а Claude Code читает stdin как UTF-8; ответ агента при
     * этом отрисовался кириллицей верно, то есть портится именно наш ввод. Молча искажать
     * текст хуже, чем отказаться: искажение выглядит как исполненная команда.
     */
    fun brief(rawName: String, text: String): BriefResult {
        val nonAscii = text.filter { it.code >= 128 }
        if (nonAscii.isNotEmpty()) return BriefResult.NotAscii(nonAscii.toSet().joinToString(""))
        val handle = tabFor(rawName) ?: return BriefResult.UnknownTab
        return if (runOnEdt { Terminal.send(handle, text, execute = true, paste = true) }) {
            BriefResult.Sent
        } else {
            BriefResult.UnknownTab
        }
    }

    /** Что сейчас на экране вкладки агента. Для разбора «запустился, но молчит». */
    fun readScreen(rawName: String): String? {
        val handle = tabFor(rawName) ?: return null
        return runOnEdt { Terminal.readScreen(handle) }
    }

    /**
     * Вкладка по имени сессии: сперва своя карта, затем поиск по дереву процессов.
     *
     * Второй путь нужен, чтобы запуск оркестратора оставался ровно `/orchestrate` в любой
     * вкладке. Плагин знает только те вкладки, которые создал сам; всё остальное он
     * доопределяет сопоставлением pid — см. [TabResolver]. Найденное запоминаем, чтобы не
     * искать заново на каждый вызов.
     */
    private fun tabFor(rawName: String): TabHandle? {
        val name = canonicalName(rawName)
        tabs[name]?.let { return it }
        val resolved = runOnEdt { TabResolver.findTabForSession(project, name) } ?: return null
        tabs[name] = resolved
        return resolved
    }

    /**
     * Вкладка родителя вместе с его записью в реестре.
     *
     * Отдельно от [tabFor] ради записи: карта вкладок её не хранит, а для связи
     * «агент → оркестратор» нужна именно она — см. [parentSessionId].
     */
    private fun resolveParent(name: String): SessionTab? {
        val resolved = runOnEdt { TabResolver.resolve(project, name) } ?: return null
        tabs[name] = resolved.handle
        return resolved
    }

    /** Вкладка уже известной сессии — по её процессу, без поиска по имени. */
    private fun resolveParent(record: SessionRecord): SessionTab? {
        val resolved = runOnEdt { TabResolver.resolveRecord(project, record) } ?: return null
        record.name?.takeIf { it.isNotBlank() }?.let { tabs[it] = resolved.handle }
        return resolved
    }

    /**
     * `sessionId` оркестратора — то, чем связь «агент → его оркестратор» задаётся на самом
     * деле.
     *
     * Имя для этого не годится, и это не придирка: имя задаёт человек, оно не уникально
     * (замер: три живые записи реестра с именем «слитие мастера») и меняется по ходу работы.
     * `sessionId` уникален и переживает resume — у тех же трёх записей он один и тот же.
     *
     * Источник по убыванию надёжности:
     *
     *  1. Запись, по которой нашлась вкладка родителя. Она получена сопоставлением pid, то
     *     есть привязана к конкретному процессу `claude`, — единственный способ развести
     *     тёзок.
     *  2. Реестр по имени, но **только если ответ однозначен**: все записи с этим именем
     *     дают один `sessionId`. Иначе — null: отсутствие связи честнее, чем связь наугад,
     *     потому что наугад означает чужую команду на экране наблюдателя.
     */
    internal fun parentSessionId(name: String, matched: SessionRecord? = null): String? {
        matched?.sessionId?.takeIf { it.isNotBlank() }?.let { return it }
        val ids = SessionRegistry.byName(name).map { it.sessionId }.filter { it.isNotBlank() }.distinct()
        return if (ids.size == 1) {
            ids.first()
        } else {
            if (ids.size > 1) {
                log.info("MetaJetCore: имя '$name' носят ${ids.size} разные сессии, родитель не определён")
            }
            null
        }
    }

    /**
     * Имя сессии из строки, которую дал оркестратор.
     *
     * `ListAgents` печатает каждую сессию как `слитие мастера [815818]`, и его контракт велит
     * копировать имя ровно так, как напечатано, — оркестратор так и делает. Но хвост в
     * скобках это ref платформы, а не часть имени: в реестре сессия называется
     * «слитие мастера». Ref ниоткуда не выводится — ни из pid, ни из sessionId (замер:
     * сессия `metajetcore-df`, sessionId `6e65d7ec-…`, ref `ebe653`), — то есть опознать по
     * нему нечего, его можно только снять.
     *
     * Пока хвост не снимался, ломалось всё, что опирается на имя, и ломалось молча: вкладка
     * родителя не находилась, поэтому агент открывался в тулвиндоу вместо места рядом с
     * оркестратором, а `MJC_PARENT` уезжал наблюдателю в виде, который тот не сопоставит ни
     * с одной живой сессией — дерево команды не собиралось вовсе.
     *
     * Снимаем только тогда, когда имени с хвостом в реестре нет: сессия вправе называть себя
     * как угодно, включая скобки на конце.
     */
    internal fun canonicalName(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || SessionRegistry.isNameTaken(trimmed)) return trimmed
        val bare = REF_SUFFIX.replace(trimmed, "").trim()
        return bare.ifEmpty { trimmed }
    }

    /** Имена вкладок, которые ведёт плагин, — включая те, где спавн не доехал. */
    fun knownTabs(): Set<String> {
        forgetClosedTabs()
        return tabs.keys.toSet()
    }

    /**
     * Забыть вкладки агентов, которые закрылись сами — агент вышел по `/exit` изнутри или
     * упал. Вкладка в этом случае исчезает без нашего участия (`closeOnProcessTermination`),
     * и держать на неё ссылку значило бы показывать в `list_agents` «управляемого» агента,
     * которого нет. Вкладки с шеллом (оркестратор) не трогаем: их закрывает человек.
     */
    private fun forgetClosedTabs() {
        val owned = tabs.filterValues { it.ownsProcess }
        if (owned.isEmpty()) return
        val closed = runOnEdt { owned.filterValues { !Terminal.isOpen(project, it) }.keys }
        if (closed.any { externalAgents.containsKey(it) }) collectReplies()
        for (name in closed) {
            tabs.remove(name)
            spawned.remove(name)
            externalAgents.remove(name)?.dispose()
        }
    }

    /**
     * Состояние терминала для диагностики.
     *
     * Через runOnEdt, а не напрямую: MCP-запросы приходят на своих потоках, а перечисление
     * вкладок и выбранного редактора — это чтение UI. Мимо EDT оно в лучшем случае врёт
     * (`currentWindow` отдавал null, из-за чего диагностика показывала «активной вкладки
     * нет» при открытом терминале), в худшем — падает на проверке потока.
     */
    fun describeTerminal(): String = runOnEdt { Terminal.describe(project) }

    /** Запомнить вкладку, открытую действием плагина (оркестратор). */
    fun register(name: String, handle: TabHandle) {
        tabs[name] = handle
    }

    /**
     * Запустить обычную сессию Claude Code в уже открытой вкладке — без роли и без флагов.
     *
     * Агенты запускаются процессом вкладки (NON_SHELL), а оркестратор — строкой в обычном
     * шелле, и намеренно. Это вкладка оркестратора: её закрывает человек, а не
     * `close_agent`, и после выхода из claude он обычно остаётся в шелле — например чтобы
     * запустить сессию заново.
     *
     * Ждать шелл приходится в фоне: действие вызывается из EDT, а между созданием вкладки и
     * стартом процесса проходит заметное время, и напечатанная в этот промежуток строка
     * теряется молча. Диалект берём по фактическому процессу этой вкладки — до его старта он
     * неизвестен, а угадывать нельзя: во вкладку с PowerShell уезжал синтаксис cmd.
     */
    fun startPlainSession(handle: TabHandle) {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!awaitTabRunning(handle)) {
                log.warn("MetaJetCore: шелл во вкладке '${handle.name}' не поднялся, команда не напечатана")
                return@executeOnPooledThread
            }
            val shell = ShellDialect.detect(runOnEdt { Terminal.process(handle) }?.shellCommand?.firstOrNull())
            val line = shell.unsetNames(inheritedMarkers()) + settings.launchCommand
            runOnEdt { Terminal.send(handle, line, execute = true) }
        }
    }

    /** Агент не-Claude: сообщения ему — через send_to_agent, а не SendMessage. */
    fun isExternal(rawName: String): Boolean = externalAgents.containsKey(canonicalName(rawName))

    fun focus(rawName: String): Boolean {
        val handle = tabFor(rawName) ?: return false
        return runOnEdt { Terminal.focus(handle) }
    }

    /**
     * Живые агенты этой роли в проекте — те, кому задачу можно отдать вместо нового спавна.
     *
     * Оркестратор в списке не участвует: он один и не заменяем. Занятость агента тоже не
     * смотрим — `busy` означает «сейчас думает», а не «занят другой задачей»: сообщение всё
     * равно встанет в его очередь.
     */
    fun reusable(role: Role): List<AgentInfo> =
        if (role == Role.ORCHESTRATOR) emptyList()
        else list().filter { it.role == role }

    /** Живые сессии в директории проекта, обогащённые тем, что плагин знает про вкладки. */
    fun list(): List<AgentInfo> {
        forgetClosedTabs()
        // Статус агента не-Claude — по его ленте: дочитываем, заодно ответы встают в ящик.
        collectReplies()
        val external = externalAgents.map { (name, agent) ->
            val known = spawned[name]
            AgentInfo(
                name = name,
                role = known?.role,
                model = known?.model.orEmpty(),
                sessionId = agent.sessionId,
                pid = known?.pid,
                status = agent.status(),
                managed = tabs.containsKey(name),
                harness = agent.harness,
            )
        }
        return external + SessionRegistry.inDirectory(projectDirectory()).map { record ->
            val name = record.name.orEmpty()
            val known = spawned[name]
            AgentInfo(
                name = name,
                role = known?.role ?: record.agent?.let { Role.fromId(it) },
                model = known?.model ?: "",
                sessionId = record.sessionId,
                pid = record.pid,
                status = record.status,
                managed = tabs.containsKey(name),
            )
        }
    }

    // ------------------------------------------------------------- internals

    private fun generateName(role: Role, domain: String?): String {
        val base = buildString {
            append(prefix())
            append('-')
            append(role.shortName)
            if (!domain.isNullOrBlank()) {
                append('-')
                // 16 — чтобы влез слот состава ревью: `review-claude-2`.
                append(domain.lowercase().filter { it.isLetterOrDigit() || it == '-' }.take(16))
            }
        }
        if (!SessionRegistry.isNameTaken(base)) return base
        // Имена глобальны на машину, поэтому коллизия реальна при нескольких проектах.
        for (suffix in 2..99) {
            val candidate = "$base-$suffix"
            if (!SessionRegistry.isNameTaken(candidate)) return candidate
        }
        return "$base-${System.currentTimeMillis() % 10_000}"
    }

    /**
     * Окружение агента. Уезжает через API терминала, а не в командной строке: так не нужны
     * ни кавычки, ни диалекты шеллов, ни ASCII-ограничение на значения.
     */
    internal fun agentEnv(
        role: Role,
        name: String,
        model: String,
        parentName: String? = null,
        parentSessionId: String? = null,
    ): Map<String, String> {
        val env = LinkedHashMap<String, String>()
        // Имя сессии — это адрес для SendMessage. Без него имя выводится платформой из имени
        // проекта, и агентов одного проекта не различить.
        env["CLAUDE_CODE_SESSION_NAME"] = name
        env["ANTHROPIC_MODEL"] = model
        // Метка для реестра: роль этим НЕ задаётся (проверено), но по ней list() узнаёт роль
        // сессий, которые плагин не создавал.
        env["CLAUDE_CODE_AGENT"] = role.id
        // Каталог для развёрнутых отчётов — ВНЕ репозитория, иначе в каждом проекте
        // пришлось бы добавлять строку в .gitignore, а артефакты агентов там не нужны.
        env["MJC_REPORTS_DIR"] = reportsDirectory().toString().replace(BACKSLASH, '/')
        // Кто завёл этого агента. Связь знает только плагин и только здесь: в реестре Claude
        // Code поля «родитель» нет вовсе, а сторонним наблюдателям дерево команды иначе
        // приходится угадывать по общему префиксу имени — на двух командах в одном проекте
        // догадка разваливается.
        //
        // Две переменные, и это не дубль. `MJC_PARENT_SESSION` — настоящий ключ: sessionId
        // уникален и переживает resume. `MJC_PARENT` — имя, оно для человека и для старых
        // получателей; **ключом оно быть не может**, потому что не уникально (замер: три
        // живые записи реестра с именем «слитие мастера») и меняется по ходу работы.
        //
        // Ключа может не быть, даже когда имя есть: родителя не удалось определить
        // однозначно (см. parentSessionId). Тогда наблюдатель обязан считать связь
        // неизвестной, а не достраивать её по имени.
        parentName?.takeIf { it.isNotBlank() }?.let { env["MJC_PARENT"] = it.take(MAX_PARENT_NAME) }
        parentSessionId?.takeIf { it.isNotBlank() }?.let { env["MJC_PARENT_SESSION"] = it }
        env.putAll(settings.extraEnv())
        return env
    }

    /**
     * Окружение Codex-агента. Здесь же едут переменные части его командной строки — задача,
     * доверие к каталогу, модель: в строке остаются только ссылки на них (см.
     * [ShellDialect.envRef]), и никакой пользовательский текст через синтаксис шелла не идёт.
     */
    internal fun codexEnv(
        role: Role,
        name: String,
        model: String,
        parentName: String?,
        parentSessionId: String?,
        task: String,
    ): Map<String, String> {
        val env = LinkedHashMap<String, String>()
        env["MJC_AGENT_NAME"] = name
        env["MJC_ROLE"] = role.id
        env["MJC_REPORTS_DIR"] = reportsDirectory().toString().replace(BACKSLASH, '/')
        parentName?.takeIf { it.isNotBlank() }?.let { env["MJC_PARENT"] = it.take(MAX_PARENT_NAME) }
        parentSessionId?.takeIf { it.isNotBlank() }?.let { env["MJC_PARENT_SESSION"] = it }
        env[ENV_TASK] = task
        env[ENV_TRUST] = Codex.trustOverride(projectDirectory())
        if (model.isNotBlank()) env[ENV_MODEL] = model
        env.putAll(settings.extraEnv())
        return env
    }

    /**
     * argv Codex-агента. Порядок: профиль роли, модель, аргументы из настроек, доверие к
     * каталогу, задача последней — она позиционный аргумент `[PROMPT]`.
     *
     * `exec` решаем по команде без ссылок на переменные: сами ссылки (`"${'$'}MJC_TASK"`) делают
     * строку «составной» для [ShellDialect.isSimple], хотя программа в ней одна.
     */
    internal fun codexArgv(role: Role, model: String): List<String> {
        val shellPath = Terminal.configuredShellPath(project) ?: ShellDialect.defaultShell()
        val dialect = ShellDialect.detect(shellPath)
        val base = settings.codexLaunchCommand + " -p " + Codex.profileName(role) +
            " " + settings.codexArgs.trim()
        val command = buildString {
            append(base.trim())
            if (model.isNotBlank()) append(" -m ").append(dialect.envRef(ENV_MODEL))
            append(" -c ").append(dialect.envRef(ENV_TRUST))
            append(' ').append(dialect.envRef(ENV_TASK))
        }
        return dialect.argv(
            shellPath,
            dialect.unsetNames(inheritedMarkers()),
            command,
            execable = ShellDialect.isSimple(base),
        )
    }

    /** Ручной режим для Codex: значения вписаны в строку POSIX-кавычками. */
    private fun codexManualCommand(role: Role, model: String, env: Map<String, String>): String {
        val assignments = env.entries.joinToString(" ") { (key, value) -> "$key=${posixQuote(value)}" }
        val modelFlag = if (model.isNotBlank()) " -m \"${'$'}$ENV_MODEL\"" else ""
        return "$assignments ${settings.codexLaunchCommand} -p ${Codex.profileName(role)} " +
            "${settings.codexArgs.trim()}$modelFlag -c \"${'$'}$ENV_TRUST\" \"${'$'}$ENV_TASK\""
    }

    /**
     * Окружение b24-агента. Как у Codex, здесь же едут переменные части командной строки:
     * задача, промпт роли, модель, пути канала — в строке остаются только ссылки на них.
     */
    internal fun b24Env(
        role: Role,
        name: String,
        model: String,
        parentName: String?,
        parentSessionId: String?,
        task: String,
        channel: B24.Channel,
    ): Map<String, String> {
        val env = LinkedHashMap<String, String>()
        env["MJC_AGENT_NAME"] = name
        env["MJC_ROLE"] = role.id
        env["MJC_REPORTS_DIR"] = reportsDirectory().toString().replace(BACKSLASH, '/')
        parentName?.takeIf { it.isNotBlank() }?.let { env["MJC_PARENT"] = it.take(MAX_PARENT_NAME) }
        parentSessionId?.takeIf { it.isNotBlank() }?.let { env["MJC_PARENT_SESSION"] = it }
        if (task.isNotBlank()) env[ENV_TASK] = task
        env[ENV_ROLE_PROMPT] = dev.metajetcore.roles.Roles.promptFor(role, Harness.B24)
        env[ENV_B24_EVENTS] = channel.events.toString()
        env[ENV_B24_INPUT] = channel.input.toString()
        if (model.isNotBlank()) env[ENV_MODEL] = model
        env.putAll(settings.extraEnv())
        return env
    }

    /**
     * argv b24-агента. UUID сессии и режим прав — литералами: они из букв, цифр и дефисов.
     * Всё остальное — ссылками на окружение вкладки. Задача последней: `-i` — «выполнить и
     * остаться в интерактиве»; без задачи CLI стартует пустым и ждёт ввода.
     */
    internal fun b24Argv(sessionId: String, model: String, hasTask: Boolean): List<String> {
        val shellPath = Terminal.configuredShellPath(project) ?: ShellDialect.defaultShell()
        val dialect = ShellDialect.detect(shellPath)
        val base = settings.b24LaunchCommand.trim().ifEmpty { "b24-cowork-code-cli" }
        val command = buildString {
            append(base)
            append(" --session-id ").append(sessionId)
            B24.approvalMode(settings.b24ApprovalMode)?.let { append(" --approval-mode ").append(it) }
            if (model.isNotBlank()) append(" -m ").append(dialect.envRef(ENV_MODEL))
            settings.b24Args.trim().takeIf { it.isNotEmpty() }?.let { append(' ').append(it) }
            append(" --json-file ").append(dialect.envRef(ENV_B24_EVENTS))
            append(" --input-file ").append(dialect.envRef(ENV_B24_INPUT))
            append(" --append-system-prompt ").append(dialect.envRef(ENV_ROLE_PROMPT))
            if (hasTask) append(" -i ").append(dialect.envRef(ENV_TASK))
        }
        return dialect.argv(
            shellPath,
            dialect.unsetNames(inheritedMarkers()),
            command,
            execable = ShellDialect.isSimple(base),
        )
    }

    /** Ручной режим для b24: значения вписаны в строку POSIX-кавычками. */
    private fun b24ManualCommand(sessionId: String, model: String, env: Map<String, String>, hasTask: Boolean): String {
        val assignments = env.entries.joinToString(" ") { (key, value) -> "$key=${posixQuote(value)}" }
        val mode = B24.approvalMode(settings.b24ApprovalMode)?.let { " --approval-mode $it" }.orEmpty()
        val modelFlag = if (model.isNotBlank()) " -m \"${'$'}$ENV_MODEL\"" else ""
        val taskFlag = if (hasTask) " -i \"${'$'}$ENV_TASK\"" else ""
        return "$assignments ${settings.b24LaunchCommand} --session-id $sessionId$mode$modelFlag " +
            "${settings.b24Args.trim()} --json-file \"${'$'}$ENV_B24_EVENTS\" " +
            "--input-file \"${'$'}$ENV_B24_INPUT\" --append-system-prompt \"${'$'}$ENV_ROLE_PROMPT\"$taskFlag"
    }

    private fun posixQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * Окружение вкладки оркестратора.
     *
     * Роль здесь только меткой: `CLAUDE_CODE_AGENT` её НЕ применяет (§2.2.1), оркестратором
     * сессия становится по вызову `/orchestrate`. Метка нужна, чтобы оркестратора было видно
     * снаружи — и нам в `list_agents`, и сторонним наблюдателям. Без неё его опознают только
     * по имени вида `<префикс>-orc`, а имя разработчик волен задать любое.
     */
    internal fun orchestratorEnv(name: String): Map<String, String> = linkedMapOf(
        "CLAUDE_CODE_SESSION_NAME" to name,
        "CLAUDE_CODE_AGENT" to Role.ORCHESTRATOR.id,
    )

    /**
     * argv процесса вкладки агента: шелл IDE, который снимет унаследованные маркеры и
     * запустит команду с флагами (см. [ShellDialect.argv]).
     *
     * Шелл — из настроек терминала IDE, а не из окружения процесса IDE: вкладку, в которой
     * разработчик набрал бы команду сам, IDE открыла бы именно им. Раньше диалект брали из
     * `ComSpec`, и в PowerShell уезжал синтаксис cmd.
     *
     * Роль — флагом `--agent`: через `CLAUDE_CODE_AGENT` она пишется в реестр, но не
     * применяется (проверено). Режим прав — флагом `--permission-mode`: `permissions.
     * defaultMode` из настроек проекта Claude Code игнорирует, а без режима агент встанет на
     * первом же запросе прав во вкладке, которую никто не читает.
     */
    internal fun launchArgv(role: Role): List<String> {
        val shellPath = Terminal.configuredShellPath(project) ?: ShellDialect.defaultShell()
        val dialect = ShellDialect.detect(shellPath)
        return dialect.argv(shellPath, dialect.unsetNames(inheritedMarkers()), commandWithFlags(role))
    }

    private fun commandWithFlags(role: Role): String {
        val roleFlag = " --agent ${RoleInstaller.agentName(role)}"
        val modeFlag = settings.permissionMode
            .trim()
            .takeIf { it.isNotEmpty() && it.all { ch -> ch.isLetter() } }
            ?.let { " --permission-mode $it" }
            .orEmpty()
        return settings.launchCommand + roleFlag + modeFlag
    }

    /**
     * Команда для ручного режима: разработчик выполняет её сам, поэтому окружение здесь
     * приходится вписывать в строку — API вкладки в этом сценарии не участвует.
     *
     * Порядок частей важен: сначала снять унаследованное, потом задать своё. Иначе
     * присваивания достанутся команде `unset`, а до `claude` не доедут.
     *
     * Форма присваиваний — POSIX: команда предназначена человеку, а он выполнит её в том
     * шелле, где ему удобно. Для полностью корректного ручного запуска есть
     * `scripts/spawn-agent.sh`.
     */
    internal fun manualCommand(
        role: Role,
        name: String,
        model: String,
        parentName: String? = null,
        parentSessionId: String? = null,
    ): String {
        val shell = ShellDialect.detect(System.getenv("SHELL"))
        val env = agentEnv(role, name, model, parentName, parentSessionId)
            .entries.joinToString(" ") { (key, value) -> "$key='$value'" }
        return shell.unsetNames(inheritedMarkers()) + env + " " + commandWithFlags(role)
    }

    /**
     * Маркеры Claude Code, которые реально протекли в процесс IDE, — их и снимаем.
     *
     * Список имён здесь не зашит намеренно: он молча устарел бы, стоит Claude Code завести
     * новую переменную. Мы вместо этого смотрим на окружение самой IDE: агент наследует
     * именно его, значит вычистить надо ровно то, что там лежит.
     *
     * Зачем вообще: если IDE запущена из терминала, который сам живёт внутри сессии Claude
     * Code, маркеры доезжают до агента по цепочке процессов. `CLAUDE_CODE_CHILD_SESSION`
     * заставляет агента считать себя вложенным и не регистрироваться в реестре (снаружи это
     * выглядит как «процесс есть, а в ListAgents его нет»), а `CLAUDE_CODE_MESSAGING_SOCKET`
     * и `..._TOKEN` — это инбокс РОДИТЕЛЬСКОЙ сессии. `ANTHROPIC_API_KEY` молча уводит с
     * подписки на API-биллинг.
     */
    internal fun inheritedMarkers(): List<String> {
        val prefixes = buildList {
            if (settings.stripInheritedClaudeMarkers) add("CLAUDE")
            if (settings.stripApiKeys) add("ANTHROPIC_")
        }
        if (prefixes.isEmpty()) return emptyList()
        return System.getenv().keys
            .filter { key -> prefixes.any { key.startsWith(it) } }
            // Имя переменной, вставленное в командную строку, обязано быть безобидным.
            .filter { key -> key.all { it.isLetterOrDigit() || it == '_' } }
            .sorted()
    }

    /** Ждём, пока во вкладке поднимется шелл: до этого печатать бессмысленно. Для вкладки с шеллом. */
    private fun awaitTabRunning(handle: TabHandle): Boolean {
        val deadline = System.currentTimeMillis() + TAB_READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (runOnEdt { Terminal.isRunning(handle) }) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    private sealed interface Readiness {
        data class Ready(val record: SessionRecord) : Readiness
        data object Exited : Readiness
        data object Timeout : Readiness
    }

    /**
     * Ждём запись сессии в реестре — либо смерти процесса вкладки, что раньше.
     *
     * Появление записи означает, что claude поднялся и забиндил inbox-сокет, то есть готов
     * и к вводу, и к межсессионным сообщениям. Таймер вместо этого признака флакует: обёртка
     * может стартовать долго. Смерть процесса до регистрации — это ошибка запуска (команды
     * нет, обёртка упала), и ждать полный таймаут ради неё незачем.
     */
    private fun awaitSession(name: String, handle: TabHandle): Readiness {
        val deadline = System.currentTimeMillis() + settings.readyTimeoutSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            SessionRegistry.findByName(name)?.let { return Readiness.Ready(it) }
            val exited = runOnEdt { Terminal.isTerminated(handle) || !Terminal.isOpen(project, handle) }
            if (exited) {
                // Запись могла появиться в последний момент: агент поднялся и сразу вышел.
                return SessionRegistry.findByName(name)?.let { Readiness.Ready(it) } ?: Readiness.Exited
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return Readiness.Timeout
    }

    /**
     * Ждём, пока во вкладке не останется живого процесса: ни самой сессии, ни её потомков.
     *
     * Признак основной — состояние сессии вкладки. Дерево процессов по pid — второй:
     * агент мог оставить после себя живого потомка, и тогда платформа спросит подтверждение
     * даже при мёртвой сессии. Неизвестный pid (null) не считаем живым — решает тогда
     * состояние сессии.
     */
    private fun awaitTabDead(handle: TabHandle): Boolean {
        val deadline = System.currentTimeMillis() + tabDeadTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            val dead = runOnEdt { Terminal.isTerminated(handle) } &&
                Terminal.isProcessTreeAlive(handle) != true
            if (dead) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        log.warn("MetaJetCore: во вкладке '${handle.name}' остался живой процесс после /exit")
        return false
    }

    /** Вкладка агента закрывается сама вслед за процессом — но не мгновенно. */
    private fun awaitTabClosed(handle: TabHandle): Boolean {
        val deadline = System.currentTimeMillis() + TAB_CLOSE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!runOnEdt { Terminal.isOpen(project, handle) }) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    private fun awaitSessionGone(name: String) {
        val deadline = System.currentTimeMillis() + EXIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (SessionRegistry.findByName(name) == null) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        log.warn("MetaJetCore: сессия '$name' осталась в реестре после /exit")
    }

    /**
     * Работа с терминалом обязана идти в EDT, а MCP-запросы приходят на своих потоках.
     * invokeAndWait, а не invokeLater: вызывающему нужен результат, чтобы решить, не пора
     * ли деградировать в ручной режим.
     */
    private fun <T> runOnEdt(block: () -> T): T {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) return block()
        var result: T? = null
        var failure: Throwable? = null
        app.invokeAndWait {
            try {
                result = block()
            } catch (e: Throwable) {
                failure = e
            }
        }
        failure?.let {
            log.warn("MetaJetCore: операция в EDT упала", it)
            throw it
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private companion object {
        val BACKSLASH: Char = 92.toChar()

        /** Длина, по которой имя родителя режет приёмник; режем сами, чтобы совпадало. */
        const val MAX_PARENT_NAME = 64

        const val POLL_INTERVAL_MS = 250L
        const val EXIT_TIMEOUT_MS = 15_000L
        const val TAB_READY_TIMEOUT_MS = 20_000L
        const val TAB_CLOSE_TIMEOUT_MS = 3_000L
        const val KILL_GRACE_MS = 3_000L

        /** Задача по умолчанию: без первого сообщения сессия Codex не создаётся. */
        const val CODEX_IDLE_TASK = "Задач пока нет. Ответь одной строкой: STATUS: DONE"

        const val ENV_TASK = "MJC_TASK"
        const val ENV_TRUST = "MJC_CODEX_TRUST"
        const val ENV_MODEL = "MJC_MODEL"
        const val ENV_THREAD = "MJC_THREAD"
        const val ENV_MESSAGE = "MJC_MESSAGE"
        const val ENV_ROLE_PROMPT = "MJC_ROLE_PROMPT"
        const val ENV_B24_EVENTS = "MJC_B24_EVENTS"
        const val ENV_B24_INPUT = "MJC_B24_INPUT"

        const val B24_CLEAR = "/clear"

        /** CLI опрашивает input раз в 500 мс; после `/clear` выжидаем с запасом. */
        const val B24_CLEAR_HOLD_MS = 1_500L
        const val OUTBOX_PUMP_MS = 1_000L

        const val DELIVERY_TIMEOUT_MS = 30_000L
        const val MAILBOX_JOURNAL = "mailbox.jsonl"

        /** `agentKey` Claude Code в списке агентов терминала IDE — для иконки вкладки. */
        const val CLAUDE_AGENT_KEY = "claude_code"

        /**
         * Хвост, которым `ListAgents` дописывает к имени сессии её ref: `имя [815818]`.
         *
         * Ref — шестнадцатеричный, длину не фиксируем: она может подрасти, когда одного
         * префикса перестанет хватать на различение.
         */
        val REF_SUFFIX = Regex("""\s*\[[0-9a-fA-F]{4,32}]${'$'}""")
    }
}
