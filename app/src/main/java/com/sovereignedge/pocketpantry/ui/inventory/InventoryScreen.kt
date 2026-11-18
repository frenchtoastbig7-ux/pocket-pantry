package com.sovereignedge.pocketpantry.ui.inventory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.sovereignedge.pocketpantry.ui.common.BigTitleBar
import com.sovereignedge.pocketpantry.ui.common.Monogram
import com.sovereignedge.pocketpantry.ui.common.Pill
import com.sovereignedge.pocketpantry.ui.common.Stat
import com.sovereignedge.pocketpantry.ui.common.Tile
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sovereignedge.pocketpantry.PantryApp
import com.sovereignedge.pocketpantry.R
import com.sovereignedge.pocketpantry.barcode.ScannerViewport
import com.sovereignedge.pocketpantry.data.Item
import com.sovereignedge.pocketpantry.data.Location
import com.sovereignedge.pocketpantry.data.Product
import com.sovereignedge.pocketpantry.ui.common.AppIconButton
import com.sovereignedge.pocketpantry.ui.common.EmptyState
import com.sovereignedge.pocketpantry.ui.common.fmtNumber
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InventoryScreen(app: PantryApp, modifier: Modifier = Modifier, onOpenSettings: () -> Unit) {
    val items by app.repo.items().collectAsStateWithLifecycle(emptyList())
    val locations by app.repo.locations().collectAsStateWithLifecycle(emptyList())
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var filter by rememberSaveable { mutableStateOf<Long?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Item?>(null) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var lookingUp by remember { mutableStateOf(false) }

    val today = LocalDate.now()
    val visible = items.filter { item ->
        (filter == null || item.locationId == filter) &&
            (query.isBlank() || item.name.contains(query, ignoreCase = true) || item.brand?.contains(query, ignoreCase = true) == true)
    }
    val expiringSoon = items.count { (it.daysUntilExpiry(today) ?: Long.MAX_VALUE) <= 3 }
    val low = items.count { it.isLow }

    fun newItem(product: Product? = null, barcode: String? = null) = Item(
        id = 0, name = product?.name ?: "", barcode = product?.barcode ?: barcode, brand = product?.brand,
        quantity = 1.0, unit = "", size = product?.quantityText ?: "", locationId = filter ?: settings.defaultLocationId.takeIf { id -> locations.any { it.id == id } } ?: locations.firstOrNull()?.id ?: 0,
        expiresOn = null, notes = "", lowThreshold = null, addedAt = 0, updatedAt = 0,
    )

    val chrome = com.sovereignedge.pocketpantry.ui.LocalChromeVisible.current
    androidx.compose.runtime.DisposableEffect(scanning) {
        chrome.value = !scanning
        onDispose { chrome.value = true }
    }

    if (scanning) {
        BackHandler { scanning = false }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            ScannerViewport(
                Modifier.fillMaxSize(),
                paused = editing != null,
                hint = "Point the camera at a barcode",
            ) { code ->
                if (editing != null) return@ScannerViewport
                // Open the editor at once; the product name arrives when the lookup answers (cache first, then Open Food Facts).
                editing = newItem(barcode = code)
                lookingUp = true
                scope.launch {
                    val product = runCatching { app.products.lookup(code) }.getOrNull()
                    lookingUp = false
                    val current = editing
                    if (current != null && current.id == 0L && current.barcode == code) {
                        // Suggest where it goes: the same barcode's usual place, or the guesser's verdict on name and category.
                        val suggested = app.repo.suggestLocation(product?.name ?: current.name, product?.brand, code, product?.category)
                        if (product != null) editing = current.copy(name = current.name.ifBlank { product.name }, brand = current.brand ?: product.brand, size = current.size.ifBlank { product.quantityText ?: "" }, locationId = suggested ?: current.locationId)
                        else {
                            if (suggested != null) editing = current.copy(locationId = suggested)
                            snackbar.showSnackbar("Unknown barcode. Name it once and Pocket Pantry will remember it.")
                        }
                    }
                }
            }
            Row(Modifier.statusBarsPadding().padding(8.dp)) {
                FilledTonalIconButton(onClick = { scanning = false }) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Close scanner") }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    } else {
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        Scaffold(
            modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                BigTitleBar(
                    title = "Pantry",
                    subtitle = if (items.isEmpty()) null else "${items.size} item${if (items.size == 1) "" else "s"}" + (if (expiringSoon > 0) " · $expiringSoon expiring soon" else "") + (if (low > 0) " · $low low" else ""),
                    scrollBehavior = scrollBehavior,
                    actions = {
                        AppIconButton(if (searching) R.drawable.ic_close else R.drawable.ic_search, "Search") {
                            searching = !searching
                            if (!searching) query = ""
                        }
                        AppIconButton(R.drawable.ic_settings, "Settings", onClick = onOpenSettings)
                    },
                )
            },
            floatingActionButton = {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SmallFloatingActionButton(onClick = { editing = newItem() }) { Icon(painterResource(R.drawable.ic_add), contentDescription = "Add item") }
                    ExtendedFloatingActionButton(
                        onClick = { scanning = true },
                        icon = { Icon(painterResource(R.drawable.ic_barcode), contentDescription = null) },
                        text = { Text("Scan") },
                    )
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (searching) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true, shape = MaterialTheme.shapes.large,
                        placeholder = { Text("Search pantry") }, leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All (${items.size})") }) }
                    items(locations, key = { it.id }) { loc ->
                        val count = items.count { it.locationId == loc.id }
                        FilterChip(selected = filter == loc.id, onClick = { filter = loc.id }, label = { Text("${loc.name} ($count)") })
                    }
                }
                if (expiringSoon > 0 || low > 0) {
                    Tile(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(), tone = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            Stat("$expiringSoon", "expiring soon", accent = if (expiringSoon > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            Stat("$low", "running low", accent = if (low > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.weight(1f))
                            if (low > 0) AssistChip(
                                onClick = {
                                    scope.launch {
                                        items.filter { it.isLow }.forEach { app.repo.addToShoppingList(it.name, barcode = it.barcode) }
                                        snackbar.showSnackbar("Added $low low item${if (low == 1) "" else "s"} to the shopping list")
                                    }
                                },
                                label = { Text("Add low to list") },
                            )
                        }
                    }
                }
                if (visible.isEmpty()) {
                    EmptyState(
                        R.drawable.ic_kitchen,
                        if (items.isEmpty()) "Your pantry is empty" else "Nothing matches",
                        if (items.isEmpty()) "Scan a barcode or add items by hand. Finished grocery trips can be added to the pantry in one tap."
                        else "Try another location or search term.",
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 160.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val grouped = if (filter == null) locations.mapNotNull { loc -> visible.filter { it.locationId == loc.id }.takeIf { it.isNotEmpty() }?.let { loc to it } }
                        else listOf((locations.firstOrNull { it.id == filter } ?: Location(0, "", 0)) to visible)
                        for ((loc, group) in grouped) {
                            if (filter == null) item(key = "h${loc.id}") {
                                Text(
                                    "${loc.name} · ${group.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 2.dp).animateItem(),
                                )
                            }
                            items(group, key = { it.id }) { item ->
                                ItemRow(
                                    item = item, today = today, modifier = Modifier.animateItem(),
                                    onClick = { editing = item },
                                    onAdjust = { delta -> scope.launch { app.repo.adjustQuantity(item.id, delta) } },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { item ->
        ItemEditorSheet(
            item = item,
            locations = locations,
            onDismiss = { editing = null },
            onSave = { saved ->
                scope.launch {
                    app.repo.upsertItem(saved)
                    if (saved.barcode != null && saved.name.isNotBlank() && app.repo.product(saved.barcode) == null) {
                        app.repo.rememberProduct(Product(saved.barcode, saved.name, saved.brand, null, "user"))
                    }
                    editing = null
                    snackbar.showSnackbar(if (item.id == 0L) "Added ${saved.name}" else "Saved")
                }
            },
            onDelete = if (item.id == 0L) null else {
                { scope.launch { app.repo.deleteItem(item.id); editing = null } }
            },
            onAddToList = { scope.launch { app.repo.addToShoppingList(item.name, barcode = item.barcode); snackbar.showSnackbar("Added ${item.name} to the shopping list") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemRow(item: Item, today: LocalDate, modifier: Modifier = Modifier, onClick: () -> Unit, onAdjust: (Double) -> Unit) {
    val days = item.daysUntilExpiry(today)
    val expiryLabel = when {
        days == null -> null
        days < 0 -> "Expired"
        days == 0L -> "Expires today"
        days <= 7 -> "Expires in $days d"
        else -> null
    }
    val urgent = days != null && days <= 3
    val scheme = MaterialTheme.colorScheme
    Surface(modifier = modifier.fillMaxWidth().clickable(onClick = onClick), shape = MaterialTheme.shapes.medium, color = scheme.surfaceContainerLow) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Monogram(item.name)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                val detail = listOfNotNull(item.brand, item.size.takeIf { it.isNotBlank() }).joinToString(" · ")
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1)
                if (expiryLabel != null || item.isLow) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        expiryLabel?.let { Pill(it, if (urgent) scheme.errorContainer else scheme.tertiaryContainer, if (urgent) scheme.onErrorContainer else scheme.onTertiaryContainer) }
                        if (item.isLow) Pill("Low", scheme.secondaryContainer, scheme.onSecondaryContainer)
                    }
                }
            }
            Surface(shape = MaterialTheme.shapes.extraLarge, color = scheme.surfaceContainerHighest) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIconButton(R.drawable.ic_remove, "Use one", onClick = { onAdjust(-1.0) }, enabled = item.quantity > 0)
                    Text((fmtNumber(item.quantity) + " " + item.unit).trim(), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.widthIn(min = 28.dp))
                    AppIconButton(R.drawable.ic_add, "Add one", onClick = { onAdjust(1.0) })
                }
            }
        }
    }
}
