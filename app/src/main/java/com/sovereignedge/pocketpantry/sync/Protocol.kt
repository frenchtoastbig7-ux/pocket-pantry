package com.sovereignedge.pocketpantry.sync

import com.sovereignedge.pocketpantry.data.Op
import com.sovereignedge.pocketpantry.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket

/**
 * One sync conversation. Both ends run the same steps, so it doesn't matter who connected to whom:
 *
 * 1. hello: household id, device id and name, and the addresses this phone can be reached at.
 * 2. have: the highest op seq we hold from each device.
 * 3. ops: everything the other side lacks, oldest first, while reading what it sends us; then done.
 *
 * After the exchange both phones hold the same op log and therefore the same state.
 */
class Protocol(private val repo: Repo, private val household: Household, private val localAddrs: () -> List<String>) {

    data class Result(val peerDevice: String, val peerName: String, val peerAddrs: List<String>, val received: Int, val sent: Int)

    class HouseholdMismatch : IOException("The other phone is in a different household")

    suspend fun exchange(socket: Socket): Result = withContext(Dispatchers.IO) {
        val key = household.encryptionKey() ?: throw IOException("Not in a household")
        val householdId = household.householdId()!!
        val frames = Frames(key, householdId.toByteArray())
        socket.soTimeout = 30_000
        socket.tcpNoDelay = true
        val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))

        frames.write(
            out,
            JSONObject().put("t", "hello").put("v", 1).put("hh", householdId).put("dev", household.deviceId)
                .put("name", household.current.deviceName).put("addrs", JSONArray(localAddrs())).toString(),
        )
        val hello = runCatching { JSONObject(frames.read(input)) }.getOrElse { throw HouseholdMismatch() }
        if (hello.optString("t") != "hello" || hello.optString("hh") != householdId) throw HouseholdMismatch()
        val peerDevice = hello.getString("dev")
        val peerName = hello.optString("name", "Phone")
        val peerAddrs = hello.optJSONArray("addrs")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
        if (peerDevice == household.deviceId) throw IOException("Connected to ourselves")

        val mine = repo.cursors()
        frames.write(out, JSONObject().put("t", "have").put("cursors", JSONObject(mine.mapValues { it.value as Any })).toString())
        val have = JSONObject(frames.read(input))
        if (have.optString("t") != "have") throw IOException("Expected have")
        val theirs = have.getJSONObject("cursors").let { o -> o.keys().asSequence().associateWith { o.getLong(it) } }

        var received = 0
        var sent = 0
        coroutineScope {
            val reader = launch {
                while (true) {
                    val msg = JSONObject(frames.read(input))
                    when (msg.optString("t")) {
                        "ops" -> {
                            val arr = msg.getJSONArray("ops")
                            val ops = (0 until arr.length()).map { Op.fromJson(arr.getJSONObject(it)) }
                            repo.applyRemote(ops)
                            received += ops.size
                        }
                        "done" -> break
                        else -> throw IOException("Unexpected message ${msg.optString("t")}")
                    }
                }
            }
            var after = ""
            while (true) {
                val batch = repo.opsMissingFrom(theirs, BATCH, after)
                if (batch.isEmpty()) break
                frames.write(out, JSONObject().put("t", "ops").put("ops", JSONArray(batch.map { it.toJson() })).toString())
                sent += batch.size
                after = batch.last().hlc
            }
            frames.write(out, JSONObject().put("t", "done").toString())
            reader.join()
        }
        Result(peerDevice, peerName, peerAddrs, received, sent)
    }

    private companion object {
        const val BATCH = 400
    }
}
