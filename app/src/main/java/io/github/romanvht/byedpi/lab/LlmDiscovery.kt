package io.github.romanvht.byedpi.lab

import android.content.Context
import android.net.Network
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * Поиск LM Studio.
 *
 * Четыре режима ([ScanMode]):
 *  - [ScanMode.LOCAL] — проверяется ровно тот адрес, который введён в поле
 *    «Адрес API» (локальный сервер на телефоне, нестандартный порт/путь);
 *  - [ScanMode.USB]  — только 127.0.0.1: если сделан `adb reverse tcp:1234 tcp:1234`,
 *    сервер доступен по кабелю и сеть для этого не нужна;
 *  - [ScanMode.WIFI] — только локальная сеть: шлюз, /24-подсети Wi-Fi/Ethernet,
 *    типовые адреса хотспотов;
 *  - [ScanMode.BOTH] — Wi-Fi и USB, но без проверки «на телефоне» (по умолчанию).
 *
 * Если передан [network] (Wi-Fi), TCP-пробы и запросы `/v1/models` привязываются
 * к этой сети — иначе при активной мобильной сети сканирование уйдёт не туда.
 * Для [ScanMode.LOCAL] привязка не используется.
 */
object LlmDiscovery {

    data class Found(
        val url: String,
        val host: String,
        val models: List<String>,
        val isGateway: Boolean = false,
        val viaUsb: Boolean = false,
        val onDevice: Boolean = false
    )

    private const val USB_HOST = "127.0.0.1"

    private val COMMON_HOSTS = listOf(
        "192.168.43.1",   // Android hotspot
        "192.168.42.129", // Linux hotspot
        "192.168.137.1",  // Windows hotspot
        "192.168.1.1",
        "192.168.0.1",
        "172.20.10.1",    // iPhone hotspot
        "10.0.0.1"
    )

    suspend fun scan(
        ctx: Context,
        mode: ScanMode = ScanMode.BOTH,
        port: Int = 1234,
        timeoutMs: Int = 400,
        network: Network? = null,
        explicitUrl: String? = null,
        onProgress: (String) -> Unit = {}
    ): List<Found> = withContext(Dispatchers.IO) {
        if (mode == ScanMode.LOCAL) {
            return@withContext scanExplicit(explicitUrl, onProgress)
        }

        val useUsb = mode == ScanMode.BOTH || mode == ScanMode.USB
        val useWifi = mode == ScanMode.BOTH || mode == ScanMode.WIFI

        val hosts = LinkedHashSet<String>()
        val gateways = if (useWifi) LabNetwork.gateways(ctx, network) else emptyList()
        val prefixes = if (useWifi) LabNetwork.lanPrefixes(ctx, network) else emptyList()

        if (useUsb) hosts.add(USB_HOST)
        if (useWifi) {
            hosts.addAll(gateways)
            for (prefix in prefixes) {
                for (i in 1..254) hosts.add("$prefix.$i")
            }
            hosts.addAll(COMMON_HOSTS)
        }

        if (hosts.isEmpty()) {
            onProgress("Нет сетей для сканирования: включи Wi-Fi или сделай adb reverse")
            return@withContext emptyList()
        }

        val hint = when {
            useUsb && useWifi -> "Проверяю USB и ${hosts.size - 1} адресов в локальной сети…"
            useUsb -> "Проверяю USB (127.0.0.1)…"
            else -> "Проверяю ${hosts.size} адресов в локальной сети…"
        }
        onProgress(hint)

        val gatewaySet = gateways.toSet()
        val semaphore = Semaphore(64)

        val found = coroutineScope {
            hosts.map { host ->
                async {
                    semaphore.withPermit {
                        probe(host, port, timeoutMs, host in gatewaySet, network)
                    }
                }
            }.awaitAll().filterNotNull()
        }

        found
            .distinctBy { it.url }
            .sortedWith(
                compareByDescending<Found> { it.onDevice }
                    .thenByDescending { it.models.isNotEmpty() }
                    .thenByDescending { it.viaUsb }
                    .thenByDescending { it.isGateway }
                    .thenBy { it.host }
            )
    }

    /**
     * Проверяет ровно тот адрес, который введён в поле подключения
     * (локальный сервер на телефоне, нестандартный порт, свой путь).
     */
    private fun scanExplicit(raw: String?, onProgress: (String) -> Unit): List<Found> {
        val normalized = LlmClient.normalizeBaseUrl(raw.orEmpty())
        val url = runCatching { URL(normalized) }.getOrNull()
        if (url == null || url.host.isNullOrBlank()) {
            onProgress("Некорректный адрес: $normalized")
            return emptyList()
        }
        val socketHost = url.host.trim('[', ']')
        val urlPort = if (url.port > 0) url.port else if (url.protocol == "https") 443 else 80
        onProgress("Проверяю $normalized …")
        val reachable = runCatching {
            Socket().use { it.connect(InetSocketAddress(socketHost, urlPort), 1500) }
        }.isSuccess
        if (!reachable) return emptyList()

        val models = fetchModels("$normalized/models", null) ?: emptyList()
        val displayHost = if (url.port > 0) "${url.host}:${url.port}" else url.host
        return listOf(
            Found(
                url = normalized,
                host = displayHost,
                models = models,
                onDevice = true
            )
        )
    }

    private fun probe(
        host: String,
        port: Int,
        timeoutMs: Int,
        isGateway: Boolean,
        network: Network?
    ): Found? {
        val loopback = host == USB_HOST
        val reachable = runCatching {
            val socket = Socket()
            try {
                if (!loopback) LabNetwork.bindSocket(network, socket)
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            } finally {
                socket.close()
            }
        }.isSuccess
        if (!reachable) return null

        val base = "http://$host:$port"
        val models = fetchModels("$base/v1/models", network)
            ?: fetchModels("$base/models", network)
            ?: emptyList()

        return Found(
            url = "$base/v1",
            host = host,
            models = models,
            isGateway = isGateway,
            viaUsb = loopback
        )
    }

    private fun fetchModels(url: String, network: Network?): List<String>? {
        val conn = runCatching {
            LabNetwork.openConnection(network, URL(url)) as? HttpURLConnection
        }.getOrNull() ?: return null
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 1500
            conn.readTimeout = 2500
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code !in 200..299) return null
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JsonParser.parseString(text).asJsonObject
            val arr = root.getAsJsonArray("data") ?: return emptyList()
            arr.mapNotNull { el ->
                el.takeIf { it.isJsonObject }?.asJsonObject?.get("id")
                    ?.takeIf { !it.isJsonNull }?.asString
            }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
