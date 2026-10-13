package com.sovereignedge.pocketpantry.ui.cart

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sovereignedge.pocketpantry.PantryApp
import com.sovereignedge.pocketpantry.R
import com.sovereignedge.pocketpantry.data.CartLine
import com.sovereignedge.pocketpantry.data.TripSummary
import com.sovereignedge.pocketpantry.ui.common.AppIconButton
import com.sovereignedge.pocketpantry.ui.common.EmptyState
import com.sovereignedge.pocketpantry.ui.common.fmtDateTime
import com.sovereignedge.pocketpantry.ui.common.fmtNumber
import com.sovereignedge.pocketpantry.ui.common.money
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripHistoryScreen(app: PantryApp, modifier: Modifier, onBack: () -> Unit) {
    val trips by app.repo.pastTrips().collectAsStateWithLifecycle(emptyList())
    val settings by app.settings.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<TripSummary?>(null) }
    val scope = rememberCoroutineScope()
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Past trips") }, navigationIcon = { AppIconButton(R.drawable.ic_arrow_back, "Back", onClick = onBack) }) },
    ) { padding ->
        if (trips.isEmpty()) {
            EmptyState(R.drawable.ic_history, "No trips yet", "Finished shopping trips show up here with their totals.", Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(trips, key = { it.session.id }) { trip ->
                    ListItem(
                        modifier = Modifier.clickable { open = trip },
                        headlineContent = { Text(trip.storeName) },
                        supportingContent = { Text("${fmtDateTime(trip.session.finishedAt ?: trip.session.startedAt)} · ${trip.lineCount} line${if (trip.lineCount == 1) "" else "s"}") },
                        trailingContent = { Text(money(trip.total, settings.currency), style = MaterialTheme.typography.titleMedium) },
                    )
                }
            }
        }
    }
    open?.let { trip ->
        var lines by remember { mutableStateOf<List<CartLine>>(emptyList()) }
        LaunchedEffect(trip.session.id) { lines = app.repo.tripLines(trip.session.id) }
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text("${trip.storeName} · ${money(trip.total, settings.currency)}") },
            text = {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(lines, key = { it.id }) { line ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(line.name)
                                Text("${fmtNumber(line.qty)} × ${money(line.unitPrice, settings.currency)}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(money(line.lineTotal, settings.currency))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = null }) { Text("Close") } },
            dismissButton = {
                TextButton(onClick = { scope.launch { app.repo.deleteSession(trip.session.id) }; open = null }) { Text("Delete trip") }
            },
        )
    }
}
