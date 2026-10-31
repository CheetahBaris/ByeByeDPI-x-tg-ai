package io.github.romanvht.byedpi.lab

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Промпты и разбор ответа модели. */
object PromptBuilder {

    fun system(ctx: Context, fullKb: Boolean = false): String {
        val asset = if (fullKb) "lab_kb.md" else "lab_kb_short.md"
        val kb = runCatching {
            ctx.assets.open(asset).bufferedReader().use { it.readText() }
        }.getOrElse {
            runCatching {
                ctx.assets.open("lab_kb.md").bufferedReader().use { it.readText() }
            }.getOrDefault("")
        }
        return kb + """

=== ПРАВИЛА ОТВЕТА ===
Ты — исследователь обхода DPI. Твоя задача — по результатам экспериментов
предлагать следующие стратегии ciadpi, которые повысят доступность сайтов.
Отвечай ТОЛЬКО валидным JSON-объектом, без markdown и пояснений вокруг.
Все текстовые поля — на русском.
Аргументы — синтаксис ciadpi: -s 1+s -d 3+s -o 2 -r -5+se -a 1.
Не используй -i, -p, -D, -E, -B, -C, -h, -v.
Не предлагай -d 1: на мобильном канале он ломает соединение.
Не добавляй порт, host и другие аргументы запуска — только параметры обхода.
""".trimIndent()
    }

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

        sb.append("## Раунд ").append(run.id).append('\n')
        sb.append("- повторов на сайт: ").append(run.repeats).append('\n')
        sb.append("- baseline (без обхода): ")
            .append(run.baseline?.let { "${it.ok}/${it.total}" } ?: "н/д")
            .append('\n')
        sb.append("- лучшая за всё время: ")
            .append(if (bestStrategy.isBlank()) "пока нет" else "`$bestStrategy` (${pct(bestScore)})")
            .append('\n')
        if (!run.network.isNullOrBlank()) sb.append("- сеть: ").append(run.network).append('\n')

        // Компактный формат: полные имена сайтов один раз, в строках — только цифры.
        // Экономит ~2/3 объёма таблицы, что критично для локальных 4B-моделей.
        sb.append("\n## Сайты (номер = колонка в результатах)\n")
        sites.forEachIndexed { i, s ->
            sb.append(i + 1).append('=').append(short(s))
                .append(if (i == sites.lastIndex) '\n' else ' ')
        }

        sb.append("\n## Результаты раунда\n")
        sb.append("Формат строки: Σ `стратегия` | цифры по сайтам 1..N. Цифра = сколько из ")
            .append(run.repeats).append(" повторов прошло (2 2 0 = сайт 1: 2/2, сайт 2: 2/2, сайт 3: 0/2).\n")
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
            sb.append("\nМёртвые сайты (0 у всех стратегий — вероятно IP/DNS-блок, ")
                .append("их провалы не про аргументы): ")
                .append(dead.joinToString(", ") { "${it.index + 1}=${short(it.value)}" })
                .append('\n')
        }

        val bestRow = run.results.filter { it.engineError == null && it.total > 0 }
            .maxByOrNull { it.score }
        if (bestRow != null && bestRow.strategy.isNotBlank()) {
            val base = run.baseline?.score ?: 0.0
            sb.append("\nЛучшая стратегия раунда: `").append(bestRow.strategy)
                .append("` — ").append(bestRow.ok).append('/').append(bestRow.total)
                .append(", ").append(pct(bestRow.score - base)).append(" к baseline\n")
        }

        if (history.isNotEmpty()) {
            sb.append("\n## История прошлых раундов\n")
            history.takeLast(historyLimit.coerceAtLeast(1)).forEach {
                sb.append("- ").append(it.take(140)).append('\n')
            }
        }

        if (tried.isNotEmpty()) {
            val all = tried.toList()
            val shown = all.takeLast(triedLimit.coerceAtLeast(1))
            sb.append("\n## Уже проверенные стратегии (НЕ повторяй)\n")
            if (all.size > shown.size) {
                sb.append("(ещё ").append(all.size - shown.size)
                    .append(" проверено раньше — их тоже не повторяй)\n")
            }
            shown.forEach { sb.append("- `").append(it).append("`\n") }
        }

        sb.append("\n## Задача\n")
        sb.append("1. Оцени вклад аргументов по цифрам: что помогает на большинстве сайтов, что мешает, что нейтрально.\n")
        sb.append("2. Учти шум: разница меньше ").append(pct(minImprovement))
            .append(" — не доказательство.\n")
        sb.append("3. Предложи до ").append(maxCandidates)
            .append(" стратегий. Большинство — изменение ОДНОГО параметра у лучшей стратегии; ")
            .append("допустимы 1-2 смелых комбинированных варианта.\n")
        sb.append("4. Не повторяй стратегии из списка проверенных.\n")
        sb.append("5. Если лучшая стратегия уже близка к 100%, проверяй её устойчивость: ")
            .append("соседние TTL, offset, порядок split/disorder, tlsrec.\n")
        sb.append("6. Верни JSON строго такого вида:\n")
        sb.append(
            """
{
  "summary": "краткий итог раунда",
  "data_quality": "насколько данным можно верить",
  "arg_findings": [
    {"arg": "-r -5+se", "effect": "positive|negative|neutral|unknown", "confidence": "low|medium|high", "evidence": "..."}
  ],
  "hypotheses": ["..."],
  "next_strategies": [
    {"args": "-X -I 0.0.0.0 -s 1+s -d 3+s -o 2 -r -5+se -a 1", "why": "..."}
  ],
  "next_experiment": "что проверить в следующем раунде",
  "apply_recommendation": "какую стратегию применить, если улучшения нет"
}
""".trimIndent()
        )
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
            hypotheses = (root.array("hypotheses") ?: emptyList()).mapNotNull { it.asStringOrNull() },
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
}
