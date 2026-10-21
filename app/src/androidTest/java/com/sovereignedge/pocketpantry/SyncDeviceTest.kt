package com.sovereignedge.pocketpantry

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sovereignedge.pocketpantry.data.Item
import com.sovereignedge.pocketpantry.data.Repo
import com.sovereignedge.pocketpantry.sync.Household
import com.sovereignedge.pocketpantry.sync.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.Socket

/** Two "phones" in one process: merge semantics through the op log, then the real encrypted socket protocol. */
@RunWith(AndroidJUnit4::class)
class SyncDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var a: Repo
    private lateinit var b: Repo

    @Before
    fun fresh() {
        context.deleteDatabase("sync-a.db")
        context.deleteDatabase("sync-b.db")
        a = Repo(context, "device-a", "sync-a.db")
        b = Repo(context, "device-b", "sync-b.db")
    }

    private suspend fun exchange(from: Repo, to: Repo) {
        val cursors = to.cursors()
        var after = ""
        while (true) {
            val batch = from.opsMissingFrom(cursors, 50, after)
            if (batch.isEmpty()) break
            to.applyRemote(batch)
            after = batch.last().hlc
        }
    }

    private suspend fun both() {
        exchange(a, b)
        exchange(b, a)
    }

    @Test
    fun itemsLocationsAndConcurrentEditsConverge() = runBlocking {
        // B joins A's household: drop its defaults, A seeds its existing rows.
        b.dropDefaultsForJoin()
        a.seedOpsForExistingState()
        val fridge = a.locations().first().first { it.name == "Fridge" }
        a.upsertItem(Item(0, "Milk", "123", null, 4.0, "L", "", fridge.id, null, "", null, 0, 0))
        both()
        assertEquals(listOf("Counter", "Freezer", "Fridge", "Pantry"), b.locations().first().map { it.name }.sorted())
        val milkB = b.itemsNow().single()
        assertEquals("Milk", milkB.name)
        assertEquals(4.0, milkB.quantity, 1e-9)

        // Both use some milk at the same time: deltas add up instead of one overwriting the other.
        val milkA = a.itemsNow().single()
        a.adjustQuantity(milkA.id, -1.0)
        b.adjustQuantity(milkB.id, -1.0)
        both()
        assertEquals(2.0, a.itemsNow().single().quantity, 1e-9)
        assertEquals(2.0, b.itemsNow().single().quantity, 1e-9)

        // Concurrent rename: the later write wins on both sides, and the quantity is untouched.
        a.upsertItem(a.itemsNow().single().copy(name = "Whole milk"))
        Thread.sleep(5)
        b.upsertItem(b.itemsNow().single().copy(brand = "Dairyco"))
        both()
        val finalA = a.itemsNow().single()
        val finalB = b.itemsNow().single()
        assertEquals(finalA.name, finalB.name)
        assertEquals("Dairyco", finalB.brand)
        assertEquals("Whole milk", finalA.name)

        // Delete on A while B is offline and keeps editing: the newer edit wins consistently on both.
        a.deleteItem(finalA.id)
        Thread.sleep(5)
        b.adjustQuantity(finalB.id, 1.0)
        both()
        assertEquals(a.itemsNow().map { it.name }, b.itemsNow().map { it.name })

        // Shopping list and list ticks replicate; re-applying the same ops is a no-op.
        b.addToShoppingList("Eggs", "12")
        both()
        val eggsA = a.shoppingListNow().single()
        a.setChecked(eggsA.id, true)
        both()
        both()
        assertTrue(b.shoppingListNow().single().checked)
        assertEquals(a.opCount(), b.opCount())
    }

    @Test
    fun duplicateLocationsMergeTheSameWayOnBothPhones() = runBlocking {
        // Both phones had their own defaults and data before pairing.
        a.seedOpsForExistingState()
        b.seedOpsForExistingState()
        val fridgeA = a.locations().first().first { it.name == "Fridge" }
        val fridgeB = b.locations().first().first { it.name == "Fridge" }
        a.upsertItem(Item(0, "Milk", null, null, 1.0, "1 Packet", "", fridgeA.id, null, "", null, 0, 0))
        b.upsertItem(Item(0, "Butter", null, null, 2.0, "", "250 g", fridgeB.id, null, "", null, 0, 0))
        both()
        assertEquals(8, a.locations().first().size) // 4 + 4 duplicates
        val mergedA = a.mergeDuplicates()
        val mergedB = b.mergeDuplicates()
        assertEquals(5, mergedA + mergedB - 5) // 4 locations + 1 store merged on each side, counted once per phone
        both()
        assertEquals(listOf("Counter", "Freezer", "Fridge", "Pantry"), a.locations().first().map { it.name }.sorted())
        assertEquals(listOf("Counter", "Freezer", "Fridge", "Pantry"), b.locations().first().map { it.name }.sorted())
        assertEquals(1, a.stores().first().size)
        val fridge = a.locations().first().first { it.name == "Fridge" }
        assertEquals(listOf("Butter", "Milk"), a.itemsNow().filter { it.locationId == fridge.id }.map { it.name }.sorted())
        assertEquals(a.itemsNow().map { it.name to it.size }.sortedBy { it.first }, b.itemsNow().map { it.name to it.size }.sortedBy { it.first })
        // Old-style "unit holds the size" data is cleaned up, and the cleanup replicates.
        a.migrateUnitsToSizes()
        both()
        val milkB = b.itemsNow().first { it.name == "Milk" }
        assertEquals("1 Packet", milkB.size)
        assertEquals("", milkB.unit)
    }

    @Test
    fun recipeRatingAndPhotoReplicate() = runBlocking {
        a.seedOpsForExistingState(); b.dropDefaultsForJoin()
        val id = a.saveRecipe("Toast", "# Toast", "", rating = 4)
        val jpeg = android.graphics.Bitmap.createBitmap(64, 48, android.graphics.Bitmap.Config.ARGB_8888).let { bmp ->
            java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        }
        a.setRecipePhoto(id, jpeg)
        both()
        val onB = b.recipesNow().single()
        assertEquals(4, onB.rating)
        val file = b.photoFile(onB)
        assertTrue("photo file must exist on B", file != null && file.length() == jpeg.size.toLong())
        b.setRecipeRating(onB.id, 5)
        b.setRecipePhoto(onB.id, null)
        both()
        val onA = a.recipesNow().single()
        assertEquals(5, onA.rating)
        assertNull(a.photoFile(onA))
    }

    @Test
    fun stockingSortsLinesAndRemembersWhereBarcodesLive() = runBlocking {
        val locs = a.locations().first().associateBy { it.name }
        // A trip with a frozen item, a dairy item, bread, and a mystery item; the chosen fallback is Pantry.
        val lines = listOf(
            com.sovereignedge.pocketpantry.data.CartLine(0, 1, "1", "Birds Eye Frozen Peas", 2.0, 1.0, false),
            com.sovereignedge.pocketpantry.data.CartLine(0, 1, "2", "Fage Greek Yogurt", 3.0, 2.0, false),
            com.sovereignedge.pocketpantry.data.CartLine(0, 1, null, "Sourdough loaf", 4.0, 1.0, false),
            com.sovereignedge.pocketpantry.data.CartLine(0, 1, "4", "Zorblax", 5.0, 1.0, false),
        )
        a.stockFromCart(lines, locs.getValue("Pantry").id)
        val byName = a.itemsNow().associateBy { it.name }
        assertEquals(locs.getValue("Freezer").id, byName.getValue("Birds Eye Frozen Peas").locationId)
        assertEquals(locs.getValue("Fridge").id, byName.getValue("Fage Greek Yogurt").locationId)
        assertEquals(locs.getValue("Counter").id, byName.getValue("Sourdough loaf").locationId)
        assertEquals(locs.getValue("Pantry").id, byName.getValue("Zorblax").locationId)
        // The household moved the mystery item to the Counter; the same barcode now suggests the Counter.
        a.upsertItem(byName.getValue("Zorblax").copy(locationId = locs.getValue("Counter").id))
        assertEquals(locs.getValue("Counter").id, a.suggestLocation("Zorblax", null, "4"))
        // Re-buying tops up the existing item wherever it is instead of creating a second one.
        a.stockFromCart(listOf(lines[3].copy(qty = 2.0)), locs.getValue("Pantry").id)
        val zorblax = a.itemsNow().filter { it.name == "Zorblax" }
        assertEquals(1, zorblax.size)
        assertEquals(3.0, zorblax.single().quantity, 1e-9)
        assertEquals(locs.getValue("Counter").id, zorblax.single().locationId)
        // The sort plan proposes moving a misplaced item and nothing else.
        a.upsertItem(byName.getValue("Fage Greek Yogurt").copy(locationId = locs.getValue("Pantry").id))
        val plan = a.autoSortPlan()
        assertEquals(listOf("Fage Greek Yogurt"), plan.map { it.item.name })
        assertEquals("Fridge", plan.single().to.name)
        a.applyMoves(plan)
        assertEquals(locs.getValue("Fridge").id, a.itemsNow().first { it.name == "Fage Greek Yogurt" }.locationId)
    }

    @Test
    fun beaconDiscoveryHearsAHouseholdPhone() = runBlocking {
        val found = java.util.concurrent.CompletableFuture<Triple<String, Int, String>>()
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
        val householdId = "0123456789abcdef0123456789abcdef"
        val discovery = com.sovereignedge.pocketpantry.sync.LanDiscovery(context, householdId, "device-a", scope) { host, port, dev -> found.complete(Triple(host, port, dev)) }
        discovery.start()
        try {
            Thread.sleep(500)
            java.net.DatagramSocket().use { socket ->
                val wrong = "pp1 ffffffffffffffff device-x 47632".toByteArray()
                socket.send(java.net.DatagramPacket(wrong, wrong.size, java.net.InetAddress.getByName("127.0.0.1"), 47633))
                val self = "pp1 ${householdId.take(16)} device-a 47632".toByteArray()
                socket.send(java.net.DatagramPacket(self, self.size, java.net.InetAddress.getByName("127.0.0.1"), 47633))
                val right = "pp1 ${householdId.take(16)} device-b 47632".toByteArray()
                socket.send(java.net.DatagramPacket(right, right.size, java.net.InetAddress.getByName("127.0.0.1"), 47633))
            }
            val hit = found.get(10, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals("device-b", hit.third)
            assertEquals(47632, hit.second)
            assertEquals("127.0.0.1", hit.first)
        } finally {
            discovery.stop()
            scope.cancel()
        }
    }

    @Test
    fun protocolOverSocketSyncsBothWays() = runBlocking {
        val secrets = SecretStore(context)
        val hhA = Household(context, secrets, "test-household-a", "test-secret-a")
        val hhB = Household(context, secrets, "test-household-b", "test-secret-b")
        val code = hhA.create()
        hhB.join(code)
        assertEquals(hhA.householdId(), hhB.householdId())
        a.seedOpsForExistingState()
        b.dropDefaultsForJoin()
        a.saveRecipe("Toast", "# Toast\n- bread", "")
        b.addToShoppingList("Butter")

        val protoA = Protocol(a, hhA) { listOf("127.0.0.1:0") }
        val protoB = Protocol(b, hhB) { listOf("127.0.0.1:0") }
        ServerSocket(0).use { server ->
            val serverSide = async(Dispatchers.IO) { server.accept().use { protoA.exchange(it) } }
            val clientSide = async(Dispatchers.IO) { Socket("127.0.0.1", server.localPort).use { protoB.exchange(it) } }
            val ra = serverSide.await()
            val rb = clientSide.await()
            assertEquals(hhB.deviceId, ra.peerDevice)
            assertEquals(hhA.deviceId, rb.peerDevice)
            assertTrue(ra.sent > 0 && rb.sent > 0)
        }
        assertEquals("Toast", b.recipes().first().single().title)
        assertEquals("Butter", a.shoppingListNow().single().name)
        assertEquals(a.opCount(), b.opCount())

        // A different household can't even complete the hello.
        val hhC = Household(context, secrets, "test-household-c", "test-secret-c").also { it.leave() }
        assertNull(hhC.householdId())
        hhC.create()
        assertNotNull(hhC.householdId())
        val protoC = Protocol(b, hhC) { emptyList() }
        ServerSocket(0).use { server ->
            val serverSide = async(Dispatchers.IO) { runCatching { server.accept().use { protoA.exchange(it) } } }
            val clientSide = async(Dispatchers.IO) { runCatching { Socket("127.0.0.1", server.localPort).use { protoC.exchange(it) } } }
            assertTrue(serverSide.await().isFailure)
            assertTrue(clientSide.await().isFailure)
        }
        hhA.leave(); hhB.leave(); hhC.leave()
    }
}
