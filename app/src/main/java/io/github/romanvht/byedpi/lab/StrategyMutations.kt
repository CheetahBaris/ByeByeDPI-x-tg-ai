package io.github.romanvht.byedpi.lab

import kotlin.math.abs

/**
 * Детерминированные мутации стратегий.
 *
 * Работают даже без LLM и дополняют её ответ: модель предлагает гипотезы,
 * а этот объект систематически проверяет окрестности лучших стратегий
 * (split / disorder / oob / tlsrec / fake+ttl / auto-mode / crossover).
 *
 * Правило: одна мутация меняет ровно один параметр, чтобы эффект был измерим.
 */
object StrategyMutations {

    private val WITH_ARG = setOf(
        'i', 'p', 'I', 'b', 'c', 'x', 'y', 'u', 'T', 'B', 'K', 'H', 'j', 'V',
        'R', 's', 'd', 'o', 'q', 'f', 't', 'O', 'l', 'Q', 'e', 'M', 'r', 'm',
        'a', 'g', 'W', 'C'
    )
    private val FLAGS = setOf('N', 'X', 'U', 'E', 'F', 'S', 'Y', 'Z', 'D')
    private val POSITIONAL = setOf("-s", "-d", "-o", "-q", "-r", "-f")

    private data class Grp(val opt: String, val value: String?)

    /**
     * Кандидаты из окрестностей лучших результатов раунда.
     *
     * @param extraParents дополнительные «родители» (например, лучшая стратегия
     *        за всё время или прошлый победитель), которые стоит доработать.
     */
    fun from(
        run: LabRun,
        cfg: LabConfig,
        tried: Set<String>,
        limit: Int = 14,
        extraParents: List<String> = emptyList()
    ): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()

        fun add(text: String) {
            val p = StrategyArgs.parse(text, cfg.sni)
            if (!p.ok || p.normalized.isBlank()) return
            if (p.normalized in seen || p.normalized in tried) return
            if (cfg.forbidD1 && isD1(p.argv)) return
            seen.add(p.normalized)
            out.add(p.normalized)
        }

        val parents = LinkedHashSet<String>()
        run.results
            .filter { it.engineError == null && it.total > 0 }
            .sortedByDescending { it.score }
            .take(2)
            .forEach { r -> if (r.strategy.isNotBlank()) parents.add(r.strategy) }
        extraParents.forEach { if (it.isNotBlank()) parents.add(it) }
        if (parents.isEmpty()) cfg.strategies.take(2).forEach { parents.add(it) }

        val parsed = parents.mapNotNull { g -> groups(g, cfg.sni) }

        // Кроссовер двух лучших: берём позиционные параметры, которых нет у первого.
        if (parsed.size >= 2) {
            add(join(crossover(parsed[0], parsed[1])))
            add(join(crossover(parsed[1], parsed[0])))
        }

        val generated = parsed.map { mutations(it) }

        // Round-robin по родителям: не тратим весь лимит на окрестности одного.
        var depth = 0
        while (out.size < limit) {
            var progressed = false
            for (list in generated) {
                if (depth < list.size) {
                    add(join(list[depth]))
                    progressed = true
                    if (out.size >= limit) break
                }
            }
            if (!progressed) break
            depth++
        }

