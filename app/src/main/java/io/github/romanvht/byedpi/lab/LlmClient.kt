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
 */
class LlmClient(
    baseUrl: String,
    private val model: String,
    private val apiKey: String?,
    /** Если задано — все запросы идут строго через эту сеть (например, Wi-Fi). */
    private val network: Network? = null,
    private val connectTimeoutMs: Int = 6_000,
    private val readTimeoutMs: Int = 300_000
) {
    val baseUrl: String = normalizeBaseUrl(baseUrl)

    fun listModels(): List<String> {
        val text = request("GET", "/models", null)
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
     * Если сервер не понимает `reasoning_effort` (старые сборки LM Studio),
     * повторяем без него.
     */
    fun chat(system: String, user: String, maxTokens: Int = 2600): String {
        var withEffort = true
        var lastError: String? = null
        repeat(2) {
            val body = JsonObject().apply {
                addProperty("model", model.ifBlank { "local-model" })
                add("messages", JsonArray().apply {
                    add(message("system", system))
                    add(message("user", user))
                })
                addProperty("temperature", 0.2)
                addProperty("max_tokens", maxTokens)
                addProperty("stream", false)
                if (withEffort) addProperty("reasoning_effort", "none")
            }
            try {
                val text = request("POST", "/chat/completions", body.toString())
                val root = JsonParser.parseString(text).asJsonObject
                val choice = root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                val msg = choice?.getAsJsonObject("message")

                val content = contentOf(msg)
                if (content.isNotBlank()) return content

                val reasoning = msg?.get("reasoning_content")
                    ?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                if (reasoning.isNotBlank()) return reasoning

                lastError = "пустой ответ модели"
            } catch (e: HttpStatusException) {
                lastError = e.message
                if ((e.code == 400 || e.code == 422) && withEffort) {
                    withEffort = false
                    return@repeat
                }
                throw e
            }
        }
        throw IllegalStateException(lastError ?: "модель не ответила")
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

    private fun request(method: String, path: String, body: String?): String {
        val url = URL(baseUrl.trimEnd('/') + path)
        val conn = LabNetwork.openConnection(network, url) as? HttpURLConnection
            ?: throw IllegalStateException("не удалось открыть HTTP-соединение")
        conn.requestMethod = method
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
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
