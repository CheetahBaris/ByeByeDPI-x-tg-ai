package io.github.romanvht.byedpi.lab

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Prompts and model response parsing.
 *
 * Everything that does not depend on the round (role, ciadpi syntax, diagnostics, JSON schema)
 * lives in the system message. The user knowledge base is added there as well
 * and is not duplicated in the request. The user message contains only data
 * for the current round — this way small models don't waste context repeating rules.
 */
object PromptBuilder {

    /** Default knowledge base: can be edited on the lab screen. */
    fun defaultKnowledge(): String = DEFAULT_KB.trim()

    /** Get the system prompt without the output format specification */
    fun system(knowledgeBase: String = "", useKnowledge: Boolean = false, customSystemPrompt: String = ""): String {
        val kb = knowledgeBase.trim()
        return buildString {
            append(CORE.trim())
            if (useKnowledge && kb.isNotEmpty()) {
                append("\n\n=== KNOWLEDGE BASE (editable) ===\n")
                append(kb)
            }
            // Добавляем пользовательский системный промпт, если он задан
            if (customSystemPrompt.isNotBlank()) {
                append("\n\n=== CUSTOM SYSTEM PROMPT ===\n")
                append(customSystemPrompt)
            }
            append("\n\n")
            // Оставляем только основные правила без спецификации формата ответа
            append(SYSTEM_RULES.trim())
        }
    }

    /** Get the current system prompt with output format specification for reference */
    fun systemWithOutputFormat(knowledgeBase: String = "", useKnowledge: Boolean = false, customSystemPrompt: String = ""): String {
        val kb = knowledgeBase.trim()
        return buildString {
            append(CORE.trim())
            if (useKnowledge && kb.isNotEmpty()) {
                append("\n\n=== KNOWLEDGE BASE (editable) ===\n")
                append(kb)
            }
            // Добавляем пользовательский системный промпт, если он задан
            if (customSystemPrompt.isNotBlank()) {
                append("\n\n=== CUSTOM SYSTEM PROMPT ===\n")
                append(customSystemPrompt)
            }
            append("\n\n")
            append(OUTPUT.trim())
        }
    }

    private val SYSTEM_RULES = """
=== RESPONSE FORMAT ===
Return exactly one JSON object with the following structure:
{
  "summary": "1–2 sentences: what the round showed",
  "data_quality": "sufficient|low|insufficient: why",
  "arg_findings": [
    {"arg": "-r-5+se", "effect": "positive|negative|neutral|unknown",
     "confidence": "low|medium|high",
     "evidence": "sites 1,3: 2 2 vs 0 0 for baseline"}
  ],
  "hypotheses": ["what we assume about DPI behavior"],
  "next_strategies": [
    {"args": "-X -I 0.0.0.0 -s1+s -d3+s -o2 -r-5+se -a1",
     "why": "which single parameter we test"}
  ],
  "next_experiment": "one question that closes the round",
  "apply_recommendation": "what to apply and when; if too early — say so"
}

Response rules:
- All text fields — in Russian, concise.
- evidence — only site numbers and figures from the request; do not invent.
- next_strategies — 3–5 (or as many as the request asks), only valid args,
  without -i/-p; each line unique.
- Do not recommend applying a strategy that was not tested in this run.
- No markdown, ```json, comments, or extra fields.
- If data is scarce: data_quality="insufficient", but still provide next_strategies.
""".trimIndent()

