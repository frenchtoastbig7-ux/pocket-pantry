package com.sovereignedge.pocketpantry.barcode

import android.util.Log
import com.sovereignedge.pocketpantry.data.Product
import com.sovereignedge.pocketpantry.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Resolves a barcode to a product name: the household's own cache first (anything anyone named by hand or looked up
 * before), then Open Food Facts (free, crowd-sourced, strong on packaged brands), then UPCitemdb (US retail
 * coverage including store brands such as Signature Select or Kirkland; keyless trial tier, about 100 lookups a day).
 * Returns null when nothing is known so the UI can ask the user to name the item once; that answer is cached and
 * synced to the household.
 */
class ProductLookup(private val repo: Repo) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun lookup(barcode: String): Product? {
        repo.product(barcode)?.let { cached ->
            // Products cached before sizes were parsed from titles: fill the size in once and share it.
            if (cached.quantityText == null) SizeParser.parse(cached.name)?.let { size ->
                return cached.copy(quantityText = size).also { repo.rememberProduct(it) }
            }
            return cached
        }
        val remote = fetchOpenFoodFacts(barcode) ?: fetchUpcItemDb(barcode) ?: return null
        repo.rememberProduct(remote)
        return remote
    }

    private fun get(url: String): JSONObject? = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.i(TAG, "${url.substringBefore('?')} -> HTTP ${response.code}")
                return@use null
            }
            JSONObject(response.body?.string() ?: return@use null)
        }
    }.onFailure { Log.i(TAG, "lookup failed: $it") }.getOrNull()

    private suspend fun fetchOpenFoodFacts(barcode: String): Product? = withContext(Dispatchers.IO) {
        val json = get("https://world.openfoodfacts.org/api/v2/product/$barcode.json?fields=product_name,brands,quantity") ?: return@withContext null
        if (json.optInt("status", 0) != 1) return@withContext null
        val product = json.optJSONObject("product") ?: return@withContext null
        val name = product.optString("product_name").trim().ifBlank { return@withContext null }
        Product(
            barcode = barcode,
            name = name,
            brand = product.optString("brands").trim().ifBlank { null }?.substringBefore(","),
            quantityText = product.optString("quantity").trim().ifBlank { null }?.let { SizeParser.parse(it) ?: it } ?: SizeParser.parse(name),
            source = "openfoodfacts",
        )
    }

    /** UPCitemdb wants 12- or 13-digit codes; EAN-8 and others aren't in its catalogue. */
    private suspend fun fetchUpcItemDb(barcode: String): Product? = withContext(Dispatchers.IO) {
        if (barcode.length !in 12..13 || !barcode.all { it.isDigit() }) return@withContext null
        val json = get("https://api.upcitemdb.com/prod/trial/lookup?upc=$barcode") ?: return@withContext null
        if (json.optString("code") != "OK") return@withContext null
        val item = json.optJSONArray("items")?.optJSONObject(0) ?: return@withContext null
        val title = item.optString("title").trim().ifBlank { return@withContext null }
        Product(
            barcode = barcode,
            name = title,
            brand = item.optString("brand").trim().ifBlank { null },
            // UPCitemdb's size field is often junk ("9-11" for a case of water); trust it only when it reads as a size,
            // otherwise take the size from the title, which usually ends in one.
            quantityText = item.optString("size").trim().takeIf { SizeParser.looksLikeSize(it) }?.let { SizeParser.parse(it) } ?: SizeParser.parse(title),
            source = "upcitemdb",
        )
    }

    private companion object {
        const val TAG = "PantryScan"
        const val USER_AGENT = "PocketPantry/0.2 (Android; https://github.com/frenchtoastbig7-ux/pocket-pantry)"
    }
}
