package com.sovereignedge.pocketpantry.barcode

/**
 * Pulls a pack size out of product text ("Coca-Cola Can, 12 fl oz" -> "12 fl oz", "Beans 4 x 15 oz" -> "4 × 15 oz").
 * Retail databases rarely give a clean size field, but the title almost always carries one.
 */
object SizeParser {
    // A mixed number ("1 1/2"), a fraction ("1/2") or a decimal. The fraction forms come first, and a match may not
    // start right after a slash, so "1/2 gallon" is never read as "2 gallon".
    private const val NUMBER = """(\d+\s+\d+/\d+|\d+/\d+|\d+(?:[.,]\d+)?)"""
    private const val PHYSICAL = """fl\.?\s*oz\.?|fluid\s+ounces?|oz\.?|ounces?|lbs?\.?|pounds?|kgs?|kilograms?|g|grams?|mg|ml|milliliters?|millilitres?|l|liters?|litres?|gal\.?|gallons?|qts?\.?|quarts?|pts?\.?|pints?"""
    private const val COUNT = """ct\.?|count|pk|pack|pcs?|pieces?|sheets?|bags?|cans?|bottles?|rolls?"""
    private const val UNIT = "($PHYSICAL|$COUNT)"
    private const val START = """(?<![\w./])"""
    private val physical = Regex("""$START$NUMBER\s*-?\s*($PHYSICAL)(?![a-z])""", RegexOption.IGNORE_CASE)
    private val single = Regex("""$START$NUMBER\s*-?\s*$UNIT(?![a-z])""", RegexOption.IGNORE_CASE)
    private val multi = Regex("""$START$NUMBER\s*(?:x|×|pack of|pk of|ct of)\s*$NUMBER\s*-?\s*$UNIT(?![a-z])""", RegexOption.IGNORE_CASE)
    private val packOf = Regex("""$START(?:pack of|case of)\s*(\d+)""", RegexOption.IGNORE_CASE)

    /** The first size found in [text], normalised, or null. */
    fun parse(text: String?): String? {
        if (text.isNullOrBlank()) return null
        multi.find(text)?.let { m ->
            return "${num(m.groupValues[1])} × ${num(m.groupValues[2])} ${unit(m.groupValues[3])}"
        }
        // A weight or volume beats a count: "2 bags, 16 oz" is 16 oz. "2 bags" on its own is still kept.
        (physical.find(text) ?: single.find(text))?.let { m ->
            return "${num(m.groupValues[1])} ${unit(m.groupValues[2])}"
        }
        packOf.find(text)?.let { return "${it.groupValues[1]} pack" }
        return null
    }

    /** True when [text] is itself just a size ("16 oz"), as opposed to a sentence or junk like "9-11". */
    fun looksLikeSize(text: String?): Boolean {
        val t = text?.trim() ?: return false
        // Compare the matched span, not the normalised output: "1 Gallon" parses to "1 gal" and is still just a size.
        val m = multi.find(t) ?: physical.find(t) ?: single.find(t) ?: packOf.find(t) ?: return false
        return m.value.length == t.length
    }

    private fun num(raw: String): String = if ('/' in raw) raw.trim().replace(Regex("\\s+"), " ") else raw.replace(',', '.').let { if ('.' in it) it.trimEnd('0').trimEnd('.') else it }.ifEmpty { "0" }

    private fun unit(raw: String): String {
        val u = raw.lowercase().replace(".", "").replace(Regex("\\s+"), " ").trim()
        return when {
            u.startsWith("fl") || u.startsWith("fluid") -> "fl oz"
            u == "oz" || u.startsWith("ounce") -> "oz"
            u.startsWith("lb") || u.startsWith("pound") -> "lb"
            u.startsWith("kg") || u.startsWith("kilo") -> "kg"
            u == "g" || u.startsWith("gram") -> "g"
            u == "mg" -> "mg"
            u == "ml" || u.startsWith("milli") -> "ml"
            u == "l" || u.startsWith("lit") -> "L"
            u.startsWith("gal") -> "gal"
            u.startsWith("qt") || u.startsWith("quart") -> "qt"
            u.startsWith("pt") || u.startsWith("pint") -> "pt"
            u.startsWith("ct") || u == "count" -> "ct"
            u == "pk" || u == "pack" -> "pack"
            u.startsWith("pc") || u.startsWith("piece") -> "pcs"
            else -> u.trimEnd('s').ifEmpty { u }
        }
    }
}
