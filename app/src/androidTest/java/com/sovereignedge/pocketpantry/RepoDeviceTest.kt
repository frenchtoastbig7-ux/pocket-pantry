package com.sovereignedge.pocketpantry

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sovereignedge.pocketpantry.data.CartLine
import com.sovereignedge.pocketpantry.data.CartSession
import com.sovereignedge.pocketpantry.data.Hlc
import com.sovereignedge.pocketpantry.data.Item
import com.sovereignedge.pocketpantry.data.Op
import com.sovereignedge.pocketpantry.data.Product
import com.sovereignedge.pocketpantry.data.Repo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Repository rules that only show up against a real SQLite database. */
@RunWith(AndroidJUnit4::class)
class RepoDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var repo: Repo

    @Before
    fun fresh() {
        context.deleteDatabase("repo-test.db")
        File(context.filesDir, "repo-test-photos").deleteRecursively()
        repo = Repo(context, "device-r", "repo-test.db", "repo-test-photos")
    }

    private suspend fun pantry() = repo.locations().first().first { it.name == "Pantry" }.id

    @Test
    fun checkoutStocksOnceAndKeepsTheTaxRateItWasFinishedAt() = runBlocking {
        val store = repo.stores().first().single()
        repo.saveStore(store.copy(taxRate = 8.0))
        val sessionId = repo.startSession(store.id)
        // The same product at two prices: two cart lines, one pantry item.
        repo.addCartLine(CartLine(0, sessionId, "111", "Beans", 2.0, 1.0, true))
        repo.addCartLine(CartLine(0, sessionId, "111", "Beans", 3.0, 1.0, true))
        val session = CartSession(sessionId, store.id, 1L, null)

        assertTrue(repo.checkout(session, pantry(), autoSort = false))
        assertEquals(2.0, repo.itemsNow().single().quantity, 0.0)
        // A repeated checkout of the same trip does nothing.
        assertFalse(repo.checkout(session, pantry(), autoSort = false))
        assertEquals(2.0, repo.itemsNow().single().quantity, 0.0)

        repo.saveStore(store.copy(taxRate = 10.0))
        assertEquals(5.40, repo.pastTrips().first().single().total, 0.001)
    }

    @Test
    fun cartQuantityChangesAreAppliedToTheStoredValue() = runBlocking {
        val store = repo.stores().first().single()
        val sessionId = repo.startSession(store.id)
        val line = repo.addCartLine(CartLine(0, sessionId, null, "Bread", 4.0, 1.0, false))
        repo.changeCartLineQty(line, 1.0)
        repo.changeCartLineQty(line, 1.0)
        assertEquals(3.0, repo.tripLines(sessionId).single().qty, 0.0)
        repeat(3) { repo.changeCartLineQty(line, -1.0) }
        assertTrue(repo.tripLines(sessionId).isEmpty())
        // Different tax treatment keeps lines apart even at the same price.
        repo.addCartLine(CartLine(0, sessionId, "222", "Soap", 1.0, 1.0, true))
        repo.addCartLine(CartLine(0, sessionId, "222", "Soap", 1.0, 1.0, false))
        assertEquals(2, repo.tripLines(sessionId).size)
        // A trip in progress means the phone is no longer pristine, so joining must not drop its store.
        assertFalse(repo.isPristine())
        assertFalse(repo.dropDefaultsForJoin())
        assertEquals(1, repo.stores().first().size)
    }

    @Test
    fun editsWriteOnlyWhatTheEditorChanged() = runBlocking {
        repo.upsertItem(Item(0, "Milk", null, null, 4.0, "", "", pantry(), null, "", null, 0, 0))
        val opened = repo.itemsNow().single()
        repo.adjustQuantity(opened.id, -1.0) // changed elsewhere while the editor was open
        repo.upsertItem(opened.copy(name = "Whole milk"), original = opened)
        val saved = repo.itemsNow().single()
        assertEquals("Whole milk", saved.name)
        assertEquals(3.0, saved.quantity, 0.0)
        // A delta passed with the edit lands in the same write.
        repo.upsertItem(saved.copy(notes = "top shelf"), original = saved, quantityDelta = -1.0)
        assertEquals(2.0, repo.itemsNow().single().quantity, 0.0)

        repo.addToShoppingList("Eggs", "12")
        val eggs = repo.shoppingListNow().single()
        repo.setChecked(eggs.id, true)
        repo.patchShoppingItem(eggs.id, name = "Large eggs")
        val after = repo.shoppingListNow().single()
        assertEquals("Large eggs", after.name)
        assertTrue(after.checked)
        assertEquals("12", after.quantityText)
    }

    @Test
    fun lookupResultsDoNotReplaceAProductTheUserNamed() = runBlocking {
        repo.rememberProduct(Product("333", "Mum's jam", null, null, "user"))
        val kept = repo.rememberProductIfAbsent(Product("333", "Fruit spread 340g", "Brand", "340 g", "openfoodfacts"))
        assertEquals("Mum's jam", kept.name)
        assertEquals("Mum's jam", repo.product("333")!!.name)
        assertEquals("Oat milk", repo.rememberProductIfAbsent(Product("444", "Oat milk", null, null, "openfoodfacts")).name)
    }

    @Test
    fun sequenceNumbersAreNotReusedAfterLeaving() = runBlocking {
        repo.addToShoppingList("Eggs")
        repo.addToShoppingList("Milk")
        val before = repo.cursors().getValue("device-r")
        repo.clearSyncState()
        assertTrue(repo.cursors().isEmpty())
        repo.seedOpsForExistingState()
        val reseeded = repo.opsMissingFrom(emptyMap()).filter { it.device == "device-r" }
        assertTrue(reseeded.isNotEmpty())
        assertTrue("seq must continue after $before", reseeded.all { it.seq > before })
        // And across a restart of the app.
        val reopened = Repo(context, "device-r", "repo-test.db", "repo-test-photos")
        reopened.clearSyncState()
        reopened.addToShoppingList("Bread")
        assertTrue(reopened.opsMissingFrom(emptyMap()).all { it.seq > before })
    }

    @Test
    fun aPeerCannotNameAFileOutsideThePhotoDirectory() = runBlocking {
        val jpeg = android.util.Base64.encodeToString(byteArrayOf(1, 2, 3), android.util.Base64.NO_WRAP)
        val hlc = Hlc("device-x")
        val outside = File(context.filesDir, "escaped.jpg").also { it.delete() }
        // The write on its own first, so a delete that follows can't tidy up after a write that got through.
        repo.applyRemote(
            listOf(
                Op(Hlc.newId(), "device-x", 1, hlc.next(), "recipes", "../escaped", Op.SET, JSONObject().put("title", "Evil").put("photo_b64", jpeg)),
                Op(Hlc.newId(), "device-x", 2, hlc.next(), "shopping_list", Hlc.newId(), Op.SET, JSONObject().put("name", "Eggs").put("photo_b64", jpeg)),
            ),
        )
        assertFalse(outside.exists())
        assertTrue(repo.photosDir.listFiles().orEmpty().isEmpty())
        assertEquals("Eggs", repo.shoppingListNow().single().name)
        // Then the delete, against a file that must survive it.
        outside.writeBytes(byteArrayOf(1))
        repo.applyRemote(listOf(Op(Hlc.newId(), "device-x", 3, hlc.next(), "recipes", "../escaped", Op.DELETE, JSONObject())))
        assertTrue(outside.exists())
        outside.delete()
        // A stored photo name that points elsewhere is neither accepted nor followed.
        val recipe = Hlc.newId()
        repo.applyRemote(listOf(Op(Hlc.newId(), "device-x", 4, hlc.next(), "recipes", recipe, Op.SET, JSONObject().put("title", "Toast").put("photo", "../../databases/repo-test.db"))))
        assertEquals(null, repo.recipesNow().single { it.title == "Toast" }.photo)
    }

    @Test
    fun wipeRemovesPhotoFilesAndReseedingCarriesThem() = runBlocking {
        val id = repo.saveRecipe("Toast", "# Toast", "")
        repo.setRecipePhoto(id, byteArrayOf(9, 8, 7))
        repo.clearSyncState()
        repo.seedOpsForExistingState()
        val seeded = repo.opsMissingFrom(emptyMap()).single { it.table == "recipes" }
        assertTrue("the reseeded recipe carries its photo", seeded.fields.has("photo_b64"))
        assertTrue(repo.wipe())
        assertTrue(repo.photosDir.listFiles().orEmpty().isEmpty())
    }
}
