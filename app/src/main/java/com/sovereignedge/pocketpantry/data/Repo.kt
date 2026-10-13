package com.sovereignedge.pocketpantry.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

/**
 * All reads and writes. Reads are exposed as Flows that re-query whenever one of the tables they depend on changes,
 * so every screen stays in sync without a cache layer.
 */
class Repo(context: Context) {
    private val helper = PantryDb(context)
    private val db: SQLiteDatabase get() = helper.writableDatabase
    private val changes = MutableSharedFlow<String>(extraBufferCapacity = 256)

    private fun <T> watch(vararg tables: String, query: (SQLiteDatabase) -> T): Flow<T> =
        changes.onStart { emit(tables.first()) }
            .filter { it in tables }
            .map { query(db) }
            .conflate()
            .flowOn(Dispatchers.IO)

    private suspend fun <T> write(vararg tables: String, block: (SQLiteDatabase) -> T): T =
        withContext(Dispatchers.IO) {
            val d = db
            d.beginTransaction()
            try {
                block(d).also { d.setTransactionSuccessful() }
            } finally {
                d.endTransaction()
            }
        }.also { tables.forEach { changes.tryEmit(it) } }

    private suspend fun <T> read(block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) { block(db) }

    // ---------- Locations ----------

    fun locations(): Flow<List<Location>> = watch(T_LOCATIONS) { it.locationsNow() }

    private fun SQLiteDatabase.locationsNow(): List<Location> =
        rawQuery("SELECT id, name, sort_order FROM locations ORDER BY sort_order, id", null).mapAll {
            Location(it.getLong(0), it.getString(1), it.getInt(2))
        }

    suspend fun addLocation(name: String): Long = write(T_LOCATIONS) { d ->
        val next = d.rawQuery("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM locations", null).single { it.getInt(0) }
        d.insertOrThrow("locations", null, cv("name" to name.trim(), "sort_order" to next))
    }

    suspend fun renameLocation(id: Long, name: String) = write(T_LOCATIONS) { d ->
        d.update("locations", cv("name" to name.trim()), "id=?", arrayOf("$id"))
    }

    /** Deleting a location cascades to its items; callers confirm first. */
    suspend fun deleteLocation(id: Long) = write(T_LOCATIONS, T_ITEMS) { d -> d.delete("locations", "id=?", arrayOf("$id")) }

    // ---------- Inventory ----------

    fun items(): Flow<List<Item>> = watch(T_ITEMS, T_LOCATIONS) { d ->
        d.rawQuery("SELECT $ITEM_COLS FROM items ORDER BY LOWER(name)", null).mapAll { it.toItem() }
    }

    suspend fun item(id: Long): Item? = read { d ->
        d.rawQuery("SELECT $ITEM_COLS FROM items WHERE id=?", arrayOf("$id")).firstOrNull { it.toItem() }
    }

    suspend fun itemsNow(): List<Item> = read { d -> d.rawQuery("SELECT $ITEM_COLS FROM items ORDER BY LOWER(name)", null).mapAll { it.toItem() } }

    suspend fun upsertItem(item: Item): Long = write(T_ITEMS) { d ->
        val now = System.currentTimeMillis()
        val values = cv(
            "name" to item.name.trim(),
            "barcode" to item.barcode?.ifBlank { null },
            "brand" to item.brand?.ifBlank { null },
            "quantity" to item.quantity,
            "unit" to item.unit.trim(),
            "location_id" to item.locationId,
            "expires_on" to item.expiresOn,
            "notes" to item.notes.trim(),
            "low_threshold" to item.lowThreshold,
            "updated_at" to now,
        )
        if (item.id == 0L) {
            values.put("added_at", now)
            d.insertOrThrow("items", null, values)
        } else {
            d.update("items", values, "id=?", arrayOf("${item.id}"))
            item.id
        }
    }

    suspend fun adjustQuantity(id: Long, delta: Double) = write(T_ITEMS) { d ->
        d.execSQL(
            "UPDATE items SET quantity = MAX(0, quantity + ?), updated_at = ? WHERE id = ?",
            arrayOf<Any>(delta, System.currentTimeMillis(), id),
        )
    }

