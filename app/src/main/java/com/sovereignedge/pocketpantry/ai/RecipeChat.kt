package com.sovereignedge.pocketpantry.ai

import com.sovereignedge.pocketpantry.Settings
import com.sovereignedge.pocketpantry.data.ChatMessage
import com.sovereignedge.pocketpantry.data.IngredientSuggestion
import com.sovereignedge.pocketpantry.data.Item
import com.sovereignedge.pocketpantry.data.Location
import com.sovereignedge.pocketpantry.data.Repo
import com.sovereignedge.pocketpantry.data.ShoppingItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException

/**
 * The recipe conversation. Lives in the Application so a reply keeps streaming while the user looks at another tab,
 * and the transcript is persisted so it survives restarts.
 */
class RecipeChat(private val repo: Repo, private val settings: Settings, private val claude: ClaudeService) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    data class State(val streaming: Boolean = false, val draft: String = "", val notice: String? = null, val error: String? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    val isBusy: Boolean get() = job?.isActive == true

    fun send(prompt: String) {
        if (isBusy || prompt.isBlank()) return
        job = scope.launch {
            _state.value = State(streaming = true)
            var assistantId = -1L
            try {
                repo.appendChat("user", prompt.trim())
                val history = repo.chatNow()
                assistantId = repo.appendChat("assistant", "")
                val system = systemPrompt(repo.itemsNow(), repo.locations().first(), repo.shoppingListNow())
                val builder = StringBuilder()
                var lastFlush = 0L
                val text = claude.stream(system, history) { event ->
                    when (event) {
                        is ClaudeService.Event.Text -> {
                            builder.append(event.delta)
                            _state.value = _state.value.copy(draft = builder.toString())
                            val now = System.currentTimeMillis()
                            if (now - lastFlush > 400) {
                                lastFlush = now
                                repo.updateChat(assistantId, builder.toString())
                            }
                        }
                        is ClaudeService.Event.Notice -> _state.value = _state.value.copy(notice = event.text)
                    }
                }
                repo.updateChat(assistantId, text.ifBlank { "(no reply)" })
                _state.value = _state.value.copy(streaming = false, draft = "")
            } catch (e: CancellationException) {
                val partial = _state.value.draft
                if (assistantId > 0) {
                    if (partial.isBlank()) repo.deleteChat(assistantId) else repo.updateChat(assistantId, partial)
                }
                _state.value = State()
                throw e
            } catch (e: Exception) {
                if (assistantId > 0 && _state.value.draft.isBlank()) repo.deleteChat(assistantId)
                _state.value = State(error = ClaudeService.describe(e))
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun dismissMessages() {
        _state.value = _state.value.copy(notice = null, error = null)
    }

    suspend fun clear() {
        stop()
        repo.clearChat()
        _state.value = State()
    }

    /** Asks Claude which ingredients from [recipe] the household is missing. */
    suspend fun missingIngredients(recipe: String): List<IngredientSuggestion> {
        val inventory = repo.itemsNow()
        val system = """
            You turn a recipe into a shopping list for one household. You will be given what the household already has.
            Return ONLY a JSON object of the form {"items":[{"name":"...","quantity":"..."}]} listing the ingredients that are
            NOT already in the inventory (allow for everyday staples like salt, pepper, water and cooking oil being present
            unless the inventory clearly lacks them). Use short grocery-style names ("chicken thighs", not "2 lb boneless
            skinless chicken thighs") and put the amount in "quantity". No prose, no code fences.
        """.trimIndent()
        val prompt = buildString {
            appendLine("INVENTORY:")
            if (inventory.isEmpty()) appendLine("(empty)") else inventory.forEach { appendLine("- ${it.name} (${fmtQty(it)})") }
            appendLine()
            appendLine("RECIPE:")
            appendLine(recipe)
        }
        val raw = claude.complete(system, prompt)
        return parseSuggestions(raw)
    }

    companion object {
        private val dateFmt = DateTimeFormatter.ISO_LOCAL_DATE

        fun fmtQty(item: Item): String {
            val q = if (item.quantity % 1.0 == 0.0) item.quantity.toLong().toString() else "%.2f".format(item.quantity).trimEnd('0').trimEnd('.')
            return (q + " " + item.unit).trim() + (if (item.size.isNotBlank()) " × ${item.size}" else "")
        }

        fun systemPrompt(items: List<Item>, locations: List<Location>, list: List<ShoppingItem>, today: LocalDate = LocalDate.now(), settings: SettingsLike? = null): String {
            val byLocation = items.groupBy { it.locationId }
            return buildString {
                appendLine(
                    "You are the cooking assistant inside Pocket Pantry, an app that tracks a household's food. " +
                        "Suggest recipes and meal plans that use what is on hand, prioritising items that expire soon, and say " +
                        "clearly which ingredients would still need to be bought. Give realistic quantities and timings. " +
                        "Use Markdown with a heading per recipe, an Ingredients list and numbered Method steps. Be concise.",
                )
                settings?.let {
                    if (it.dietaryNotes.isNotBlank()) appendLine("\nDietary notes and preferences: ${it.dietaryNotes.trim()}")
                    if (it.household.isNotBlank()) appendLine("Household: ${it.household.trim()}")
                }
                appendLine("\nToday is ${today.format(dateFmt)}.")
                appendLine("\n## Inventory")
                if (items.isEmpty()) appendLine("(nothing recorded yet)")
                for (loc in locations) {
                    val here = byLocation[loc.id] ?: continue
                    appendLine("### ${loc.name}")
                    for (item in here) {
                        append("- ${item.name}")
                        item.brand?.let { append(" ($it)") }
                        append(": ${fmtQty(item)}")
                        item.daysUntilExpiry(today)?.let { d ->
                            append(
                                when {
                                    d < 0 -> " [expired ${-d} day(s) ago]"
                                    d == 0L -> " [expires today]"
                                    d <= 7 -> " [expires in $d day(s)]"
                                    else -> " [expires ${item.expiryDate}]"
                                },
                            )
                        }
                        if (item.isLow) append(" [running low]")
                        if (item.notes.isNotBlank()) append(" - ${item.notes}")
                        appendLine()
                    }
                }
                val open = list.filter { !it.checked }
                if (open.isNotEmpty()) {
                    appendLine("\n## Already on the shopping list")
                    open.forEach { appendLine("- ${it.name}${if (it.quantityText.isNotBlank()) " (${it.quantityText})" else ""}") }
                }
            }
        }

        /** Tolerant parse: finds the first JSON object in [raw] even if the model wrapped it in prose or fences. */
        fun parseSuggestions(raw: String): List<IngredientSuggestion> {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) return emptyList()
            val obj = runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull() ?: return emptyList()
            val arr: JSONArray = obj.optJSONArray("items") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val entry = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = entry.optString("name").trim().ifBlank { return@mapNotNull null }
                IngredientSuggestion(name, entry.optString("quantity").trim())
            }
        }
    }

    /** The two preference fields the prompt needs, decoupled from Android so the prompt builder is unit-testable. */
    interface SettingsLike {
        val dietaryNotes: String
        val household: String
    }

    private fun systemPrompt(items: List<Item>, locations: List<Location>, list: List<ShoppingItem>): String {
        val s = settings.current
        return systemPrompt(
            items, locations, list,
            settings = object : SettingsLike {
                override val dietaryNotes = s.dietaryNotes
                override val household = s.household
            },
        )
    }
}
