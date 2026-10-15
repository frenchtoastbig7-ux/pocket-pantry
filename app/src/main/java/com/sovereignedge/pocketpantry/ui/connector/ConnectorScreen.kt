package com.sovereignedge.pocketpantry.ui.connector

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sovereignedge.pocketpantry.PantryApp
import com.sovereignedge.pocketpantry.R
import com.sovereignedge.pocketpantry.bridge.ConnectorService
import com.sovereignedge.pocketpantry.bridge.VpnAddresses
import com.sovereignedge.pocketpantry.ui.common.AppIconButton
import com.sovereignedge.pocketpantry.ui.common.ConfirmDialog
import com.sovereignedge.pocketpantry.ui.common.SectionHeader
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConnectorScreen(app: PantryApp, onBack: () -> Unit) {
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val status by ConnectorService.status.collectAsStateWithLifecycle()
    val activity by ConnectorService.activity.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var confirmRotate by remember { mutableStateOf(false) }
    val vpn = remember(status) { VpnAddresses.candidates(context) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        app.settings.setConnectorEnabled(true)
        ConnectorService.start(context)
    }
    fun enable() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            app.settings.setConnectorEnabled(true)
            ConnectorService.start(context)
        } else notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text("Claude connector") }, navigationIcon = { AppIconButton(R.drawable.ic_arrow_back, "Back", onClick = onBack) }) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Lets Claude Code or Claude Desktop on your own computer read and update this household's pantry, shopping list, prices and recipes, " +
                        "using your Claude subscription. The phone runs a small MCP server that only listens on your Tailscale address (or on USB), " +
                        "protected by a token. No API key, no account, nothing leaves your network.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Connector", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Switch(checked = settings.connectorEnabled, onCheckedChange = { on -> if (on) enable() else { app.settings.setConnectorEnabled(false); ConnectorService.stop(context) } })
                }
                Text(
                    when (val s = status) {
                        is ConnectorService.Status.Running -> "Listening on ${s.host}:${s.port}"
                        is ConnectorService.Status.Waiting -> s.reason
                        ConnectorService.Status.Stopped -> if (settings.connectorEnabled) "Starting…" else "Off"
                    },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                )
            }

            SectionHeader("Reach the phone over")
            SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                listOf("vpn" to "Tailscale / VPN", "loopback" to "USB (adb)").forEachIndexed { index, (id, label) ->
                    SegmentedButton(
                        selected = settings.connectorBind == id, shape = SegmentedButtonDefaults.itemShape(index, 2),
                        onClick = { app.settings.setConnectorBind(id); if (settings.connectorEnabled) ConnectorService.start(context) },
                    ) { Text(label) }
                }
            }
            Text(
                if (vpn.isEmpty()) "No VPN address right now. With Tailscale on, this phone's 100.x address appears here."
                else "VPN addresses: ${vpn.joinToString { "${it.address} (${it.label})" }}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp),
            )

            SectionHeader("Connect Claude Code")
            val host = (status as? ConnectorService.Status.Running)?.host ?: if (settings.connectorBind == "loopback") VpnAddresses.LOOPBACK else vpn.firstOrNull()?.address
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (host == null) {
                    Text("Turn on Tailscale (or switch to USB) to get the command.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    val token = app.secrets.bridgeToken()
                    val command = "claude mcp add --transport http --scope user pocket-pantry http://$host:${settings.connectorPort}/mcp --header \"Authorization: Bearer $token\""
                    Text("Run this once on your computer:", style = MaterialTheme.typography.bodySmall)
                    if (settings.connectorBind == "loopback") {
                        Text("adb forward tcp:${settings.connectorPort} tcp:${settings.connectorPort}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                    Card { Text(command.replace(token, "••••••••"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp)) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = {
                            val clip = ClipData.newPlainText("Claude Code command", command).apply {
                                description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                            }
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                            scope.launch { snackbar.showSnackbar("Command copied (includes the token)") }
                        }) { Text("Copy command") }
                        OutlinedButton(onClick = { confirmRotate = true }) { Text("New token") }
                    }
                    Text(
                        "Then ask Claude Code things like \"what can we cook tonight?\" or \"add what's missing for lasagne to the shopping list\". " +
                            "On the phone, the Claude app's Remote Control reaches the same session.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionHeader("Permissions")
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Allow deleting")
                    Text("Off: Claude can add and change items but not remove pantry or list entries.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = settings.connectorAllowDeletes, onCheckedChange = { app.settings.setConnectorAllowDeletes(it) })
            }

            SectionHeader("Recent activity")
            if (activity.isEmpty()) Text("No tool calls yet.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            activity.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmRotate) {
        ConfirmDialog("Make a new token?", "Claude Code will need the new command; the old token stops working.", confirmLabel = "New token",
            onConfirm = {
                app.secrets.rotateBridgeToken()
                if (settings.connectorEnabled) ConnectorService.start(context)
                scope.launch { snackbar.showSnackbar("New token made. Copy the command again.") }
            }, onDismiss = { confirmRotate = false })
    }
}
