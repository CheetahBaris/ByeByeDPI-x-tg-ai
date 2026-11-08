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
    private val params: LlmParams = LlmParams.EMPTY
) {
    val baseUrl: String = normalizeBaseUrl(baseUrl)

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
     */
    fun chat(system: String, user: String, maxTokens: Int = 2600): String {
        val attempts = buildList {
            if (params.hasOptional) add(Attempt(withOptional = true, withReasoning = true))
            if (params.reasoningEffort != null) add(Attempt(withOptional = true, withReasoning = false))
            if (params.hasOptional) add(Attempt(withOptional = false, withReasoning = false))
            if (isEmpty()) add(Attempt(withOptional = false, withReasoning = false))
        }

        var lastError = "модель не ответила"
        for (attempt in attempts) {
            val body = requestBody(system, user, maxTokens, attempt).toString()
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
                lastError = e.message ?: "HTTP ${e.code}"
                // 400/422 — сервер не понял поле. Пробуем следующее, более простое тело.
                if (e.code == 400 || e.code == 422) continue
                throw e
            }
        }
        throw IllegalStateException(lastError)
    }

    private data class Attempt(val withOptional: Boolean, val withReasoning: Boolean)

    private fun requestBody(
        system: String,
        user: String,
        maxTokens: Int,
        attempt: Attempt
    ): JsonObject = JsonObject().apply {
        addProperty("model", model.ifBlank { "local-model" })
        add("messages", JsonArray().apply {
            add(message("system", system))
            add(message("user", user))
        })
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
