package io.github.evgenykon.mybooks.core

/**
 * Minimal, dependency-free XML reader used for FB2 parsing.
 *
 * FB2 files in the wild are large (a few MB of markup) but simple; a tolerant
 * tokenizer that builds a light tree is enough and keeps the shared module free
 * of third-party dependencies (important for Kotlin/Native + Swift interop).
 */
internal class XmlNode(
    val name: String,
    val attrs: Map<String, String>,
    val children: MutableList<Any> = mutableListOf(),
) {
    fun elements(): List<XmlNode> = children.filterIsInstance<XmlNode>()

    fun childrenNamed(n: String): List<XmlNode> = elements().filter { it.name == n }

    fun firstChild(n: String): XmlNode? = elements().firstOrNull { it.name == n }

    fun attr(name: String): String? = attrs[name]

    /** Concatenated text of the subtree, entities already decoded. */
    fun text(): String {
        val sb = StringBuilder()
        collectText(sb)
        return sb.toString()
    }

    private fun collectText(sb: StringBuilder) {
        for (c in children) {
            when (c) {
                is String -> sb.append(c)
                is XmlNode -> c.collectText(sb)
            }
        }
    }
}

internal object Xml {

    fun parse(source: String): XmlNode {
        val root = XmlNode("#document", emptyMap())
        val stack = mutableListOf(root)
        val pendingText = StringBuilder()
        val n = source.length
        var i = 0

        fun flushText() {
            if (pendingText.isNotEmpty()) {
                val decoded = Entities.decode(pendingText.toString())
                if (decoded.isNotEmpty()) stack.last().children.add(decoded)
                pendingText.clear()
            }
        }

        while (i < n) {
            val ch = source[i]
            if (ch != '<') {
                pendingText.append(ch)
                i++
                continue
            }
            when {
                source.startsWith("<!--", i) -> {
                    val e = source.indexOf("-->", i)
                    i = if (e < 0) n else e + 3
                }
                source.startsWith("<![CDATA[", i) -> {
                    val e = source.indexOf("]]>", i)
                    val end = if (e < 0) n else e
                    pendingText.append(source, i + 9, end)
                    i = if (e < 0) n else e + 3
                }
                source.startsWith("<?", i) -> {
                    val e = source.indexOf("?>", i)
                    i = if (e < 0) n else e + 2
                }
                source.startsWith("<!", i) -> {
                    val e = source.indexOf('>', i)
                    i = if (e < 0) n else e + 1
                }
                else -> {
                    flushText()
                    val close = source.indexOf('>', i)
                    if (close < 0) break
                    var tag = source.substring(i + 1, close).trim()
                    val selfClosing = tag.endsWith("/")
                    if (selfClosing) tag = tag.dropLast(1).trim()
                    if (tag.startsWith("/")) {
                        val name = localName(tag.substring(1).trim().substringBefore(' '))
                        var depth = stack.size - 1
                        while (depth > 0) {
                            if (stack[depth].name == name) break
                            depth--
                        }
                        if (depth > 0) {
                            while (stack.size > depth) stack.removeAt(stack.size - 1)
                        }
                    } else {
                        val name = localName(tag.substringBefore(' ').substringBefore('\n').substringBefore('\t'))
                        val attrs = parseAttributes(tag)
                        val node = XmlNode(name, attrs)
                        stack.last().children.add(node)
                        if (!selfClosing) stack.add(node)
                    }
                    i = close + 1
                }
            }
        }
        flushText()
        return root
    }

    private fun localName(raw: String): String {
        val idx = raw.indexOf(':')
        return if (idx >= 0) raw.substring(idx + 1) else raw
    }