    fun user(
        run: LabRun,
        history: List<String>,
        bestStrategy: String,
        bestScore: Double,
        tried: Collection<String>,
        maxCandidates: Int = 12,
        minImprovement: Double = 0.02,
        triedLimit: Int = 15,
        historyLimit: Int = 3
    ): String {
        val sites = run.sites
        val sb = StringBuilder()

        sb.append("## Round ").append(run.id).append('\n')
        sb.append("- repeats per site: ").append(run.repeats).append('\n')
        sb.append("- baseline (no bypass): ")
            .append(run.baseline?.let { "${it.ok}/${it.total}" } ?: "n/a")
            .append('\n')
        sb.append("- best ever: ")
            .append(if (bestStrategy.isBlank()) "none yet" else "`$bestStrategy` (${pct(bestScore)})")
            .append('\n')
        if (!run.network.isNullOrBlank()) sb.append("- network: ").append(run.network).append('\n')

        // Compact format: full site names once, then only numbers in rows.
        // Saves ~2/3 of table volume, critical for local 4B models.
        sb.append("\n## Sites (number = column in results)\n")
        sites.forEachIndexed { i, s ->
            sb.append(i + 1).append('=').append(short(s))
                .append(if (i == sites.lastIndex) '\n' else ' ')
        }

        sb.append("\n## Round results\n")
        sb.append("Line format: Σ `strategy` | digits for sites 1..N. Digit = how many out of ")
            .append(run.repeats)
            .append(" repeats succeeded (2 2 0 = site 1: 2/2, site 2: 2/2, site 3: 0/2).\n")
        run.results.sortedByDescending { it.score }.forEach { r ->
            sb.append(r.ok).append('/').append(r.total).append(' ')
                .append('`').append(r.strategy.ifBlank { "<baseline>" }).append("` |")
            for (s in sites) {
                val st = r.perSite[s]
                sb.append(' ').append(if (st == null) "?" else st.ok.toString())
            }
            sb.append('\n')
        }

        val dead = sites.withIndex().filter { (_, s) ->
            run.results.all { (it.perSite[s]?.ok ?: 0) == 0 }
        }
        if (dead.isNotEmpty()) {
            sb.append("\nDead sites (0 for all strategies — likely IP/DNS block, ")
                .append("their failures are not about args): ")
                .append(dead.joinToString(", ") { "${it.index + 1}=${short(it.value)}" })
                .append('\n')
        }

        val bestRow = run.results.filter { it.engineError == null && it.total > 0 }
            .maxByOrNull { it.score }
        if (bestRow != null && bestRow.strategy.isNotBlank()) {
            val base = run.baseline?.score ?: 0.0
            sb.append("\nBest strategy of the round: `").append(bestRow.strategy)
                .append("` — ").append(bestRow.ok).append('/').append(bestRow.total)
                .append(", ").append(pct(bestRow.score - base)).append(" vs baseline\n")
        }

        if (history.isNotEmpty()) {
            sb.append("\n## History of previous rounds\n")
            history.takeLast(historyLimit.coerceAtLeast(1)).forEach {
                sb.append("- ").append(it.take(140)).append('\n')
            }
        }

        if (tried.isNotEmpty()) {
            val all = tried.toList()
            val shown = all.takeLast(triedLimit.coerceAtLeast(1))
            sb.append("\n## Already tested strategies (DO NOT repeat)\n")
            if (all.size > shown.size) {
                sb.append("(").append(all.size - shown.size)
                    .append(" more tested earlier — do not repeat them either)\n")
            }
            shown.forEach { sb.append("- `").append(it).append("`\n") }
        }

        sb.append("\n## Task\n")
        sb.append("Propose up to ").append(maxCandidates)
            .append(" strategies: majority — one edit of the best, 1–2 bold variants. ")
            .append("Do not repeat tested ones. Difference less than ")
            .append(pct(minImprovement)).append(" — noise, not proof.\n")
        sb.append("Reply with a single JSON object according to the schema in the system message, ")
            .append("without markdown or surrounding text.\n")
        // Qwen3 and compatible models understand this directive; others simply ignore it.
        sb.append("/no_think")
        return sb.toString()
    }

    fun parseAnalysis(text: String): LlmAnalysis {
        val json = extractJson(text)
            ?: return LlmAnalysis(summary = text.trim().take(1500))
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
            ?: return LlmAnalysis(summary = text.trim().take(1500))

        val nextArray = arrayOf("next_strategies", "strategies", "candidates")
            .firstNotNullOfOrNull { key -> root.array(key) }

        return LlmAnalysis(
            summary = root.str("summary"),
            dataQuality = root.str("data_quality"),
            findings = (root.array("arg_findings") ?: emptyList()).mapNotNull { item ->
                val x = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                Finding(
                    arg = x.str("arg"),
                    effect = x.str("effect"),
                    confidence = x.str("confidence"),
                    evidence = x.str("evidence")
                )
            },
            hypotheses = (root.array("hypotheses")
                ?: emptyList()).mapNotNull { it.asStringOrNull() },
            next = (nextArray ?: emptyList()).mapNotNull { item ->
                when {
                    item.isJsonObject -> {
                        val x = item.asJsonObject
                        val args = x.str("args").ifBlank { x.str("strategy") }
                        if (args.isBlank()) null else Candidate(args, x.str("why"))
                    }

                    item.isJsonPrimitive -> Candidate(item.asString, "")
                    else -> null
                }
            },
            experiment = root.str("next_experiment"),
            apply = root.str("apply_recommendation")
        )
    }

