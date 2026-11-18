package com.sovereignedge.pocketpantry.sync

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Android 17 (API 37) blocks local-network traffic (multicast DNS, broadcasts, even TCP to a LAN address) until the
 * user grants ACCESS_LOCAL_NETWORK at runtime. Older releases don't have the permission and allow it implicitly.
 */
object LocalNetwork {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    val required: Boolean get() = Build.VERSION.SDK_INT >= 37

    fun granted(context: Context): Boolean =
        !required || context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
}
