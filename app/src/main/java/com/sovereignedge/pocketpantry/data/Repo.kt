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
import org.json.JSONArray
import org.json.JSONObject

/**
 * All reads and writes. Reads are Flows that re-query whenever a table they depend on changes. Writes to synced
 * tables go through [commit] as ops, which are applied locally exactly as a remote op would be and kept in the op
 * log for other household devices. Only the live cart and the recipe chat are written directly.
 */
class Repo(context: Context, val deviceId: String, dbName: String = "pantry.db") {
    private val helper = PantryDb(context, dbName)
    private val db: SQLiteDatabase get() = helper.writableDatabase
    private val changes = MutableSharedFlow<String>(extraBufferCapacity = 256)

    /** Bus keys as tables change; the sync manager pushes after local writes. */
    fun changeTicks(): Flow<String> = changes
    val hlc = Hlc(deviceId)
    private var localSeq = 0L

    init {
        db.rawQuery("SELECT MAX(hlc) FROM ops", null).use { if (it.moveToFirst()) it.getString(0)?.let(hlc::observe) }
        localSeq = db.rawQuery("SELECT COALESCE(MAX(seq), 0) FROM ops WHERE device=?", arrayOf(deviceId)).single { it.getLong(0) }
    }

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

    // ---------- Op log ----------