    private fun parseAttributes(tag: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var i = 0
        val n = tag.length
        // skip element name
        while (i < n && !tag[i].isWhitespace()) i++
        while (i < n) {
            while (i < n && tag[i].isWhitespace()) i++
            if (i >= n) break
            val nameStart = i
            while (i < n && tag[i] != '=' && !tag[i].isWhitespace()) i++
            if (i >= n) break
            val name = localName(tag.substring(nameStart, i))
            while (i < n && tag[i].isWhitespace()) i++
            if (i >= n || tag[i] != '=') {
                if (name.isNotEmpty()) out[name] = ""
                continue
            }
            i++ // '='
            while (i < n && tag[i].isWhitespace()) i++
            if (i >= n) break
            val quote = tag[i]
            val value: String
            if (quote == '"' || quote == '\'') {
                i++
                val start = i
                while (i < n && tag[i] != quote) i++
                value = tag.substring(start, i)
                if (i < n) i++
            } else {
                val start = i
                while (i < n && !tag[i].isWhitespace()) i++
                value = tag.substring(start, i)
            }
            if (name.isNotEmpty()) out[name] = Entities.decode(value)
        }
        return out
    }
}

internal object Entities {
    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to "\u00A0", "mdash" to "\u2014", "ndash" to "\u2013", "hellip" to "\u2026",
        "laquo" to "\u00AB", "raquo" to "\u00BB", "copy" to "\u00A9", "reg" to "\u00AE",
        "deg" to "\u00B0", "plusmn" to "\u00B1", "sect" to "\u00A7", "para" to "\u00B6",
        "middot" to "\u00B7", "bull" to "\u2022", "dagger" to "\u2020", "times" to "\u00D7",
        "eacute" to "\u00E9", "egrave" to "\u00E8", "agrave" to "\u00E0", "ccedil" to "\u00E7",
        "uuml" to "\u00FC", "ouml" to "\u00F6", "auml" to "\u00E4", "szlig" to "\u00DF",
        "shy" to "\u00AD", "thinsp" to "\u2009", "ensp" to "\u2002", "emsp" to "\u2003",
    )

    fun decode(input: String): String {
        if (input.indexOf('&') < 0) return input
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '&') {
                val semi = input.indexOf(';', i)
                if (semi > i && semi - i <= 12) {
                    val body = input.substring(i + 1, semi)
                    val replacement = resolve(body)
                    if (replacement != null) {
                        sb.append(replacement)
                        i = semi + 1
                        continue
                    }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun resolve(body: String): String? {
        if (body.startsWith("#")) {
            val code = if (body.length > 1 && (body[1] == 'x' || body[1] == 'X')) {
                body.substring(2).toIntOrNull(16)
            } else {
                body.substring(1).toIntOrNull(10)
            }
            if (code == null || code < 0 || code > 0x10FFFF) return null
            return codePointToString(code)
        }
        return named[body]
    }

    private fun codePointToString(code: Int): String = when {
        code < 0x80 -> code.toChar().toString()
        code < 0x800 -> {
            val c1 = (0xC0 or (code shr 6)).toChar()
            val c2 = (0x80 or (code and 0x3F)).toChar()
            "$c1$c2"
        }
        code < 0x10000 -> {
            val c1 = (0xE0 or (code shr 12)).toChar()
            val c2 = (0x80 or ((code shr 6) and 0x3F)).toChar()
            val c3 = (0x80 or (code and 0x3F)).toChar()
            "$c1$c2$c3"
        }
        else -> {
            val v = code - 0x10000
            val c1 = (0xF0 or (v shr 18)).toChar()
            val c2 = (0x80 or ((v shr 12) and 0x3F)).toChar()
            val c3 = (0x80 or ((v shr 6) and 0x3F)).toChar()
            val c4 = (0x80 or (v and 0x3F)).toChar()
            "$c1$c2$c3$c4"
        }
    }
}

/** Byte -> text decoding for the encodings FB2 files actually use. */
internal object Charsets {

    fun detectEncoding(bytes: ByteArray): String {
        val head = StringBuilder()
        var i = 0
        while (i < bytes.size && i < 512 && head.length < 512) {
            val b = bytes[i].toInt() and 0xFF
            head.append(b.toChar())
            i++
        }
        val decl = head.toString()
        val idx = decl.indexOf("encoding", ignoreCase = true)
        if (idx >= 0) {
            val eq = decl.indexOf('=', idx)
            if (eq > 0) {
                var j = eq + 1
                while (j < decl.length && decl[j] != '"' && decl[j] != '\'') j++
                if (j < decl.length) {
                    val quote = decl[j]
                    val end = decl.indexOf(quote, j + 1)
                    if (end > j) {
                        return decl.substring(j + 1, end).trim().lowercase()
                    }
                }
                var k = eq + 1
                while (k < decl.length && decl[k].isWhitespace()) k++
                val start = k
                while (k < decl.length && !decl[k].isWhitespace() && decl[k] != '?') k++
                if (k > start) return decl.substring(start, k).trim().lowercase()
            }
        }
        // No declaration: try UTF-8, fall back to cp1251.
        return if (isValidUtf8(bytes)) "utf-8" else "windows-1251"
    }

    fun decode(bytes: ByteArray, encodingHint: String? = null): String {
        var data = bytes
        // strip BOM
        if (data.size >= 3 && (data[0].toInt() and 0xFF) == 0xEF &&
            (data[1].toInt() and 0xFF) == 0xBB && (data[2].toInt() and 0xFF) == 0xBF
        ) {
            data = data.copyOfRange(3, data.size)
        }
        val enc = (encodingHint ?: detectEncoding(data)).lowercase()
        return when {
            enc.contains("1251") || enc == "cp1251" || enc == "win-1251" -> decodeSingleByte(data, CP1251_UPPER)
            enc.contains("koi8") -> decodeSingleByte(data, KOI8R_UPPER)
            enc.contains("1252") || enc.contains("latin1") || enc.contains("8859") ->
                decodeSingleByte(data, LATIN1_UPPER)
            enc.contains("utf-16") -> decodeUtf16(data)
            else -> data.decodeToString()
        }
    }

    private fun decodeSingleByte(data: ByteArray, upper: String): String {
        val sb = StringBuilder(data.size)
        for (b in data) {
            val v = b.toInt() and 0xFF
            if (v < 0x80) sb.append(v.toChar()) else sb.append(upper[v - 0x80])
        }
        return sb.toString()
    }

    private fun decodeUtf16(data: ByteArray): String {
        val sb = StringBuilder(data.size / 2)
        var i = 0
        while (i + 1 < data.size) {
            val lo = data[i].toInt() and 0xFF
            val hi = data[i + 1].toInt() and 0xFF
            sb.append(((hi shl 8) or lo).toChar())
            i += 2
        }
        return sb.toString()
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean {
        var i = 0
        val n = bytes.size
        while (i < n) {
            val b = bytes[i].toInt() and 0xFF
            if (b < 0x80) {
                i++
                continue
            }
            val need = when {
                b and 0xE0 == 0xC0 -> 1
                b and 0xF0 == 0xE0 -> 2
                b and 0xF8 == 0xF0 -> 3
                else -> return false
            }
            if (i + need >= n) return true
            for (k in 1..need) {
                if ((bytes[i + k].toInt() and 0xC0) != 0x80) return false
            }
            i += need + 1
        }
        return true
    }

    private const val CP1251_UPPER =
        "\u0402\u0403\u201A\u0453\u201E\u2026\u2020\u2021\u20AC\u2030\u0409\u2039\u040A\u040C\u040B\u040F" +
            "\u0452\u2018\u2019\u201C\u201D\u2022\u2013\u2014\u0098\u2122\u0459\u203A\u045A\u045C\u045B\u045F" +
            "\u00A0\u040E\u045E\u0408\u00A4\u0490\u00A6\u00A7\u0401\u00A9\u0404\u00AB\u00AC\u00AD\u00AE\u0407" +
            "\u00B0\u00B1\u0406\u0456\u0491\u00B5\u00B6\u00B7\u0451\u2116\u0454\u00BB\u0458\u0405\u0455\u0457" +
            "\u0410\u0411\u0412\u0413\u0414\u0415\u0416\u0417\u0418\u0419\u041A\u041B\u041C\u041D\u041E\u041F" +
            "\u0420\u0421\u0422\u0423\u0424\u0425\u0426\u0427\u0428\u0429\u042A\u042B\u042C\u042D\u042E\u042F" +
            "\u0430\u0431\u0432\u0433\u0434\u0435\u0436\u0437\u0438\u0439\u043A\u043B\u043C\u043D\u043E\u043F" +
            "\u0440\u0441\u0442\u0443\u0444\u0445\u0446\u0447\u0448\u0449\u044A\u044B\u044C\u044D\u044E\u044F"

    private const val KOI8R_UPPER =
        "\u2500\u2502\u250C\u2510\u2514\u2518\u251C\u2524\u252C\u2534\u253C\u2580\u2584\u2588\u258C\u2590" +
            "\u2591\u2592\u2593\u2320\u25A0\u2219\u221A\u2248\u2264\u2265\u00A0\u2321\u00B0\u00B2\u00B7\u00F7" +
            "\u2550\u2551\u2552\u0451\u2553\u2554\u2555\u2556\u2557\u2558\u2559\u255A\u255B\u255C\u255D\u255E" +
            "\u255F\u2560\u2561\u0401\u2562\u2563\u2564\u2565\u2566\u2567\u2568\u2569\u256A\u256B\u256C\u00A9" +
            "\u044E\u0430\u0431\u0446\u0434\u0435\u0444\u0433\u0445\u0438\u0439\u043A\u043B\u043C\u043D\u043E" +
            "\u043F\u044F\u0440\u0441\u0442\u0443\u0436\u0432\u044C\u044B\u0437\u0448\u044D\u0449\u0447\u044A" +
            "\u042E\u0410\u0411\u0426\u0414\u0415\u0424\u0413\u0425\u0418\u0419\u041A\u041B\u041C\u041D\u041E" +
            "\u041F\u042F\u0420\u0421\u0422\u0423\u0416\u0412\u042C\u042B\u0417\u0428\u042D\u0429\u0427\u042A"

    private const val LATIN1_UPPER =
        "\u00C0\u00C1\u00C2\u00C3\u00C4\u00C5\u00C6\u00C7\u00C8\u00C9\u00CA\u00CB\u00CC\u00CD\u00CE\u00CF" +
            "\u00D0\u00D1\u00D2\u00D3\u00D4\u00D5\u00D6\u00D7\u00D8\u00D9\u00DA\u00DB\u00DC\u00DD\u00DE\u00DF" +
            "\u00E0\u00E1\u00E2\u00E3\u00E4\u00E5\u00E6\u00E7\u00E8\u00E9\u00EA\u00EB\u00EC\u00ED\u00EE\u00EF" +
            "\u00F0\u00F1\u00F2\u00F3\u00F4\u00F5\u00F6\u00F7\u00F8\u00F9\u00FA\u00FB\u00FC\u00FD\u00FE\u00FF" +
            "\u0100\u0101\u0102\u0103\u0104\u0105\u0106\u0107\u0108\u0109\u010A\u010B\u010C\u010D\u010E\u010F" +
            "\u0110\u0111\u0112\u0113\u0114\u0115\u0116\u0117\u0118\u0119\u011A\u011B\u011C\u011D\u011E\u011F" +
            "\u0120\u0121\u0122\u0123\u0124\u0125\u0126\u0127\u0128\u0129\u012A\u012B\u012C\u012D\u012E\u012F" +
            "\u0130\u0131\u0132\u0133\u0134\u0135\u0136\u0137\u0138\u0139\u013A\u013B\u013C\u013D\u013E\u013F"
}
