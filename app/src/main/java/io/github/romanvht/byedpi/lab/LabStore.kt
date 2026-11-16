package io.github.romanvht.byedpi.lab

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Лучшая стратегия за всё время (хранится между запусками). */
data class BestStrategy(
    val strategy: String = "",
    val score: Double = 0.0,
    val ok: Int = 0,
    val total: Int = 0,
    val runId: String = "",
    val created: String = ""
)

/** Сохранение раундов, отчётов и лучших стратегий в filesDir/lab. */
class LabStore(private val ctx: Context) {

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val dir: File
        get() = File(ctx.filesDir, "lab").apply { mkdirs() }

    private val bestFile: File get() = File(dir, "best.json")
    private val resultFile: File get() = File(dir, "last-result.json")

    fun saveRun(run: LabRun): File {
        val f = File(dir, "run-${run.id}.json")
        f.writeText(gson.toJson(run), Charsets.UTF_8)
        return f
    }

    /** Обновляет глобальный топ стратегий по всем запускам. */
    fun recordRun(run: LabRun) {
        val existing = bestStrategies().associateBy { it.strategy }.toMutableMap()
        var changed = false

        for (r in run.results) {
            if (r.engineError != null || r.total == 0 || r.strategy.isBlank()) continue
            val candidate = BestStrategy(
                strategy = r.strategy,
                score = r.score,
                ok = r.ok,
                total = r.total,
                runId = run.id,
                created = run.created
            )
            val old = existing[candidate.strategy]
            if (old == null || candidate.score > old.score + 1e-9) {
                existing[candidate.strategy] = candidate
                changed = true
            }
        }

        if (!changed) return
        val top = existing.values
            .sortedWith(compareByDescending<BestStrategy> { it.score }.thenByDescending { it.ok })
            .take(60)
        runCatching { bestFile.writeText(gson.toJson(top), Charsets.UTF_8) }
    }

    fun bestStrategies(): List<BestStrategy> {
        val f = bestFile
        if (!f.exists()) return emptyList()
        return runCatching {
            val type = object : TypeToken<List<BestStrategy>>() {}.type
            gson.fromJson<List<BestStrategy>>(f.readText(Charsets.UTF_8), type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun lastBest(): BestStrategy? = bestStrategies().firstOrNull()

    fun saveResult(result: LabResult) {
        runCatching { resultFile.writeText(gson.toJson(result), Charsets.UTF_8) }
    }

    fun lastResult(): LabResult? {
        val f = resultFile
        if (!f.exists()) return null
        return runCatching {
            gson.fromJson(f.readText(Charsets.UTF_8), LabResult::class.java)
        }.getOrNull()
    }

    fun saveReport(
        run: LabRun,
        analysis: LlmAnalysis?,
        bestStrategy: String = "",
        bestScore: Double = 0.0,
        extra: String? = null
    ): File {
        val sb = StringBuilder()
        sb.append("# Анализ раунда ").append(run.id).append("\n\n")
        sb.append("- сайтов: ").append(run.sites.size)
            .append(", повторов: ").append(run.repeats).append('\n')
        sb.append("- baseline: ")
            .append(run.baseline?.let { "${it.ok}/${it.total} (${pct(it.score)})" } ?: "н/д")
            .append('\n')
        if (bestStrategy.isNotBlank()) {
            sb.append("- лучшая за всё время: `").append(bestStrategy)
                .append("` (").append(pct(bestScore)).append(")\n")
        }

        val bestRow = run.results.filter { it.engineError == null && it.total > 0 }
            .maxByOrNull { it.score }
        if (bestRow != null && bestRow.strategy.isNotBlank()) {
            sb.append("\n## Лучшая стратегия раунда\n\n`").append(bestRow.strategy)
                .append("` — ").append(bestRow.ok).append('/').append(bestRow.total)
                .append(" (").append(pct(bestRow.score)).append(")\n")
        }

        analysis?.let {
            sb.append("\n## Итог\n").append(it.summary).append("\n")
            if (it.dataQuality.isNotBlank()) sb.append("\n## Качество данных\n").append(it.dataQuality).append("\n")
            if (it.findings.isNotEmpty()) {
                sb.append("\n## Выводы по аргументам\n\n| аргумент | эффект | уверенность | основание |\n|---|---|---|---|\n")
                it.findings.forEach { f ->
                    sb.append("| ").append(f.arg).append(" | ").append(f.effect)
                        .append(" | ").append(f.confidence).append(" | ")
                        .append(f.evidence.replace("|", "\\|").replace("\n", " "))
                        .append(" |\n")
                }
            }
            if (it.hypotheses.isNotEmpty()) {
                sb.append("\n## Гипотезы\n")
                it.hypotheses.forEach { h -> sb.append("- ").append(h).append('\n') }
            }
            if (it.next.isNotEmpty()) {
                sb.append("\n## Следующие стратегии\n")
                it.next.forEachIndexed { i, c ->
                    sb.append(i + 1).append(". `").append(c.args).append("`")
                    if (c.why.isNotBlank()) sb.append(" — ").append(c.why)
                    sb.append('\n')
                }
            }
            if (it.experiment.isNotBlank()) sb.append("\n## План\n").append(it.experiment).append("\n")
            if (it.apply.isNotBlank()) sb.append("\n## Как применить\n").append(it.apply).append("\n")
        }

        if (!extra.isNullOrBlank()) sb.append("\n## Примечания\n").append(extra).append("\n")

        sb.append("\n## Сырые результаты\n\n")
        run.results.sortedByDescending { it.score }.forEach { r ->
            sb.append("- `").append(r.strategy.ifBlank { "<baseline>" }).append("` — ")
                .append(r.ok).append('/').append(r.total)
                .append(" (").append(pct(r.score)).append(")")
            if (r.perSite.isNotEmpty()) {
                sb.append(" · ").append(
                    r.perSite.entries.joinToString(", ") { (site, st) -> "$site ${st.ok}/${st.total}" }
                )
            }
            if (r.engineError != null) sb.append(" [").append(r.engineError).append("]")
            sb.append('\n')
        }

        val f = File(dir, "report-${run.id}.md")
        f.writeText(sb.toString(), Charsets.UTF_8)
        return f
    }

    fun listRuns(): List<File> =
        dir.listFiles { f -> f.name.startsWith("run-") && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun latestReport(): File? =
        dir.listFiles { f -> f.name.startsWith("report-") && f.name.endsWith(".md") }
            ?.maxByOrNull { it.lastModified() }

    /** Полный журнал запуска: по нему можно разбирать поведение приложения и LLM. */
    fun saveLog(text: String, id: String = timestamp()): File {
        val f = File(dir, "log-$id.txt")
        f.writeText(text, Charsets.UTF_8)
        return f
    }

    fun latestLog(): File? =
        dir.listFiles { f -> f.name.startsWith("log-") && f.name.endsWith(".txt") }
            ?.maxByOrNull { it.lastModified() }

    fun latestRun(): LabRun? {
        val f = listRuns().firstOrNull() ?: return null
        return runCatching {
            gson.fromJson(f.readText(Charsets.UTF_8), LabRun::class.java)
        }.getOrNull()
    }

    private fun pct(v: Double): String = "${(v * 100).toInt()}%"

    companion object {
        fun timestamp(): String =
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    }
}
