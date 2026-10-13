package com.sovereignedge.pocketpantry.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

const val SYNC_PORT = 47632
private const val TAG = "PantrySync"

/** Addresses other phones can reach this one at, Tailscale first because those work from anywhere. */
object Addresses {
    private const val TAILSCALE_V6_PREFIX = "fd7a:115c:a1e0"

    data class Local(val host: String, val tailscale: Boolean) {
        val hostPort: String get() = if (':' in host) "[$host]:$SYNC_PORT" else "$host:$SYNC_PORT"
    }

    fun isTailscale(address: InetAddress): Boolean = when (address) {
        // Tailscale hands out 100.64.0.0/10 (CGNAT range) and fd7a:115c:a1e0::/48.
        is Inet4Address -> address.address.let { (it[0].toInt() and 0xff) == 100 && (it[1].toInt() and 0xc0) == 0x40 }
        is Inet6Address -> address.hostAddress?.startsWith(TAILSCALE_V6_PREFIX) == true
        else -> false
    }

    fun local(): List<Local> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> nif.inetAddresses.toList() }
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isMulticastAddress }
            .filter { it is Inet4Address || isTailscale(it) }
            .map { Local(it.hostAddress!!.substringBefore('%'), isTailscale(it)) }
            .distinct()
            .sortedByDescending { it.tailscale }
    }.getOrDefault(emptyList())

    fun onTailscale(): Boolean = local().any { it.tailscale }

    /** "host:port" or "[v6]:port" or a bare host (defaults to [SYNC_PORT]). */
    fun parse(addr: String): InetSocketAddress {
        val text = addr.trim()
        if (text.startsWith("[")) {
            val host = text.substringAfter('[').substringBefore(']')
            val port = text.substringAfter("]:", "").toIntOrNull() ?: SYNC_PORT
            return InetSocketAddress(host, port)
        }
        val colons = text.count { it == ':' }
        return when {
            colons == 0 -> InetSocketAddress(text, SYNC_PORT)
            colons == 1 -> InetSocketAddress(text.substringBefore(':'), text.substringAfter(':').toIntOrNull() ?: SYNC_PORT)
            else -> InetSocketAddress(text, SYNC_PORT) // bare IPv6
        }
    }
}

/** Accepts incoming sync connections from household phones. */
class SyncServer(private val protocol: Protocol, private val scope: CoroutineScope, private val onResult: suspend (Protocol.Result) -> Unit) {
    private var server: ServerSocket? = null
    private var job: Job? = null

    val running: Boolean get() = job?.isActive == true

    fun start() {
        if (running) return
        job = scope.launch(Dispatchers.IO) {
            val socket = try {
                ServerSocket(SYNC_PORT).also { it.reuseAddress = true }
            } catch (e: Exception) {
                Log.w(TAG, "listen failed: $e")
                return@launch
            }
            server = socket
            try {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (_: Exception) { break }
                    launch {
                        try {
                            client.use { onResult(protocol.exchange(it)) }
                        } catch (e: Exception) {
                            Log.i(TAG, "incoming sync failed: $e")
                        }
                    }
                }
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        job?.cancel()
        job = null
        server = null
    }
}

/** Connects to one address and runs the exchange. */
class SyncClient(private val protocol: Protocol) {
    suspend fun sync(addr: String, timeoutMillis: Int = 4000): Protocol.Result = withContext(Dispatchers.IO) {
        Socket().use { socket ->
            socket.connect(Addresses.parse(addr), timeoutMillis)
            protocol.exchange(socket)
        }
    }
}

/**
 * Finds household phones on the same Wi-Fi with multicast DNS. The service name carries a prefix of the household id
 * so phones from other households are ignored before any connection is made. Tailscale doesn't carry multicast, so
 * remembered addresses cover that case.
 */
class LanDiscovery(context: Context, private val householdId: String, private val deviceId: String, private val onFound: (host: String, port: Int, deviceId: String) -> Unit) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val prefix = "pp-${householdId.take(8)}-"
    private val myName = "$prefix${deviceId.take(8)}"
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null

    fun start() {
        if (registration != null) return
        val info = NsdServiceInfo().apply {
            serviceName = myName
            serviceType = SERVICE_TYPE
            port = SYNC_PORT
            setAttribute("dev", deviceId)
        }
        registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { Log.w(TAG, "nsd register failed $errorCode") }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }.also { runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, it) } }

        discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "nsd discover failed $errorCode") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName
                if (!name.startsWith(prefix) || name == myName) return
                resolve(serviceInfo)
            }
        }.also { runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, it) } }
    }

    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo) {
        nsd.resolveService(
            info,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host?.hostAddress ?: return
                    val dev = serviceInfo.attributes["dev"]?.let { String(it) } ?: serviceInfo.serviceName
                    if (dev == deviceId) return
                    onFound(host, serviceInfo.port, dev)
                }
            },
        )
    }

    fun stop() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        registration = null
        discovery = null
    }

    private companion object {
        const val SERVICE_TYPE = "_pocketpantry._tcp."
    }
}
