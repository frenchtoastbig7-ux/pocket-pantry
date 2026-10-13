package com.sovereignedge.pocketpantry.ui.household

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.sovereignedge.pocketpantry.PantryApp
import com.sovereignedge.pocketpantry.R
import com.sovereignedge.pocketpantry.barcode.ScannerViewport
import com.sovereignedge.pocketpantry.sync.Addresses
import com.sovereignedge.pocketpantry.sync.SyncService
import com.sovereignedge.pocketpantry.ui.LocalChromeVisible
import com.sovereignedge.pocketpantry.ui.common.AppIconButton
import com.sovereignedge.pocketpantry.ui.common.ConfirmDialog
import com.sovereignedge.pocketpantry.ui.common.SectionHeader
import com.sovereignedge.pocketpantry.ui.common.TextPromptDialog
import com.sovereignedge.pocketpantry.ui.common.fmtDateTime
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HouseholdScreen(app: PantryApp, onBack: () -> Unit) {
    val household by app.household.state.collectAsStateWithLifecycle()
    val status by app.sync.status.collectAsStateWithLifecycle()
    val peers by app.repo.peers().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var scanning by rememberSaveable { mutableStateOf(false) }
    var typedCode by rememberSaveable { mutableStateOf("") }
    var joining by remember { mutableStateOf(false) }
    var addPeer by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var name by rememberSaveable(household.deviceName) { mutableStateOf(household.deviceName) }
    val addrs = remember(household.joined, status.listening) { Addresses.local() }

    val chrome = LocalChromeVisible.current
    LaunchedEffect(scanning) { chrome.value = !scanning }

    fun join(input: String) {
        if (joining) return
        joining = true
        scope.launch {
            try {
                val info = app.household.join(input)
                if (app.repo.isPristine()) app.repo.dropDefaultsForJoin()
                app.repo.seedOpsForExistingState()
                info.peerAddrs.forEach { app.repo.addManualPeer(it) }
                app.sync.restart()
                scanning = false
                typedCode = ""
                snackbar.showSnackbar("Joined. Looking for the other phone…")
                snackbar.showSnackbar(app.sync.syncNow("join"))
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "That code didn't work")
            } finally {
                joining = false
            }
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        app.household.setBackgroundSync(true)
        SyncService.start(context)
        if (!granted) scope.launch { snackbar.showSnackbar("Background sync is on; the notification is hidden because notifications are off.") }
    }

    if (scanning) {
        BackHandler { scanning = false }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            ScannerViewport(Modifier.fillMaxSize(), paused = joining, hint = "Scan the QR code shown on the other phone") { code ->
                if (code.startsWith("pocketpantry://")) join(code)
            }
            Row(Modifier.statusBarsPadding().padding(8.dp)) {
                FilledTonalIconButton(onClick = { scanning = false }) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Close") }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text("Household") }, navigationIcon = { AppIconButton(R.drawable.ic_arrow_back, "Back", onClick = onBack) }) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            if (!household.joined) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("One pantry for everyone at home", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Phones in a household share the pantry, shopping list, stores, prices, trips and saved recipes. They talk to each other " +
                            "directly over your Wi-Fi, or anywhere over Tailscale, encrypted with a code only you hold. There is no account and no server.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                app.household.create()
                                app.repo.seedOpsForExistingState()
                                app.sync.restart()
                                snackbar.showSnackbar("Household created. Show the code to the other phone.")
                            }
                        },
                    ) { Text("Start a household") }
                    Text("Or join one that already exists:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { scanning = true }) {
                        Icon(painterResource(R.drawable.ic_qr), contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Scan the other phone's QR code")
                    }
                    OutlinedTextField(
                        value = typedCode, onValueChange = { typedCode = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Or type the 32-character code") }, placeholder = { Text("ABCD-EFGH-…") },
                    )
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = typedCode.isNotBlank() && !joining, onClick = { join(typedCode) }) {
                        Text(if (joining) "Joining…" else "Join with code")
                    }
                    Text(
                        "Typing the code works when both phones are on the same Wi-Fi. From elsewhere, add the other phone's Tailscale address afterwards.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val payload = remember(household.code, addrs, household.deviceName) { app.household.qrPayload(addrs.map { it.hostPort }) }
                val qr = remember(payload) { payload?.let(::qrBitmap) }
                Card(Modifier.padding(16.dp).fillMaxWidth()) {
                    Column(Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Household code", style = MaterialTheme.typography.titleMedium)
                        qr?.let { Image(it.asImageBitmap(), contentDescription = "Household QR code", modifier = Modifier.size(220.dp)) }
                        Text(household.code ?: "", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                        Text("Anyone with this code can read and change the household's data. Share it only with people at home.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { clipboard.setText(AnnotatedString(household.code ?: "")) }) { Text("Copy") }
                            FilledTonalButton(onClick = {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, "Join my Pocket Pantry household with this code: ${household.code}")
                                }
                                context.startActivity(Intent.createChooser(send, "Share household code"))
                            }) { Text("Share") }
                        }
                    }
                }

                SectionHeader("Sync")
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (status.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        buildString {
                            append(if (status.listening) "Listening for household phones. " else "Not listening. ")
                            status.lastSyncAt?.let { append("Last synced ${fmtDateTime(it)}.") } ?: append("Not synced yet.")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    status.lastMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Button(enabled = !status.syncing, onClick = { scope.launch { snackbar.showSnackbar(app.sync.syncNow()) } }) { Text("Sync now") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Keep syncing in the background")
                            Text("Stays reachable when the app is closed, so a phone that's out can sync over Tailscale. Shows a quiet notification.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = household.backgroundSync,
                            onCheckedChange = { on ->
                                if (on) {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                                        app.household.setBackgroundSync(true)
                                        SyncService.start(context)
                                    } else notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    app.household.setBackgroundSync(false)
                                    SyncService.stop(context)
                                }
                            },
                        )
                    }
                }

                SectionHeader("This phone")
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it; app.household.setDeviceName(it) }, singleLine = true,
                        label = { Text("Name shown to the household") }, modifier = Modifier.fillMaxWidth(),
                    )
                    if (addrs.isEmpty()) Text("No network address right now.", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        addrs.forEach { a -> AssistChip(onClick = { clipboard.setText(AnnotatedString(a.hostPort)) }, label = { Text(if (a.tailscale) "Tailscale ${a.hostPort}" else a.hostPort) }) }
                    }
                    Text("Tailscale addresses let the other phone reach this one from anywhere; Wi-Fi addresses only work at home. Tap to copy.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                SectionHeader("Phones in this household")
                if (peers.isEmpty()) Text("None yet. Scan this phone's QR code on the other phone, or add its address below.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp))
                peers.forEach { peer ->
                    ListItem(
                        headlineContent = { Text(peer.name) },
                        supportingContent = {
                            Text(
                                buildString {
                                    append(peer.lastSync?.let { "Synced ${fmtDateTime(it)}" } ?: "Never synced")
                                    val ts = peer.addrs.filter { Addresses.isTailscale(Addresses.parse(it).let { s -> java.net.InetAddress.getByName(s.hostString) }) }
                                    if (ts.isNotEmpty()) append(" · Tailscale ${ts.first()}")
                                    else if (peer.addrs.isNotEmpty()) append(" · ${peer.addrs.first()}")
                                },
                            )
                        },
                        trailingContent = { AppIconButton(R.drawable.ic_delete, "Forget", onClick = { scope.launch { app.repo.forgetPeer(peer.deviceId) } }) },
                    )
                }
                TextButton(onClick = { addPeer = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Add a phone by address") }
                Text(
                    "Use the other phone's Tailscale address (100.x.y.z) to sync away from home; the port is ${com.sovereignedge.pocketpantry.sync.SYNC_PORT} unless you add one.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp),
                )

                SectionHeader("Leave")
                TextButton(onClick = { confirmLeave = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Leave household", color = MaterialTheme.colorScheme.error) }
                Text("Your data stays on this phone; it just stops syncing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (addPeer) {
        TextPromptDialog("Add a phone by address", "Address", confirmLabel = "Add", onDismiss = { addPeer = false }) { addr ->
            scope.launch {
                app.repo.addManualPeer(addr.trim())
                snackbar.showSnackbar(app.sync.syncNow("manual address"))
            }
        }
    }
    if (confirmLeave) {
        ConfirmDialog("Leave the household?", "This phone keeps its data and stops syncing. The others are unaffected.", confirmLabel = "Leave",
            onConfirm = {
                scope.launch {
                    app.sync.stop()
                    SyncService.stop(context)
                    app.household.setBackgroundSync(false)
                    app.household.leave()
                    app.repo.clearSyncState()
                }
            }, onDismiss = { confirmLeave = false })
    }
}

private fun qrBitmap(text: String, size: Int = 512): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
