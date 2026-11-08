package io.github.romanvht.byedpi.lab

/**
 * Необязательные параметры OpenAI-совместимого API.
 *
 * `null` (или `false` для флагов) означает «не отправлять этот параметр вообще»:
 * так старые сборки LM Studio и нестандартные серверы не падают на неизвестных полях.
 */
data class LlmParams(
    val temperature: Double? = null,
    val topP: Double? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    val seed: Int? = null,
    val stream: Boolean = false,
    val reasoningEffort: String? = null,
    val responseFormatJson: Boolean = false,
    val stop: List<String> = emptyList(),
    val serviceTier: String? = null,
    val user: String? = null
) {
    /** Есть ли хоть один необязательный параметр (нужно для повторного запроса без них). */
    val hasOptional: Boolean
        get() = temperature != null || topP != null || presencePenalty != null ||
            frequencyPenalty != null || seed != null || stream || reasoningEffort != null ||
            responseFormatJson || stop.isNotEmpty() || serviceTier != null || user != null

    companion object {
        /** Все поля выключены: в запрос уходят только model/messages/max_tokens. */
        val EMPTY = LlmParams()

        val REASONING_LEVELS = listOf("none", "low", "medium", "high")
        val SERVICE_TIERS = listOf("auto", "default", "flex", "priority")
    }

    /** Краткое описание для журнала: что именно уйдёт в запросе. */
    fun describe(): String {
        val parts = ArrayList<String>()
        temperature?.let { parts.add("temperature=$it") }
        topP?.let { parts.add("top_p=$it") }
        presencePenalty?.let { parts.add("presence_penalty=$it") }
        frequencyPenalty?.let { parts.add("frequency_penalty=$it") }
        seed?.let { parts.add("seed=$it") }
        if (stream) parts.add("stream=true")
        reasoningEffort?.let { parts.add("reasoning_effort=$it") }
        if (responseFormatJson) parts.add("response_format=json_object")
        if (stop.isNotEmpty()) parts.add("stop=${stop.joinToString("|")}")
        serviceTier?.let { parts.add("service_tier=$it") }
        user?.let { parts.add("user=$it") }
        return if (parts.isEmpty()) "не отправляются (только model/messages/max_tokens)" else parts.joinToString(", ")
    }
}
