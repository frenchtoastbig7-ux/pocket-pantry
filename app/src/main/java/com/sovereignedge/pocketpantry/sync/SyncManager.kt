package com.sovereignedge.pocketpantry.sync

import android.content.Context
import android.util.Log
import com.sovereignedge.pocketpantry.data.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

data class SyncStatus(
    val listening: Boolean = false,
    val syncing: Boolean = false,
    val lastSyncAt: Long? = null,
    val lastMessage: String? = null,
    val discovered: Int = 0,
)

/**
 * Keeps this phone reachable and keeps it up to date: listens for other household phones, advertises on the LAN,
 * syncs after local changes and on a timer, and remembers every address a peer has been reached at so the next
 * attempt can go over Tailscale when Wi-Fi isn't shared.
 */
class SyncManager(private val context: Context, val repo: Repo, val household: Household) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> Log.w("PantrySync", "background sync task failed: $e") })
    private val protocol = Protocol(repo, household) { Addresses.local().map { it.hostPort } }
    private val client = SyncClient(protocol)
    private val server = SyncServer(protocol, scope) { onExchanged(it, incoming = true) }
    private var discovery: LanDiscovery? = null
    private val discovered = ConcurrentHashMap<String, String>() // deviceId -> host:port
    private val syncLock = Mutex()
    private var loop: Job? = null
    private var debounce: Job? = null

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status

    val isRunning: Boolean get() = loop?.isActive == true

    /** Starts listening, advertising and the periodic sync. Safe to call repeatedly. */
    fun start() {
        if (isRunning || !household.isJoined) return
        val householdId = household.householdId() ?: return
        if (!LocalNetwork.granted(context)) {
            Log.w("PantrySync", "local network permission not granted; discovery and LAN sync will fail until it is")
            _status.value = _status.value.copy(lastMessage = "Allow local network access to find phones on your Wi-Fi")
        }
        server.start()
        discovery = LanDiscovery(context, householdId, household.deviceId, scope) { host, port, dev ->
            val addr = if (':' in host) "[$host]:$port" else "$host:$port"
            if (discovered.put(dev, addr) != addr) {
                _status.value = _status.value.copy(discovered = discovered.size)
                scope.launch { syncNow(reason = "found $dev") }
            }
        }.also { it.start() }
        _status.value = _status.value.copy(listening = true)
        loop = scope.launch {
            delay(1500)
            while (true) {
                syncNow(reason = "periodic")
                delay(PERIOD_MS)
            }
        }
        scope.launch { repo.changeTicks().collect { if (it != "peers" && it != "chat") scheduleSoon() } }
    }

    fun stop() {
        loop?.cancel(); loop = null
        debounce?.cancel(); debounce = null
        discovery?.stop(); discovery = null
        server.stop()
        discovered.clear()
        _status.value = _status.value.copy(listening = false, discovered = 0)
    }

    fun restart() {
        stop()
        start()
    }

    /** A local change happened: push it to whoever is reachable, after a short quiet period. */
    private fun scheduleSoon() {
        debounce?.cancel()
        debounce = scope.launch {
            delay(DEBOUNCE_MS)
            syncNow(reason = "change")
        }
    }

    /** Tries every known way to reach each household phone. Returns a one-line summary. */
    suspend fun syncNow(reason: String = "manual"): String = syncLock.withLock {
        if (!household.isJoined) return "Not in a household"
        _status.value = _status.value.copy(syncing = true)
        try {
            val peers = repo.peersNow()
            val onTailscale = Addresses.onTailscale()
            val targets = LinkedHashMap<String, List<String>>() // device -> candidate addrs in order
            discovered.forEach { (dev, addr) -> targets[dev] = listOf(addr) }
            for (peer in peers) {
                val ordered = peer.addrs.sortedByDescending { addr -> onTailscale == Addresses.isTailscale(Addresses.parse(addr).let { java.net.InetAddress.getByName(it.hostString) }) }
                targets[peer.deviceId] = (targets[peer.deviceId] ?: emptyList()) + ordered
            }
            if (targets.isEmpty()) return "No other phone known yet. Join on the same Wi-Fi or add an address."
            val results = targets.map { (dev, addrs) ->
                scope.async { syncDevice(dev, addrs.distinct()) }
            }.awaitAll()
            // The same phone can be reached through discovery and a remembered address; report it once.
            val ok = results.filterNotNull().distinctBy { it.peerDevice }
            val summary = when {
                ok.isEmpty() -> "Couldn't reach ${targets.size} phone${if (targets.size == 1) "" else "s"}"
                else -> "Synced with ${ok.joinToString { it.peerName }}: received ${ok.sumOf { it.received }}, sent ${ok.sumOf { it.sent }}"
            }
            Log.i("PantrySync", "$reason: $summary")
            _status.value = _status.value.copy(lastMessage = summary, lastSyncAt = if (ok.isEmpty()) _status.value.lastSyncAt else System.currentTimeMillis())
            summary
        } finally {
            _status.value = _status.value.copy(syncing = false)
        }
    }

    private suspend fun syncDevice(device: String, addrs: List<String>): Protocol.Result? {
        for (addr in addrs) {
            try {
                val result = client.sync(addr)
                onExchanged(result, incoming = false, via = addr)
                return result
            } catch (e: Protocol.HouseholdMismatch) {
                Log.i("PantrySync", "$addr: ${e.message}")
            } catch (e: Exception) {
                Log.d("PantrySync", "$device via $addr: $e")
            }
        }
        if (device.startsWith("manual:")) return null
        return null
    }

    private suspend fun onExchanged(result: Protocol.Result, incoming: Boolean, via: String? = null) {
        val addrs = (listOfNotNull(via) + result.peerAddrs).distinct()
        repo.rememberPeer(result.peerDevice, result.peerName, addrs, synced = true)
        if (via != null) repo.forgetPeer("manual:$via")
        if (incoming) {
            Log.i("PantrySync", "incoming from ${result.peerName}: received ${result.received}, sent ${result.sent}")
            _status.value = _status.value.copy(lastSyncAt = System.currentTimeMillis(), lastMessage = "Synced with ${result.peerName}: received ${result.received}, sent ${result.sent}")
        }
    }

    private companion object {
        const val PERIOD_MS = 120_000L
        const val DEBOUNCE_MS = 2_500L
    }
}
