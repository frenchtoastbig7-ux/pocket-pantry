package com.sovereignedge.pocketpantry.barcode

/**
 * Guesses where a product is stored from its name and the category text a lookup returned. Offline, instant, and
 * deliberately conservative: it returns null when nothing in the text points anywhere, so callers fall back to the
 * user's choice rather than guessing wildly.
 */
object LocationGuesser {
    enum class Kind { FREEZER, FRIDGE, COUNTER, PANTRY }

    private val freezer = words("frozen", "freezer", "ice cream", "popsicle", "popsicles", "gelato", "sorbet", "fish sticks", "ice pops", "frozen foods", "frozen food")
    // Shelf-stable signals; these beat fridge/counter words so "canned tomatoes" and "chicken broth" stay in the pantry.
    private val pantry = words(
        "canned", "can", "cans", "jar", "jarred", "dried", "dry", "pasta", "spaghetti", "penne", "macaroni", "noodles", "ramen", "rice", "quinoa", "couscous",
        "cereal", "cereals", "granola", "oats", "oatmeal", "crackers", "chips", "pretzels", "popcorn", "cookies", "biscuits", "flour", "sugar", "oil", "vinegar",
        "spice", "spices", "seasoning", "seasonings", "salt", "pepper", "sauce", "paste", "soup", "broth", "stock", "bouillon", "mix", "powder", "instant",
        "nuts", "peanuts", "almonds", "peanut butter", "honey", "syrup", "tea", "coffee", "chocolate", "candy", "bar", "bars", "beans", "lentils", "chickpeas",
        "tuna", "sardines", "anchovies", "tomato sauce", "tomato paste", "diced tomatoes", "crushed tomatoes", "salsa", "ketchup", "mustard", "mayonnaise",
        "baking", "yeast", "vanilla", "extract", "cocoa", "gravy", "shelf stable", "shelf-stable", "water", "soda", "cola", "juice box", "baby food", "formula",
        "pantry", "canned goods", "grains", "snacks", "snack", "condiments", "breakfast cereals", "legumes", "spreads", "sweeteners", "beverages", "packets", "packet",
        "cream of", "condensed", "evaporated", "relish", "flakes", "curry", "sazon", "adobo", "achiote", "bouillon cubes", "pop", "popcorn", "flavor", "flavored",
        "flavour", "chunks", "minced", "jerky", "marinade", "dressing", "glaze", "croutons", "stuffing", "pudding", "gelatin", "jello", "crisps", "trail mix", "wafers",
        "biscotti", "tortilla chips", "taco", "tacos", "shells", "enchilada", "refried", "hominy", "masa", "cornmeal", "grits", "hot sauce", "soy sauce", "fish sauce",
        "oyster sauce", "ramen", "udon", "yakisoba", "pho", "cup", "bowl", "microwave", "microwavable", "pouch", "pouches", "tetra", "concentrate", "bulk",
    )
    private val fridge = words(
        "milk", "yogurt", "yoghurt", "cheese", "cheddar", "mozzarella", "parmesan", "feta", "brie", "butter", "margarine", "cream", "creamer", "sour cream",
        "cottage", "kefir", "eggs", "egg", "tofu", "tempeh", "hummus", "deli", "ham", "turkey", "bacon", "sausage", "sausages", "hot dogs", "salami", "pepperoni",
        "chicken", "beef", "pork", "steak", "ground beef", "ground turkey", "fish", "salmon", "shrimp", "cod", "tilapia", "lettuce", "spinach", "kale", "arugula",
        "salad", "greens", "berries", "strawberries", "blueberries", "raspberries", "blackberries", "grapes", "cherries", "mushrooms", "carrots", "celery",
        "broccoli", "cauliflower", "cucumber", "cucumbers", "zucchini", "asparagus", "cilantro", "parsley", "basil", "dill", "chives", "scallions", "green onions",
        "leeks", "cabbage", "brussels", "radishes", "beets", "dough", "pie crust", "pizza dough", "crescent", "orange juice", "almond milk", "oat milk", "soy milk",
        "dairy", "dairies", "cheeses", "yogurts", "fresh", "refrigerated", "meats", "meat", "poultry", "seafood", "fresh vegetables", "fresh fruits", "fresh fruit",
        "fresh vegetable", "produce", "fermented", "kimchi", "sauerkraut", "pickles", "guacamole", "pesto", "jam", "jelly", "fillet", "fillets", "steaks", "cutlets",
        "thighs", "breasts", "wings", "drumsticks", "ribs", "roast", "chops", "mince", "tenderloin", "brisket",
    )
    // A cut of meat or fish is fresh (or frozen, which the caller already respects) even when the species word is a pantry staple ("tuna").
    private val freshCut = words("fillet", "fillets", "steak", "steaks", "thighs", "breasts", "wings", "drumsticks", "chops", "cutlets", "roast", "ribs", "tenderloin", "brisket", "ground beef", "ground turkey", "fresh")
    private val counter = words(
        "banana", "bananas", "avocado", "avocados", "tomato", "tomatoes", "onion", "onions", "garlic", "potato", "potatoes", "sweet potato", "sweet potatoes",
        "shallot", "shallots", "bread", "loaf", "bagel", "bagels", "baguette", "rolls", "buns", "tortillas", "apple", "apples", "orange", "oranges", "lemon",
        "lemons", "lime", "limes", "pear", "pears", "peach", "peaches", "plum", "plums", "mango", "mangos", "mangoes", "pineapple", "melon", "watermelon",
        "cantaloupe", "kiwi", "squash", "ginger", "nectarine", "nectarines", "clementines", "grapefruit", "bakery", "fruits", "fruit",
    )

    /** Keyword match on the item's name, brand and category text. */
    fun guess(name: String?, brand: String? = null, category: String? = null): Kind? {
        val text = listOfNotNull(name, brand, category).joinToString(" ").lowercase()
            .replace(Regex("""[^a-z0-9%&+ ]"""), " ").replace(Regex("\\s+"), " ")
        if (text.isBlank()) return null
        val padded = " $text "
        fun hits(set: Set<String>) = set.count { padded.contains(" $it ") }
        if (hits(freezer) > 0) return Kind.FREEZER
        if (hits(freshCut) > 0) return Kind.FRIDGE
        val p = hits(pantry)
        val f = hits(fridge)
        val c = hits(counter)
        // A shelf-stable word is decisive: "evaporated milk" and "chicken broth" are pantry no matter how many fresh words follow.
        return when {
            p > 0 -> Kind.PANTRY
            f > 0 && f >= c -> Kind.FRIDGE
            c > 0 -> Kind.COUNTER
            else -> null
        }
    }

    /** Which of the household's locations matches a [Kind], by name ("Fridge", "Refrigerator", "Freezer"...). */
    fun matchLocation(kind: Kind, names: List<String>): String? {
        val wanted = when (kind) {
            Kind.FREEZER -> listOf("freezer", "frozen")
            Kind.FRIDGE -> listOf("fridge", "refrigerator", "refrigerador", "nevera", "cold")
            Kind.COUNTER -> listOf("counter", "countertop", "fruit bowl", "bench")
            Kind.PANTRY -> listOf("pantry", "cupboard", "cabinet", "shelf", "dry")
        }
        return names.firstOrNull { n -> wanted.any { n.trim().lowercase().contains(it) } }
    }

    private fun words(vararg w: String): Set<String> = w.map { it.lowercase() }.toSet()
}
