package io.github.romanvht.byedpi.lab

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.Socket
import java.net.URL
import java.net.URLConnection
import kotlin.coroutines.resume

/**
 * Сетевая часть лаборатории.
 *
 * Задача: телефон может быть одновременно в Wi-Fi (где живёт LM Studio) и в
 * мобильной сети, которую надо проверить. По умолчанию Android гонит весь
 * трафик в Wi-Fi, поэтому без привязки тесты были бы «про Wi-Fi».
 *
 * Здесь три вещи:
 *  - [acquire] — находит (и при необходимости поднимает) нужную сеть;
 *  - [bindAppProcess] / [openConnection] / [bindSocket] — направляют трафик
 *    в выбранную сеть точечно, не ломая остальное;
 *  - [lanPrefixes] / [gateways] — подсказки для поиска LM Studio по Wi-Fi.
 *
 * Процесс движка (`:native`) привязывается отдельно — через NativeEngine.setNetwork,
 * потому что он живёт в другом процессе.
 */
object LabNetwork {

    private const val REQUEST_TIMEOUT_MS = 6_000L

    /** Держим запросы сетей зарегистрированными, иначе система может их «погасить». */
    private val holds = LinkedHashMap<Network, ConnectivityManager.NetworkCallback>()

    fun manager(ctx: Context): ConnectivityManager? =
        ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    fun active(ctx: Context): Network? = manager(ctx)?.activeNetwork

    /** Находит уже активную сеть нужного типа (без попыток её поднять). */
    fun find(ctx: Context, target: NetTarget): Network? {
        val cm = manager(ctx) ?: return null
        val all = cm.allNetworks.toList()
        return when (target) {
            NetTarget.SYSTEM -> cm.activeNetwork
            NetTarget.AUTO -> cm.activeNetwork ?: all.firstOrNull { usable(cm, it) }
            NetTarget.WIFI -> pick(cm, all, NetworkCapabilities.TRANSPORT_WIFI)
            NetTarget.CELLULAR -> pick(cm, all, NetworkCapabilities.TRANSPORT_CELLULAR)
            NetTarget.ETHERNET -> pick(cm, all, NetworkCapabilities.TRANSPORT_ETHERNET)
            NetTarget.USB -> null
        }
    }

    /**
     * Выбирает сеть нужного транспорта.
     *
     * Для мобильной сети обязателен интернет (у LTE есть IMS-сеть без него),
     * а для Wi-Fi/Ethernet интернет не обязателен: LM Studio может жить в
     * локальной сети без выхода в интернет.
     */
    private fun pick(
        cm: ConnectivityManager,
        networks: List<Network>,
        transport: Int
    ): Network? {
        val candidates = networks.filter { has(cm, it, transport) }
        return candidates.firstOrNull { validated(cm, it) }
            ?: candidates.firstOrNull { !isLocalOnly(cm, it) }
            ?: candidates.firstOrNull()
    }

    private fun isLocalOnly(cm: ConnectivityManager, network: Network): Boolean =
        cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true

