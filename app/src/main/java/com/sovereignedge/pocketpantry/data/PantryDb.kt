package com.sovereignedge.pocketpantry.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Plain SQLite: the schema is small and this avoids an annotation-processing step in the build. */
class PantryDb(context: Context) : SQLiteOpenHelper(context, "pantry.db", null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE locations(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                sort_order INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE items(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                barcode TEXT,
                brand TEXT,
                quantity REAL NOT NULL DEFAULT 1,
                unit TEXT NOT NULL DEFAULT '',
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
                barcode TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                brand TEXT,
                quantity_text TEXT,
                source TEXT NOT NULL,
                updated_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE stores(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                tax_rate REAL NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE prices(
                store_id INTEGER NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
                barcode TEXT NOT NULL,
                price REAL NOT NULL,
                seen_at INTEGER NOT NULL,
                PRIMARY KEY(store_id, barcode))"""
        )
        db.execSQL(
            """CREATE TABLE cart_sessions(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
                started_at INTEGER NOT NULL,
                finished_at INTEGER)"""
        )
        db.execSQL(
            """CREATE TABLE cart_lines(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
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
                name TEXT NOT NULL,
                quantity_text TEXT NOT NULL DEFAULT '',
                barcode TEXT,
                checked INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE recipes(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                prompt TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE chat_messages(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                role TEXT NOT NULL,
                text TEXT NOT NULL,
                created_at INTEGER NOT NULL)"""
        )
        listOf("Pantry", "Counter", "Fridge", "Freezer").forEachIndexed { index, name ->
            db.execSQL("INSERT INTO locations(name, sort_order) VALUES(?, ?)", arrayOf<Any>(name, index))
        }
        db.execSQL("INSERT INTO stores(name, tax_rate) VALUES(?, ?)", arrayOf<Any>("My grocery store", 0.0))
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    companion object {
        const val VERSION = 1
    }
}
