package x.vladgba.sync.core.util

/**
 * Minimal JSON reader/writer (no dependencies).
 * Values: Map<String, Any?>, List<Any?>, String, Long, Double, Boolean, null.
 */
object Json {
    fun stringify(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) p.fail("trailing data")
        return v
    }

    fun parseObject(text: String): JObj = JObj(parse(text) as? Map<String, Any?> ?: throw JsonException("expected object"))

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Double -> if (v.isFinite()) sb.append(v) else sb.append("null")
            is Float -> write(sb, v.toDouble())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString())
                    sb.append(':')
                    write(sb, x)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, x)
                }
                sb.append(']')
            }
            is Enum<*> -> quote(sb, v.name)
            else -> throw JsonException("can't serialize ${v.javaClass}")
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun fail(msg: String): Nothing = throw JsonException("$msg at $i")

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            if (i >= s.length) fail("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else fail("unexpected '$c'")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("bad literal")
            i += word.length
            return v
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (s.getOrNull(i) == '}') {
                i++; return m
            }
            while (true) {
                ws()
                if (s.getOrNull(i) != '"') fail("expected key")
                val k = str()
                ws()
                if (s.getOrNull(i) != ':') fail("expected ':'")
                i++
                ws()
                m[k] = value()
                ws()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    '}' -> {
                        i++; return m
                    }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            ws()
            if (s.getOrNull(i) == ']') {
                i++; return l
            }
            while (true) {
                ws()
                l += value()
                ws()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    ']' -> {
                        i++; return l
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) fail("bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("bad unicode escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> fail("bad escape '$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: fail("bad number '$t'")
        }
    }
}

class JsonException(message: String) : RuntimeException(message)

/** Typed accessors over a parsed JSON object. */
class JObj(val map: Map<String, Any?>) {
    fun has(k: String) = map[k] != null
    fun str(k: String): String = map[k] as? String ?: throw JsonException("missing string '$k'")
    fun strOr(k: String, d: String?): String? = map[k] as? String ?: d
    fun long(k: String): Long = (map[k] as? Number)?.toLong() ?: throw JsonException("missing number '$k'")
    fun longOr(k: String, d: Long?): Long? = (map[k] as? Number)?.toLong() ?: d
    fun int(k: String): Int = long(k).toInt()
    fun bool(k: String, d: Boolean = false): Boolean = map[k] as? Boolean ?: d
    fun obj(k: String): JObj = JObj(map[k] as? Map<String, Any?> ?: throw JsonException("missing object '$k'"))
    fun objOrNull(k: String): JObj? = (map[k] as? Map<String, Any?>)?.let { JObj(it) }
    fun list(k: String): List<Any?> = map[k] as? List<Any?> ?: emptyList()
    fun objList(k: String): List<JObj> = list(k).map { JObj(it as? Map<String, Any?> ?: throw JsonException("bad item in '$k'")) }
    fun strList(k: String): List<String> = list(k).map { it as? String ?: throw JsonException("bad item in '$k'") }
}
