package com.sovereignedge.pocketpantry.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

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

    /** Splits "host:port", "[v6]:port" or a bare host into host and port text (null when no port was written). */
    private fun split(addr: String): Pair<String, String?> {
        val text = addr.trim()
        if (text.startsWith("[")) return text.substringAfter('[').substringBefore(']') to text.substringAfter("]:", "").ifEmpty { null }
        return if (text.count { it == ':' } == 1) text.substringBefore(':') to text.substringAfter(':') else text to null // several colons: bare IPv6
    }

    /** "host:port" or "[v6]:port" or a bare host (defaults to [SYNC_PORT]). Resolves a host name; throws on a bad port. */
    fun parse(addr: String): InetSocketAddress {
        val (host, port) = split(addr)
        return InetSocketAddress(host, if (port == null) SYNC_PORT else port.toIntOrNull() ?: throw IllegalArgumentException("bad port in $addr"))
    }

    /** Whether [addr] could be dialled: a host and, if a port is written, one in range. Nothing is resolved. */
    fun isValid(addr: String): Boolean {
        val (host, port) = split(addr)
        return host.isNotBlank() && host.none { it.isWhitespace() } && (port == null || port.toIntOrNull() in 1..65535)
    }

    /** Whether [addr] is a Tailscale address, judged from its text alone; a host name is never looked up here. */
    fun isTailscaleAddr(addr: String): Boolean = runCatching {
        val host = split(addr).first
        (NUMERIC_V4.matches(host) || ':' in host) && isTailscale(InetAddress.getByName(host))
    }.getOrDefault(false)

    private val NUMERIC_V4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
}

