package dev.metajetcore.agents

import com.intellij.openapi.diagnostic.Logger
import dev.metajetcore.harness.Harness
import dev.metajetcore.registry.SessionRecord
import dev.metajetcore.roles.Role
import dev.metajetcore.util.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Агент группы: кто его запустил и как его найти снова.
 *
 * @param owner ключ группы — sessionId оркестратора, запустившего агента.
 * @param pid процесс агента: у Claude — сам `claude` (из реестра), у Codex и b24 — процесс
 *   вкладки. По нему агент узнаётся после `/clear` (sessionId Claude меняется) и после
 *   перезагрузки плагина.
 * @param procStart время старта процесса Claude из реестра: pid переиспользуется, и без этого
 *   новый чужой процесс с тем же pid стал бы «нашим агентом».
 * @param sessionId адрес не-Claude агента: тред Codex или сессия b24.
 */
data class TeamMember(
    val name: String,
    val owner: String,
    val harness: Harness,
    val role: Role?,
    val model: String,
    val pid: Long?,
    val procStart: String? = null,
    val sessionId: String? = null,
    val rollout: String? = null,
    val channel: String? = null,
)

/** Оркестратор-владелец группы: sessionId и процесс, в котором он живёт. */
data class TeamOwner(val sessionId: String, val pid: Long?, val procStart: String?)

/**
 * Состав групп: какой оркестратор каких агентов запустил (docs/PLAN-TEAM.md).
 *
 * Всё, что плагин делает с агентами, ограничено группой вызывающего, и эта граница не может
 * жить только в памяти IDE: плагин перезагружается при обновлении, а агенты в терминалах
 * остаются живыми — и без записи на диске стали бы ничьими. Файл — плагина, не Claude Code:
 * реестр `~/.claude/sessions` мы только читаем.
 *
 * Читаем-меняем-пишем целиком под замком, запись — через временный файл и перемещение:
 * оборванная запись не должна стереть группы.
 */
class TeamStore(private val file: Path) {
    private val log = Logger.getInstance(TeamStore::class.java)
    private val lock = Any()
    private var loaded = false
    private val owners = LinkedHashMap<String, TeamOwner>()
    private val members = ArrayList<TeamMember>()

    /**
     * Ключ группы вызывающего.
     *
     * `/clear` у оркестратора меняет его sessionId, а процесс остаётся тем же. Группа при этом
     * не должна пропадать — её агенты живы и работают. Поэтому: группы под этим sessionId нет,
     * но есть группа, владелец которой — тот же процесс (pid и время старта совпадают), —
     * группа переезжает на новый sessionId.
     */
    fun ownerKey(caller: SessionRecord): String = synchronized(lock) {
        load()
        val key = caller.sessionId
        if (owners.containsKey(key)) {
            refreshOwner(caller)
            return key
        }
        val previous = owners.values.firstOrNull { owner ->
            caller.procStart != null && owner.pid == caller.pid.toLong() && owner.procStart == caller.procStart
        }
        if (previous != null) {
            log.info("MetaJetCore: группа ${previous.sessionId} переезжает на $key — тот же процесс оркестратора")
            owners.remove(previous.sessionId)
            members.replaceAll { if (it.owner == previous.sessionId) it.copy(owner = key) else it }
        }
        refreshOwner(caller)
        save()
        key
    }

    private fun refreshOwner(caller: SessionRecord) {
        val fresh = TeamOwner(caller.sessionId, caller.pid.toLong(), caller.procStart)
        if (owners[caller.sessionId] != fresh) {
            owners[caller.sessionId] = fresh
            if (members.any { it.owner == caller.sessionId }) save()
        }
    }

    fun members(owner: String): List<TeamMember> = synchronized(lock) {
        load()
        members.filter { it.owner == owner }
    }

    fun all(): List<TeamMember> = synchronized(lock) {
        load()
        members.toList()
    }

    fun add(member: TeamMember) = synchronized(lock) {
        load()
        members.removeAll { it.name == member.name }
        members += member
        save()
    }

    fun remove(member: TeamMember) = synchronized(lock) {
        load()
        if (members.remove(member)) save()
    }

    fun replace(old: TeamMember, new: TeamMember) = synchronized(lock) {
        load()
        val i = members.indexOf(old)
        if (i >= 0 && members[i] != new) {
            members[i] = new
            save()
        }
    }

    // ------------------------------------------------------------- disk

    private fun load() {
        if (loaded) return
        loaded = true
        val root = try {
            if (!Files.isRegularFile(file)) return
            Json.parseOrNull(Files.readString(file)) as? Json.Obj ?: return
        } catch (e: Exception) {
            log.warn("MetaJetCore: состав групп $file не прочитан", e)
            return
        }
        root["owners"]?.asList.orEmpty().forEach { o ->
            val id = o["sessionId"]?.asString ?: return@forEach
            owners[id] = TeamOwner(id, o["pid"]?.asLong, o["procStart"]?.asString)
        }
        root["members"]?.asList.orEmpty().forEach { m ->
            members += TeamMember(
                name = m["name"]?.asString ?: return@forEach,
                owner = m["owner"]?.asString ?: return@forEach,
                harness = Harness.fromKey(m["harness"]?.asString) ?: return@forEach,
                role = m["role"]?.asString?.let { Role.fromId(it) },
                model = m["model"]?.asString.orEmpty(),
                pid = m["pid"]?.asLong,
                procStart = m["procStart"]?.asString,
                sessionId = m["sessionId"]?.asString,
                rollout = m["rollout"]?.asString,
                channel = m["channel"]?.asString,
            )
        }
    }

    private fun save() {
        // Владельца пишем, только пока у него есть агенты: группа без агентов не нужна.
        // В памяти не чистим — иначе каждый вызов такого оркестратора переписывал бы файл.
        val json = Json.obj(
            "owners" to Json.arr(owners.values.filter { o -> members.any { it.owner == o.sessionId } }.map { o ->
                Json.obj(
                    "sessionId" to Json.of(o.sessionId),
                    "pid" to (o.pid?.let { Json.of(it) } ?: Json.Null),
                    "procStart" to Json.of(o.procStart),
                )
            }),
            "members" to Json.arr(members.map { m ->
                Json.obj(
                    "name" to Json.of(m.name),
                    "owner" to Json.of(m.owner),
                    "harness" to Json.of(m.harness.key),
                    "role" to (m.role?.let { Json.of(it.id) } ?: Json.Null),
                    "model" to Json.of(m.model),
                    "pid" to (m.pid?.let { Json.of(it) } ?: Json.Null),
                    "procStart" to Json.of(m.procStart),
                    "sessionId" to Json.of(m.sessionId),
                    "rollout" to Json.of(m.rollout),
                    "channel" to Json.of(m.channel),
                )
            }),
        )
        try {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(tmp, json.render())
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            log.warn("MetaJetCore: состав групп не записан в $file", e)
        }
    }
}
