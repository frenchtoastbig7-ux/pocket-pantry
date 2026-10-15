package com.sovereignedge.pocketpantry.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.sovereignedge.pocketpantry.MainActivity
import com.sovereignedge.pocketpantry.PantryApp
import com.sovereignedge.pocketpantry.R

/**
 * Keeps the sync listener alive while the app is in the background, so a phone that is away (on Tailscale) can still
 * exchange changes with the one at home. Opt-in from the Household screen; shows a quiet persistent notification.
 */
class SyncService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as PantryApp
        if (!app.household.current.backgroundSync || !app.household.isJoined) {
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Household sync", NotificationManager.IMPORTANCE_MIN).apply { description = "Shown while Pocket Pantry stays reachable for household sync" },
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_kitchen)
            .setContentTitle("Household sync is on")
            .setContentText("Reachable by the other phones in your household")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        app.sync.start()
        return START_STICKY
    }

    override fun onDestroy() {
        val app = application as PantryApp
        if (!app.foregrounded) app.sync.pause()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "household_sync"
        private const val NOTIFICATION_ID = 7

        fun start(context: Context) = context.startForegroundService(Intent(context, SyncService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, SyncService::class.java))
    }
}

/** Brings the background listener back after a reboot when it was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as PantryApp
        if (app.household.current.backgroundSync && app.household.isJoined) SyncService.start(context)
    }
}
