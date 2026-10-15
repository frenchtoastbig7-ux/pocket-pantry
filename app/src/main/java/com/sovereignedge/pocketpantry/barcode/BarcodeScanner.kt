package com.sovereignedge.pocketpantry.barcode

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * Live camera preview that reports retail barcodes. [onBarcode] fires once per code, then not again for the same
 * code until [cooldownMillis] has passed, so holding the phone over a product doesn't add it five times.
 */
@Composable
fun BarcodeCamera(
    modifier: Modifier = Modifier,
    paused: Boolean = false,
    cooldownMillis: Long = 2500,
    onBarcode: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    if (!granted) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text(
                    "Pocket Pantry needs the camera to read barcodes. Nothing is recorded; frames are analysed on the phone and discarded.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(
                    modifier = Modifier.padding(top = 16.dp),
                    onClick = {
                        if (asked) {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                            )
                        } else launcher.launch(Manifest.permission.CAMERA)
                    },
                ) { Text(if (asked) "Open app settings" else "Allow camera") }
            }
        }
        return
    }

    val currentPaused by rememberUpdatedState(paused)
    val currentOnBarcode by rememberUpdatedState(onBarcode)
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
                    Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_ITF, Barcode.FORMAT_QR_CODE,
                )
                .build(),
        )
    }
    val lastSeen = remember { HashMap<String, Long>() }
    // Held so the camera can be released when the scanner leaves the screen; binding to the Activity lifecycle alone
    // would keep it (and the privacy indicator) running until the Activity stops.
    val boundProvider = remember { arrayOfNulls<ProcessCameraProvider>(1) }
    DisposableEffect(Unit) {
        onDispose {
            boundProvider[0]?.unbindAll()
            executor.shutdown()
            scanner.close()
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy ->
                    analyze(proxy, scanner, lastSeen, cooldownMillis, currentPaused) { code ->
                        previewView.post { currentOnBarcode(code) }
                    }
                }
                provider.unbindAll()
                if (executor.isShutdown) return@addListener // the scanner was closed before the camera finished opening
                boundProvider[0] = provider
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

private val frames = java.util.concurrent.atomic.AtomicLong()

@OptIn(ExperimentalGetImage::class)
private fun analyze(
    proxy: androidx.camera.core.ImageProxy,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    lastSeen: HashMap<String, Long>,
    cooldownMillis: Long,
    paused: Boolean,
    onCode: (String) -> Unit,
) {
    val media = proxy.image
    if (paused || media == null) {
        proxy.close()
        return
    }
    if (frames.incrementAndGet() == 1L) android.util.Log.i("PantryScan", "analysing ${proxy.width}x${proxy.height} rotation ${proxy.imageInfo.rotationDegrees}")
    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            val now = System.currentTimeMillis()
            for (barcode in barcodes) {
                val value = barcode.rawValue?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                val last = lastSeen[value] ?: 0L
                if (now - last < cooldownMillis) continue
                lastSeen[value] = now
                android.util.Log.i("PantryScan", "decoded ${barcode.format}: $value")
                onCode(value)
                break
            }
        }
        .addOnFailureListener { e -> android.util.Log.w("PantryScan", "barcode detection failed: $e") }
        .addOnCompleteListener { proxy.close() }
}

/** Scanner with a framing hint, for use inside a sheet or a full-screen route. */
@Composable
fun ScannerViewport(modifier: Modifier = Modifier, paused: Boolean = false, hint: String, onBarcode: (String) -> Unit) {
    Box(modifier) {
        BarcodeCamera(Modifier.fillMaxSize(), paused = paused, onBarcode = onBarcode)
        if (hint.isNotBlank()) Text(
            hint,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp),
            textAlign = TextAlign.Center,
            color = androidx.compose.ui.graphics.Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
