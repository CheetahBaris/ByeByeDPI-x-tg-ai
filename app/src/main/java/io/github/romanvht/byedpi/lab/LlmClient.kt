package io.github.romanvht.byedpi.lab

import android.net.Network
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Клиент OpenAI-совместимого API (LM Studio, Ollama, OpenAI, ...).
 *
 * Адрес можно вводить как угодно:
 *  - `192.168.1.5:1234`      -> http://192.168.1.5:1234/v1
 *  - `http://192.168.1.5:1234` -> http://192.168.1.5:1234/v1
 *  - `http://127.0.0.1:1234/v1` -> без изменений
 *  - `https://api.openai.com/v1` -> без изменений
 *
 * Необязательные параметры ([LlmParams]) отправляются только если пользователь
 * включил их в настройках. Если сервер отвечает 400/422 на неизвестное поле,
 * запрос автоматически повторяется без необязательных параметров, а затем —
 * минимальным телом (model/messages/max_tokens).
 */
class LlmClient(
    baseUrl: String,
    private val model: String,
    private val apiKey: String?,
    /** Если задано — все запросы идут строго через эту сеть (например, Wi-Fi). */
    private val network: Network? = null,
    private val connectTimeoutMs: Int = 6_000,
    private val readTimeoutMs: Int = 300_000,
    private val params: LlmParams = LlmParams.EMPTY,
    /** Одна сессия: system/правила/база отправляются один раз, дальше — только новые данные. */
    private val session: Boolean = false,
    /** Контекст модели в токенах: сессия сжимается, чтобы не выйти за него. 0 — не проверять. */
    private val contextTokens: Int = 0,
    /** Журнал лаборатории: сообщения о сжатии сессии. */
    private val onLog: (String) -> Unit = {}
) {
    val baseUrl: String = normalizeBaseUrl(baseUrl)

    /** История диалога в режиме одной сессии. */
    private val history = ArrayList<JsonObject>()
    private var historyTokens = 0

    /**
     * Рабочий лимит контекста. Если сервер сообщил, что реальный context length
     * меньше настроенного, здесь запоминается уточнённое значение — чтобы
     * следующие раунды сжимались заранее и не тратили попытку на ошибку.
     */
    private var effectiveContextTokens = contextTokens

    fun hasSessionHistory(): Boolean =
        history.any { SessionCompactor.role(it) != "system" }

    /** Приблизительный размер истории сессии в токенах. */
    fun sessionTokens(): Int = historyTokens

    fun listModels(): List<String> {
        val text = request("GET", "/models", null, accept = "application/json")
        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: throw IllegalStateException("ответ не JSON: ${text.take(200)}")
        val arr = root.getAsJsonArray("data") ?: return emptyList()
        return arr.mapNotNull { el ->
            el.takeIf { it.isJsonObject }?.asJsonObject?.get("id")
                ?.takeIf { !it.isJsonNull }?.asString
        }
    }

    /**
     * Один запрос к /chat/completions.
     *
     * Параметры из [params] добавляются в тело. При ошибке 400/422 модель
     * получает запрос без них — так работают старые серверы, которые не знают
     * `reasoning_effort`, `response_format`, `service_tier` и т.п.
     *
     * В режиме [session] system-сообщение (правила + системный промпт + база)
     * добавляется только в первый запрос. Дальше отправляется накопленная
     * история диалога и новое user-сообщение.
     */
    fun chat(system: String, user: String, maxTokens: Int = 2600): String {
        if (session) {
            if (history.isEmpty() && system.isNotBlank()) {
                history.add(message("system", system))
                historyTokens = SessionCompactor.estimate(history)
            } else if (history.none { SessionCompactor.role(it) == "system" } && system.isNotBlank()) {
                history.add(0, message("system", system))
                historyTokens = SessionCompactor.estimate(history)
            }
        }
        val content = sendWithCompaction(maxTokens) { buildMessages(system, user) }
        if (session) remember(user, content)
        return content
    }

    /**
     * Продолжение сессии: отправляет только новое user-сообщение.
     * Используется, например, для повтора при невалидном JSON.
     */
    fun followUp(user: String, maxTokens: Int = 2600): String {
        check(session) { "продолжение доступно только в режиме одной сессии" }
        check(hasSessionHistory()) { "в сессии ещё нет истории" }
        val content = sendWithCompaction(maxTokens) {
            ArrayList<JsonObject>(history.size + 1).apply {
                addAll(history)
                add(message("user", user))
            }
        }
        remember(user, content)
        return content
    }

    private fun buildMessages(system: String, user: String): List<JsonObject> {
        val out = ArrayList<JsonObject>(history.size + 2)
        if (session) {
            out.addAll(history)
        } else if (system.isNotBlank()) {
            out.add(message("system", system))
        }
        out.add(message("user", user))
        return out
    }

    private fun remember(user: String, content: String) {
        history.add(message("user", user))
        history.add(message("assistant", content))
        historyTokens = SessionCompactor.estimate(history)
    }

    /**
     * Отправляет запрос и сжимает сессию, если промпт не помещается в контекст.
     *
     * Сжатие как в opencode: старые раунды заменяются короткой сводкой,
     * свежие сообщения остаются. Если сервер всё равно отвечает, что контекст
     * переполнен (его реальный лимит меньше настроенного), промпт и запас на
     * ответ уменьшаются вдвое и запрос повторяется.
     */
    private fun sendWithCompaction(
        maxTokens: Int,
        build: () -> List<JsonObject>
    ): String {
        var messages = build()
        if (session && effectiveContextTokens > 0) {
            val before = SessionCompactor.estimate(messages)
            val budget = effectiveContextTokens - maxTokens - 128
            if (budget in 1 until before) {
                SessionCompactor.compact(
                    history,
                    budget - SessionCompactor.messageTokens(messages.last()),
                    onLog
                )
                historyTokens = SessionCompactor.estimate(history)
                messages = build()
                val after = SessionCompactor.estimate(messages)
                if (after < before) {
                    onLog(
                        "Сессия сжата заранее: ~$before → ~$after токенов " +
                            "(бюджет $budget, ответ ≤$maxTokens)"
                    )
                }
            }
        }

        var currentMax = maxTokens
        var current = messages
        var overflows = 0
        while (true) {
            try {
                return perform(current, currentMax)
            } catch (e: ContextOverflowException) {
                if (!session || overflows >= 3) {
                    throw IllegalStateException(
                        "Контекст модели переполнен" +
                            (if (session) " даже после сжатия сессии" else "") +
                            ": промпт ~${SessionCompactor.estimate(current)} + ответ ≤$currentMax токенов. " +
                            "Укажи в «Контекст» реальный context length модели, " +
                            "сократи базу знаний/системный промпт или подними контекст в LM Studio. " +
                            "Ответ сервера: ${e.message}",
                        e
                    )
                }
                overflows++
                val promptNow = SessionCompactor.estimate(current)
                val newMax = (currentMax / 2).coerceAtLeast(256)
                // Реальный лимит сервера меньше настроенного: сжимаем промпт
                // ступенчато, чтобы сначала сохранить правила и базу.
                val factor = when (overflows) {
                    1 -> 0.70
                    2 -> 0.45
                    else -> 0.30
                }
                val target = (promptNow * factor).toInt().coerceAtLeast(512)
                effectiveContextTokens = (target + newMax).coerceAtLeast(512)
                onLog(
                    "Сервер: контекст переполнен (~$promptNow + ответ ≤$currentMax). " +
                        "Сжимаю сессию до ~$target токенов, ответ до ≤$newMax " +
                        "(реальный контекст ≈ $effectiveContextTokens)…"
                )
                currentMax = newMax
                SessionCompactor.compact(
                    history,
                    target - SessionCompactor.messageTokens(current.last()),
                    onLog
                )
                historyTokens = SessionCompactor.estimate(history)
                current = build()
            }
        }
    }

    private fun perform(messages: List<JsonObject>, maxTokens: Int): String {
        val attempts = buildList {
            if (params.hasOptional) add(Attempt(withOptional = true, withReasoning = true))
            if (params.reasoningEffort != null) add(Attempt(withOptional = true, withReasoning = false))
            if (params.hasOptional) add(Attempt(withOptional = false, withReasoning = false))
            if (isEmpty()) add(Attempt(withOptional = false, withReasoning = false))
        }

        var lastError = "модель не ответила"
        for (attempt in attempts) {
            val body = requestBody(messages, maxTokens, attempt).toString()
            try {
                val text = request(
                    "POST",
                    "/chat/completions",
                    body,
                    accept = if (params.stream) "text/event-stream" else "application/json"
                )
                val content = if (params.stream && looksLikeSse(text)) {
                    parseStream(text)
                } else {
                    parseCompletion(text)
                }
                if (content.isNotBlank()) return content
                lastError = "пустой ответ модели"
            } catch (e: HttpStatusException) {
                // Контекст переполнен — не пытаемся повторять то же тело,
                // отдаём наверх, чтобы сессия сжалась и запрос ушёл заново.
                if (isContextOverflow(e)) {
                    throw ContextOverflowException(e.code, e.message.orEmpty())
                }
                lastError = e.message ?: "HTTP ${e.code}"
                // 400/422 — сервер не понял поле. Пробуем следующее, более простое тело.
                if (e.code == 400 || e.code == 422) continue
                throw e
            }
        }
        throw IllegalStateException(lastError)
    }

    private fun isContextOverflow(e: HttpStatusException): Boolean {
        if (e.code !in CONTEXT_ERROR_CODES) return false
        val text = e.message.orEmpty().lowercase()
        return CONTEXT_ERROR_MARKERS.any { it in text }
    }

    private class ContextOverflowException(code: Int, message: String) :
        RuntimeException("HTTP $code: $message")

    private data class Attempt(val withOptional: Boolean, val withReasoning: Boolean)

    private fun requestBody(
        messages: List<JsonObject>,
        maxTokens: Int,
        attempt: Attempt
    ): JsonObject = JsonObject().apply {
        addProperty("model", model.ifBlank { "local-model" })
        add("messages", JsonArray().apply { messages.forEach { add(it) } })
        addProperty("max_tokens", maxTokens)

        if (attempt.withOptional) {
            params.temperature?.let { addProperty("temperature", it) }
            params.topP?.let { addProperty("top_p", it) }
            params.presencePenalty?.let { addProperty("presence_penalty", it) }
            params.frequencyPenalty?.let { addProperty("frequency_penalty", it) }
            params.seed?.let { addProperty("seed", it) }
            if (params.stream) addProperty("stream", true)
            if (params.responseFormatJson) {
                add("response_format", JsonObject().apply { addProperty("type", "json_object") })
            }
            if (params.stop.isNotEmpty()) {
                add("stop", JsonArray().apply { params.stop.forEach { add(it) } })
            }
            params.serviceTier?.let { addProperty("service_tier", it) }
            params.user?.let { addProperty("user", it) }
        }
        if (attempt.withReasoning) {
            params.reasoningEffort?.let { addProperty("reasoning_effort", it) }
        }
    }

    private fun parseCompletion(text: String): String {
        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: return ""
        val choice = root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
        val msg = choice?.getAsJsonObject("message")
        val content = contentOf(msg)
        if (content.isNotBlank()) return content
        return msg?.get("reasoning_content")
            ?.takeIf { !it.isJsonNull }?.asString.orEmpty()
    }

    private fun looksLikeSse(text: String): Boolean =
        text.lineSequence().any { it.trimStart().startsWith("data:") }

    /** Разбирает SSE-поток `data: {...}` и склеивает дельты ответа. */
    private fun parseStream(text: String): String {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        for (line in text.lineSequence()) {
            val trimmed = line.trimStart()
            if (!trimmed.startsWith("data:")) continue
            val payload = trimmed.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") continue
            val obj = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull() ?: continue
            val choice = obj.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject ?: continue
            val delta = choice.getAsJsonObject("delta")
            content.append(
                delta?.get("content")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            )
            reasoning.append(
                delta?.get("reasoning_content")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            )
        }
        return content.toString().ifBlank { reasoning.toString() }
    }

    private fun contentOf(msg: JsonObject?): String {
        val node: JsonElement = msg?.get("content") ?: return ""
        if (node.isJsonNull) return ""
        if (node.isJsonPrimitive) return node.asString
        if (node.isJsonArray) {
            return node.asJsonArray.mapNotNull { el ->
                val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                o.get("text")?.takeIf { !it.isJsonNull }?.asString
            }.joinToString("\n")
        }
        return ""
    }

    private fun message(role: String, content: String): JsonObject = JsonObject().apply {
        addProperty("role", role)
        addProperty("content", content)
    }

    private fun request(
        method: String,
        path: String,
        body: String?,
        accept: String
    ): String {
        val url = URL(baseUrl.trimEnd('/') + path)
        val conn = LabNetwork.openConnection(network, url) as? HttpURLConnection
            ?: throw IllegalStateException("не удалось открыть HTTP-соединение")
        conn.requestMethod = method
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", accept)
        if (!apiKey.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
        if (body != null) {
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
        } ?: ""
        conn.disconnect()
        if (code !in 200..299) {
            throw HttpStatusException(code, "HTTP $code: ${text.take(300)}")
        }
        return text
    }

    class HttpStatusException(val code: Int, message: String) : RuntimeException(message)

    companion object {
        private val CONTEXT_ERROR_CODES = setOf(400, 413, 422, 500, 503)

        private val CONTEXT_ERROR_MARKERS = listOf(
            "context", "exceeds", "too long", "too many tokens",
            "maximum context", "sequence length", "n_ctx", "kv cache"
        )

        fun normalizeBaseUrl(raw: String): String {
            var s = raw.trim()
            if (s.isEmpty()) return "http://127.0.0.1:1234/v1"
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            s = s.trimEnd('/')
                .removeSuffix("/chat/completions")
                .removeSuffix("/models")
                .trimEnd('/')
            val path = runCatching { URL(s).path.orEmpty() }.getOrDefault("")
            if (path.isEmpty() || path == "/") s = "$s/v1"
            return s
        }
    }
}
