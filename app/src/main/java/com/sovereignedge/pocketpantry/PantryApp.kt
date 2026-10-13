package com.sovereignedge.pocketpantry

import android.app.Application
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraXConfig
import com.sovereignedge.pocketpantry.ai.ClaudeService
import com.sovereignedge.pocketpantry.ai.RecipeChat
import com.sovereignedge.pocketpantry.barcode.ProductLookup
import com.sovereignedge.pocketpantry.data.Repo

class PantryApp : Application(), CameraXConfig.Provider {
    lateinit var repo: Repo
    lateinit var secrets: SecretStore
    lateinit var settings: Settings
    lateinit var claude: ClaudeService
    lateinit var chat: RecipeChat
    lateinit var products: ProductLookup

    override fun onCreate() {
        super.onCreate()
        repo = Repo(this)
        secrets = SecretStore(this)
        settings = Settings(this, secrets)
        claude = ClaudeService(settings)
        chat = RecipeChat(repo, settings, claude)
        products = ProductLookup(repo)
    }

    /** Only the back camera is used for barcodes, so CameraX needn't probe (or require) a front camera. */
    override fun getCameraXConfig(): CameraXConfig =
        CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
            .setAvailableCamerasLimiter(CameraSelector.DEFAULT_BACK_CAMERA)
            .build()
}
