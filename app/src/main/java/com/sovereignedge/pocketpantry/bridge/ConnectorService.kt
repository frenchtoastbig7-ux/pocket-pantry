package com.sovereignedge.pocketpantry.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import com.sovereignedge.pocketpantry.MainActivity
import com.sovereignedge.pocketpantry.PantryApp
import com.sovereignedge.pocketpantry.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the [ConnectorServer] running in the foreground, bound only to the VPN address (or loopback for USB), and
 * re-binds when the VPN connects, disconnects or changes address.
 */
class ConnectorService : Service() {
    sealed interface Status {
        data object Stopped : Status
        data class Waiting(val reason: String) : Status
        data class Running(val host: String, val port: Int) : Status
    }

    companion object {
        private const val CHANNEL = "claude_connector"
        private const val NOTIFICATION_ID = 8
        val status = MutableStateFlow<Status>(Status.Stopped)
        val activity = MutableStateFlow<List<String>>(emptyList())

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, ConnectorService::class.java)) }
        }

        fun stop(context: Context) = context.stopService(Intent(context, ConnectorService::class.java))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rebindLock = Mutex()
    private var server: ConnectorServer? = null
    private var boundHost: String? = null
    private var callbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = rebind()
        override fun onLost(network: Network) = rebind()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = rebind()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as PantryApp
        if (!app.settings.current.connectorEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        notify("Starting")
        if (!callbackRegistered) {
            // The default request excludes VPNs; drop NOT_VPN so Tailscale's network is reported too.
            val request = NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
            getSystemService(ConnectivityManager::class.java).registerNetworkCallback(request, networkCallback)
            callbackRegistered = true
        }
        rebind(force = true)
        return START_STICKY
    }

    private fun notify(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Claude connector", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_restaurant)
            .setContentTitle("Claude connector")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        runCatching { startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
            .onFailure { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun rebind(force: Boolean = false) {
        scope.launch {
            rebindLock.withLock {
                val app = application as PantryApp
                val settings = app.settings.current
                val port = settings.connectorPort
                val host = if (settings.connectorBind == "loopback") VpnAddresses.LOOPBACK else VpnAddresses.candidates(this@ConnectorService).firstOrNull()?.address
                if (!force && host == boundHost && server != null) return@withLock

                server?.stop()
                server = null
                boundHost = null

                if (host == null) {
                    status.value = Status.Waiting("No VPN connection. Turn on Tailscale on this phone, or switch to USB.")
                    notify("Waiting for Tailscale")
                    return@withLock
                }
                val candidate = ConnectorServer(
                    repo = app.repo, settings = app.settings, token = app.secrets.bridgeToken(), host = host, port = port,
                    onActivity = { tool ->
                        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                        activity.update { (listOf("$time  $tool") + it).take(30) }
                    },
                )
                try {
                    candidate.start()
                } catch (e: Exception) {
                    status.value = Status.Waiting("Couldn't listen on $host:$port (${e.message})")
                    notify("Couldn't start")
                    return@withLock
                }
                server = candidate
                boundHost = host
                status.value = Status.Running(host, port)
                notify("Listening on $host:$port")
            }
        }
    }

    override fun onDestroy() {
        if (callbackRegistered) runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        server?.stop()
        server = null
        status.value = Status.Stopped
        scope.cancel()
        super.onDestroy()
    }
}
