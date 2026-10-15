package com.sovereignedge.pocketpantry.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sovereignedge.pocketpantry.BuildConfig
import com.sovereignedge.pocketpantry.PantryApp
import com.sovereignedge.pocketpantry.R
import com.sovereignedge.pocketpantry.Settings
import com.sovereignedge.pocketpantry.ai.ClaudeService
import com.sovereignedge.pocketpantry.data.Location
import com.sovereignedge.pocketpantry.data.Store
import com.sovereignedge.pocketpantry.ui.cart.StoreDialog
import com.sovereignedge.pocketpantry.ui.common.AppIcon
import com.sovereignedge.pocketpantry.ui.common.AppIconButton
import com.sovereignedge.pocketpantry.ui.common.ConfirmDialog
import com.sovereignedge.pocketpantry.ui.common.GroupLabel
import com.sovereignedge.pocketpantry.ui.common.Tile
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.graphics.Color
import com.sovereignedge.pocketpantry.ui.common.TextPromptDialog
import com.sovereignedge.pocketpantry.ui.common.fmtNumber
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: PantryApp, onBack: () -> Unit, onOpenHousehold: () -> Unit, onOpenConnector: () -> Unit) {
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val hh by app.household.state.collectAsStateWithLifecycle()
    val stores by app.repo.stores().collectAsStateWithLifecycle(emptyList())
    val locations by app.repo.locations().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var keyInput by rememberSaveable { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var diet by rememberSaveable { mutableStateOf(settings.dietaryNotes) }
    var household by rememberSaveable { mutableStateOf(settings.household) }
    var currency by rememberSaveable { mutableStateOf(settings.currency) }
    var editStore by remember { mutableStateOf<Store?>(null) }
    var deleteStore by remember { mutableStateOf<Store?>(null) }
    var addLocation by remember { mutableStateOf(false) }
    var renameLocation by remember { mutableStateOf<Location?>(null) }
    var deleteLocation by remember { mutableStateOf<Location?>(null) }
    var confirmWipe by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { AppIconButton(R.drawable.ic_arrow_back, "Back", onClick = onBack) }) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            GroupLabel("Household")
            Tile(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable(onClick = onOpenHousehold),
                headlineContent = { Text("Household sync") },
                supportingContent = { Text(if (hh.joined) "Sharing the pantry, lists, stores and recipes with your household" else "Share one pantry with the people you live with, phone to phone, no account") },
                trailingContent = { AppIcon(R.drawable.ic_arrow_forward, null) },
            )
            }

            GroupLabel("Claude")
            Tile(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable(onClick = onOpenConnector),
                headlineContent = { Text("Claude connector") },
                supportingContent = { Text(if (settings.connectorEnabled) "On: Claude Code on your computer can read and update the pantry over Tailscale" else "Let Claude Code or Claude Desktop use the pantry on your own subscription, no API key") },
                trailingContent = { AppIcon(R.drawable.ic_arrow_forward, null) },
            )
            Text("Recipes inside the app (below) use an API key instead.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (settings.hasApiKey) "Connected with an API key. Recipes are unlocked." else "Not connected. Recipes are locked until a key is added.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (settings.hasApiKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Create a key at console.anthropic.com → API keys. It is encrypted with this phone's hardware Keystore and only " +
                        "ever sent to api.anthropic.com. Anthropic's terms don't allow third-party apps to sign in with a Claude.ai " +
                        "subscription, so a key is the supported way to connect.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = keyInput, onValueChange = { keyInput = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (settings.hasApiKey) "Replace API key" else "API key (sk-ant-…)") },
                    visualTransformation = PasswordVisualTransformation(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = keyInput.trim().length > 20, onClick = {
                        app.settings.setApiKey(keyInput)
                        app.claude.forget()
                        keyInput = ""
                        testResult = null
                        scope.launch { snackbar.showSnackbar("Key saved") }
                    }) { Text("Save key") }
                    OutlinedButton(enabled = settings.hasApiKey && !testing, onClick = {
                        testing = true
                        testResult = null
                        scope.launch {
                            testResult = try { app.claude.testConnection() } catch (e: Exception) { ClaudeService.describe(e) }
                            testing = false
                        }
                    }) { Text(if (testing) "Testing…" else "Test connection") }
                    if (settings.hasApiKey) TextButton(onClick = { confirmForget = true }) { Text("Forget") }
                }
                testResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.height(8.dp))
            }
            }

            GroupLabel("Model")
            Tile(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Settings.MODELS.forEach { (id, label) ->
                Row(Modifier.fillMaxWidth().clickable { app.settings.setModel(id) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = settings.model == id, onClick = { app.settings.setModel(id) })
                    Column {
                        Text(label)
                        Text(id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text("Effort", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()) {
                Settings.EFFORTS.forEachIndexed { index, effort ->
                    SegmentedButton(selected = settings.effort == effort, onClick = { app.settings.setEffort(effort) }, shape = SegmentedButtonDefaults.itemShape(index, Settings.EFFORTS.size)) {
                        Text(effort.replaceFirstChar { it.uppercase() })
                    }
                }
            }
            Text("Higher effort thinks longer for better meal plans and costs more per request.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp))
            }

            GroupLabel("Appearance")
            Tile(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()) {
                Settings.THEME_MODES.forEachIndexed { index, (id, label) ->
                    SegmentedButton(selected = settings.themeMode == id, onClick = { app.settings.setThemeMode(id) }, shape = SegmentedButtonDefaults.itemShape(index, Settings.THEME_MODES.size)) {
                        Text(label)
                    }
                }
            }
            Text("Colours follow your wallpaper (Material You) in both light and dark.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp))
            }

            GroupLabel("Cooking preferences")
            Tile(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = diet, onValueChange = { diet = it; app.settings.setDietaryNotes(it) }, modifier = Modifier.fillMaxWidth(), minLines = 2,
                    label = { Text("Dietary notes, allergies, dislikes") }, placeholder = { Text("e.g. vegetarian, no peanuts, kids hate mushrooms") },
                )
                OutlinedTextField(
                    value = household, onValueChange = { household = it; app.settings.setHousehold(it) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Household") }, placeholder = { Text("e.g. 2 adults, 1 toddler; weeknights are busy") },
                )
            }
            }

            GroupLabel("Stores")
            Tile(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            stores.forEach { store ->
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(store.name) },
                    supportingContent = { Text(if (store.taxRate > 0) "Tax ${fmtNumber(store.taxRate)}%" else "No tax") },
                    trailingContent = {
                        Row {
                            AppIconButton(R.drawable.ic_edit, "Edit") { editStore = store }
                            AppIconButton(R.drawable.ic_delete, "Delete") { deleteStore = store }
                        }
                    },
                )
            }
            TextButton(onClick = { editStore = Store(0, "", 0.0) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Add store") }
            OutlinedTextField(
                value = currency, onValueChange = { currency = it; app.settings.setCurrency(it) }, singleLine = true,
                label = { Text("Currency symbol") }, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp).fillMaxWidth(),
            )
            }

            GroupLabel("Storage locations")
            Tile(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            locations.forEach { loc ->
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(loc.name) },
                    trailingContent = {
                        Row {
                            AppIconButton(R.drawable.ic_edit, "Rename") { renameLocation = loc }
                            AppIconButton(R.drawable.ic_delete, "Delete", enabled = locations.size > 1) { deleteLocation = loc }
                        }
                    },
                )
            }
            TextButton(onClick = { addLocation = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Add location") }
            }

            GroupLabel("Data")
            Tile(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Text(
                "Everything stays on this phone (and on the other phones in your household). Deleting the app deletes the data.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            )
            TextButton(onClick = { confirmWipe = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Delete all data", color = MaterialTheme.colorScheme.error) }
            }
            Text(
                "Pocket Pantry ${BuildConfig.VERSION_NAME} · Product names via Open Food Facts (ODbL).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    editStore?.let { store ->
        StoreDialog(store, onDismiss = { editStore = null }) { saved ->
            scope.launch { app.repo.saveStore(saved) }
            editStore = null
        }
    }
    deleteStore?.let { store ->
        ConfirmDialog("Delete ${store.name}?", "Its remembered prices and past trips are deleted too.",
            onConfirm = { scope.launch { app.repo.deleteStore(store.id) } }, onDismiss = { deleteStore = null })
    }
    if (addLocation) TextPromptDialog("Add location", "Name", confirmLabel = "Add", onDismiss = { addLocation = false }) { scope.launch { app.repo.addLocation(it) } }
    renameLocation?.let { loc ->
        TextPromptDialog("Rename location", "Name", initial = loc.name, onDismiss = { renameLocation = null }) { scope.launch { app.repo.renameLocation(loc.id, it) } }
    }
    deleteLocation?.let { loc ->
        ConfirmDialog("Delete ${loc.name}?", "Every item stored there is deleted with it.",
            onConfirm = { scope.launch { app.repo.deleteLocation(loc.id) } }, onDismiss = { deleteLocation = null })
    }
    if (confirmForget) {
        ConfirmDialog("Forget the API key?", "Recipes lock again until a key is added.", confirmLabel = "Forget",
            onConfirm = { app.settings.clearApiKey(); app.claude.forget(); testResult = null }, onDismiss = { confirmForget = false })
    }
    if (confirmWipe) {
        ConfirmDialog("Delete all data?", "Pantry, carts, lists, recipes, stores and the API key are erased.", confirmLabel = "Delete everything",
            onConfirm = {
                scope.launch {
                    app.chat.clear()
                    app.repo.wipe()
                    app.settings.wipe()
                    app.claude.forget()
                    snackbar.showSnackbar("All data deleted")
                }
            }, onDismiss = { confirmWipe = false })
    }
}
