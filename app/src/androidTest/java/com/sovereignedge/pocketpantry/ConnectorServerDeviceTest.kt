package com.sovereignedge.pocketpantry

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sovereignedge.pocketpantry.bridge.ConnectorServer
import com.sovereignedge.pocketpantry.data.Repo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/** Runs the connector's MCP server on Android's runtime and speaks raw MCP JSON-RPC to it over a socket. */
@RunWith(AndroidJUnit4::class)
class ConnectorServerDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val token = "device-test-token"
    private val port = ServerSocket(0).use { it.localPort }
    private val calls = mutableListOf<String>()
    private lateinit var repo: Repo
    private lateinit var settings: Settings
    private lateinit var server: ConnectorServer

    @Before
    fun start() {
        context.deleteDatabase("connector-test.db")
        repo = Repo(context, "connector-test", "connector-test.db")
        settings = Settings(context, SecretStore(context))
        settings.setConnectorAllowDeletes(false)
        server = ConnectorServer(repo, settings, token, "127.0.0.1", port) { calls += it }
        server.start()
    }

    @After
    fun stop() = server.stop()

    private class Response(val status: Int, val body: String, val session: String?)

    /** Plain HTTP/1.1 over a socket; the app's cleartext policy only governs its own outgoing requests. */
    private fun post(json: String, auth: String?, session: String? = null): Response {
        val payload = json.toByteArray()
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 10_000
            val head = buildString {
                append("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Type: application/json\r\nAccept: application/json, text/event-stream\r\n")
                auth?.let { append("Authorization: $it\r\n") }
                session?.let { append("Mcp-Session-Id: $it\r\n") }
                append("Content-Length: ${payload.size}\r\nConnection: close\r\n\r\n")
            }
            socket.getOutputStream().apply { write(head.toByteArray()); write(payload); flush() }
            val raw = ByteArrayOutputStream()
            try { socket.getInputStream().copyTo(raw) } catch (_: SocketTimeoutException) { }
            val text = raw.toString("UTF-8")
            val split = text.indexOf("\r\n\r\n").takeIf { it >= 0 } ?: text.length
            val headers = text.substring(0, split).split("\r\n")
            val status = headers.first().split(" ").getOrNull(1)?.toIntOrNull() ?: -1
            val sessionHeader = headers.drop(1).firstOrNull { it.startsWith("Mcp-Session-Id:", ignoreCase = true) }?.substringAfter(':')?.trim()
            val chunked = headers.any { it.equals("Transfer-Encoding: chunked", ignoreCase = true) }
            val body = text.substring(minOf(split + 4, text.length)).let { if (chunked) dechunk(it) else it }
            return Response(status, body, sessionHeader)
        }
    }

    private fun dechunk(body: String): String {
        val out = StringBuilder(); var i = 0
        while (i < body.length) {
            val lineEnd = body.indexOf("\r\n", i).takeIf { it >= 0 } ?: break
            val size = body.substring(i, lineEnd).substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) break
            val start = lineEnd + 2
            out.append(body, start, minOf(start + size, body.length)); i = start + size + 2
        }
        return out.toString()
    }

    private fun call(session: String?, id: Int, name: String, args: String) =
        post("""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$name","arguments":$args}}""", "Bearer $token", session).body

    @Test
    fun authListAddAndDeleteGateOnDevice() = runBlocking {
        assertEquals(401, post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", auth = null).status)
        val bearer = "Bearer $token"
        val init = post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"device-test","version":"1"}}}""", bearer)
        assertEquals("initialize: ${init.body}", 200, init.status)
        val session = init.session
        post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", bearer, session)

        val list = post("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", bearer, session).body
        for (tool in listOf("list_inventory", "add_item", "update_item", "remove_item", "get_shopping_list", "add_to_shopping_list", "price_lookup", "save_recipe")) {
            assertTrue("tools/list lacks $tool: $list", list.contains("\"$tool\""))
        }

        assertTrue(call(session, 3, "add_item", """{"name":"Oat milk","location":"Fridge","quantity":2,"size":"1 L","expires_on":"2026-10-30"}""").contains("Added 2"))
        val inventory = call(session, 4, "list_inventory", """{"location":"fridge"}""")
        assertTrue(inventory, inventory.contains("Oat milk") && inventory.contains("1 L"))
        val id = repo.itemsNow().single().id
        assertTrue(call(session, 5, "update_item", """{"id":$id,"quantity_delta":-1}""").contains("quantity 1"))
        assertEquals(1.0, repo.itemsNow().single().quantity, 1e-9)

        val denied = call(session, 6, "remove_item", """{"id":$id}""")
        assertTrue("delete must be gated: $denied", denied.contains("hasn't allowed"))
        settings.setConnectorAllowDeletes(true)
        assertTrue(call(session, 7, "remove_item", """{"id":$id}""").contains("Removed"))
        assertTrue(repo.itemsNow().isEmpty())

        assertTrue(call(session, 8, "add_to_shopping_list", """{"items":[{"name":"Eggs","quantity":"12"},{"name":"Butter"}]}""").contains("Added 2"))
        assertEquals(listOf("Butter", "Eggs"), repo.shoppingListNow().map { it.name }.sorted())
        assertTrue(calls.containsAll(listOf("add_item", "list_inventory", "update_item", "remove_item", "add_to_shopping_list")))
    }
}
