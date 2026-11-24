package io.github.romanvht.byedpi.lab

/**
 * Разбор и валидация аргументов ciadpi.
 * Порт логики из byedpi_agent.py: те же диапазоны, те же запреты.
 */
object StrategyArgs {

    data class Parsed(
        val ok: Boolean,
        val argv: List<String>,
        val normalized: String,
        val error: String?
    )

    private val POS = Regex("""^-?\d+(?::\d+(?::\d+)?)?(?:\+[shn][a-z]*)?$""")
    private val RANGE = Regex("""^\d+(?:-\d+)?$""")
    private val TIMEOUT = Regex("""^\d+(?:\.\d+)?(?::\d+(?:\.\d+)?){0,3}$""")

    private val WITH_ARG = setOf(
        'i', 'p', 'I', 'b', 'c', 'x', 'y', 'u', 'T', 'B', 'K', 'H', 'j', 'V',
        'R', 's', 'd', 'o', 'q', 'f', 't', 'O', 'l', 'Q', 'e', 'M', 'r', 'm',
        'a', 'g', 'W', 'C', 'A', 'n'
    )
    private val FLAGS = setOf('N', 'X', 'U', 'E', 'F', 'S', 'Y', 'Z', 'D')
    private val FORBIDDEN = setOf('i', 'p', 'D', 'w', 'E', 'B', 'C')

    private val LONG = mapOf(
        "split" to 's', "disorder" to 'd', "oob" to 'o', "disoob" to 'q',
        "fake" to 'f', "tlsrec" to 'r', "ttl" to 't', "fake-sni" to 'n',
        "auto" to 'A', "auto-mode" to 'L', "hosts" to 'H', "ipset" to 'j',
        "proto" to 'K', "pf" to 'V', "round" to 'R', "timeout" to 'T',
        "md5sig" to 'S', "drop-sack" to 'Y', "fake-tls-mod" to 'Q',
        "fake-offset" to 'O', "fake-data" to 'l', "oob-data" to 'e',
        "mod-http" to 'M', "tlsminor" to 'm', "udp-fake" to 'a',
        "def-ttl" to 'g', "no-domain" to 'N', "no-ipv6" to 'X',
        "no-udp" to 'U', "tfo" to 'F'
    )

    fun parse(raw: String, sni: String = "google.com"): Parsed {
        val text = raw.replace("{sni}", sni).trim()
        if (text.isEmpty()) return Parsed(true, emptyList(), "", null)
        val tokens = tokenize(text) ?: return Parsed(false, emptyList(), "", "не разобрать кавычки")
        if (tokens.size > 64) return Parsed(false, emptyList(), "", "слишком длинная стратегия")

        val argv = ArrayList<String>()
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            if (tok.startsWith("--")) {
                var name = tok.substring(2)
                var value: String? = null
                if (name.contains('=')) {
                    value = name.substringAfter('=')
                    name = name.substringBefore('=')
                }
                val short = LONG[name] ?: return Parsed(false, emptyList(), "", "неизвестная опция --$name")
                if (short in FLAGS) {
                    if (value != null) return Parsed(false, emptyList(), "", "--$name не принимает значение")
                    argv.add("--$name")
                    i++
                    continue
                }
                if (value == null) {
                    if (i + 1 >= tokens.size) return Parsed(false, emptyList(), "", "у --$name нет значения")
                    value = tokens[++i]
                }
                val err = check(short, value)
                if (err != null) return Parsed(false, emptyList(), "", err)
                argv.add("--$name")
                argv.add(value)
                i++
                continue
            }
            if (tok.startsWith("-") && tok.length > 1) {
                val body = tok.substring(1)
                val c = body[0]
                val rest = body.substring(1)
                if (c !in WITH_ARG && c !in FLAGS) {
                    return Parsed(false, emptyList(), "", "неизвестная опция -$c")
                }
                if (c in FORBIDDEN) return Parsed(false, emptyList(), "", "опция -$c запрещена")
                if (c in FLAGS) {
                    if (rest.isNotEmpty()) {
                        for (ch in body) {
                            if (ch !in FLAGS) return Parsed(false, emptyList(), "", "неизвестная опция -$ch")
                            if (ch in FORBIDDEN) return Parsed(false, emptyList(), "", "опция -$ch запрещена")
                            argv.add("-$ch")
                        }
                    } else {
                        argv.add("-$c")
                    }
                    i++
                    continue
                }
                val value: String
                if (rest.isNotEmpty()) {
                    value = rest
                } else {
                    if (i + 1 >= tokens.size) return Parsed(false, emptyList(), "", "у -$c нет значения")
                    value = tokens[++i]
                }
                val err = check(c, value)
                if (err != null) return Parsed(false, emptyList(), "", err)
                argv.add("-$c")
                argv.add(value)
                i++
                continue
            }
            return Parsed(false, emptyList(), "", "непонятный токен: $tok")
        }
        return Parsed(true, argv, argv.joinToString(" "), null)
    }

    private fun tokenize(s: String): List<String>? {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var has = false
        for (ch in s) {
            when {
                quote != null -> if (ch == quote) quote = null else { cur.append(ch); has = true }
                ch == '"' || ch == '\'' -> { quote = ch; has = true }
                ch.isWhitespace() -> if (has) { out.add(cur.toString()); cur.setLength(0); has = false }
                else -> { cur.append(ch); has = true }
            }
        }
        if (quote != null) return null
        if (has) out.add(cur.toString())
        return out
    }

    private fun check(opt: Char, value: String): String? {
        if (value.isEmpty()) return "пустое значение для -$opt"
        return when (opt) {
            's', 'd', 'o', 'q', 'f', 'O', 'r' ->
                if (POS.matches(value)) null else "плохая позиция для -$opt: $value"
            't', 'g', 'm' -> intRange(opt, value, 1, 255)
            'c' -> intRange(opt, value, 1, 32000)
            'a' -> intRange(opt, value, 0, 10000)
            'x' -> intRange(opt, value, 0, 3)
            'T' -> if (TIMEOUT.matches(value)) null else "плохой таймаут -T: $value"
            'V', 'R' -> if (RANGE.matches(value)) null else "плохой диапазон -$opt: $value"
            'K' -> if (value.matches(Regex("[thui]+(,[thui]+)*"))) null else "плохой -K: $value"
            'M' -> if (value.matches(Regex("[hdr]+(,[hdr]+)*"))) null else "плохой -M: $value"
            'A' -> if (value.matches(Regex("[trsnc]+(,[trsnc]+)*(,?p=\\d+(\\.\\d+)?)?"))) null else "плохой -A: $value"
            'L' -> if (value.matches(Regex("[son]+(,[son]+)*"))) null else "плохой -L: $value"
            'Q' -> if (value.matches(Regex("([ro]+)?(,?msize=-?\\d+)?"))) null else "плохой -Q: $value"
            'n' -> if (value.length <= 253 && !value.any { it.isWhitespace() }) null else "плохой -n: $value"
            else -> null
        }
    }

    private fun intRange(opt: Char, value: String, lo: Int, hi: Int): String? {
        val n = value.toIntOrNull() ?: return "-$opt ждёт число: $value"
        if (n < lo || n > hi) return "-$opt вне диапазона $lo..$hi: $n"
        return null
    }
}
