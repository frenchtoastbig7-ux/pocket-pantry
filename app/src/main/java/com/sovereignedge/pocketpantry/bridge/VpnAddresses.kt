package com.sovereignedge.pocketpantry.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Finds addresses the connector may listen on. It never listens on Wi-Fi or mobile data directly: only on a VPN
 * (Tailscale, Headscale or WireGuard), or on loopback for a USB `adb forward` connection.
 */
object VpnAddresses {
    data class Candidate(val address: String, val interfaceName: String, val label: String)

    fun candidates(context: Context): List<Candidate> {
        val found = LinkedHashMap<String, Candidate>()
        val cm = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val link = cm.getLinkProperties(network) ?: continue
            for (la in link.linkAddresses) {
                val addr = la.address as? Inet4Address ?: continue
                found.putIfAbsent(addr.hostAddress!!, Candidate(addr.hostAddress!!, link.interfaceName.orEmpty(), label(addr)))
            }
        }
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp }.forEach { nif ->
                nif.inetAddresses.toList().filterIsInstance<Inet4Address>().forEach { addr ->
                    if (isVpnInterfaceName(nif.name) || isCgnat(addr)) {
                        found.putIfAbsent(addr.hostAddress!!, Candidate(addr.hostAddress!!, nif.name, label(addr)))
                    }
                }
            }
        }
        return found.values.toList()
    }

    /** Tailscale and Headscale hand out addresses from the carrier-grade NAT range 100.64.0.0/10. */
    fun isCgnat(addr: InetAddress): Boolean {
        val b = addr.address
        if (b.size != 4) return false
        return (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xFF) in 64..127
    }

    fun isVpnInterfaceName(name: String): Boolean =
        name.startsWith("tun") || name.startsWith("wg") || name.startsWith("tailscale") || name.startsWith("ipsec")

    private fun label(addr: InetAddress) = if (isCgnat(addr)) "Tailscale / Headscale" else "VPN"

    const val LOOPBACK = "127.0.0.1"
}
