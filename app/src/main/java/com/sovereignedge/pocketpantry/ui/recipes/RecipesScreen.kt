package com.sovereignedge.pocketpantry.ui.recipes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sovereignedge.pocketpantry.PantryApp
import com.sovereignedge.pocketpantry.R
import com.sovereignedge.pocketpantry.ai.ClaudeService
import com.sovereignedge.pocketpantry.data.ChatMessage
import com.sovereignedge.pocketpantry.data.IngredientSuggestion
import com.sovereignedge.pocketpantry.data.Recipe
import com.sovereignedge.pocketpantry.ui.common.AppIconButton
import com.sovereignedge.pocketpantry.ui.common.ConfirmDialog
import com.sovereignedge.pocketpantry.ui.common.EmptyState
import com.sovereignedge.pocketpantry.ui.common.Markdown
import com.sovereignedge.pocketpantry.ui.common.TextPromptDialog
import com.sovereignedge.pocketpantry.ui.common.firstTitle
import com.sovereignedge.pocketpantry.ui.common.fmtDateTime
import kotlinx.coroutines.launch

private val QUICK_PROMPTS = listOf(
    "What can I cook tonight with what I have?",
    "Use up what's expiring soon",
    "Plan dinners for the next 7 days",
    "Quick lunch ideas for tomorrow",
    "A dessert from pantry staples",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipesScreen(app: PantryApp, modifier: Modifier = Modifier, onOpenSettings: () -> Unit) {
    val settings by app.settings.state.collectAsStateWithLifecycle()
    if (!settings.hasApiKey) {
        Scaffold(modifier = modifier, contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = { TopAppBar(title = { Text("Recipes") }) }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (settings.connectorEnabled) {
                    EmptyState(
                        R.drawable.ic_restaurant, "Ask Claude Code for recipes",
                        "Your Claude connector is on, so Claude Code on your computer (or the Claude app through Remote Control) already knows this pantry: ask it what to cook and it can fill the shopping list. " +
                            "Recipes inside this tab are a separate path that needs an Anthropic API key, because the app itself can't use a Claude subscription.",
                        Modifier.weight(1f, fill = false),
                    )
                } else {
                    EmptyState(
                        R.drawable.ic_lock, "Connect Claude to unlock recipes",
                        "Recipes and meal plans are generated by Claude from what's actually in your pantry. Add your Claude API key in Settings; it stays encrypted on this phone and requests go straight to Anthropic. " +
                            "Or turn on the Claude connector to use the pantry from Claude Code on your own subscription instead.",
                        Modifier.weight(1f, fill = false),
                    )
                }
                Button(onClick = onOpenSettings, modifier = Modifier.padding(bottom = 48.dp)) { Text("Open Settings") }
            }
        }
        return
    }

    val messages by app.repo.chat().collectAsStateWithLifecycle(emptyList())
    val recipes by app.repo.recipes().collectAsStateWithLifecycle(emptyList())
    val chatState by app.chat.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var section by rememberSaveable { mutableIntStateOf(0) }
    var input by rememberSaveable { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf<String?>(null) }
    var suggestions by remember { mutableStateOf<List<IngredientSuggestion>?>(null) }
    var extracting by remember { mutableStateOf(false) }
    var openRecipe by remember { mutableStateOf<Recipe?>(null) }

    LaunchedEffect(chatState.error, chatState.notice) {
        chatState.error?.let { snackbar.showSnackbar(it); app.chat.dismissMessages() }
        chatState.notice?.let { snackbar.showSnackbar(it); app.chat.dismissMessages() }
    }

    fun addToList(markdown: String) {
        if (extracting) return
        extracting = true
        scope.launch {
            try {
                val found = app.chat.missingIngredients(markdown)
                if (found.isEmpty()) snackbar.showSnackbar("Claude found nothing missing from your pantry") else suggestions = found
            } catch (e: Exception) {
                snackbar.showSnackbar(ClaudeService.describe(e))
            } finally {
                extracting = false
            }
        }
    }

    openRecipe?.let { recipe ->
        BackHandler { openRecipe = null }
        RecipeDetail(
            recipe = recipe, modifier = modifier, extracting = extracting, onBack = { openRecipe = null },
            onAddToList = { addToList(recipe.body) },
            onDelete = { scope.launch { app.repo.deleteRecipe(recipe.id) }; openRecipe = null },
        )
    } ?: Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Recipes") },
                actions = {
                    if (section == 0) AppIconButton(R.drawable.ic_delete, "New conversation", enabled = messages.isNotEmpty()) { confirmClear = true }
                    AppIconButton(R.drawable.ic_settings, "Settings", onClick = onOpenSettings)
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()) {
                SegmentedButton(selected = section == 0, onClick = { section = 0 }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Ask Claude") }
                SegmentedButton(selected = section == 1, onClick = { section = 1 }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Saved (${recipes.size})") }
            }
            if (extracting) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (section == 1) {
                if (recipes.isEmpty()) {
                    EmptyState(R.drawable.ic_bookmark, "No saved recipes", "Tap Save under any reply from Claude to keep it here, with or without a connection.")
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(recipes, key = { it.id }) { recipe ->
                            ListItem(
                                modifier = Modifier.clickable { openRecipe = recipe },
                                headlineContent = { Text(recipe.title) },
                                supportingContent = { Text(fmtDateTime(recipe.createdAt)) },
                            )
                        }
                    }
                }
            } else {
                val listState = rememberLazyListState()
                LaunchedEffect(messages.size, chatState.draft.length) {
                    if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1, Int.MAX_VALUE)
                }
                LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (messages.isEmpty()) item {
                        Text(
                            "Claude knows what's in your pantry, what's expiring and what's on your shopping list. Ask for anything, or start with a suggestion below.",
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                    items(messages, key = { it.id }) { message ->
                        val isLast = message.id == messages.lastOrNull()?.id
                        val live = !message.isUser && isLast && chatState.streaming
                        MessageBubble(
                            message = message,
                            text = if (live && chatState.draft.isNotEmpty()) chatState.draft else message.text,
                            streaming = live,
                            extracting = extracting,
                            onSave = { saving = message.text },
                            onAddToList = { addToList(message.text) },
                        )
                    }
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(QUICK_PROMPTS) { prompt -> SuggestionChip(onClick = { if (!chatState.streaming) app.chat.send(prompt) }, label = { Text(prompt) }) }
                }
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), maxLines = 4,
                        placeholder = { Text("Ask for a recipe or meal plan…") },
                    )
                    if (chatState.streaming) {
                        FilledIconButton(onClick = { app.chat.stop() }) { Icon(painterResource(R.drawable.ic_stop), contentDescription = "Stop") }
                    } else {
                        FilledIconButton(enabled = input.isNotBlank(), onClick = { app.chat.send(input); input = "" }) {
                            Icon(painterResource(R.drawable.ic_send), contentDescription = "Send")
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog("Start a new conversation?", "The current chat is deleted. Saved recipes are kept.", confirmLabel = "New chat",
            onConfirm = { scope.launch { app.chat.clear() } }, onDismiss = { confirmClear = false })
    }
    saving?.let { body ->
        TextPromptDialog("Save recipe", "Title", initial = firstTitle(body), onDismiss = { saving = null }) { title ->
            scope.launch {
                app.repo.saveRecipe(title, body, messages.lastOrNull { it.isUser }?.text ?: "")
                snackbar.showSnackbar("Saved \"$title\"")
            }
        }
    }
    suggestions?.let { found ->
        SuggestionsDialog(found, onDismiss = { suggestions = null }) { chosen ->
            suggestions = null
            scope.launch {
                chosen.forEach { app.repo.addToShoppingList(it.name, it.quantity) }
                snackbar.showSnackbar("Added ${chosen.size} item${if (chosen.size == 1) "" else "s"} to the shopping list")
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, text: String, streaming: Boolean, extracting: Boolean, onSave: () -> Unit, onAddToList: () -> Unit) {
    if (message.isUser) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth(0.85f)) {
                Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            if (text.isBlank() && streaming) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("  Claude is thinking…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Markdown(text)
            }
            if (!streaming && text.isNotBlank()) {
                Row {
                    TextButton(onClick = onSave) { Text("Save") }
                    TextButton(onClick = onAddToList, enabled = !extracting) { Text("Add missing to list") }
                }
            }
        }
    }
}


@Composable
private fun SuggestionsDialog(found: List<IngredientSuggestion>, onDismiss: () -> Unit, onAdd: (List<IngredientSuggestion>) -> Unit) {
    val selected = remember { mutableStateOf(found.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to shopping list") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Claude thinks you're missing these. Untick anything you already have.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                found.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(checked = s in selected.value, onCheckedChange = { on -> selected.value = if (on) selected.value + s else selected.value - s })
                        Column {
                            Text(s.name)
                            if (s.quantity.isNotBlank()) Text(s.quantity, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = selected.value.isNotEmpty(), onClick = { onAdd(found.filter { it in selected.value }) }) { Text("Add ${selected.value.size}") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecipeDetail(recipe: Recipe, modifier: Modifier, extracting: Boolean, onBack: () -> Unit, onAddToList: () -> Unit, onDelete: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(recipe.title, maxLines = 1) },
                navigationIcon = { AppIconButton(R.drawable.ic_arrow_back, "Back", onClick = onBack) },
                actions = {
                    AppIconButton(R.drawable.ic_playlist_add, "Add missing ingredients to list", onClick = onAddToList, enabled = !extracting)
                    AppIconButton(R.drawable.ic_delete, "Delete") { confirmDelete = true }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (extracting) LinearProgressIndicator(Modifier.fillMaxWidth())
            Markdown(recipe.body)
            Spacer(Modifier.height(32.dp))
        }
    }
    if (confirmDelete) ConfirmDialog("Delete \"${recipe.title}\"?", "This can't be undone.", onConfirm = onDelete, onDismiss = { confirmDelete = false })
}
