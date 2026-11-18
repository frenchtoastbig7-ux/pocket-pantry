package com.sovereignedge.pocketpantry.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

/**
 * Plain SQLite: the schema is small and this avoids an annotation-processing step in the build.
 * Every synced table carries a `uuid` so rows can be referred to across devices; the integer `id` stays local.
 */
class PantryDb(context: Context, name: String = "pantry.db") : SQLiteOpenHelper(context, name, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE locations(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                sort_order INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE items(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                barcode TEXT,
                brand TEXT,
                quantity REAL NOT NULL DEFAULT 1,
                unit TEXT NOT NULL DEFAULT '',
                size TEXT NOT NULL DEFAULT '',
                location_id INTEGER NOT NULL REFERENCES locations(id) ON DELETE CASCADE,
                expires_on INTEGER,
                notes TEXT NOT NULL DEFAULT '',
                low_threshold REAL,
                added_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX items_location ON items(location_id)")
        db.execSQL("CREATE INDEX items_barcode ON items(barcode)")
        db.execSQL(
            """CREATE TABLE products(
                uuid TEXT NOT NULL UNIQUE,
                barcode TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                brand TEXT,
                quantity_text TEXT,
                source TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                category TEXT)"""
        )
        db.execSQL(
            """CREATE TABLE stores(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                tax_rate REAL NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE prices(
                uuid TEXT NOT NULL UNIQUE,
                store_id INTEGER NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
                barcode TEXT NOT NULL,
                price REAL NOT NULL,
                seen_at INTEGER NOT NULL,
                PRIMARY KEY(store_id, barcode))"""
        )
        db.execSQL(
            """CREATE TABLE cart_sessions(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL UNIQUE,
                store_id INTEGER NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
                started_at INTEGER NOT NULL,
                finished_at INTEGER)"""
        )
        db.execSQL(
            """CREATE TABLE cart_lines(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL UNIQUE,
                session_id INTEGER NOT NULL REFERENCES cart_sessions(id) ON DELETE CASCADE,
                barcode TEXT,
                name TEXT NOT NULL,
                unit_price REAL NOT NULL DEFAULT 0,
                qty REAL NOT NULL DEFAULT 1,
                taxable INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL("CREATE INDEX cart_lines_session ON cart_lines(session_id)")
        db.execSQL(
            """CREATE TABLE shopping_list(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                quantity_text TEXT NOT NULL DEFAULT '',
                barcode TEXT,
                checked INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE recipes(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL UNIQUE,
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                prompt TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                rating INTEGER NOT NULL DEFAULT 0,
                photo TEXT)"""
        )
        db.execSQL(
            """CREATE TABLE chat_messages(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                role TEXT NOT NULL,
                text TEXT NOT NULL,
                created_at INTEGER NOT NULL)"""
        )
        createSyncTables(db)
        seedDefaults(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Give every existing row a uuid so it can take part in household sync.
            for (table in listOf("locations", "items", "stores", "cart_sessions", "cart_lines", "shopping_list", "recipes")) {
                db.execSQL("ALTER TABLE $table ADD COLUMN uuid TEXT")
                db.rawQuery("SELECT id FROM $table", null).use { c ->
                    while (c.moveToNext()) db.execSQL("UPDATE $table SET uuid=? WHERE id=?", arrayOf<Any>(UUID.randomUUID().toString(), c.getLong(0)))
                }
                db.execSQL("CREATE UNIQUE INDEX ${table}_uuid ON $table(uuid)")
            }
            db.execSQL("ALTER TABLE products ADD COLUMN uuid TEXT")
            db.execSQL("UPDATE products SET uuid = 'product:' || barcode")
            db.execSQL("CREATE UNIQUE INDEX products_uuid ON products(uuid)")
            db.execSQL("ALTER TABLE prices ADD COLUMN uuid TEXT")
            db.execSQL("UPDATE prices SET uuid = 'price:' || (SELECT uuid FROM stores WHERE stores.id = prices.store_id) || ':' || barcode")
            db.execSQL("CREATE UNIQUE INDEX prices_uuid ON prices(uuid)")
            createSyncTables(db)
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE items ADD COLUMN size TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE recipes ADD COLUMN rating INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE recipes ADD COLUMN photo TEXT")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE products ADD COLUMN category TEXT")
        }
    }

    private fun createSyncTables(db: SQLiteDatabase) {
        // Every op ever seen, from any device. `seq` is the origin device's own counter.
        db.execSQL(
            """CREATE TABLE ops(
                local_seq INTEGER PRIMARY KEY AUTOINCREMENT,
                op_id TEXT NOT NULL UNIQUE,
                device TEXT NOT NULL,
                seq INTEGER NOT NULL,
                hlc TEXT NOT NULL,
                table_name TEXT NOT NULL,
                row_uuid TEXT NOT NULL,
                kind TEXT NOT NULL,
                payload TEXT NOT NULL)"""
        )
        db.execSQL("CREATE INDEX ops_device_seq ON ops(device, seq)")
        db.execSQL("CREATE INDEX ops_row ON ops(table_name, row_uuid)")
        // Per-field last-writer-wins timestamps.
        db.execSQL("CREATE TABLE clocks(table_name TEXT NOT NULL, row_uuid TEXT NOT NULL, field TEXT NOT NULL, hlc TEXT NOT NULL, PRIMARY KEY(table_name, row_uuid, field))")
        db.execSQL("CREATE TABLE tombstones(table_name TEXT NOT NULL, row_uuid TEXT NOT NULL, hlc TEXT NOT NULL, PRIMARY KEY(table_name, row_uuid))")
        // Other household devices: how to reach them and when we last did.
        db.execSQL("CREATE TABLE peers(device_id TEXT PRIMARY KEY, name TEXT NOT NULL, addrs TEXT NOT NULL, last_seen INTEGER NOT NULL, last_sync INTEGER)")
    }

    private fun seedDefaults(db: SQLiteDatabase) {
        listOf("Pantry", "Counter", "Fridge", "Freezer").forEachIndexed { index, name ->
            db.execSQL("INSERT INTO locations(uuid, name, sort_order) VALUES(?, ?, ?)", arrayOf<Any>(UUID.randomUUID().toString(), name, index))
        }
        db.execSQL("INSERT INTO stores(uuid, name, tax_rate) VALUES(?, ?, ?)", arrayOf<Any>(UUID.randomUUID().toString(), "My grocery store", 0.0))
    }

    companion object {
        const val VERSION = 5
        val DEFAULT_LOCATIONS = listOf("Pantry", "Counter", "Fridge", "Freezer")
        const val DEFAULT_STORE = "My grocery store"
    }
}