        return out
    }

    /**
     * Систематическая сетка вокруг известного рабочего шаблона.
     * Используется, когда LLM недоступна или все родители провалились.
     */
    fun systematic(cfg: LabConfig, tried: Set<String>, limit: Int = 20): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()

        fun add(text: String) {
            val p = StrategyArgs.parse(text, cfg.sni)
            if (!p.ok || p.normalized.isBlank()) return
            if (p.normalized in seen || p.normalized in tried) return
            if (cfg.forbidD1 && isD1(p.argv)) return
            seen.add(p.normalized)
            out.add(p.normalized)
        }

        // Сетка вокруг победителя на МегаФоне/LTE.
        val combos = ArrayList<Triple<Int, Int, Int>>()
        for (s in 1..6) {
            for (d in 1..8) {
                for (r in -1 downTo -9) combos.add(Triple(s, d, r))
            }
        }
        combos.sortBy { abs(it.first - 1) + abs(it.second - 3) + abs(it.third + 5) }

        for ((s, d, r) in combos) {
            for (o in intArrayOf(2, 1, 3)) {
                add("-X -I 0.0.0.0 -s$s+s -d$d+s -o$o -r$r+se -a1")
                if (out.size >= limit) return out
            }
        }

        val extras = listOf(
            "-I 0.0.0.0 -s1+s -d3+s -o2 -r-5+se -a1",
            "-X -I 0.0.0.0 -s1+s -d3+s -o2 -r-5+se -f-1 -t3 -a1",
            "-X -I 0.0.0.0 -s1+s -d3+s -o2 -r-5+se -f-1 -t5 -a1",
            "-X -I 0.0.0.0 -s1+s -d3+s -o2 -r-5+se -A s -a1",
            "-X -I 0.0.0.0 -s1+s -d3+s -o2 -r-5+se -A t,r,s -a1",
            "-X -I 0.0.0.0 -s1:3+s -d3+s -o2 -r-5+se -a1",
            "-X -I 0.0.0.0 -s2+s -d3+s -o2 -r-5+se -a1",
            "-X -I 0.0.0.0 -s1+s -d4+s -o2 -r-5+se -a1",
            "-X -I 0.0.0.0 -s1+s -d3+s -o2 -r-6+se -a1"
        )
        extras.forEach { add(it) }
        return out.take(limit)
    }

    // ---------------------------------------------------------------- мутации

    private fun mutations(parent: List<Grp>): List<List<Grp>> {
        val out = ArrayList<List<Grp>>()

        // 1. Позиции: соседние значения и варианты суффиксов.
        var positionalDone = 0
        for (idx in parent.indices) {
            val g = parent[idx]
            if (g.opt !in POSITIONAL || g.value == null) continue
            positionalDone++
            if (positionalDone > 3) break
            val allowSuffix = g.opt != "-o"
            val allowNegative = g.opt == "-r" || g.opt == "-f"
            for (v in positionalVariants(g.value, allowSuffix, allowNegative)) {
                out.add(replace(parent, idx, g.copy(value = v)))
            }
        }

        // 2. TTL / fake+ttl.
        val ttlIndex = parent.indexOfFirst { it.opt == "-t" }
        if (ttlIndex >= 0) {
            val v = parent[ttlIndex].value?.toIntOrNull()
            if (v != null) {
                for (d in intArrayOf(-2, -1, 1, 2, 3)) {
                    val nv = (v + d).coerceIn(1, 20)
                    if (nv == v) continue
                    out.add(replace(parent, ttlIndex, Grp("-t", nv.toString())))
                }
            }
        } else {
            for (t in intArrayOf(3, 5, 7, 9)) {
                out.add(parent + Grp("-f", "-1") + Grp("-t", t.toString()))
            }
            if (parent.none { it.opt == "-f" }) {
                out.add(parent + Grp("-f", "-1"))
            }
        }

        // 3. Auto-mode.
        val autoIndex = parent.indexOfFirst { it.opt == "-A" }
        if (autoIndex >= 0) {
            for (mode in listOf("s", "t", "r", "t,r,s", "s,p=0.1")) {
                out.add(replace(parent, autoIndex, Grp("-A", mode)))
            }
        } else {
            for (mode in listOf("s", "t", "r", "t,r,s")) {
                out.add(parent + Grp("-A", mode))
            }
        }

        // 4. Убрать один необязательный аргумент.
        for (i in parent.indices.reversed()) {
            val opt = parent[i].opt
            if (opt in setOf("-f", "-t", "-A", "-a", "-o", "-q", "-r", "-M", "-Q", "-O")) {
                out.add(removeAt(parent, i))
                break
            }
        }

        // 5. Поменять split <-> disorder.
        val sIndex = parent.indexOfFirst { it.opt == "-s" }
        val dIndex = parent.indexOfFirst { it.opt == "-d" }
        if (sIndex >= 0 && dIndex < 0) {
            out.add(replace(parent, sIndex, Grp("-d", parent[sIndex].value)))
        }
        if (dIndex >= 0 && sIndex < 0) {
            out.add(replace(parent, dIndex, Grp("-s", parent[dIndex].value)))
        }

        // 6. Добавить базовые -a / -o, если их нет.
        if (parent.none { it.opt == "-a" }) out.add(parent + Grp("-a", "1"))
        if (parent.none { it.opt == "-o" }) out.add(parent + Grp("-o", "2"))

        // 7. Переключить -X.
        if (parent.none { it.opt == "-X" }) {
            out.add(listOf(Grp("-X", null)) + parent)
        } else {
            out.add(parent.filterNot { it.opt == "-X" })
        }

        return out
    }

    private fun crossover(a: List<Grp>, b: List<Grp>): List<Grp> {
        val result = ArrayList(a)
        for (opt in listOf("-s", "-d", "-o", "-r")) {
            if (result.any { it.opt == opt }) continue
            val g = b.firstOrNull { it.opt == opt } ?: continue
            result.add(g)
        }
        return result
    }

    private fun positionalVariants(
        value: String,
        allowSuffix: Boolean,
        allowNegative: Boolean
    ): List<String> {
        val m = Regex("""^(-?\d+)((?::\d+(?::\d+)?)?(?:\+[shn])?[emrs]?)$""").find(value)
            ?: return emptyList()
        val num = m.groupValues[1].toIntOrNull() ?: return emptyList()
        val tail = m.groupValues[2]
        val out = LinkedHashSet<String>()

        for (d in intArrayOf(-2, -1, 1, 2)) {
            val n = num + d
            if (n == 0) continue
            if (!allowNegative && n < 0) continue
            out.add("$n$tail")
        }

        if (allowSuffix) {
            for (sfx in listOf("+s", "+sm", "+se", "+sr", "")) {
                out.add("$num$sfx")
            }
        }

        out.remove(value)
        return out.toList()
    }

    // ------------------------------------------------------------- разбор/сборка

    private fun groups(raw: String, sni: String): List<Grp>? {
        val parsed = StrategyArgs.parse(raw, sni)
        if (!parsed.ok) return null
        return parseGroups(parsed.argv)
    }

    /** Возвращает null, если в стратегии есть длинные опции (их не мутируем). */
    private fun parseGroups(argv: List<String>): List<Grp>? {
        val out = ArrayList<Grp>()
        var i = 0
        while (i < argv.size) {
            val tok = argv[i]
            if (!tok.startsWith("-") || tok == "-") return null
            if (tok.startsWith("--")) return null

            if (tok.length > 2) {
                val c = tok[1]
                when {
                    c in WITH_ARG -> {
                        out.add(Grp("-$c", tok.substring(2)))
                        i++
                    }
                    else -> {
                        for (ch in tok.substring(1)) {
                            if (ch !in FLAGS) return null
                            out.add(Grp("-$ch", null))
                        }
                        i++
                    }
                }
                continue
            }

            val c = tok[1]
            when {
                c in WITH_ARG -> {
                    if (i + 1 >= argv.size) return null
                    out.add(Grp(tok, argv[i + 1]))
                    i += 2
                }
                c in FLAGS -> {
                    out.add(Grp(tok, null))
                    i++
                }
                else -> return null
            }
        }
        return out
    }

    private fun join(groups: List<Grp>): String =
        groups.joinToString(" ") { g ->
            if (g.value == null) g.opt else "${g.opt} ${g.value}"
        }

    private fun replace(groups: List<Grp>, index: Int, value: Grp): List<Grp> =
        groups.mapIndexed { i, g -> if (i == index) value else g }

    private fun removeAt(groups: List<Grp>, index: Int): List<Grp> =
        groups.filterIndexed { i, _ -> i != index }

    private fun isD1(argv: List<String>): Boolean =
        argv.indices.any { i -> argv[i] == "-d" && i + 1 < argv.size && argv[i + 1] == "1" }
}
