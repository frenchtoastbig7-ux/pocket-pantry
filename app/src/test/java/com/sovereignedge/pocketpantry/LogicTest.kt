package com.sovereignedge.pocketpantry

import com.sovereignedge.pocketpantry.ai.RecipeChat
import com.sovereignedge.pocketpantry.data.CartLine
import com.sovereignedge.pocketpantry.data.CartTotals
import com.sovereignedge.pocketpantry.data.Item
import com.sovereignedge.pocketpantry.data.Location
import com.sovereignedge.pocketpantry.data.ShoppingItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LogicTest {
    private fun line(price: Double, qty: Double, taxable: Boolean) = CartLine(0, 1, null, "x", price, qty, taxable)

    @Test
    fun cartTotalsApplyTaxOnlyToTaxableLines() {
        val totals = CartTotals.of(listOf(line(2.50, 2.0, false), line(10.0, 1.0, true)), taxRatePercent = 8.0)
        assertEquals(15.0, totals.subtotal, 1e-9)
        assertEquals(0.80, totals.tax, 1e-9)
        assertEquals(15.80, totals.total, 1e-9)
        assertEquals(3.0, totals.count, 1e-9)
    }

    @Test
    fun suggestionsParseFromProseWrappedJson() {
        val raw = "Sure! ```json\n{\"items\":[{\"name\":\"garlic\",\"quantity\":\"2 cloves\"},{\"name\":\"\"},{\"name\":\"lemon\"}]}\n```"
        val parsed = RecipeChat.parseSuggestions(raw)
        assertEquals(listOf("garlic", "lemon"), parsed.map { it.name })
        assertEquals("2 cloves", parsed[0].quantity)
        assertEquals("", parsed[1].quantity)
        assertTrue(RecipeChat.parseSuggestions("no json here").isEmpty())
    }

    @Test
    fun systemPromptGroupsByLocationAndFlagsExpiry() {
        val today = LocalDate.of(2026, 10, 12)
        val fridge = Location(1, "Fridge", 0)
        val pantry = Location(2, "Pantry", 1)
        val items = listOf(
            Item(1, "Milk", null, "Dairyco", 1.0, "L", 1, today.plusDays(2).toEpochDay(), "", null, 0, 0),
            Item(2, "Rice", null, null, 0.5, "kg", 2, null, "", 1.0, 0, 0),
            Item(3, "Yogurt", null, null, 2.0, "", 1, today.minusDays(1).toEpochDay(), "", null, 0, 0),
        )
        val list = listOf(ShoppingItem(1, "Eggs", "12", null, false, 0), ShoppingItem(2, "Bread", "", null, true, 0))
        val prompt = RecipeChat.systemPrompt(items, listOf(fridge, pantry), list, today)
        assertTrue(prompt.indexOf("### Fridge") < prompt.indexOf("### Pantry"))
        assertTrue(prompt.contains("- Milk (Dairyco): 1 L [expires in 2 day(s)]"))
        assertTrue(prompt.contains("- Yogurt: 2 [expired 1 day(s) ago]"))
        assertTrue(prompt.contains("- Rice: 0.5 kg [running low]"))
        assertTrue(prompt.contains("- Eggs (12)"))
        assertTrue(!prompt.contains("Bread"))
    }
}
