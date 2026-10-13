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

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Shopping list")
                        if (list.isNotEmpty()) Text("$remaining of ${list.size} left", style = MaterialTheme.typography.bodySmall)
                    }
                },
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
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it }, placeholder = { Text("Add to list…") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }),
                    modifier = Modifier.weight(1f),
                )
                FilledIconButton(onClick = { add() }, enabled = newName.isNotBlank()) { Icon(painterResource(R.drawable.ic_add), contentDescription = "Add") }
            }
            if (list.isEmpty()) {
                EmptyState(R.drawable.ic_list, "Nothing to buy", "Type an item above, add low-stock pantry items from the menu, or let a recipe fill the list for you.")
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(list, key = { it.id }) { entry ->
                        ListItem(
                            modifier = Modifier.clickable { editing = entry },
                            leadingContent = {
                                Checkbox(checked = entry.checked, onCheckedChange = { scope.launch { app.repo.setChecked(entry.id, it) } })
                            },
                            headlineContent = {
                                Text(entry.name, textDecoration = if (entry.checked) TextDecoration.LineThrough else null,
                                    color = if (entry.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                            },
                            supportingContent = if (entry.quantityText.isBlank()) null else {
                                { Text(entry.quantityText) }
                            },
                        )
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