/** Accepts incoming sync connections from household phones. */
class SyncServer(
    private val protocol: Protocol,
    private val scope: CoroutineScope,
    /** Told true once the port is bound and false when the listener ends; never true for a failed bind. */
    private val onListening: (Boolean) -> Unit = {},
    private val onResult: suspend (Protocol.Result) -> Unit,
) {
    private val lock = Any()
    private var server: ServerSocket? = null
    private var job: Job? = null
    private var generation = 0
    /** Each exchange can buffer a frame of several megabytes before the peer has proved it knows the household. */
    private val slots = java.util.concurrent.Semaphore(MAX_INCOMING)

    val running: Boolean get() = job?.isActive == true

    fun start() {
        if (running) return
        val mine = synchronized(lock) { ++generation }
        job = scope.launch(Dispatchers.IO) {
            val socket = try {
                ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(SYNC_PORT)) }
            } catch (e: Exception) {
                Log.w(TAG, "listen failed: $e")
                return@launch
            }
            // stop() may have run while we were binding. It had no socket to close then, so close it here
            // rather than block in accept() with the port held.
            val current = synchronized(lock) { (mine == generation).also { if (it) server = socket } }
            if (!current) {
                runCatching { socket.close() }
                return@launch
            }
            onListening(true)
            try {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (_: Exception) { break }
                    if (!slots.tryAcquire()) {
                        runCatching { client.close() }
                        continue
                    }
                    launch {
                        try {
                            client.use { onResult(protocol.exchange(it)) }
                        } catch (e: Exception) {
                            Log.i(TAG, "incoming sync failed: $e")
                        } finally {
                            slots.release()
                        }
                    }
                }
            } finally {
                runCatching { socket.close() }
                if (synchronized(lock) { mine == generation }) onListening(false)
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            generation++
            runCatching { server?.close() }
            server = null
        }
        job?.cancel()
        job = null
        onListening(false)
    }

    private companion object {
        const val MAX_INCOMING = 4
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
 * Finds household phones on the same Wi-Fi two ways: DNS-SD through the system responder, and a small UDP broadcast
 * beacon on [BEACON_PORT], because multicast DNS is unreliable on some phones and routers. Both only ever carry a
 * prefix of the household id, so other households are ignored before any connection is made. Tailscale doesn't carry
 * broadcasts, so remembered addresses cover that case.
 */
class LanDiscovery(
    private val context: Context,
    private val householdId: String,
    private val deviceId: String,
    private val scope: CoroutineScope,
    private val onFound: (host: String, port: Int, deviceId: String) -> Unit,
) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val prefix = "pp-${householdId.take(8)}-"
    private val myName = "$prefix${deviceId.take(8)}"
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var beacon: Job? = null
    private var beaconSocket: DatagramSocket? = null
    private val resolving = java.util.concurrent.ConcurrentHashMap.newKeySet<NsdManager.ServiceInfoCallback>()
    /** False once [stop] has run: results that were already on their way are dropped. */
    @Volatile private var active = false

    fun start() {
        if (registration != null) return
        active = true
        // Phones drop multicast and broadcast frames unless an app holds this lock.
        multicastLock = wifi?.createMulticastLock("pocketpantry-sync")?.apply { setReferenceCounted(false); runCatching { acquire() } }
        startNsd()
        startBeacon()
    }

    private fun startNsd() {
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
        }.also { runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, it) }.onFailure { e -> Log.w(TAG, "nsd register: $e") } }

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
        }.also { runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, it) }.onFailure { e -> Log.w(TAG, "nsd discover: $e") } }
    }

    private fun resolved(serviceInfo: NsdServiceInfo) {
        if (!active) return
        val addresses = if (Build.VERSION.SDK_INT >= 34) serviceInfo.hostAddresses else listOfNotNull(serviceInfo.host)
        // Prefer IPv4; Android reports link-local IPv6 with a scope suffix that can't be dialled as written.
        val host = (addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull { !it.isLinkLocalAddress })?.hostAddress?.substringBefore('%') ?: return
        val dev = serviceInfo.attributes["dev"]?.let { String(it) } ?: serviceInfo.serviceName
        if (dev == deviceId) return
        Log.i(TAG, "nsd found $dev at $host:${serviceInfo.port}")
        onFound(host, serviceInfo.port, dev)
    }

    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= 34) {
            runCatching {
                nsd.registerServiceInfoCallback(
                    info, context.mainExecutor,
                    object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) { resolving.remove(this) }
                        override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                            resolved(serviceInfo)
                            if (resolving.remove(this)) runCatching { nsd.unregisterServiceInfoCallback(this) }
                        }
                        override fun onServiceLost() = Unit
                        override fun onServiceInfoCallbackUnregistered() = Unit
                    }.also { resolving.add(it) },
                )
            }.onFailure { e -> Log.w(TAG, "nsd resolve: $e") }
        } else {
            nsd.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) = resolved(serviceInfo)
                },
            )
        }
    }

    /** Every few seconds: "pp1 <household prefix> <device id> <port>" to the broadcast address; listen for the same. */
    private fun startBeacon() {
        beacon = scope.launch(Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> Log.w(TAG, "beacon loop ended: $e") }) {
            val socket = try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(BEACON_PORT))
                    soTimeout = 1000
                }
            } catch (e: Exception) {
                Log.w(TAG, "beacon bind failed: $e")
                return@launch
            }
            beaconSocket = socket
            val payload = "pp1 ${householdId.take(16)} $deviceId $SYNC_PORT".toByteArray()
            val buffer = ByteArray(256)
            var lastSend = 0L
            try {
                while (isActive) {
                    val now = System.currentTimeMillis()
                    if (now - lastSend >= BEACON_INTERVAL_MS) {
                        lastSend = now
                        for (target in broadcastTargets()) {
                            runCatching { socket.send(DatagramPacket(payload, payload.size, target, BEACON_PORT)) }
                        }
                    }
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (e: Exception) {
                        if (socket.isClosed || !isActive) break // stop() closed the socket under us
                        Log.d(TAG, "beacon receive: $e")
                        continue
                    }
                    val parts = String(packet.data, 0, packet.length).split(' ')
                    if (parts.size != 4 || parts[0] != "pp1" || parts[1] != householdId.take(16) || parts[2] == deviceId) continue
                    val host = packet.address.hostAddress?.substringBefore('%') ?: continue
                    if (!active) break
                    Log.i(TAG, "beacon from ${parts[2]} at $host")
                    onFound(host, parts[3].toIntOrNull() ?: SYNC_PORT, parts[2])
                }
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    /** Directed broadcast addresses of every IPv4 interface, plus the limited broadcast address. */
    private fun broadcastTargets(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }
            .mapNotNull { it.broadcast }
            .plus(InetAddress.getByName("255.255.255.255"))
            .distinct()
    }.getOrDefault(listOf(InetAddress.getByName("255.255.255.255")))

    fun stop() {
        active = false
        // Lookups still waiting for an answer would otherwise report a phone after discovery has stopped.
        resolving.toList().forEach { if (resolving.remove(it)) runCatching { nsd.unregisterServiceInfoCallback(it) } }
        registration?.let { runCatching { nsd.unregisterService(it) } }
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        registration = null
        discovery = null
        beacon?.cancel(); beacon = null
        runCatching { beaconSocket?.close() }; beaconSocket = null
        multicastLock?.let { runCatching { if (it.isHeld) it.release() } }; multicastLock = null
    }

    private companion object {
        const val SERVICE_TYPE = "_pocketpantry._tcp."
        const val BEACON_PORT = 47633
        const val BEACON_INTERVAL_MS = 5_000L
    }
}