    /** Builder for the ops of one local change. */
    inner class Tx(val d: SQLiteDatabase) {
        val ops = ArrayList<Op>()
        private fun op(table: String, row: String, kind: String, fields: JSONObject) {
            ops += Op(Hlc.newId(), deviceId, ++localSeq, hlc.next(), table, row, kind, fields)
        }

        fun set(table: String, row: String, fields: Map<String, Any?>) = op(table, row, Op.SET, JSONObject().apply { fields.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } })
        fun delta(table: String, row: String, field: String, amount: Double) = op(table, row, Op.DELTA, JSONObject().put(field, amount))
        fun delete(table: String, row: String) = op(table, row, Op.DELETE, JSONObject())
    }

    /** Runs [build] to collect ops, applies them, and records them. */
    private suspend fun commit(build: Tx.() -> Unit): Set<String> = withContext(Dispatchers.IO) {
        val d = db
        val touched = HashSet<String>()
        d.beginTransaction()
        try {
            val tx = Tx(d).apply(build)
            for (op in tx.ops) applyOp(d, op)?.let(touched::add)
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
        touched
    }.also { it.forEach(changes::tryEmit) }

    /** Applies ops from another device (already deduplicated by op id inside). Returns the bus keys that changed. */
    suspend fun applyRemote(ops: List<Op>): Set<String> = withContext(Dispatchers.IO) {
        val d = db
        val touched = HashSet<String>()
        d.beginTransaction()
        try {
            for (op in ops.sortedBy { it.hlc }) {
                hlc.observe(op.hlc)
                applyOp(d, op)?.let(touched::add)
            }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
        touched
    }.also { it.forEach(changes::tryEmit) }

    /** Highest seq held per origin device: what we'd tell a peer we already have. */
    suspend fun cursors(): Map<String, Long> = read { d ->
        d.rawQuery("SELECT device, MAX(seq) FROM ops GROUP BY device", null).mapAll { it.getString(0) to it.getLong(1) }.toMap()
    }

    /** Ops a peer with [theirCursors] is missing, oldest first, so parents arrive before children. */
    suspend fun opsMissingFrom(theirCursors: Map<String, Long>, limit: Int = 500, afterHlc: String = ""): List<Op> = read { d ->
        val mine = d.rawQuery("SELECT DISTINCT device FROM ops", null).mapAll { it.getString(0) }
        val clauses = mine.joinToString(" OR ") { "(device=? AND seq>?)" }
        if (clauses.isEmpty()) return@read emptyList()
        val args = mine.flatMap { listOf(it, (theirCursors[it] ?: 0L).toString()) } + afterHlc + limit.toString()
        d.rawQuery("SELECT op_id, device, seq, hlc, table_name, row_uuid, kind, payload FROM ops WHERE ($clauses) AND hlc > ? ORDER BY hlc LIMIT ?", args.toTypedArray())
            .mapAll { Op(it.getString(0), it.getString(1), it.getLong(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6), JSONObject(it.getString(7))) }
    }

    suspend fun opCount(): Long = read { d -> d.rawQuery("SELECT COUNT(*) FROM ops", null).single { it.getLong(0) } }

    /** Applies one op to state. Returns the bus key to notify, or null when the op was already known or ignored. */
    private fun applyOp(d: SQLiteDatabase, op: Op): String? {
        val table = SyncTable.byName[op.table] ?: return null
        val inserted = d.insertWithOnConflict(
            "ops", null,
            cv("op_id" to op.id, "device" to op.device, "seq" to op.seq, "hlc" to op.hlc, "table_name" to op.table, "row_uuid" to op.row, "kind" to op.kind, "payload" to op.fields.toString()),
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        if (inserted == -1L) return null
        when (op.kind) {
            Op.DELETE -> {
                val tomb = d.rawQuery("SELECT hlc FROM tombstones WHERE table_name=? AND row_uuid=?", arrayOf(op.table, op.row)).firstOrNull { it.getString(0) }
                if (tomb == null || op.hlc > tomb) {
                    d.insertWithOnConflict("tombstones", null, cv("table_name" to op.table, "row_uuid" to op.row, "hlc" to op.hlc), SQLiteDatabase.CONFLICT_REPLACE)
                }
                d.delete(op.table, "uuid=?", arrayOf(op.row))
                d.delete("clocks", "table_name=? AND row_uuid=?", arrayOf(op.table, op.row))
            }
            Op.DELTA -> {
                val field = op.fields.keys().asSequence().firstOrNull() ?: return null
                val col = table.columns[field] ?: return null
                d.execSQL("UPDATE ${op.table} SET $col = MAX(0, $col + ?) WHERE uuid = ?", arrayOf<Any>(op.fields.getDouble(field), op.row))
            }
            Op.SET -> {
                val tomb = d.rawQuery("SELECT hlc FROM tombstones WHERE table_name=? AND row_uuid=?", arrayOf(op.table, op.row)).firstOrNull { it.getString(0) }
                if (tomb != null && tomb > op.hlc) return null
                if (tomb != null) d.delete("tombstones", "table_name=? AND row_uuid=?", arrayOf(op.table, op.row))
                val exists = d.rawQuery("SELECT 1 FROM ${op.table} WHERE uuid=?", arrayOf(op.row)).firstOrNull { true } ?: false
                if (!exists) ensureRow(d, table, op.row)
                val values = ContentValues()
                for (field in op.fields.keys()) {
                    val clock = d.rawQuery("SELECT hlc FROM clocks WHERE table_name=? AND row_uuid=? AND field=?", arrayOf(op.table, op.row, field)).firstOrNull { it.getString(0) }
                    if (clock != null && clock >= op.hlc) continue
                    val raw = op.fields.get(field).takeUnless { it == JSONObject.NULL }
                    val ref = table.refs[field]
                    if (ref != null) {
                        val (col, refTable) = ref
                        val refUuid = raw as? String ?: continue
                        values.put(col, localIdOf(d, refTable, refUuid) ?: ensureRow(d, SyncTable.byName.getValue(refTable), refUuid))
                    } else {
                        val col = table.columns[field] ?: continue
                        putValue(values, col, raw)
                    }
                    d.insertWithOnConflict("clocks", null, cv("table_name" to op.table, "row_uuid" to op.row, "field" to field, "hlc" to op.hlc), SQLiteDatabase.CONFLICT_REPLACE)
                }
                if (values.size() > 0) d.update(op.table, values, "uuid=?", arrayOf(op.row))
            }
        }
        return table.bus
    }

    /** Inserts a row with defaults (and placeholder parents for required references) so a later set can fill it. */
    private fun ensureRow(d: SQLiteDatabase, table: SyncTable, uuid: String): Long {
        localIdOf(d, table.name, uuid)?.let { return it }
        val values = cv("uuid" to uuid)
        table.defaults.forEach { (col, v) -> putValue(values, col, v) }
        for ((col, refTable) in table.refs.values) {
            values.put(col, ensureRow(d, SyncTable.byName.getValue(refTable), "placeholder:$refTable"))
        }
        if (table.name == "products") values.put("barcode", uuid.removePrefix("product:"))
        d.insertOrThrow(table.name, null, values)
        return localIdOf(d, table.name, uuid)!!
    }

    private fun localIdOf(d: SQLiteDatabase, table: String, uuid: String): Long? =
        d.rawQuery("SELECT rowid FROM $table WHERE uuid=?", arrayOf(uuid)).firstOrNull { it.getLong(0) }

    private fun uuidOf(d: SQLiteDatabase, table: String, id: Long): String =
        d.rawQuery("SELECT uuid FROM $table WHERE rowid=?", arrayOf("$id")).firstOrNull { it.getString(0) } ?: error("$table $id has no uuid")

    private fun putValue(values: ContentValues, col: String, raw: Any?) {
        when (raw) {
            null -> values.putNull(col)
            is String -> values.put(col, raw)
            is Boolean -> values.put(col, if (raw) 1 else 0)
            is Int -> values.put(col, raw.toLong())
            is Long -> values.put(col, raw)
            is Double -> if (raw % 1.0 == 0.0 && col in INTEGER_COLUMNS) values.put(col, raw.toLong()) else values.put(col, raw)
            is Number -> values.put(col, raw.toDouble())
            else -> values.put(col, raw.toString())
        }
    }

    /**
     * Creates ops for rows that have none yet (data from before the household existed), so they replicate.
     * Safe to call repeatedly.
     */
    suspend fun seedOpsForExistingState() = commit {
        fun seed(table: SyncTable, selectFields: String, map: (Cursor) -> Map<String, Any?>) {
            d.rawQuery("SELECT uuid, $selectFields FROM ${table.name} WHERE uuid NOT IN (SELECT row_uuid FROM ops WHERE table_name=?)", arrayOf(table.name))
                .mapAll { it.getString(0) to map(it) }
                .forEach { (uuid, fields) -> set(table.name, uuid, fields) }
        }
        seed(SyncTable.byName.getValue("locations"), "name, sort_order") { mapOf("name" to it.getString(1), "sort_order" to it.getInt(2)) }
        seed(SyncTable.byName.getValue("stores"), "name, tax_rate") { mapOf("name" to it.getString(1), "tax_rate" to it.getDouble(2)) }
        seed(SyncTable.byName.getValue("items"), "$ITEM_COLS, (SELECT uuid FROM locations WHERE id = location_id)") { c ->
            val item = c.toItem(offset = 1)
            itemFields(item, c.getString(ITEM_COL_COUNT + 1))
        }
        seed(SyncTable.byName.getValue("products"), "barcode, name, brand, quantity_text, source, updated_at") {
            mapOf("barcode" to it.getString(1), "name" to it.getString(2), "brand" to it.getStringOrNull(3), "quantity_text" to it.getStringOrNull(4), "source" to it.getString(5), "updated_at" to it.getLong(6))
        }
        seed(SyncTable.byName.getValue("prices"), "(SELECT uuid FROM stores WHERE id = store_id), barcode, price, seen_at") {
            mapOf("store" to it.getString(1), "barcode" to it.getString(2), "price" to it.getDouble(3), "seen_at" to it.getLong(4))
        }
        d.rawQuery("SELECT uuid, (SELECT uuid FROM stores WHERE id = store_id), started_at, finished_at FROM cart_sessions WHERE finished_at IS NOT NULL AND uuid NOT IN (SELECT row_uuid FROM ops WHERE table_name='cart_sessions')", null)
            .mapAll { listOf(it.getString(0), it.getString(1), it.getLong(2), it.getLong(3)) }
            .forEach { (uuid, store, started, finished) -> set("cart_sessions", uuid as String, mapOf("store" to store, "started_at" to started, "finished_at" to finished)) }
        d.rawQuery("SELECT l.uuid, s.uuid, l.barcode, l.name, l.unit_price, l.qty, l.taxable FROM cart_lines l JOIN cart_sessions s ON s.id = l.session_id WHERE s.finished_at IS NOT NULL AND l.uuid NOT IN (SELECT row_uuid FROM ops WHERE table_name='cart_lines')", null)
            .mapAll { c -> c.getString(0) to mapOf("session" to c.getString(1), "barcode" to c.getStringOrNull(2), "name" to c.getString(3), "unit_price" to c.getDouble(4), "qty" to c.getDouble(5), "taxable" to c.getInt(6)) }
            .forEach { (uuid, fields) -> set("cart_lines", uuid, fields) }
        seed(SyncTable.byName.getValue("shopping_list"), "name, quantity_text, barcode, checked, created_at") {
            mapOf("name" to it.getString(1), "quantity_text" to it.getString(2), "barcode" to it.getStringOrNull(3), "checked" to it.getInt(4), "created_at" to it.getLong(5))
        }
        seed(SyncTable.byName.getValue("recipes"), "title, body, prompt, created_at") {
            mapOf("title" to it.getString(1), "body" to it.getString(2), "prompt" to it.getString(3), "created_at" to it.getLong(4))
        }
    }

    /**
     * One-time cleanup for data entered before the size field existed: a unit that contains a digit ("1 Packet",
     * "1.25 OZ") was really a pack size. Emitted as ops so every phone converges on the same result.
     */
    suspend fun migrateUnitsToSizes() = commit {
        d.rawQuery("SELECT uuid, unit FROM items WHERE size = '' AND unit GLOB '*[0-9]*'", null)
            .mapAll { it.getString(0) to it.getString(1) }
            .forEach { (uuid, unit) -> set("items", uuid, mapOf("size" to unit.trim(), "unit" to "")) }
    }

    /**
     * Two phones that each had data before pairing both brought their own "Pantry", "Fridge" and "My grocery store".
     * Fold rows with the same name together, keeping the one with the smallest uuid so every phone picks the same
     * survivor, and point items, prices and trips at it. Emitted as ops, so it replicates. Returns how many were merged.
     */
    suspend fun mergeDuplicates(): Int {
        var merged = 0
        commit {
            fun groups(table: String) = d.rawQuery("SELECT id, uuid, name FROM $table WHERE uuid NOT LIKE 'placeholder:%'", null)
                .mapAll { Triple(it.getLong(0), it.getString(1), it.getString(2).trim().lowercase()) }
                .groupBy { it.third }.values.filter { it.size > 1 }.map { it.sortedBy { row -> row.second } }
            for (group in groups("locations")) {
                val keep = group.first()
                for (dup in group.drop(1)) {
                    d.rawQuery("SELECT uuid FROM items WHERE location_id=?", arrayOf("${dup.first}")).mapAll { it.getString(0) }
                        .forEach { set("items", it, mapOf("location" to keep.second)) }
                    delete("locations", dup.second)
                    merged++
                }
            }
            for (group in groups("stores")) {
                val keep = group.first()
                for (dup in group.drop(1)) {
                    d.rawQuery("SELECT uuid, barcode, price, seen_at FROM prices WHERE store_id=?", arrayOf("${dup.first}"))
                        .mapAll { listOf(it.getString(0), it.getString(1), it.getDouble(2), it.getLong(3)) }
                        .forEach { (uuid, bc, price, seen) ->
                            set("prices", "price:${keep.second}:$bc", mapOf("store" to keep.second, "barcode" to bc, "price" to price, "seen_at" to seen))
                            delete("prices", uuid as String)
                        }
                    d.rawQuery("SELECT uuid, finished_at FROM cart_sessions WHERE store_id=?", arrayOf("${dup.first}"))
                        .mapAll { it.getString(0) to it.isNull(1) }
                        .forEach { (uuid, open) ->
                            if (open) d.update("cart_sessions", cv("store_id" to keep.first), "uuid=?", arrayOf(uuid)) // live cart: local only
                            else set("cart_sessions", uuid, mapOf("store" to keep.second))
                        }
                    delete("stores", dup.second)
                    merged++
                }
            }
        }
        return merged
    }

    /** True when nothing but the seeded defaults exists, so joining a household can start from the other phone's data. */
    suspend fun isPristine(): Boolean = read { d ->
        fun count(sql: String) = d.rawQuery(sql, null).single { it.getLong(0) }
        count("SELECT COUNT(*) FROM items") == 0L && count("SELECT COUNT(*) FROM shopping_list") == 0L && count("SELECT COUNT(*) FROM recipes") == 0L &&
            count("SELECT COUNT(*) FROM cart_sessions WHERE finished_at IS NOT NULL") == 0L && count("SELECT COUNT(*) FROM ops") == 0L &&
            d.storesNow().map { it.name } == listOf(PantryDb.DEFAULT_STORE) && d.locationsNow().map { it.name } == PantryDb.DEFAULT_LOCATIONS
    }

    /** Removes the seeded defaults (without ops) so a joined household's locations and stores arrive without duplicates. */
    suspend fun dropDefaultsForJoin() = write(T_LOCATIONS, T_STORES, T_CART) { d ->
        d.delete("locations", null, null)
        d.delete("stores", null, null)
    }

    // ---------- Peers ----------

    data class Peer(val deviceId: String, val name: String, val addrs: List<String>, val lastSeen: Long, val lastSync: Long?)

    fun peers(): Flow<List<Peer>> = watch(T_PEERS) { it.peersNow() }
    suspend fun peersNow(): List<Peer> = read { it.peersNow() }

    private fun SQLiteDatabase.peersNow(): List<Peer> =
        rawQuery("SELECT device_id, name, addrs, last_seen, last_sync FROM peers ORDER BY name", null).mapAll { c ->
            val arr = JSONArray(c.getString(2))
            Peer(c.getString(0), c.getString(1), (0 until arr.length()).map { arr.getString(it) }, c.getLong(3), if (c.isNull(4)) null else c.getLong(4))
        }

    /** Records a device we talked to. Addresses are merged, newest first, so a Tailscale address learnt once is kept. */
    suspend fun rememberPeer(deviceId: String, name: String, addrs: List<String>, synced: Boolean) = write(T_PEERS) { d ->
        val existing = d.peersNow().firstOrNull { it.deviceId == deviceId }
        val merged = (addrs + (existing?.addrs ?: emptyList())).distinct().take(12)
        val now = System.currentTimeMillis()
        d.insertWithOnConflict(
            "peers", null,
            cv("device_id" to deviceId, "name" to name, "addrs" to JSONArray(merged).toString(), "last_seen" to now, "last_sync" to (if (synced) now else existing?.lastSync)),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    suspend fun addManualPeer(addr: String) = write(T_PEERS) { d ->
        d.insertWithOnConflict("peers", null, cv("device_id" to "manual:$addr", "name" to addr, "addrs" to JSONArray(listOf(addr)).toString(), "last_seen" to 0L), SQLiteDatabase.CONFLICT_IGNORE)
    }

    suspend fun forgetPeer(deviceId: String) = write(T_PEERS) { d -> d.delete("peers", "device_id=?", arrayOf(deviceId)) }

    /** Leaving a household: forget the other devices and the replication log, keep the data. */
    suspend fun clearSyncState() = write(T_PEERS) { d ->
        d.delete("peers", null, null)
        d.delete("ops", null, null)
        d.delete("clocks", null, null)
        d.delete("tombstones", null, null)
        localSeq = 0
    }

    // ---------- Locations ----------

    fun locations(): Flow<List<Location>> = watch(T_LOCATIONS) { it.locationsNow() }

    private fun SQLiteDatabase.locationsNow(): List<Location> =
        rawQuery("SELECT id, name, sort_order FROM locations WHERE uuid NOT LIKE 'placeholder:%' ORDER BY sort_order, id", null).mapAll {
            Location(it.getLong(0), it.getString(1), it.getInt(2))
        }

    suspend fun addLocation(name: String) = commit {
        val next = d.rawQuery("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM locations", null).single { it.getInt(0) }
        set("locations", Hlc.newId(), mapOf("name" to name.trim(), "sort_order" to next))
    }

    suspend fun renameLocation(id: Long, name: String) = commit { set("locations", uuidOf(d, "locations", id), mapOf("name" to name.trim())) }

    /** Deleting a location deletes its items; callers confirm first. */
    suspend fun deleteLocation(id: Long) = commit {
        d.rawQuery("SELECT uuid FROM items WHERE location_id=?", arrayOf("$id")).mapAll { it.getString(0) }.forEach { delete("items", it) }
        delete("locations", uuidOf(d, "locations", id))
    }

    // ---------- Inventory ----------

    fun items(): Flow<List<Item>> = watch(T_ITEMS, T_LOCATIONS) { d ->
        d.rawQuery("SELECT $ITEM_COLS FROM items ORDER BY LOWER(name)", null).mapAll { it.toItem() }
    }

    suspend fun item(id: Long): Item? = read { d ->
        d.rawQuery("SELECT $ITEM_COLS FROM items WHERE id=?", arrayOf("$id")).firstOrNull { it.toItem() }
    }

    suspend fun itemsNow(): List<Item> = read { d -> d.rawQuery("SELECT $ITEM_COLS FROM items ORDER BY LOWER(name)", null).mapAll { it.toItem() } }

    private fun itemFields(item: Item, locationUuid: String) = mapOf(
        "name" to item.name.trim(), "barcode" to item.barcode?.ifBlank { null }, "brand" to item.brand?.ifBlank { null },
        "quantity" to item.quantity, "unit" to item.unit.trim(), "size" to item.size.trim(), "location" to locationUuid, "expires_on" to item.expiresOn,
        "notes" to item.notes.trim(), "low_threshold" to item.lowThreshold, "added_at" to item.addedAt, "updated_at" to item.updatedAt,
    )

    /**
     * New items write every field. Edits write only the fields that differ from what's stored, so saving the editor
     * on one phone doesn't overwrite a field the other phone changed in the meantime.
     */
    suspend fun upsertItem(item: Item) = commit {
        val now = System.currentTimeMillis()
        val locationUuid = uuidOf(d, "locations", item.locationId)
        if (item.id == 0L) {
            set("items", Hlc.newId(), itemFields(item.copy(addedAt = now, updatedAt = now), locationUuid))
        } else {
            val current = d.rawQuery("SELECT $ITEM_COLS FROM items WHERE id=?", arrayOf("${item.id}")).firstOrNull { it.toItem() } ?: return@commit
            val before = itemFields(current, uuidOf(d, "locations", current.locationId))
            val after = itemFields(item.copy(addedAt = current.addedAt, updatedAt = now), locationUuid)
            val changed = after.filter { (k, v) -> k != "updated_at" && before[k] != v }
            if (changed.isNotEmpty()) set("items", uuidOf(d, "items", item.id), changed + ("updated_at" to now))
        }
    }

    suspend fun adjustQuantity(id: Long, delta: Double) = commit { delta("items", uuidOf(d, "items", id), "quantity", delta) }

    suspend fun deleteItem(id: Long) = commit { delete("items", uuidOf(d, "items", id)) }

    /**
     * Adds finished cart lines to the inventory. A line whose barcode (or, failing that, name) already exists in the
     * chosen location tops up that item; anything else becomes a new item.
     */
    suspend fun stockFromCart(lines: List<CartLine>, locationId: Long) = commit {
        val now = System.currentTimeMillis()
        val locationUuid = uuidOf(d, "locations", locationId)
        for (line in lines) {
            val existing = line.barcode?.let { bc ->
                d.rawQuery("SELECT uuid FROM items WHERE barcode=? AND location_id=? LIMIT 1", arrayOf(bc, "$locationId")).firstOrNull { it.getString(0) }
            } ?: d.rawQuery("SELECT uuid FROM items WHERE LOWER(name)=LOWER(?) AND location_id=? LIMIT 1", arrayOf(line.name, "$locationId")).firstOrNull { it.getString(0) }
            if (existing != null) {
                delta("items", existing, "quantity", line.qty)
            } else {
                val item = Item(0, line.name, line.barcode, null, line.qty, "", "", locationId, null, "", null, now, now)
                set("items", Hlc.newId(), itemFields(item, locationUuid))
            }
        }
    }

    // ---------- Product cache ----------

    suspend fun product(barcode: String): Product? = read { d ->
        d.rawQuery("SELECT barcode, name, brand, quantity_text, source FROM products WHERE barcode=?", arrayOf(barcode)).firstOrNull {
            Product(it.getString(0), it.getString(1), it.getStringOrNull(2), it.getStringOrNull(3), it.getString(4))
        }
    }

    suspend fun rememberProduct(product: Product) = commit {
        set(
            "products", "product:${product.barcode}",
            mapOf("barcode" to product.barcode, "name" to product.name.trim(), "brand" to product.brand, "quantity_text" to product.quantityText, "source" to product.source, "updated_at" to System.currentTimeMillis()),
        )
    }

    // ---------- Stores and prices ----------

    fun stores(): Flow<List<Store>> = watch(T_STORES) { d -> d.storesNow() }

    private fun SQLiteDatabase.storesNow(): List<Store> =
        rawQuery("SELECT id, name, tax_rate FROM stores WHERE uuid NOT LIKE 'placeholder:%' ORDER BY LOWER(name)", null).mapAll { Store(it.getLong(0), it.getString(1), it.getDouble(2)) }

    suspend fun saveStore(store: Store): Long {
        val uuid = if (store.id == 0L) Hlc.newId() else read { uuidOf(it, "stores", store.id) }
        commit { set("stores", uuid, mapOf("name" to store.name.trim(), "tax_rate" to store.taxRate)) }
        return read { localIdOf(it, "stores", uuid) ?: 0L }
    }

    suspend fun deleteStore(id: Long) = commit {
        d.rawQuery("SELECT uuid FROM prices WHERE store_id=?", arrayOf("$id")).mapAll { it.getString(0) }.forEach { delete("prices", it) }
        d.rawQuery("SELECT id, uuid FROM cart_sessions WHERE store_id=?", arrayOf("$id")).mapAll { it.getLong(0) to it.getString(1) }.forEach { (sid, suuid) ->
            d.rawQuery("SELECT uuid FROM cart_lines WHERE session_id=?", arrayOf("$sid")).mapAll { it.getString(0) }.forEach { delete("cart_lines", it) }
            delete("cart_sessions", suuid)
        }
        delete("stores", uuidOf(d, "stores", id))
    }

    suspend fun lastPrice(storeId: Long, barcode: String): Double? = read { d ->
        d.rawQuery("SELECT price FROM prices WHERE store_id=? AND barcode=?", arrayOf("$storeId", barcode)).firstOrNull { it.getDouble(0) }
    }

    // ---------- Cart (the live cart is local to the shopping phone; finished trips are synced) ----------

    fun openSession(storeId: Long): Flow<CartSession?> = watch(T_CART) { d -> d.openSessionNow(storeId) }

    private fun SQLiteDatabase.openSessionNow(storeId: Long): CartSession? =
        rawQuery(
            "SELECT id, store_id, started_at, finished_at FROM cart_sessions WHERE store_id=? AND finished_at IS NULL ORDER BY id DESC LIMIT 1",
            arrayOf("$storeId"),
        ).firstOrNull { it.toSession() }

    suspend fun startSession(storeId: Long): Long = write(T_CART) { d ->
        d.openSessionNow(storeId)?.id ?: d.insertOrThrow("cart_sessions", null, cv("uuid" to Hlc.newId(), "store_id" to storeId, "started_at" to System.currentTimeMillis()))
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
                    "uuid" to Hlc.newId(), "session_id" to line.sessionId, "barcode" to line.barcode, "name" to line.name.trim(),
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

    /** Closes the trip, remembers each barcode's price at this store, and publishes the trip to the household. */
    suspend fun finishSession(session: CartSession) = commit {
        val now = System.currentTimeMillis()
        val storeUuid = uuidOf(d, "stores", session.storeId)
        val sessionUuid = uuidOf(d, "cart_sessions", session.id)
        set("cart_sessions", sessionUuid, mapOf("store" to storeUuid, "started_at" to session.startedAt, "finished_at" to now))
        for (line in d.cartLinesNow(session.id)) {
            set(
                "cart_lines", uuidOf(d, "cart_lines", line.id),
                mapOf("session" to sessionUuid, "barcode" to line.barcode, "name" to line.name, "unit_price" to line.unitPrice, "qty" to line.qty, "taxable" to if (line.taxable) 1 else 0),
            )
            val bc = line.barcode ?: continue
            set("prices", "price:$storeUuid:$bc", mapOf("store" to storeUuid, "barcode" to bc, "price" to line.unitPrice, "seen_at" to now))
        }
    }

    suspend fun deleteSession(id: Long) = commit {
        val uuid = uuidOf(d, "cart_sessions", id)
        d.rawQuery("SELECT uuid FROM cart_lines WHERE session_id=?", arrayOf("$id")).mapAll { it.getString(0) }.forEach { delete("cart_lines", it) }
        delete("cart_sessions", uuid)
    }

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

    private fun SQLiteDatabase.shoppingNow(): List<ShoppingItem> =
        rawQuery("SELECT id, name, quantity_text, barcode, checked, created_at FROM shopping_list ORDER BY checked, LOWER(name)", null).mapAll {
            ShoppingItem(it.getLong(0), it.getString(1), it.getString(2), it.getStringOrNull(3), it.getInt(4) == 1, it.getLong(5))
        }

    fun shoppingList(): Flow<List<ShoppingItem>> = watch(T_LIST) { it.shoppingNow() }

    suspend fun shoppingListNow(): List<ShoppingItem> = read { it.shoppingNow() }

    /** Adds to the list unless an unchecked entry with the same name is already there. */
    suspend fun addToShoppingList(name: String, quantityText: String = "", barcode: String? = null) = commit {
        val trimmed = name.trim()
        val existing = d.rawQuery("SELECT id FROM shopping_list WHERE LOWER(name)=LOWER(?) AND checked=0 LIMIT 1", arrayOf(trimmed)).firstOrNull { it.getLong(0) }
        if (existing == null) {
            set("shopping_list", Hlc.newId(), mapOf("name" to trimmed, "quantity_text" to quantityText.trim(), "barcode" to barcode, "checked" to 0, "created_at" to System.currentTimeMillis()))
        }
    }

    suspend fun updateShoppingItem(item: ShoppingItem) = commit {
        set("shopping_list", uuidOf(d, "shopping_list", item.id), mapOf("name" to item.name.trim(), "quantity_text" to item.quantityText.trim(), "checked" to if (item.checked) 1 else 0))
    }

    suspend fun setChecked(id: Long, checked: Boolean) = commit { set("shopping_list", uuidOf(d, "shopping_list", id), mapOf("checked" to if (checked) 1 else 0)) }

    suspend fun deleteShoppingItem(id: Long) = commit { delete("shopping_list", uuidOf(d, "shopping_list", id)) }

    suspend fun clearChecked() = commit {
        d.rawQuery("SELECT uuid FROM shopping_list WHERE checked=1", null).mapAll { it.getString(0) }.forEach { delete("shopping_list", it) }
    }

    /**
     * Ticks the first unchecked list entry matching a scanned product, by barcode first, then by a name containment
     * either way ("Milk" matches "Whole Milk 2L"). Returns the ticked entry.
     */
    suspend fun tickMatching(barcode: String?, name: String): ShoppingItem? {
        var hit: ShoppingItem? = null
        commit {
            val open = d.shoppingNow().filter { !it.checked }
            val lower = name.lowercase()
            hit = open.firstOrNull { barcode != null && it.barcode == barcode }
                ?: open.filter { it.name.length >= 3 }.firstOrNull { lower.contains(it.name.lowercase()) || it.name.lowercase().contains(lower) }
            hit?.let { set("shopping_list", uuidOf(d, "shopping_list", it.id), mapOf("checked" to 1, "barcode" to (it.barcode ?: barcode))) }
        }
        return hit
    }

    // ---------- Recipes and chat ----------

    fun recipes(): Flow<List<Recipe>> = watch(T_RECIPES) { d ->
        d.rawQuery("SELECT id, title, body, created_at, prompt FROM recipes ORDER BY created_at DESC", null).mapAll {
            Recipe(it.getLong(0), it.getString(1), it.getString(2), it.getLong(3), it.getString(4))
        }
    }

    suspend fun saveRecipe(title: String, body: String, prompt: String) = commit {
        set("recipes", Hlc.newId(), mapOf("title" to title.trim(), "body" to body, "prompt" to prompt, "created_at" to System.currentTimeMillis()))
    }

    suspend fun deleteRecipe(id: Long) = commit { delete("recipes", uuidOf(d, "recipes", id)) }

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

    /** Everything, including the replication log. Used by the settings "delete all data" action. */
    suspend fun wipe() = write(*ALL_TABLES) { d ->
        listOf("chat_messages", "recipes", "shopping_list", "cart_lines", "cart_sessions", "prices", "stores", "products", "items", "locations", "ops", "clocks", "tombstones", "peers").forEach {
            d.delete(it, null, null)
        }
        localSeq = 0
        PantryDb.DEFAULT_LOCATIONS.forEachIndexed { index, name ->
            d.insertOrThrow("locations", null, cv("uuid" to Hlc.newId(), "name" to name, "sort_order" to index))
        }
        d.insertOrThrow("stores", null, cv("uuid" to Hlc.newId(), "name" to PantryDb.DEFAULT_STORE, "tax_rate" to 0.0))
    }

    // ---------- Helpers ----------

    private fun Cursor.toItem(offset: Int = 0) = Item(
        id = getLong(offset), name = getString(offset + 1), barcode = getStringOrNull(offset + 2), brand = getStringOrNull(offset + 3),
        quantity = getDouble(offset + 4), unit = getString(offset + 5), size = getString(offset + 6), locationId = getLong(offset + 7),
        expiresOn = if (isNull(offset + 8)) null else getLong(offset + 8), notes = getString(offset + 9),
        lowThreshold = if (isNull(offset + 10)) null else getDouble(offset + 10), addedAt = getLong(offset + 11), updatedAt = getLong(offset + 12),
    )

    private fun Cursor.toSession() = CartSession(getLong(0), getLong(1), getLong(2), if (isNull(3)) null else getLong(3))

    private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    private inline fun <T> Cursor.mapAll(map: (Cursor) -> T): List<T> = use { c ->
        buildList { while (c.moveToNext()) add(map(c)) }
    }

    private inline fun <T> Cursor.firstOrNull(map: (Cursor) -> T): T? = use { c -> if (c.moveToFirst()) map(c) else null }

    private inline fun <T> Cursor.single(map: (Cursor) -> T): T = use { c -> c.moveToFirst(); map(c) }

    private fun cv(vararg pairs: Pair<String, Any?>) = ContentValues().apply {
        for ((key, value) in pairs) putValue(this, key, value)
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
        const val T_PEERS = "peers"
        val ALL_TABLES = arrayOf(T_LOCATIONS, T_ITEMS, T_PRODUCTS, T_STORES, T_PRICES, T_CART, T_LIST, T_RECIPES, T_CHAT, T_PEERS)
        const val ITEM_COLS = "id, name, barcode, brand, quantity, unit, size, location_id, expires_on, notes, low_threshold, added_at, updated_at"
        const val ITEM_COL_COUNT = 13
        /** Columns that must stay integers even though JSON hands numbers back as doubles. */
        val INTEGER_COLUMNS = setOf("sort_order", "expires_on", "added_at", "updated_at", "seen_at", "started_at", "finished_at", "created_at", "checked", "taxable")
    }
}
