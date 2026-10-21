package com.sovereignedge.pocketpantry.barcode

/**
 * Pulls a pack size out of product text ("Coca-Cola Can, 12 fl oz" -> "12 fl oz", "Beans 4 x 15 oz" -> "4 × 15 oz").
 * Retail databases rarely give a clean size field, but the title almost always carries one.
 */
object SizeParser {
    private const val NUMBER = """(\d+(?:[.,]\d+)?)"""
    private const val UNIT = """(fl\.?\s*oz\.?|fluid\s+ounces?|oz\.?|ounces?|lbs?\.?|pounds?|kgs?|kilograms?|g|grams?|mg|ml|milliliters?|millilitres?|l|liters?|litres?|ct\.?|count|pk|pack|pcs?|pieces?|sheets?|bags?|cans?|bottles?|rolls?)"""
    private val single = Regex("""(?<![\w.])$NUMBER\s*-?\s*$UNIT(?![a-z])""", RegexOption.IGNORE_CASE)
    private val multi = Regex("""(?<![\w.])$NUMBER\s*(?:x|×|pack of|pk of|ct of)\s*$NUMBER\s*-?\s*$UNIT(?![a-z])""", RegexOption.IGNORE_CASE)
    private val packOf = Regex("""(?<![\w.])(?:pack of|case of)\s*(\d+)""", RegexOption.IGNORE_CASE)

    /** The first size found in [text], normalised, or null. */
    fun parse(text: String?): String? {
        if (text.isNullOrBlank()) return null
        multi.find(text)?.let { m ->
            return "${num(m.groupValues[1])} × ${num(m.groupValues[2])} ${unit(m.groupValues[3])}"
        }
        single.find(text)?.let { m ->
            val value = num(m.groupValues[1])
            val unit = unit(m.groupValues[2])
            // "2 bags" on its own is a count, not a size; keep it only when nothing better exists.
            return "$value $unit"
        }
        packOf.find(text)?.let { return "${it.groupValues[1]} pack" }
        return null
    }

    /** True when [text] is itself just a size ("16 oz"), as opposed to a sentence or junk like "9-11". */
    fun looksLikeSize(text: String?): Boolean = text != null && parse(text)?.length == text.trim().length

    private fun num(raw: String): String = raw.replace(',', '.').let { if ('.' in it) it.trimEnd('0').trimEnd('.') else it }.ifEmpty { "0" }

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
            u.startsWith("ct") || u == "count" -> "ct"
            u == "pk" || u == "pack" -> "pack"
            u.startsWith("pc") || u.startsWith("piece") -> "pcs"
            else -> u.trimEnd('s').ifEmpty { u }
        }
    }
}
