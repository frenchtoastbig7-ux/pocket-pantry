package com.sovereignedge.pocketpantry.barcode

import com.sovereignedge.pocketpantry.data.Product
import com.sovereignedge.pocketpantry.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Resolves a barcode to a product name: the local cache first, then Open Food Facts (free, no key, no account).
 * Returns null when nothing is known so the UI can ask the user to name the item once; that answer is cached.
 */
class ProductLookup(private val repo: Repo) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun lookup(barcode: String): Product? {
        repo.product(barcode)?.let { return it }
        val remote = fetchOpenFoodFacts(barcode) ?: return null
        repo.rememberProduct(remote)
        return remote
    }

    private suspend fun fetchOpenFoodFacts(barcode: String): Product? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://world.openfoodfacts.org/api/v2/product/$barcode.json?fields=product_name,brands,quantity")
                .header("User-Agent", "PocketPantry/0.1 (Android; https://github.com/frenchtoastbig7-ux)")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body?.string() ?: return@use null)
                if (json.optInt("status", 0) != 1) return@use null
                val product = json.optJSONObject("product") ?: return@use null
                val name = product.optString("product_name").trim().ifBlank { return@use null }
                Product(
                    barcode = barcode,
                    name = name,
                    brand = product.optString("brands").trim().ifBlank { null }?.substringBefore(","),
                    quantityText = product.optString("quantity").trim().ifBlank { null },
                    source = "openfoodfacts",
                )
            }
        }.getOrNull()
    }
}
