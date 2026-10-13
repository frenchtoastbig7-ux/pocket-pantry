package com.sovereignedge.pocketpantry.ui.inventory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sovereignedge.pocketpantry.data.Item
import com.sovereignedge.pocketpantry.data.Location
import com.sovereignedge.pocketpantry.ui.common.ConfirmDialog
import com.sovereignedge.pocketpantry.ui.common.fmtDate
import com.sovereignedge.pocketpantry.ui.common.fmtNumber
import com.sovereignedge.pocketpantry.ui.common.parseNumber
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemEditorSheet(
    item: Item,
    locations: List<Location>,
    onDismiss: () -> Unit,
    onSave: (Item) -> Unit,
    onDelete: (() -> Unit)?,
    onAddToList: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(item.name) }
    var brand by rememberSaveable { mutableStateOf(item.brand ?: "") }
    var quantity by rememberSaveable { mutableStateOf(fmtNumber(item.quantity)) }
    var unit by rememberSaveable { mutableStateOf(item.unit) }
    var locationId by rememberSaveable { mutableStateOf(item.locationId) }
    var expiresOn by rememberSaveable { mutableStateOf(item.expiresOn) }
    var lowThreshold by rememberSaveable { mutableStateOf(item.lowThreshold?.let(::fmtNumber) ?: "") }
    var notes by rememberSaveable { mutableStateOf(item.notes) }
    var pickDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (item.id == 0L) "Add item" else "Edit item", style = MaterialTheme.typography.titleLarge)
            item.barcode?.let { Text("Barcode $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = brand, onValueChange = { brand = it }, label = { Text("Brand (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = quantity, onValueChange = { quantity = it }, label = { Text("Quantity") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                )
                OutlinedTextField(value = unit, onValueChange = { unit = it }, label = { Text("Unit") }, placeholder = { Text("pcs, g, L…") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Text("Location", style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(locations, key = { it.id }) { loc ->
                    FilterChip(selected = locationId == loc.id, onClick = { locationId = loc.id }, label = { Text(loc.name) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.weight(1f)) {
                    Text(expiresOn?.let { "Expires ${fmtDate(LocalDate.ofEpochDay(it))}" } ?: "Set expiry date")
                }
                if (expiresOn != null) TextButton(onClick = { expiresOn = null }) { Text("Clear") }
            }
            OutlinedTextField(
                value = lowThreshold, onValueChange = { lowThreshold = it }, label = { Text("Warn when quantity at or below (optional)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                if (onDelete != null) TextButton(onClick = { confirmDelete = true }, colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
                if (item.id != 0L) TextButton(onClick = onAddToList) { Text("Add to list") }
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = name.isNotBlank() && parseNumber(quantity) != null && locationId != 0L,
                    onClick = {
                        onSave(
                            item.copy(
                                name = name.trim(), brand = brand.trim().ifBlank { null }, quantity = parseNumber(quantity) ?: 1.0,
                                unit = unit.trim(), locationId = locationId, expiresOn = expiresOn,
                                lowThreshold = parseNumber(lowThreshold), notes = notes.trim(),
                            ),
                        )
                    },
                ) { Text("Save") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (pickDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (expiresOn?.let(LocalDate::ofEpochDay) ?: LocalDate.now().plusDays(7)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { expiresOn = it / 86_400_000L }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }

    if (confirmDelete && onDelete != null) {
        ConfirmDialog("Delete ${item.name}?", "This removes it from the pantry.", onConfirm = onDelete, onDismiss = { confirmDelete = false })
    }
}