    /** Rough token estimate: Latin ~0.28 token/char, Cyrillic ~0.55. */
    fun estimateTokens(text: String): Int {
        var cost = 0.0
        for (ch in text) cost += if (ch.code < 0x80) 0.28 else 0.55
        return cost.toInt()
    }

    private fun extractJson(text: String): String? {
        var t = text.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```").trim()
            if (t.endsWith("```")) t = t.dropLast(3).trim()
        }
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return t.substring(start, end + 1)
    }

    private fun short(site: String): String {
        val s = site.removePrefix("https://").removePrefix("http://").trimEnd('/')
        return if (s.length <= 24) s else s.take(21) + "..."
    }

    private fun pct(v: Double): String {
        val sign = if (v > 0) "+" else ""
        return "$sign${(v * 100).toInt()}%"
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject.array(key: String): List<JsonElement>? {
        val node = get(key) ?: return null
        if (!node.isJsonArray) return null
        return runCatching { node.asJsonArray.toList() }.getOrNull()
    }

    private fun JsonElement.asStringOrNull(): String? =
        if (isJsonPrimitive) asString else null

    // ---------------------------------------------------------------- texts

    private val CORE = """
You are a DPI bypass engineer (ciadpi, ByeByeDPI 1.8.2 / byedpi 17.x, Android).
Using the run table, determine how DPI handles TLS/HTTP and propose next
strategies. You only see numbers, not traffic or logs. Do not invent facts.

=== INPUT ===
- "repeats per site: R" — number of checks per site.
- "Sites": 1=youtube.com 2=... — column numbers.
- Line `ok/total strategy | 2 1 0`: digit = successes out of R on the site with that number,
  `?` = no data.
- `<baseline>` — no bypass; compare against it, not zero.
- "Best..." — parent of new candidates; "dead" sites (0 for all) — IP/DNS block,
  they are not fixed by args; "tested" — do not repeat.

=== SYNTAX ===
pos_t = offset[:repeats:skip][+s|+h|+n][e|m|r]
- offset — bytes from start of request, negative — from end.
- :repeats:skip — series of cuts: 1:3:5 = positions 1, 6, 11.
- +s — from SNI (TLS), +h — from Host (HTTP), +n — no anchor; then +e (end of name),
  +m (middle), +r (random within name).
- Examples: -s1+s, -d3+s, -o2, -r-5+se, -f-1 -t4, -s1:3:5+sm.
- Keys can be repeated; order — as in command line; tlsrec (-r) always before split.

Operations:
- -s — split and send in order.
- -d — disorder: first part with TTL=1, server re-requests, order breaks.
  On mobile network -d 1 often gives 0 — do not propose.
- -o — OOB (URG), part out of stream; -q — disorder + OOB.
- -f — fake before original; -t TTL of fake (default 8, try 3..6);
  -O fake shift, -l ':string' custom bytes, -n 'www.?????.com' random SNI,
  -Q r|o|msize=N ClientHello modification.
- -r — tlsrec: split TLS record with new 5-byte header; usually -r-5+se.
- -M h,d,r — HTTP (Host/domain case, spaces→tabs); -m N — minor TLS version.

Network/service:
- -X no-ipv6, -I IP (0.0.0.0 = IPv4 only), -N no-domain, -U no-udp, -g N def-ttl,
  -a N UDP-fake, -Y drop-sack, -F tfo.
- -S md5sig on Android without root almost always EPERM — do not propose.
- -T sec timeout auto; -A t,r,s,c,n — new group (timeout/RST, redirect, TLS error,
  connect error, always); -L s,o,n — auto-mode; groups left to right, after failure
  the next suitable one is taken.
- Allowed: -X -I -N -U -g -s -d -o -q -f -t -O -l -n -Q -e -M -r -m -a -Y -T -A -L -R -K -H -j -V.
- Forbidden: -i -p -D -w -E -B -C -h -v. No port, IP, or launch params. -d 1 forbidden.

=== DIAGNOSTICS ===
1. All zeros, including baseline → bench/network/port, not strategy. data_quality=insufficient.
2. baseline > 0, desync helps on most TLS → block of ClientHello/SNI.
3. timeout without RST, HTTP dead → silent drop of IP/SNI; check -X, IPv4, fake SNI;
   do not fix a site dead for everyone.
4. RST/TLS error on all TLS, HTTP alive → ClientHello; try -s/-d/-o/-r, -n, -Q.
5. Only -f TTL 3..6 helps → TTL-based filter, fragile; check TTL and repeats.
6. Only -r helps → DPI parses TLS record entirely; move position, add second -r.
7. Only -o/-q helps → OOB; watch middleboxes, combine with -d.
8. One site 0 for all → CDN/QUIC/IPv6, not arg failure.
9. Difference <10–15% or 1 of 2 repeats → noise.
10. engineError/invalid → launch error, not measurement.

=== SEARCH ===
- Start — best ever; if none: -X -I 0.0.0.0 -s1+s -d3+s -o2 -r-5+se -a1.
- 60–70% of candidates — one edit of the best: ±1..3 offset, -o1/-o2/-o3, -r-5/-6+se,
  -s2+s, TTL 3..6, order of split/disorder, remove one arg (ablation).
- 20–30% — different mechanic: -q instead of -d, -s1:3:5, -n, -M, -m, fake+split.
- 0–1 bold hypothesis; ≤8–10 args per line.
- Best ≥90% → check stability (neighboring TTL/offset, repeat, ablation).
- All at baseline level → change mechanic, do not tweak offset.
- On mobile network add -X -I 0.0.0.0 if IPv6 is not proven.
- Do not repeat tested ones, do not duplicate, do not propose invalid.
""".trimIndent()

    private val OUTPUT = """
=== RESPONSE FORMAT ===
Return exactly one JSON object:
{
  "summary": "1–2 sentences: what the round showed",
  "data_quality": "sufficient|low|insufficient: why",
  "arg_findings": [
    {"arg": "-r-5+se", "effect": "positive|negative|neutral|unknown",
     "confidence": "low|medium|high",
     "evidence": "sites 1,3: 2 2 vs 0 0 for baseline"}
  ],
  "hypotheses": ["what we assume about DPI behavior"],
  "next_strategies": [
    {"args": "-X -I 0.0.0.0 -s1+s -d3+s -o2 -r-5+se -a1",
     "why": "which single parameter we test"}
  ],
  "next_experiment": "one question that closes the round",
  "apply_recommendation": "what to apply and when; if too early — say so"
}

Response rules:
- All text fields — in Russian, concise.
- evidence — only site numbers and figures from the request; do not invent.
- next_strategies — 3–5 (or as many as the request asks), only valid args,
  without -i/-p; each line unique.
- Do not recommend applying a strategy that was not tested in this run.
- No markdown, ```json, comments, or extra fields.
- If data is scarce: data_quality="insufficient", but still provide next_strategies.
""".trimIndent()

    /**
     * Compact default knowledge base.
     *
     * Only practical observations that cannot be derived from the table.
     * Everything else (syntax, diagnostics, schema) is already in the system prompt,
     * so the base does not duplicate rules and is easy for the user to edit.
     */
    private val DEFAULT_KB = """
Practice (Android, ciadpi 17.x):
- On mobile networks there is often an IPv6 address but no IPv6 route: without -X -I 0.0.0.0
  some domains fail with ENETUNREACH. If baseline is low, start with these args.
- Working formula on LTE (MegaFon, Xiaomi 13): -X -I 0.0.0.0 -s1+s -d3+s -o2 -r-5+se -a1
  (12/12 YouTube + Instagram). Stable neighborhood: -o1/-o3, -r-6+se, -s2+s,
  adding -f-1 -t3.
- -d 1 on mobile channel often gives 0/timeouts; use -s1+s -d3+s instead.
- tlsrec -r-5+se — key argument; -r1+s and -r4+se are noticeably worse.
- -f/-q do not always add gain to split+oob+tlsrec; test separately.
- -S (md5sig) on Android without root almost always EPERM; -E requires root. Do not propose.
- -Y and -F depend on kernel: test in a separate run, do not mix with the working formula.
- telegram.org and web.telegram.org are often DNS-blocked; discord.com, x.com, rutracker.org,
  speedtest.net — IP-blocked. Their zeros are not fixed by args.
- Mobile network ≠ Wi-Fi: DPI and MTU differ, do not transfer results between networks.
- Adaptive DPI: behavior changes after a series of failures; baseline and repeats are mandatory.
""".trimIndent()
}