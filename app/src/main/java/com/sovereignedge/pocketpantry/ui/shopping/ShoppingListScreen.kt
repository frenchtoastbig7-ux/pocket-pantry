package com.sovereignedge.pocketpantry.ui.shopping

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.sovereignedge.pocketpantry.ui.common.BigTitleBar
import com.sovereignedge.pocketpantry.ui.common.GroupLabel
import com.sovereignedge.pocketpantry.ui.common.Tile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sovereignedge.pocketpantry.PantryApp
import com.sovereignedge.pocketpantry.R
import com.sovereignedge.pocketpantry.data.ShoppingItem
import com.sovereignedge.pocketpantry.ui.common.AppIconButton
import com.sovereignedge.pocketpantry.ui.common.EmptyState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingListScreen(app: PantryApp, modifier: Modifier = Modifier, onOpenSettings: () -> Unit) {
    val list by app.repo.shoppingList().collectAsStateWithLifecycle(emptyList())
    val items by app.repo.items().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var newName by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ShoppingItem?>(null) }
    val remaining = list.count { !it.checked }

    fun add() {
        val name = newName.trim()
        if (name.isEmpty()) return
        scope.launch { app.repo.addToShoppingList(name) }
        newName = ""
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            BigTitleBar(
                title = "Shopping list",
                subtitle = if (list.isEmpty()) null else "$remaining of ${list.size} left to buy",
                scrollBehavior = scrollBehavior,
                actions = {
                    AppIconButton(R.drawable.ic_more, "More") { menu = true }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Add low-stock pantry items") },
                            onClick = {
                                menu = false
                                scope.launch {
                                    val low = items.filter { it.isLow }
                                    low.forEach { app.repo.addToShoppingList(it.name, barcode = it.barcode) }
                                    snackbar.showSnackbar(if (low.isEmpty()) "Nothing is running low" else "Added ${low.size} item${if (low.size == 1) "" else "s"}")
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Clear checked items") },
                            enabled = list.any { it.checked },
                            onClick = { menu = false; scope.launch { app.repo.clearChecked() } },
                        )
                    }
                    AppIconButton(R.drawable.ic_settings, "Settings", onClick = onOpenSettings)
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it }, placeholder = { Text("Add to list…") }, singleLine = true, shape = MaterialTheme.shapes.large,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }),
                    modifier = Modifier.weight(1f),
                )
                FilledIconButton(onClick = { add() }, enabled = newName.isNotBlank(), modifier = Modifier.size(52.dp)) { Icon(painterResource(R.drawable.ic_add), contentDescription = "Add") }
            }
            if (list.isEmpty()) {
                EmptyState(R.drawable.ic_list, "Nothing to buy", "Type an item above, add low-stock pantry items from the menu, or let a recipe fill the list for you.")
            } else {
                val open = list.filter { !it.checked }
                val done = list.filter { it.checked }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                    if (open.isNotEmpty()) item(key = "open") {
                        Tile(Modifier.fillMaxWidth().animateItem()) {
                            open.forEachIndexed { index, entry ->
                                ShoppingRow(entry, onToggle = { scope.launch { app.repo.setChecked(entry.id, it) } }, onClick = { editing = entry })
                                if (index < open.lastIndex) HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }
                    }
                    if (done.isNotEmpty()) {
                        item(key = "done-label") { GroupLabel("In the cart", Modifier.animateItem()) }
                        item(key = "done") {
                            Tile(Modifier.fillMaxWidth().animateItem(), tone = MaterialTheme.colorScheme.surfaceContainerLowest) {
                                done.forEachIndexed { index, entry ->
                                    ShoppingRow(entry, onToggle = { scope.launch { app.repo.setChecked(entry.id, it) } }, onClick = { editing = entry })
                                    if (index < done.lastIndex) HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { entry ->
        var name by rememberSaveable(entry.id) { mutableStateOf(entry.name) }
        var qty by rememberSaveable(entry.id) { mutableStateOf(entry.quantityText) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit item") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = qty, onValueChange = { qty = it }, label = { Text("Quantity / note") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    scope.launch { app.repo.updateShoppingItem(entry.copy(name = name, quantityText = qty)) }
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { scope.launch { app.repo.deleteShoppingItem(entry.id) }; editing = null }) { Text("Delete") }
                    TextButton(onClick = { editing = null }) { Text("Cancel") }
                }
            },
        )
    }
}

@Composable
private fun ShoppingRow(entry: ShoppingItem, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 8.dp, end = 16.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = entry.checked, onCheckedChange = onToggle)
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                entry.name, style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (entry.checked) TextDecoration.LineThrough else null,
                color = if (entry.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (entry.quantityText.isNotBlank()) Text(entry.quantityText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
