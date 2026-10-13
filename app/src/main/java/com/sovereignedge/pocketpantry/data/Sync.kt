package com.sovereignedge.pocketpantry.data

import org.json.JSONObject
import java.util.UUID

/**
 * One replicated change. Every write the app makes, local or remote, is an Op; the database state is what you get
 * by applying all known ops. Ops are immutable, deduplicated by [id], and ordered per origin [device] by [seq].
 *
 * - `set`: [fields] is a map of column -> value; each field is last-writer-wins by [hlc].
 * - `delta`: [fields] holds one numeric column -> amount added (quantities), so concurrent changes both count.
 * - `delete`: tombstone; a later `set` from a device that hasn't seen the delete recreates the row.
 */
data class Op(
    val id: String,
    val device: String,
    val seq: Long,
    val hlc: String,
    val table: String,
    val row: String,
    val kind: String,
    val fields: JSONObject,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("dev", device).put("seq", seq).put("hlc", hlc)
        .put("tbl", table).put("row", row).put("k", kind).put("f", fields)

    companion object {
        const val SET = "set"
        const val DELTA = "delta"
        const val DELETE = "delete"

        fun fromJson(o: JSONObject) = Op(
            id = o.getString("id"), device = o.getString("dev"), seq = o.getLong("seq"), hlc = o.getString("hlc"),
            table = o.getString("tbl"), row = o.getString("row"), kind = o.getString("k"), fields = o.optJSONObject("f") ?: JSONObject(),
        )
    }
}

/**
 * Hybrid logical clock: wall time plus a counter, formatted so plain string comparison orders events. Devices with
 * skewed clocks still converge because receiving a later timestamp pulls the local clock forward.
 */
class Hlc(private val device: String) {
    private var millis = 0L
    private var counter = 0

    @Synchronized
    fun next(): String {
        val now = System.currentTimeMillis()
        if (now > millis) {
            millis = now
            counter = 0
        } else counter++
        return format(millis, counter, device)
    }

    /** Folds a remote timestamp in so our next tick sorts after it. */
    @Synchronized
    fun observe(remote: String) {
        val parts = remote.split('-', limit = 3)
        val rMillis = parts.getOrNull(0)?.toLongOrNull() ?: return
        val rCounter = parts.getOrNull(1)?.toIntOrNull(16) ?: 0
        when {
            rMillis > millis -> {
                millis = rMillis
                counter = rCounter
            }
            rMillis == millis && rCounter > counter -> counter = rCounter
        }
    }

    companion object {
        fun format(millis: Long, counter: Int, device: String) = "%013d-%04x-%s".format(millis, counter, device)
        fun newId(): String = UUID.randomUUID().toString()
    }
}

/** How a synced table maps between op fields and SQL columns. */
class SyncTable(
    val name: String,
    /** Op field -> SQL column for plain values. */
    val columns: Map<String, String>,
    /** Op field (holding a row uuid) -> (SQL column holding the local integer id, referenced table). */
    val refs: Map<String, Pair<String, String>> = emptyMap(),
    /** Values a freshly created row gets for columns the op didn't carry. */
    val defaults: Map<String, Any?> = emptyMap(),
    /** The change-bus key screens watch. */
    val bus: String,
) {
    companion object {
        val ALL: List<SyncTable> = listOf(
            SyncTable("locations", mapOf("name" to "name", "sort_order" to "sort_order"), defaults = mapOf("name" to "Unnamed", "sort_order" to 99), bus = "locations"),
            SyncTable(
                "items",
                mapOf(
                    "name" to "name", "barcode" to "barcode", "brand" to "brand", "quantity" to "quantity", "unit" to "unit",
                    "expires_on" to "expires_on", "notes" to "notes", "low_threshold" to "low_threshold", "added_at" to "added_at", "updated_at" to "updated_at",
                ),
                refs = mapOf("location" to ("location_id" to "locations")),
                defaults = mapOf("name" to "Unnamed", "quantity" to 1.0, "unit" to "", "notes" to "", "added_at" to 0L, "updated_at" to 0L),
                bus = "items",
            ),
            SyncTable(
                "products", mapOf("barcode" to "barcode", "name" to "name", "brand" to "brand", "quantity_text" to "quantity_text", "source" to "source", "updated_at" to "updated_at"),
                defaults = mapOf("barcode" to "", "name" to "Unnamed", "source" to "sync", "updated_at" to 0L), bus = "products",
            ),
            SyncTable("stores", mapOf("name" to "name", "tax_rate" to "tax_rate"), defaults = mapOf("name" to "Unnamed", "tax_rate" to 0.0), bus = "stores"),
            SyncTable(
                "prices", mapOf("barcode" to "barcode", "price" to "price", "seen_at" to "seen_at"), refs = mapOf("store" to ("store_id" to "stores")),
                defaults = mapOf("barcode" to "", "price" to 0.0, "seen_at" to 0L), bus = "prices",
            ),
            SyncTable(
                "cart_sessions", mapOf("started_at" to "started_at", "finished_at" to "finished_at"), refs = mapOf("store" to ("store_id" to "stores")),
                defaults = mapOf("started_at" to 0L), bus = "cart",
            ),
            SyncTable(
                "cart_lines", mapOf("barcode" to "barcode", "name" to "name", "unit_price" to "unit_price", "qty" to "qty", "taxable" to "taxable"),
                refs = mapOf("session" to ("session_id" to "cart_sessions")), defaults = mapOf("name" to "Unnamed", "unit_price" to 0.0, "qty" to 1.0, "taxable" to 0), bus = "cart",
            ),
            SyncTable(
                "shopping_list", mapOf("name" to "name", "quantity_text" to "quantity_text", "barcode" to "barcode", "checked" to "checked", "created_at" to "created_at"),
                defaults = mapOf("name" to "Unnamed", "quantity_text" to "", "checked" to 0, "created_at" to 0L), bus = "shopping_list",
            ),
            SyncTable(
                "recipes", mapOf("title" to "title", "body" to "body", "prompt" to "prompt", "created_at" to "created_at"),
                defaults = mapOf("title" to "Recipe", "body" to "", "prompt" to "", "created_at" to 0L), bus = "recipes",
            ),
        )
        val byName: Map<String, SyncTable> = ALL.associateBy { it.name }
    }
}
