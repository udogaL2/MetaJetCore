package dev.metajetcore.mcp

import com.intellij.openapi.diagnostic.Logger
import dev.metajetcore.util.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * Прописывает MCP-сервер плагина в конфигурацию Claude Code.
 *
 * Пишем ровно туда же, куда написала бы команда `claude mcp add --scope local`:
 * `~/.claude.json` -> `projects["<путь проекта>"].mcpServers.metajetcore`. Своих ключей не
 * заводим, формат не выдумываем.
 *
 * Зачем вообще: иначе установка требует ручного шага — найти пункт меню, скопировать команду,
 * выполнить её в терминале. Шаг ломкий (при установке плагина без перезапуска IDE меню вообще
 * не перестраивается) и повторяется при каждой смене порта.
 *
 * Запись идёт **по ключу проекта**, поэтому два окна IDE с разными проектами не мешают друг
 * другу: у каждого свой ключ и свой порт. Единственный конфликтный случай — один и тот же
 * проект, открытый в двух IDE; там второй экземпляр не займёт предпочтительный порт, и
 * вызывающий обязан в этом случае конфиг не трогать (см. [McpServerService]).
 *
 * Файл принадлежит Claude Code, поэтому: читаем-меняем-пишем целиком, трогаем только свой
 * ключ, сохраняем всё остальное как есть, и подменяем файл атомарно через временный.
 */
object McpConfigWriter {
    private val log = Logger.getInstance(McpConfigWriter::class.java)

    private const val SERVER_NAME = "metajetcore"

    fun configPath(): Path {
        val home = System.getenv("CLAUDE_CONFIG_DIR")?.takeIf { it.isNotBlank() }
            ?.let { Paths.get(it, ".claude.json") }
        return home ?: Paths.get(System.getProperty("user.home"), ".claude.json")
    }

    /** Уже ли прописан наш сервер с этим самым URL. */
    fun isUpToDate(projectPath: String, url: String): Boolean {
        val root = readConfig() ?: return false
        val existing = root["projects"]?.get(normalize(projectPath))
            ?.get("mcpServers")?.get(SERVER_NAME)?.get("url")?.asString
        return existing == url
    }

    /**
     * Прописывает сервер. Возвращает false, если записать не удалось — тогда вызывающий
     * сообщает разработчику, что подключать придётся руками.
     */
    fun write(projectPath: String, url: String): Boolean {
        val path = configPath()
        val key = normalize(projectPath)

        return try {
            val root = readConfig() ?: Json.obj()
            val updated = withServer(root, key, url)

            // Атомарная подмена: файл принадлежит Claude Code, оборванная запись сделала бы
            // его невалидным и сломала бы не только нас.
            val temp = Files.createTempFile(path.parent ?: Paths.get("."), ".claude", ".json.tmp")
            Files.write(temp, updated.render().toByteArray(StandardCharsets.UTF_8))
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                // ATOMIC_MOVE поддерживают не все файловые системы.
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
            log.info("MetaJetCore: $SERVER_NAME прописан в $path для $key -> $url")
            true
        } catch (e: Exception) {
            log.warn("MetaJetCore: не удалось записать $path", e)
            false
        }
    }

    /** Копия конфигурации с добавленным сервером; всё остальное сохраняется как было. */
    private fun withServer(root: Json, projectKey: String, url: String): Json {
        val server = Json.obj(
            "type" to Json.of("http"),
            "url" to Json.of(url),
        )

        val projects = root["projects"]?.asMap.orEmpty()
        val project = projects[projectKey]?.asMap.orEmpty()
        val servers = project["mcpServers"]?.asMap.orEmpty()

        val newServers = LinkedHashMap(servers).apply { put(SERVER_NAME, server) }
        val newProject = LinkedHashMap(project).apply { put("mcpServers", Json.Obj(newServers)) }
        val newProjects = LinkedHashMap(projects).apply { put(projectKey, Json.Obj(newProject)) }
        val newRoot = LinkedHashMap(root.asMap.orEmpty()).apply { put("projects", Json.Obj(newProjects)) }

        return Json.Obj(newRoot)
    }

    private fun readConfig(): Json? = try {
        val path = configPath()
        if (!Files.exists(path)) {
            null
        } else {
            Json.parseOrNull(Files.readString(path, StandardCharsets.UTF_8))
        }
    } catch (e: Exception) {
        log.warn("MetaJetCore: не удалось прочитать ${configPath()}", e)
        null
    }

    /** Claude Code хранит пути проектов с прямыми слэшами и без хвостового разделителя. */
    private fun normalize(projectPath: String): String =
        projectPath.replace(BACKSLASH, '/').trimEnd('/')

    private val BACKSLASH: Char = 92.toChar()
}
