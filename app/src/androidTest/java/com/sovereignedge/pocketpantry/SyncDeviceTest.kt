package com.sovereignedge.pocketpantry

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sovereignedge.pocketpantry.data.Item
import com.sovereignedge.pocketpantry.data.Repo
import com.sovereignedge.pocketpantry.sync.Household
import com.sovereignedge.pocketpantry.sync.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
        a.upsertItem(Item(0, "Milk", "123", null, 4.0, "L", fridge.id, null, "", null, 0, 0))
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