    suspend fun deleteItem(id: Long) = write(T_ITEMS) { d -> d.delete("items", "id=?", arrayOf("$id")) }

    /**
     * Adds finished cart lines to the inventory. A line whose barcode (or, failing that, name) already exists in the
     * chosen location tops up that item; anything else becomes a new item.
     */
    suspend fun stockFromCart(lines: List<CartLine>, locationId: Long) = write(T_ITEMS) { d ->
        val now = System.currentTimeMillis()
        for (line in lines) {
            val existing = line.barcode?.let { bc ->
                d.rawQuery("SELECT id FROM items WHERE barcode=? AND location_id=? LIMIT 1", arrayOf(bc, "$locationId")).firstOrNull { it.getLong(0) }
            } ?: d.rawQuery("SELECT id FROM items WHERE LOWER(name)=LOWER(?) AND location_id=? LIMIT 1", arrayOf(line.name, "$locationId")).firstOrNull { it.getLong(0) }
            if (existing != null) {
                d.execSQL("UPDATE items SET quantity = quantity + ?, updated_at = ? WHERE id = ?", arrayOf<Any>(line.qty, now, existing))
            } else {
                d.insertOrThrow(
                    "items", null,
                    cv(
                        "name" to line.name, "barcode" to line.barcode, "quantity" to line.qty, "unit" to "",
                        "location_id" to locationId, "notes" to "", "added_at" to now, "updated_at" to now,
                    ),
                )
            }
        }
    }

    // ---------- Product cache ----------

    suspend fun product(barcode: String): Product? = read { d ->
        d.rawQuery("SELECT barcode, name, brand, quantity_text, source FROM products WHERE barcode=?", arrayOf(barcode)).firstOrNull {
            Product(it.getString(0), it.getString(1), it.getStringOrNull(2), it.getStringOrNull(3), it.getString(4))
        }
    }

