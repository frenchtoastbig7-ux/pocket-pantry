package com.sovereignedge.pocketpantry

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.sovereignedge.pocketpantry.ui.AppRoot
import com.sovereignedge.pocketpantry.ui.isDarkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as PantryApp
        intent?.dataString?.takeIf { it.startsWith("pocketpantry://join") }?.let { app.pendingJoin.value = it }
        // Debug builds: `adb shell am start -a com.sovereignedge.pocketpantry.SEED_DEMO -n <pkg>/.MainActivity` fills in sample data.
        if (BuildConfig.DEBUG && intent?.action == "com.sovereignedge.pocketpantry.SEED_DEMO") {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val storeId = app.repo.seedDemo()
                app.settings.setActiveStore(storeId)
                app.settings.setApiKey("sk-ant-demo-placeholder-not-a-real-key")
            }
        }
        setContent {
            val settings by app.settings.state.collectAsStateWithLifecycle()
            val dark = isDarkTheme(settings.themeMode)
            // Status and navigation bar icons must match the app theme even when it differs from the OS setting.
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            AppRoot(app)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.dataString?.takeIf { it.startsWith("pocketpantry://join") }?.let { (application as PantryApp).pendingJoin.value = it }
    }

    override fun onStart() {
        super.onStart()
        (application as PantryApp).onActivityStarted()
    }

    override fun onStop() {
        (application as PantryApp).onActivityStopped()
        super.onStop()
    }
}
