package com.sovereignedge.pocketpantry.ui.cart

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.sovereignedge.pocketpantry.ui.common.BigTitleBar
import com.sovereignedge.pocketpantry.ui.common.Monogram
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sovereignedge.pocketpantry.PantryApp
import com.sovereignedge.pocketpantry.R
import com.sovereignedge.pocketpantry.barcode.ScannerViewport
import com.sovereignedge.pocketpantry.data.CartLine
import com.sovereignedge.pocketpantry.data.CartSession
import com.sovereignedge.pocketpantry.data.CartTotals
import com.sovereignedge.pocketpantry.data.Product
import com.sovereignedge.pocketpantry.data.Store
import com.sovereignedge.pocketpantry.ui.common.AppIconButton
import com.sovereignedge.pocketpantry.ui.common.ConfirmDialog
import com.sovereignedge.pocketpantry.ui.common.EmptyState
import com.sovereignedge.pocketpantry.ui.common.fmtNumber
import com.sovereignedge.pocketpantry.ui.common.money
import com.sovereignedge.pocketpantry.ui.common.parseNumber
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** A scanned product waiting for the user to confirm a price (or a name, when the barcode is unknown). */
private data class PendingLine(val barcode: String?, val name: String, val price: Double?, val knownProduct: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CartScreen(app: PantryApp, modifier: Modifier = Modifier, onOpenSettings: () -> Unit) {
    val stores by app.repo.stores().collectAsStateWithLifecycle(emptyList())
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val store = stores.firstOrNull { it.id == settings.activeStoreId }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var pickStore by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }

    if (showHistory) {
        BackHandler { showHistory = false }
        TripHistoryScreen(app, modifier, onBack = { showHistory = false })
        return
    }

    if (store == null || pickStore) {
        StorePicker(
            app = app, stores = stores, modifier = modifier, current = store,
            onPicked = { app.settings.setActiveStore(it.id); pickStore = false },
            onCancel = if (store == null) null else {
                { pickStore = false }
            },
            onOpenSettings = onOpenSettings,
        )
        return
    }

    val session by app.repo.openSession(store.id).collectAsStateWithLifecycle(null)
    LaunchedEffect(store.id, session) { if (session == null) app.repo.startSession(store.id) }
    val currentSession = session
    val lines by remember(currentSession?.id) { currentSession?.let { app.repo.cartLines(it.id) } ?: flowOf(emptyList()) }
        .collectAsStateWithLifecycle(emptyList())
    val totals = CartTotals.of(lines, store.taxRate)
    val currency = settings.currency

    var scanning by rememberSaveable { mutableStateOf(false) }
    var lookingUp by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingLine?>(null) }
    var editing by remember { mutableStateOf<CartLine?>(null) }
    var finishing by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    suspend fun addLine(sessionId: Long, barcode: String?, name: String, price: Double, qty: Double, taxable: Boolean, rememberName: Boolean) {
        app.repo.addCartLine(CartLine(0, sessionId, barcode, name, price, qty, taxable))
        if (rememberName && barcode != null) app.repo.rememberProduct(Product(barcode, name, null, null, "user"))
        val ticked = app.repo.tickMatching(barcode, name)
        snackbar.showSnackbar(
            if (ticked != null) "Added $name · ticked \"${ticked.name}\" off your list" else "Added $name ${money(price * qty, currency)}",
        )
    }

    fun onScanned(code: String) {
        val sessionId = currentSession?.id ?: return
        if (pending != null) return
        lookingUp = true
        scope.launch {
            // Cached products answer instantly; a remembered price means no dialog at all.
            val cached = app.repo.product(code)
            val lastPrice = app.repo.lastPrice(store.id, code)
            if (cached != null && lastPrice != null) {
                lookingUp = false
                addLine(sessionId, code, cached.name, lastPrice, 1.0, false, rememberName = false)
                return@launch
            }
            pending = PendingLine(code, cached?.name ?: "", lastPrice, knownProduct = cached != null)
            if (cached == null) {
                val product = runCatching { app.products.lookup(code) }.getOrNull()
                val current = pending
                if (product != null && current != null && current.barcode == code) pending = current.copy(name = current.name.ifBlank { product.name }, knownProduct = true)
            }
            lookingUp = false
        }
    }

    val chrome = com.sovereignedge.pocketpantry.ui.LocalChromeVisible.current
    androidx.compose.runtime.DisposableEffect(scanning) {
        chrome.value = !scanning
        onDispose { chrome.value = true }
    }

    if (scanning) {
        BackHandler { scanning = false }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            ScannerViewport(
                Modifier.fillMaxSize(), paused = pending != null,
                hint = "",
                onBarcode = ::onScanned,
            )
            Row(Modifier.statusBarsPadding().padding(8.dp)) {
                FilledTonalIconButton(onClick = { scanning = false }) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Close scanner") }
            }
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding(), tonalElevation = 3.dp) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(store.name, style = MaterialTheme.typography.labelMedium)
                        Text("${fmtNumber(totals.count)} item${if (totals.count == 1.0) "" else "s"}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (lookingUp) "Looking up product…" else "Scan items as they go in the cart",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(money(totals.total, currency), style = MaterialTheme.typography.headlineMedium)
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
        }
    } else {
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        Scaffold(
            modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                BigTitleBar(
                    title = store.name, subtitle = "Tap the store name to change it", scrollBehavior = scrollBehavior,
                    navigationIcon = { AppIconButton(R.drawable.ic_store, "Change store") { pickStore = true } },
                    actions = {
                        AppIconButton(R.drawable.ic_history, "Past trips") { showHistory = true }
                        AppIconButton(R.drawable.ic_more, "More") { menu = true }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Clear cart") }, enabled = lines.isNotEmpty(), onClick = { menu = false; confirmClear = true })
                            DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; onOpenSettings() })
                        }
                    },
                )
            },
            floatingActionButton = {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SmallFloatingActionButton(onClick = { pending = PendingLine(null, "", null, knownProduct = false) }) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = "Add without barcode")
                    }
                    ExtendedFloatingActionButton(
                        onClick = { scanning = true },
                        icon = { Icon(painterResource(R.drawable.ic_barcode), contentDescription = null) },
                        text = { Text("Scan") },
                    )
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                Tile(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(), tone = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.padding(horizontal = 22.dp, vertical = 18.dp)) {
                        Text("Cart total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(money(totals.total, currency), style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            buildString {
                                append("${fmtNumber(totals.count)} item${if (totals.count == 1.0) "" else "s"} · subtotal ${money(totals.subtotal, currency)}")
                                if (store.taxRate > 0) append(" · tax ${money(totals.tax, currency)} (${fmtNumber(store.taxRate)}%)")
                            },
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                        Spacer(Modifier.height(14.dp))
                        Button(onClick = { finishing = true }, enabled = lines.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Finish trip") }
                    }
                }
                if (lines.isEmpty()) {
                    EmptyState(R.drawable.ic_cart, "Cart is empty", "Scan barcodes as you shop and watch the total. Known products at this store are added at their last price in one scan.")
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 160.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(lines, key = { it.id }) { line ->
                            Surface(
                                modifier = Modifier.fillMaxWidth().animateItem().clickable { editing = line },
                                shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Monogram(line.name)
                                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                        Text(line.name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                                        Text("${fmtNumber(line.qty)} × ${money(line.unitPrice, currency)}" + if (line.taxable) " · taxable" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(money(line.lineTotal, currency), style = MaterialTheme.typography.titleMedium)
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            AppIconButton(R.drawable.ic_remove, "One fewer", onClick = {
                                                scope.launch { if (line.qty <= 1) app.repo.deleteCartLine(line.id) else app.repo.updateCartLine(line.copy(qty = line.qty - 1)) }
                                            })
                                            AppIconButton(R.drawable.ic_add, "One more", onClick = { scope.launch { app.repo.updateCartLine(line.copy(qty = line.qty + 1)) } })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pending?.let { p ->
        LineDialog(
            title = if (p.barcode == null) "Add item" else if (p.knownProduct) "Price for ${p.name}" else if (lookingUp) "Looking up product…" else "New product",
            barcode = p.barcode, initialName = p.name, initialPrice = p.price, initialQty = 1.0, initialTaxable = false,
            currency = currency, onDismiss = { pending = null },
            onConfirm = { name, price, qty, taxable ->
                val sessionId = currentSession?.id ?: return@LineDialog
                pending = null
                scope.launch { addLine(sessionId, p.barcode, name, price, qty, taxable, rememberName = !p.knownProduct) }
            },
        )
    }

    editing?.let { line ->
        LineDialog(
            title = "Edit line", barcode = line.barcode, initialName = line.name, initialPrice = line.unitPrice, initialQty = line.qty,
            initialTaxable = line.taxable, currency = currency, onDismiss = { editing = null },
            onDelete = { scope.launch { app.repo.deleteCartLine(line.id) }; editing = null },
            onConfirm = { name, price, qty, taxable ->
                editing = null
                scope.launch { app.repo.updateCartLine(line.copy(name = name, unitPrice = price, qty = qty, taxable = taxable)) }
            },
        )
    }

    if (confirmClear && currentSession != null) {
        ConfirmDialog("Clear the cart?", "All ${lines.size} lines are removed.", confirmLabel = "Clear",
            onConfirm = { scope.launch { app.repo.clearCart(currentSession.id) } }, onDismiss = { confirmClear = false })
    }

    if (finishing && currentSession != null) {
        FinishTripDialog(
            app = app, session = currentSession, lines = lines, total = money(totals.total, currency),
            onDismiss = { finishing = false },
            onDone = { stocked ->
                finishing = false
                scope.launch { snackbar.showSnackbar(if (stocked) "Trip saved and ${lines.size} item${if (lines.size == 1) "" else "s"} added to the pantry" else "Trip saved") }
            },
        )
    }
}

@Composable
private fun LineDialog(
    title: String,
    barcode: String?,
    initialName: String,
    initialPrice: Double?,
    initialQty: Double,
    initialTaxable: Boolean,
    currency: String,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onConfirm: (name: String, price: Double, qty: Double, taxable: Boolean) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var price by rememberSaveable { mutableStateOf(initialPrice?.let { "%.2f".format(it) } ?: "") }
    var qty by rememberSaveable { mutableStateOf(fmtNumber(initialQty)) }
    var taxable by rememberSaveable { mutableStateOf(initialTaxable) }
    LaunchedEffect(initialName) { if (name.isBlank()) name = initialName }
    val valid = name.isNotBlank() && parseNumber(price) != null && (parseNumber(qty) ?: 0.0) > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                barcode?.let { Text("Barcode $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = price, onValueChange = { price = it }, label = { Text("Price ($currency)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = qty, onValueChange = { qty = it }, label = { Text("Qty") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Taxable", Modifier.weight(1f))
                    Switch(checked = taxable, onCheckedChange = { taxable = it })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(name.trim(), parseNumber(price) ?: 0.0, parseNumber(qty) ?: 1.0, taxable) }) { Text("Add") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Remove") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FinishTripDialog(app: PantryApp, session: CartSession, lines: List<CartLine>, total: String, onDismiss: () -> Unit, onDone: (Boolean) -> Unit) {
    val locations by app.repo.locations().collectAsStateWithLifecycle(emptyList())
    val settings by app.settings.state.collectAsStateWithLifecycle()
    var addToPantry by rememberSaveable { mutableStateOf(true) }
    var autoSort by rememberSaveable { mutableStateOf(true) }
    var locationId by rememberSaveable { mutableStateOf(settings.defaultLocationId) }
    val scope = rememberCoroutineScope()
    val chosen = locations.firstOrNull { it.id == locationId } ?: locations.firstOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Finish trip · $total") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Prices are remembered for this store so the next scan of the same product is instant.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Add ${lines.size} item${if (lines.size == 1) "" else "s"} to the pantry", Modifier.weight(1f))
                    Switch(checked = addToPantry, onCheckedChange = { addToPantry = it })
                }
                if (addToPantry) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Sort into Fridge, Freezer and Pantry automatically", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = autoSort, onCheckedChange = { autoSort = it })
                    }
                    Text(if (autoSort) "Anything not recognised goes to:" else "Where do they go?", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        locations.forEach { loc ->
                            FilterChip(selected = chosen?.id == loc.id, onClick = { locationId = loc.id }, label = { Text(loc.name) })
                        }
                    }
                    Text("You can move items between locations afterwards from the Pantry tab.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !addToPantry || chosen != null, onClick = {
                scope.launch {
                    if (addToPantry && chosen != null) {
                        app.repo.stockFromCart(lines, chosen.id, autoSort)
                        app.settings.setDefaultLocation(chosen.id)
                    }
                    app.repo.finishSession(session)
                    onDone(addToPantry)
                }
            }) { Text("Finish") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorePicker(
    app: PantryApp,
    stores: List<Store>,
    modifier: Modifier,
    current: Store?,
    onPicked: (Store) -> Unit,
    onCancel: (() -> Unit)?,
    onOpenSettings: () -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (onCancel != null) BackHandler(onBack = onCancel)
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Which store?") },
                navigationIcon = { if (onCancel != null) AppIconButton(R.drawable.ic_arrow_back, "Back", onClick = onCancel) },
                actions = { AppIconButton(R.drawable.ic_settings, "Settings", onClick = onOpenSettings) },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(painterResource(R.drawable.ic_add), null) }, text = { Text("Add store") })
        },
    ) { padding ->
        if (stores.isEmpty()) {
            EmptyState(R.drawable.ic_store, "Add your grocery store", "Cart totals, tax and remembered prices are kept per store.", Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(stores, key = { it.id }) { store ->
                    ListItem(
                        modifier = Modifier.clickable { onPicked(store) },
                        headlineContent = { Text(store.name) },
                        supportingContent = { Text(if (store.taxRate > 0) "Tax ${fmtNumber(store.taxRate)}%" else "No tax") },
                        trailingContent = { if (store.id == current?.id) Icon(painterResource(R.drawable.ic_check), contentDescription = "Current") },
                    )
                }
            }
        }
    }
    if (adding) {
        StoreDialog(onDismiss = { adding = false }) { store ->
            scope.launch {
                val id = app.repo.saveStore(store)
                onPicked(store.copy(id = id))
            }
            adding = false
        }
    }
}

/** Shared with Settings: name and tax rate for a store. */
@Composable
fun StoreDialog(store: Store = Store(0, "", 0.0), onDismiss: () -> Unit, onSave: (Store) -> Unit) {
    var name by rememberSaveable { mutableStateOf(store.name) }
    var tax by rememberSaveable { mutableStateOf(if (store.taxRate == 0.0) "" else fmtNumber(store.taxRate)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (store.id == 0L) "Add store" else "Edit store") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Store name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = tax, onValueChange = { tax = it }, label = { Text("Sales tax % on taxable items") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(store.copy(name = name.trim(), taxRate = parseNumber(tax) ?: 0.0)) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
