package com.sovereignedge.pocketpantry

import android.app.Application
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraXConfig
import com.sovereignedge.pocketpantry.ai.ClaudeService
import com.sovereignedge.pocketpantry.ai.RecipeChat
import com.sovereignedge.pocketpantry.barcode.ProductLookup
import com.sovereignedge.pocketpantry.data.Repo
import com.sovereignedge.pocketpantry.sync.Household
import com.sovereignedge.pocketpantry.sync.SyncManager
import com.sovereignedge.pocketpantry.sync.SyncService

class PantryApp : Application(), CameraXConfig.Provider {
    lateinit var repo: Repo
    lateinit var secrets: SecretStore
    lateinit var settings: Settings
    lateinit var claude: ClaudeService
    lateinit var chat: RecipeChat
    lateinit var products: ProductLookup
    lateinit var household: Household
    lateinit var sync: SyncManager

    /** True while an Activity is started; the sync listener runs then, and in the background only if opted in. */
    var foregrounded = false

    /** A pocketpantry://join link the app was opened with; the Household screen consumes it. */
    val pendingJoin = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    override fun onCreate() {
        super.onCreate()
        secrets = SecretStore(this)
        household = Household(this, secrets)
        repo = Repo(this, household.deviceId)
        settings = Settings(this, secrets)
        claude = ClaudeService(settings)
        chat = RecipeChat(repo, settings, claude)
        products = ProductLookup(repo)
        sync = SyncManager(this, repo, household)
    }

    fun onActivityStarted() {
        foregrounded = true
        sync.start()
    }

    fun onActivityStopped() {
        foregrounded = false
        if (household.current.backgroundSync && household.isJoined) SyncService.start(this) else sync.stop()
    }

    /** Only the back camera is used for barcodes, so CameraX needn't probe (or require) a front camera. */
    override fun getCameraXConfig(): CameraXConfig =
        CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
            .setAvailableCamerasLimiter(CameraSelector.DEFAULT_BACK_CAMERA)
            .build()
}
