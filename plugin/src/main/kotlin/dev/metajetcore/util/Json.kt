package dev.metajetcore.util

/**
 * Минимальный JSON без внешних зависимостей.
 *
 * Обоснование в docs/ARCHITECTURE.md: любая сторонняя библиотека в плагине — это шанс
 * конфликтнуть с версией, которую тащит платформа, а конфликт классов проявляется как
 * падение плагина после апгрейда IDE. Объём JSON здесь маленький (JSON-RPC MCP и файлы
 * реестра сессий), так что 200 строк своего кода дешевле любой зависимости.
 */
sealed interface Json {
    data object Null : Json
    data class Bool(val value: Boolean) : Json
    data class Num(val value: Double) : Json
    data class Str(val value: String) : Json
    data class Arr(val items: List<Json>) : Json
    data class Obj(val fields: Map<String, Json>) : Json

    companion object {
        fun of(value: String?): Json = if (value == null) Null else Str(value)
        fun of(value: Boolean): Json = Bool(value)
        fun of(value: Int): Json = Num(value.toDouble())
        fun of(value: Long): Json = Num(value.toDouble())

        fun obj(vararg pairs: Pair<String, Json>): Obj = Obj(linkedMapOf(*pairs))
        fun arr(items: List<Json>): Arr = Arr(items)

        fun parse(text: String): Json = Parser(text).run {
            val value = parseValue()
            skipWhitespace()
            require(atEnd()) { "trailing content at offset $position" }
            value
        }

        /** Возвращает null вместо исключения — для чтения возможно повреждённых файлов реестра. */
        fun parseOrNull(text: String): Json? = try {
            parse(text)
        } catch (_: Exception) {
            null
        }
    }

    // --- удобные аксессоры: не бросают, возвращают null ---

    operator fun get(key: String): Json? = (this as? Obj)?.fields?.get(key)

    val asString: String? get() = (this as? Str)?.value
    val asBoolean: Boolean? get() = (this as? Bool)?.value
    val asInt: Int? get() = (this as? Num)?.value?.toInt()
    val asLong: Long? get() = (this as? Num)?.value?.toLong()
    val asList: List<Json>? get() = (this as? Arr)?.items
    val asMap: Map<String, Json>? get() = (this as? Obj)?.fields

    fun render(): String = StringBuilder().also { write(it) }.toString()

    private fun write(out: StringBuilder) {
        when (this) {
            is Null -> out.append("null")
            is Bool -> out.append(if (value) "true" else "false")
            is Num -> {
                val whole = value.toLong()
                if (value == whole.toDouble() && !value.isInfinite()) out.append(whole)
                else out.append(value)
            }
            is Str -> escape(value, out)
            is Arr -> {
                out.append('[')
                items.forEachIndexed { i, item ->
                    if (i > 0) out.append(',')
                    item.write(out)
                }
                out.append(']')
            }
            is Obj -> {
                out.append('{')
                var first = true
                for ((key, value) in fields) {
                    if (!first) out.append(',')
                    first = false
                    escape(key, out)
                    out.append(':')
                    value.write(out)
                }
                out.append('}')
            }
        }
    }

    private fun escape(text: String, out: StringBuilder) {
        out.append('"')
        for (ch in text) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                else ->
                    // сюда попадает и form feed: управляющие символы уезжают как \u00xx,
                    // что валидно для JSON и избавляет от литерала-невидимки в исходнике
                    if (ch < ' ') out.append("\\u%04x".format(ch.code))
                    else out.append(ch)
            }
        }
        out.append('"')
    }
}

private val FORM_FEED: Char = 12.toChar()

private class Parser(private val text: String) {
    var position = 0

    fun atEnd(): Boolean = position >= text.length

    fun skipWhitespace() {
        while (position < text.length && text[position].isWhitespace()) position++
    }

    fun parseValue(): Json {
        skipWhitespace()
        require(!atEnd()) { "unexpected end of input" }
        return when (val ch = text[position]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> Json.Str(parseString())
            't' -> literal("true", Json.Bool(true))
            'f' -> literal("false", Json.Bool(false))
            'n' -> literal("null", Json.Null)
            else ->
                if (ch == '-' || ch.isDigit()) parseNumber()
                else error("unexpected character '$ch' at offset $position")
        }
    }

    private fun literal(word: String, value: Json): Json {
        require(text.startsWith(word, position)) { "expected $word at offset $position" }
        position += word.length
        return value
    }

    private fun parseObject(): Json.Obj {
        position++ // '{'
        val fields = LinkedHashMap<String, Json>()
        skipWhitespace()
        if (!atEnd() && text[position] == '}') {
            position++
            return Json.Obj(fields)
        }
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            require(!atEnd() && text[position] == ':') { "expected ':' at offset $position" }
            position++
            fields[key] = parseValue()
            skipWhitespace()
            require(!atEnd()) { "unterminated object" }
            when (text[position]) {
                ',' -> position++
                '}' -> { position++; return Json.Obj(fields) }
                else -> error("expected ',' or '}' at offset $position")
            }
        }
    }

    private fun parseArray(): Json.Arr {
        position++ // '['
        val items = ArrayList<Json>()
        skipWhitespace()
        if (!atEnd() && text[position] == ']') {
            position++
            return Json.Arr(items)
        }
        while (true) {
            items.add(parseValue())
            skipWhitespace()
            require(!atEnd()) { "unterminated array" }
            when (text[position]) {
                ',' -> position++
                ']' -> { position++; return Json.Arr(items) }
                else -> error("expected ',' or ']' at offset $position")
            }
        }
    }

    private fun parseString(): String {
        require(!atEnd() && text[position] == '"') { "expected string at offset $position" }
        position++
        val out = StringBuilder()
        while (true) {
            require(!atEnd()) { "unterminated string" }
            when (val ch = text[position++]) {
                '"' -> return out.toString()
                '\\' -> {
                    require(!atEnd()) { "unterminated escape" }
                    when (val esc = text[position++]) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'b' -> out.append('\b')
                        'f' -> out.append(FORM_FEED)
                        'u' -> {
                            require(position + 4 <= text.length) { "truncated \\u escape" }
                            out.append(text.substring(position, position + 4).toInt(16).toChar())
                            position += 4
                        }
                        else -> error("bad escape '\\$esc' at offset ${position - 1}")
                    }
                }
                else -> out.append(ch)
            }
        }
    }

    private fun parseNumber(): Json.Num {
        val start = position
        if (!atEnd() && text[position] == '-') position++
        while (!atEnd() && (text[position].isDigit() || text[position] in ".eE+-")) position++
        val slice = text.substring(start, position)
        return Json.Num(slice.toDoubleOrNull() ?: error("bad number '$slice' at offset $start"))
    }
}
