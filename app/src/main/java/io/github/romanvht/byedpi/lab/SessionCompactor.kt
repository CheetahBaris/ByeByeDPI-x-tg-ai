package io.github.romanvht.byedpi.lab

import com.google.gson.JsonObject

/**
 * Сжатие истории одной сессии с LLM.
 *
 * Работает как compaction в opencode: старые пары `user/assistant` заменяются
 * короткой сводкой раундов, свежие сообщения остаются целиком. Сводка берётся
 * из поля `summary` JSON-ответов модели, поэтому отдельный запрос к LLM не нужен.
 *
 * Все функции — чистые и не ходят в сеть, чтобы логику можно было покрыть тестами.
 */
internal object SessionCompactor {

    /** Маркер сообщения со сводкой сжатой сессии. */
    const val SUMMARY_MARKER = "[СВОДКА ПРЕДЫДУЩИХ РАУНДОВ]"

    /** Сколько токенов максимум занимает сводка старых раундов. */
    private const val SUMMARY_MAX_TOKENS = 500

    fun role(m: JsonObject): String =
        m.get("role")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    fun content(m: JsonObject): String =
        m.get("content")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    fun isSummary(m: JsonObject): Boolean =
        role(m) == "user" && content(m).startsWith(SUMMARY_MARKER)

    fun messageTokens(m: JsonObject): Int =
        4 + PromptBuilder.estimateTokens(content(m))

    fun estimate(messages: List<JsonObject>): Int =
        messages.sumOf { messageTokens(it) }

    /**
     * Оставляет в истории system, короткую сводку старых раундов и самые
     * свежие пары user/assistant, которые помещаются в [historyBudget] токенов.
     *
     * @return новый размер истории в токенах.
     */
    fun compact(
        history: MutableList<JsonObject>,
        historyBudget: Int,
        log: (String) -> Unit = {}
    ): Int {
        if (history.isEmpty()) return 0

        // 1. System: при нехватке места убираем базу знаний, затем оставляем только схему.
        val sys = history.firstOrNull { role(it) == "system" }
        if (sys != null) {
            var sysTokens = messageTokens(sys)
            if (sysTokens > historyBudget) {
                val text = content(sys)
                val noKb = PromptBuilder.withoutKnowledge(text)
                if (noKb != text && PromptBuilder.estimateTokens(noKb) < sysTokens) {
                    setContent(sys, noKb)
                    sysTokens = messageTokens(sys)
                    log("Сжатие: база знаний убрана из system (~$sysTokens токенов)")
                }
            }
            if (sysTokens > historyBudget) {
                val minimal = PromptBuilder.responseFormat()
                if (PromptBuilder.estimateTokens(minimal) < sysTokens) {
                    setContent(sys, minimal)
                    log(
                        "Сжатие: в system оставлена только схема ответа " +
                            "(~${messageTokens(sys)} токенов)"
                    )
                }
            }
        }

        val sysTokens = sys?.let { messageTokens(it) } ?: 0
        val remaining = historyBudget - sysTokens
        val oldSummary = history.firstOrNull { isSummary(it) }
            ?.let { content(it).removePrefix(SUMMARY_MARKER).trim() }
            .orEmpty()

        // 2. Собираем пары user/assistant из конца: свежие сообщения важнее.
        val dialogue = history.filter { role(it) != "system" && !isSummary(it) }
        val pairs = ArrayList<Pair<JsonObject, JsonObject>>()
        var i = dialogue.size - 1
        while (i >= 1) {
            val a = dialogue[i]
            val u = dialogue[i - 1]
            if (role(u) == "user" && role(a) == "assistant") {
                pairs.add(0, u to a)
                i -= 2
            } else {
                i--
            }
        }

        val keep = ArrayList<Pair<JsonObject, JsonObject>>()
        if (remaining > 0 && pairs.isNotEmpty()) {
            // Под сводку оставляем не больше трети доступного места.
            val summaryReserve = minOf(SUMMARY_MAX_TOKENS, remaining / 3)
            val pool = remaining - summaryReserve
            var used = 0
            for (pair in pairs.asReversed()) {
                val t = messageTokens(pair.first) + messageTokens(pair.second)
                if (used + t > pool) break
                keep.add(0, pair)
                used += t
            }
        }
        val keptIndex = pairs.size - keep.size
        val dropped = pairs.subList(0, keptIndex)

        // 3. Сводка: прошлая сводка + выброшенные раунды, самые свежие строки важнее.
        val lines = ArrayList<String>()
        oldSummary.lines().map { it.trim() }.filter { it.isNotEmpty() }
            .forEach { lines.add(it) }
        var round = 0
        for ((u, a) in dropped) {
            Regex("""##\s*Round\s*(\d+)""").find(content(u))
                ?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { round = it }
            val parsed = PromptBuilder.parseAnalysis(content(a)).summary
                .replace('\n', ' ').trim()
            val text = parsed.ifBlank { content(a).replace('\n', ' ').trim().take(160) }
            if (text.isNotEmpty()) {
                lines.add("- " + (if (round > 0) "раунд $round: " else "") + text.take(400))
            }
        }
        val summaryLines = ArrayList<String>()
        var summaryUsed = 0
        val summaryBudget = minOf(SUMMARY_MAX_TOKENS, remaining / 2)
        for (line in lines.asReversed()) {
            val t = PromptBuilder.estimateTokens(line)
            if (summaryUsed + t > summaryBudget) break
            summaryLines.add(0, line)
            summaryUsed += t
        }
        val summary = summaryLines.joinToString("\n").trim()

        // 4. Пересобираем историю: system + сводка + сохранённые пары.
        val rebuilt = ArrayList<JsonObject>(keep.size * 2 + 2)
        sys?.let { rebuilt.add(it) }
        if (summary.isNotEmpty() && remaining > summaryUsed) {
            rebuilt.add(message("user", SUMMARY_MARKER + "\n" + summary))
        }
        for ((u, a) in keep) {
            rebuilt.add(u)
            rebuilt.add(a)
        }
        history.clear()
        history.addAll(rebuilt)
        return estimate(history)
    }

    private fun setContent(m: JsonObject, text: String) {
        m.addProperty("content", text)
    }

    private fun message(role: String, content: String): JsonObject = JsonObject().apply {
        addProperty("role", role)
        addProperty("content", content)
    }
}
