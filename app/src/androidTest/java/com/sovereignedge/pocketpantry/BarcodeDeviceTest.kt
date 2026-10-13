package com.sovereignedge.pocketpantry

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.sovereignedge.pocketpantry.barcode.ProductLookup
import com.sovereignedge.pocketpantry.data.Repo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Runs the bundled ML Kit model and the product lookup on a real device/emulator with a generated EAN-13 image. */
@RunWith(AndroidJUnit4::class)
class BarcodeDeviceTest {
    private val ean = "3017620422003" // Nutella 400 g, a well-known Open Food Facts entry

    @Test
    fun mlKitDecodesGeneratedEan13() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val bitmap = assets.open("ean13_$ean.png").use { BitmapFactory.decodeStream(it) }
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_UPC_A).build(),
        )
        val result = Tasks.await(scanner.process(InputImage.fromBitmap(bitmap, 0)), 30, TimeUnit.SECONDS)
        assertEquals(listOf(ean), result.map { it.rawValue })
    }

    @Test
    fun openFoodFactsLookupIsCached() = runBlocking {
        val online = runCatching { InetAddress.getByName("world.openfoodfacts.org").hostAddress != null }.getOrDefault(false)
        assumeTrue("needs network", online)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("pantry.db")
        val repo = Repo(context)
        val lookup = ProductLookup(repo)
        val first = lookup.lookup(ean)
        assertNotNull("Open Food Facts should know $ean", first)
        assertEquals("openfoodfacts", first!!.source)
        assertEquals(first.name, repo.product(ean)?.name)
    }
}