    /**
     * Возвращает сеть для выбранной цели.
     *
     * Если сети нет в списке активных и [bringUp] = true — просит систему её
     * поднять (актуально для LTE, когда Wi-Fi является сетью по умолчанию).
     * Запрошенная сеть удерживается до [release].
     */
    suspend fun acquire(
        ctx: Context,
        target: NetTarget,
        bringUp: Boolean = true
    ): Network? {
        find(ctx, target)?.let { return it }
        if (!bringUp || target == NetTarget.SYSTEM || target == NetTarget.AUTO) return null

        val cm = manager(ctx) ?: return null
        val transport = when (target) {
            NetTarget.WIFI -> NetworkCapabilities.TRANSPORT_WIFI
            NetTarget.CELLULAR -> NetworkCapabilities.TRANSPORT_CELLULAR
            NetTarget.ETHERNET -> NetworkCapabilities.TRANSPORT_ETHERNET
            else -> return null
        }

        val request = NetworkRequest.Builder()
            .addTransportType(transport)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        return withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            suspendCancellableCoroutine<Network?> { cont ->
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        synchronized(holds) { holds[network] = this }
                        if (cont.isActive) cont.resume(network)
                    }

                    override fun onUnavailable() {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                val registered = runCatching { cm.requestNetwork(request, callback) }.isSuccess
                if (!registered && cont.isActive) cont.resume(null)
            }
        }
    }

    /** Снимает все удержания сетей. Вызывать после завершения подбора. */
    fun release(ctx: Context) {
        val cm = manager(ctx) ?: return
        synchronized(holds) {
            holds.values.forEach { callback ->
                runCatching { cm.unregisterNetworkCallback(callback) }
            }
            holds.clear()
        }
    }

    /** Привязывает текущий процесс к сети (null — вернуть системную по умолчанию). */
    fun bindAppProcess(ctx: Context, network: Network?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val cm = manager(ctx) ?: return false
        return runCatching { cm.bindProcessToNetwork(network) }.getOrDefault(false)
    }

    /** Открывает соединение строго через указанную сеть (не через сеть процесса). */
    fun openConnection(network: Network?, url: URL): URLConnection =
        if (network != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            network.openConnection(url)
        } else {
            url.openConnection()
        }

    /** Привязывает конкретный сокет к сети (для TCP-проб при сканировании). */
    fun bindSocket(network: Network?, socket: Socket): Boolean =
        network != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            runCatching { network.bindSocket(socket) }.isSuccess

    /**
     * Адрес ведёт на сам телефон (локальный LLM-сервер или adb reverse).
     * Для таких адресов привязка к Wi-Fi/LTE не нужна.
     */
    fun isLoopbackUrl(raw: String): Boolean {
        val s = raw.trim().let {
            if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it"
        }
        val host = runCatching { URL(s).host }.getOrNull()
            ?.trim('[', ']')
            ?.lowercase()
            ?: return false
        return host == "localhost" || host == "127.0.0.1" ||
            host == "::1" || host == "0.0.0.0"
    }

    /** /24-подсети локальных сетей (Wi-Fi/Ethernet). Мобильные и VPN не трогаем. */
    fun lanPrefixes(ctx: Context, only: Network? = null): List<String> {
        val cm = manager(ctx) ?: return emptyList()
        val out = LinkedHashSet<String>()
        val networks = if (only != null) listOf(only) else cm.allNetworks.toList()
        for (network in networks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) continue
            val lp = cm.getLinkProperties(network) ?: continue
            for (la in lp.linkAddresses) {
                val address = la.address as? Inet4Address ?: continue
                if (address.isLoopbackAddress) continue
                address.hostAddress?.substringBeforeLast('.')?.let { out.add(it) }
            }
        }
        return out.toList()
    }

    /** Шлюзы локальных сетей — самый вероятный адрес ПК с LM Studio. */
    fun gateways(ctx: Context, only: Network? = null): List<String> {
        val cm = manager(ctx) ?: return emptyList()
        val out = LinkedHashSet<String>()
        val networks = if (only != null) listOf(only) else cm.allNetworks.toList()
        for (network in networks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) continue
            val lp = cm.getLinkProperties(network) ?: continue
            for (route in lp.routes) {
                if (!route.isDefaultRoute) continue
                val gw = route.gateway as? Inet4Address ?: continue
                gw.hostAddress?.let { out.add(it) }
            }
        }
        return out.toList()
    }

    /** Человекочитаемое описание сетей для лога. */
    fun describe(ctx: Context): List<String> {
        val cm = manager(ctx) ?: return listOf("ConnectivityManager недоступен")
        val out = ArrayList<String>()
        val active = cm.activeNetwork
        out.add("По умолчанию: ${label(cm, active)}")
        for (network in cm.allNetworks) {
            if (network == active) continue
            out.add("Доступна: ${label(cm, network)}")
        }
        return out
    }

    fun label(ctx: Context, network: Network?): String {
        val cm = manager(ctx) ?: return "?"
        return label(cm, network)
    }

    fun targetLabel(target: NetTarget): String = when (target) {
        NetTarget.AUTO -> "авто"
        NetTarget.SYSTEM -> "как в системе"
        NetTarget.WIFI -> "Wi-Fi"
        NetTarget.CELLULAR -> "мобильная сеть"
        NetTarget.ETHERNET -> "Ethernet / USB-модем"
        NetTarget.USB -> "USB / локально (127.0.0.1)"
    }

    private fun label(cm: ConnectivityManager, network: Network?): String {
        if (network == null) return "нет"
        val caps = cm.getNetworkCapabilities(network)
        val lp = cm.getLinkProperties(network)
        val transports = ArrayList<String>()
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) transports.add("Wi-Fi")
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) transports.add("мобильная")
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) transports.add("ethernet")
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) transports.add("VPN")
        val addresses = lp?.linkAddresses
            ?.mapNotNull { it.address.hostAddress }
            ?.filter { !it.contains(':') }
            .orEmpty()
        return buildString {
            append(transports.joinToString("+").ifEmpty { "сеть" })
            if (addresses.isNotEmpty()) append(' ').append(addresses.joinToString(", "))
        }
    }

    private fun usable(cm: ConnectivityManager, network: Network): Boolean {
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun validated(cm: ConnectivityManager, network: Network): Boolean =
        cm.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

    private fun has(cm: ConnectivityManager, network: Network, transport: Int): Boolean {
        val caps = cm.getNetworkCapabilities(network) ?: return false
        if (!caps.hasTransport(transport)) return false
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
        // Мобильная сеть без интернета (IMS/SUPL) для нас бесполезна.
        if (transport == NetworkCapabilities.TRANSPORT_CELLULAR &&
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        ) return false
        return true
    }
}
