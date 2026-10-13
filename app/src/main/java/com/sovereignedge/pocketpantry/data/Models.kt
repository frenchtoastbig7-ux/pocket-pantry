package com.sovereignedge.pocketpantry.data

import java.time.LocalDate

/** A storage location in the household: Pantry, Fridge, Freezer, Counter... */
data class Location(val id: Long, val name: String, val sortOrder: Int)

/** Something in the house. [quantity] is a count or a measure in [unit]; [expiresOn] is an epoch day. */
data class Item(
    val id: Long,
    val name: String,
    val barcode: String?,
    val brand: String?,
    val quantity: Double,
    val unit: String,
    val locationId: Long,
    val expiresOn: Long?,
    val notes: String,
    val lowThreshold: Double?,
    val addedAt: Long,
    val updatedAt: Long,
) {
    val expiryDate: LocalDate? get() = expiresOn?.let(LocalDate::ofEpochDay)
    val isLow: Boolean get() = lowThreshold != null && quantity <= lowThreshold
    fun daysUntilExpiry(today: LocalDate = LocalDate.now()): Long? = expiryDate?.toEpochDay()?.minus(today.toEpochDay())
}

/** What a barcode resolved to, cached locally so the second scan is instant and offline. */
data class Product(val barcode: String, val name: String, val brand: String?, val quantityText: String?, val source: String)

/** A grocery store the user shops at. [taxRate] is a percentage applied to taxable lines. */
data class Store(val id: Long, val name: String, val taxRate: Double)

data class CartSession(val id: Long, val storeId: Long, val startedAt: Long, val finishedAt: Long?)

data class CartLine(
    val id: Long,
    val sessionId: Long,
    val barcode: String?,
    val name: String,
    val unitPrice: Double,
    val qty: Double,
    val taxable: Boolean,
) {
    val lineTotal: Double get() = unitPrice * qty
}

data class CartTotals(val subtotal: Double, val tax: Double, val count: Double) {
    val total: Double get() = subtotal + tax

    companion object {
        fun of(lines: List<CartLine>, taxRatePercent: Double): CartTotals {
            val subtotal = lines.sumOf { it.lineTotal }
            val taxable = lines.filter { it.taxable }.sumOf { it.lineTotal }
            return CartTotals(subtotal, taxable * taxRatePercent / 100.0, lines.sumOf { it.qty })
        }
    }
}

data class TripSummary(val session: CartSession, val storeName: String, val total: Double, val lineCount: Int)

data class ShoppingItem(
    val id: Long,
    val name: String,
    val quantityText: String,
    val barcode: String?,
    val checked: Boolean,
    val createdAt: Long,
)

data class Recipe(val id: Long, val title: String, val body: String, val createdAt: Long, val prompt: String)

data class ChatMessage(val id: Long, val role: String, val text: String, val createdAt: Long) {
    val isUser: Boolean get() = role == "user"
}

/** Shopping-list candidates Claude extracted from a recipe. */
data class IngredientSuggestion(val name: String, val quantity: String)
