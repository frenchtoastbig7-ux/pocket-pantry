package com.sovereignedge.pocketpantry

import com.sovereignedge.pocketpantry.data.Hlc
import com.sovereignedge.pocketpantry.sync.Base32
import com.sovereignedge.pocketpantry.sync.Frames
import com.sovereignedge.pocketpantry.sync.Household
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class SyncLogicTest {
    @Test
    fun base32RoundTripsAndCodesNormalise() {
        val bytes = ByteArray(20).also { SecureRandom().nextBytes(it) }
        val code = Base32.encode(bytes)
        assertEquals(32, code.length)
        assertArrayEquals(bytes, Base32.decode(code))
        val typed = Household.formatCode(code).lowercase().replace("-", " - ")
        assertEquals(code, Household.parse(typed).code)
        assertThrows(IllegalArgumentException::class.java) { Household.parse("ABCD-1234") }
    }

    @Test
    fun hlcIsMonotonicAndAbsorbsRemoteTime() {
        val a = Hlc("device-a")
        val first = a.next()
        val second = a.next()
        assertTrue(second > first)
        val future = Hlc.format(System.currentTimeMillis() + 60_000, 3, "device-b")
        a.observe(future)
        val third = a.next()
        assertTrue(third > future)
        assertTrue(third.endsWith("device-a"))
    }

    @Test
    fun framesAuthenticateHouseholdAndKey() {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val frames = Frames(key, "household-1".toByteArray())
        val sealed = frames.seal("hello".toByteArray())
        assertEquals("hello", String(frames.open(sealed)))
        assertThrows(Exception::class.java) { Frames(key, "household-2".toByteArray()).open(sealed) }
        val other = ByteArray(32).also { SecureRandom().nextBytes(it) }
        assertThrows(Exception::class.java) { Frames(other, "household-1".toByteArray()).open(sealed) }
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { frames.open(sealed) }
    }
}