    suspend fun rememberProduct(product: Product) = write(T_PRODUCTS) { d ->
        d.insertWithOnConflict(
            "products", null,
            cv(
                "barcode" to product.barcode, "name" to product.name.trim(), "brand" to product.brand,
                "quantity_text" to product.quantityText, "source" to product.source, "updated_at" to System.currentTimeMillis(),
            ),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    // ---------- Stores and prices ----------

    fun stores(): Flow<List<Store>> = watch(T_STORES) { d -> d.storesNow() }

    private fun SQLiteDatabase.storesNow(): List<Store> =
        rawQuery("SELECT id, name, tax_rate FROM stores ORDER BY LOWER(name)", null).mapAll { Store(it.getLong(0), it.getString(1), it.getDouble(2)) }

    suspend fun saveStore(store: Store): Long = write(T_STORES) { d ->
        val values = cv("name" to store.name.trim(), "tax_rate" to store.taxRate)
        if (store.id == 0L) d.insertOrThrow("stores", null, values) else {
            d.update("stores", values, "id=?", arrayOf("${store.id}")); store.id
        }
    }

    suspend fun deleteStore(id: Long) = write(T_STORES, T_CART) { d -> d.delete("stores", "id=?", arrayOf("$id")) }

    suspend fun lastPrice(storeId: Long, barcode: String): Double? = read { d ->
        d.rawQuery("SELECT price FROM prices WHERE store_id=? AND barcode=?", arrayOf("$storeId", barcode)).firstOrNull { it.getDouble(0) }
    }

    // ---------- Cart ----------

    /** The open trip at [storeId], if any. */
    fun openSession(storeId: Long): Flow<CartSession?> = watch(T_CART) { d -> d.openSessionNow(storeId) }

    private fun SQLiteDatabase.openSessionNow(storeId: Long): CartSession? =
        rawQuery(
            "SELECT id, store_id, started_at, finished_at FROM cart_sessions WHERE store_id=? AND finished_at IS NULL ORDER BY id DESC LIMIT 1",
            arrayOf("$storeId"),
        ).firstOrNull { it.toSession() }

    suspend fun startSession(storeId: Long): Long = write(T_CART) { d ->
        d.openSessionNow(storeId)?.id ?: d.insertOrThrow("cart_sessions", null, cv("store_id" to storeId, "started_at" to System.currentTimeMillis()))
    }

    fun cartLines(sessionId: Long): Flow<List<CartLine>> = watch(T_CART) { d -> d.cartLinesNow(sessionId) }

    private fun SQLiteDatabase.cartLinesNow(sessionId: Long): List<CartLine> =
        rawQuery(
            "SELECT id, session_id, barcode, name, unit_price, qty, taxable FROM cart_lines WHERE session_id=? ORDER BY id DESC",
            arrayOf("$sessionId"),
        ).mapAll { CartLine(it.getLong(0), it.getLong(1), it.getStringOrNull(2), it.getString(3), it.getDouble(4), it.getDouble(5), it.getInt(6) == 1) }

    /** Adds a line, or bumps the quantity when the same barcode is already in the cart at the same price. */
    suspend fun addCartLine(line: CartLine): Long = write(T_CART) { d ->
        val same = line.barcode?.let { bc ->
            d.rawQuery(
                "SELECT id FROM cart_lines WHERE session_id=? AND barcode=? AND unit_price=? LIMIT 1",
                arrayOf("${line.sessionId}", bc, "${line.unitPrice}"),
            ).firstOrNull { it.getLong(0) }
        }
        if (same != null) {
            d.execSQL("UPDATE cart_lines SET qty = qty + ? WHERE id = ?", arrayOf<Any>(line.qty, same))
            same
        } else {
            d.insertOrThrow(
                "cart_lines", null,
                cv(
                    "session_id" to line.sessionId, "barcode" to line.barcode, "name" to line.name.trim(),
                    "unit_price" to line.unitPrice, "qty" to line.qty, "taxable" to if (line.taxable) 1 else 0,
                ),
            )
        }
    }

    suspend fun updateCartLine(line: CartLine) = write(T_CART) { d ->
        d.update(
            "cart_lines",
            cv("name" to line.name.trim(), "unit_price" to line.unitPrice, "qty" to line.qty, "taxable" to if (line.taxable) 1 else 0),
            "id=?", arrayOf("${line.id}"),
        )
    }

    suspend fun deleteCartLine(id: Long) = write(T_CART) { d -> d.delete("cart_lines", "id=?", arrayOf("$id")) }

    suspend fun clearCart(sessionId: Long) = write(T_CART) { d -> d.delete("cart_lines", "session_id=?", arrayOf("$sessionId")) }

    /** Closes the trip and remembers each barcode's price at this store for next time. */
    suspend fun finishSession(session: CartSession) = write(T_CART, T_PRICES) { d ->
        val now = System.currentTimeMillis()
        for (line in d.cartLinesNow(session.id)) {
            val bc = line.barcode ?: continue
            d.insertWithOnConflict(
                "prices", null,
                cv("store_id" to session.storeId, "barcode" to bc, "price" to line.unitPrice, "seen_at" to now),
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
        d.update("cart_sessions", cv("finished_at" to now), "id=?", arrayOf("${session.id}"))
    }

    suspend fun deleteSession(id: Long) = write(T_CART) { d -> d.delete("cart_sessions", "id=?", arrayOf("$id")) }

    fun pastTrips(): Flow<List<TripSummary>> = watch(T_CART, T_STORES) { d ->
        d.rawQuery(
            """SELECT s.id, s.store_id, s.started_at, s.finished_at, st.name, st.tax_rate
               FROM cart_sessions s JOIN stores st ON st.id = s.store_id
               WHERE s.finished_at IS NOT NULL ORDER BY s.finished_at DESC LIMIT 50""",
            null,
        ).mapAll { c ->
            val session = c.toSession()
            val lines = d.cartLinesNow(session.id)
            TripSummary(session, c.getString(4), CartTotals.of(lines, c.getDouble(5)).total, lines.size)
        }
    }

    suspend fun tripLines(sessionId: Long): List<CartLine> = read { d -> d.cartLinesNow(sessionId) }

    // ---------- Shopping list ----------

    fun shoppingList(): Flow<List<ShoppingItem>> = watch(T_LIST) { d ->
        d.rawQuery("SELECT id, name, quantity_text, barcode, checked, created_at FROM shopping_list ORDER BY checked, LOWER(name)", null).mapAll {
            ShoppingItem(it.getLong(0), it.getString(1), it.getString(2), it.getStringOrNull(3), it.getInt(4) == 1, it.getLong(5))
        }
    }

    suspend fun shoppingListNow(): List<ShoppingItem> = read { d ->
        d.rawQuery("SELECT id, name, quantity_text, barcode, checked, created_at FROM shopping_list ORDER BY checked, LOWER(name)", null).mapAll {
            ShoppingItem(it.getLong(0), it.getString(1), it.getString(2), it.getStringOrNull(3), it.getInt(4) == 1, it.getLong(5))
        }
    }

    /** Adds to the list unless an unchecked entry with the same name is already there. */
    suspend fun addToShoppingList(name: String, quantityText: String = "", barcode: String? = null): Long = write(T_LIST) { d ->
        val trimmed = name.trim()
        val existing = d.rawQuery("SELECT id FROM shopping_list WHERE LOWER(name)=LOWER(?) AND checked=0 LIMIT 1", arrayOf(trimmed)).firstOrNull { it.getLong(0) }
        existing ?: d.insertOrThrow(
            "shopping_list", null,
            cv("name" to trimmed, "quantity_text" to quantityText.trim(), "barcode" to barcode, "checked" to 0, "created_at" to System.currentTimeMillis()),
        )
    }

    suspend fun updateShoppingItem(item: ShoppingItem) = write(T_LIST) { d ->
        d.update(
            "shopping_list",
            cv("name" to item.name.trim(), "quantity_text" to item.quantityText.trim(), "checked" to if (item.checked) 1 else 0),
            "id=?", arrayOf("${item.id}"),
        )
    }

    suspend fun setChecked(id: Long, checked: Boolean) = write(T_LIST) { d ->
        d.update("shopping_list", cv("checked" to if (checked) 1 else 0), "id=?", arrayOf("$id"))
    }

    suspend fun deleteShoppingItem(id: Long) = write(T_LIST) { d -> d.delete("shopping_list", "id=?", arrayOf("$id")) }

    suspend fun clearChecked() = write(T_LIST) { d -> d.delete("shopping_list", "checked=1", null) }

    /**
     * Ticks the first unchecked list entry matching a scanned product, by barcode first, then by a name containment
     * either way ("Milk" matches "Whole Milk 2L"). Returns the ticked entry.
     */
    suspend fun tickMatching(barcode: String?, name: String): ShoppingItem? = write(T_LIST) { d ->
        val open = d.rawQuery("SELECT id, name, quantity_text, barcode, checked, created_at FROM shopping_list WHERE checked=0", null).mapAll {
            ShoppingItem(it.getLong(0), it.getString(1), it.getString(2), it.getStringOrNull(3), false, it.getLong(5))
        }
        val lower = name.lowercase()
        val hit = open.firstOrNull { barcode != null && it.barcode == barcode }
            ?: open.filter { it.name.length >= 3 }.firstOrNull { lower.contains(it.name.lowercase()) || it.name.lowercase().contains(lower) }
        hit?.also { d.update("shopping_list", cv("checked" to 1, "barcode" to (it.barcode ?: barcode)), "id=?", arrayOf("${it.id}")) }
    }

    // ---------- Recipes and chat ----------

    fun recipes(): Flow<List<Recipe>> = watch(T_RECIPES) { d ->
        d.rawQuery("SELECT id, title, body, created_at, prompt FROM recipes ORDER BY created_at DESC", null).mapAll {
            Recipe(it.getLong(0), it.getString(1), it.getString(2), it.getLong(3), it.getString(4))
        }
    }

    suspend fun saveRecipe(title: String, body: String, prompt: String): Long = write(T_RECIPES) { d ->
        d.insertOrThrow("recipes", null, cv("title" to title.trim(), "body" to body, "prompt" to prompt, "created_at" to System.currentTimeMillis()))
    }

    suspend fun deleteRecipe(id: Long) = write(T_RECIPES) { d -> d.delete("recipes", "id=?", arrayOf("$id")) }

    fun chat(): Flow<List<ChatMessage>> = watch(T_CHAT) { d -> d.chatNow() }

    private fun SQLiteDatabase.chatNow(): List<ChatMessage> =
        rawQuery("SELECT id, role, text, created_at FROM chat_messages ORDER BY id", null).mapAll {
            ChatMessage(it.getLong(0), it.getString(1), it.getString(2), it.getLong(3))
        }

    suspend fun chatNow(): List<ChatMessage> = read { it.chatNow() }

    suspend fun appendChat(role: String, text: String): Long = write(T_CHAT) { d ->
        d.insertOrThrow("chat_messages", null, cv("role" to role, "text" to text, "created_at" to System.currentTimeMillis()))
    }

    suspend fun updateChat(id: Long, text: String) = write(T_CHAT) { d -> d.update("chat_messages", cv("text" to text), "id=?", arrayOf("$id")) }

    suspend fun deleteChat(id: Long) = write(T_CHAT) { d -> d.delete("chat_messages", "id=?", arrayOf("$id")) }

    suspend fun clearChat() = write(T_CHAT) { d -> d.delete("chat_messages", null, null) }

    /** Everything. Used by the settings "delete all data" action. */
    suspend fun wipe() = write(*ALL_TABLES) { d ->
        listOf("chat_messages", "recipes", "shopping_list", "cart_lines", "cart_sessions", "prices", "stores", "products", "items", "locations").forEach {
            d.delete(it, null, null)
        }
        listOf("Pantry", "Counter", "Fridge", "Freezer").forEachIndexed { index, name ->
            d.insertOrThrow("locations", null, cv("name" to name, "sort_order" to index))
        }
    }

    // ---------- Helpers ----------

    private fun Cursor.toItem() = Item(
        id = getLong(0), name = getString(1), barcode = getStringOrNull(2), brand = getStringOrNull(3),
        quantity = getDouble(4), unit = getString(5), locationId = getLong(6),
        expiresOn = if (isNull(7)) null else getLong(7), notes = getString(8),
        lowThreshold = if (isNull(9)) null else getDouble(9), addedAt = getLong(10), updatedAt = getLong(11),
    )

    private fun Cursor.toSession() = CartSession(getLong(0), getLong(1), getLong(2), if (isNull(3)) null else getLong(3))

    private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    private inline fun <T> Cursor.mapAll(map: (Cursor) -> T): List<T> = use { c ->
        buildList { while (c.moveToNext()) add(map(c)) }
    }

    private inline fun <T> Cursor.firstOrNull(map: (Cursor) -> T): T? = use { c -> if (c.moveToFirst()) map(c) else null }

    private inline fun <T> Cursor.single(map: (Cursor) -> T): T = use { c -> c.moveToFirst(); map(c) }

    private fun cv(vararg pairs: Pair<String, Any?>) = ContentValues().apply {
        for ((key, value) in pairs) when (value) {
            null -> putNull(key)
            is String -> put(key, value)
            is Long -> put(key, value)
            is Int -> put(key, value)
            is Double -> put(key, value)
            is Boolean -> put(key, if (value) 1 else 0)
            else -> put(key, value.toString())
        }
    }

    private companion object {
        const val T_LOCATIONS = "locations"
        const val T_ITEMS = "items"
        const val T_PRODUCTS = "products"
        const val T_STORES = "stores"
        const val T_PRICES = "prices"
        const val T_CART = "cart"
        const val T_LIST = "shopping_list"
        const val T_RECIPES = "recipes"
        const val T_CHAT = "chat"
        val ALL_TABLES = arrayOf(T_LOCATIONS, T_ITEMS, T_PRODUCTS, T_STORES, T_PRICES, T_CART, T_LIST, T_RECIPES, T_CHAT)
        const val ITEM_COLS = "id, name, barcode, brand, quantity, unit, location_id, expires_on, notes, low_threshold, added_at, updated_at"
    }
}
