package com.sovereignedge.pocketpantry

import com.sovereignedge.pocketpantry.data.Hlc
import com.sovereignedge.pocketpantry.sync.Base32
import com.sovereignedge.pocketpantry.sync.Frames
import com.sovereignedge.pocketpantry.sync.Household
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // AEADBadTagException specifically: the frame must be refused at authentication, not fail for some other reason.
        val badTag = javax.crypto.AEADBadTagException::class.java
        assertThrows(badTag) { Frames(key, "household-2".toByteArray()).open(sealed) }
        val other = ByteArray(32).also { SecureRandom().nextBytes(it) }
        assertThrows(badTag) { Frames(other, "household-1".toByteArray()).open(sealed) }
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        assertThrows(badTag) { frames.open(sealed) }
    }

    @Test
    fun clockStaysOrderedPastTheCounterLimit() {
        val a = Hlc("device-a")
        // A peer far in the future pins the wall-clock part, so every tick from here on has to come from the counter.
        a.observe(Hlc.format(System.currentTimeMillis() + 3_600_000, 0xfff0, "device-b"))
        var previous = a.next()
        repeat(64) {
            val next = a.next()
            assertTrue("$next must sort after $previous", next > previous)
            previous = next
        }
    }

    @Test
    fun clockTextIsAsciiInEveryLocale() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-SA-u-nu-arab"))
            assertEquals("0000000001234-000a-dev", Hlc.format(1234, 10, "dev"))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun addressesAreJudgedWithoutResolvingOrThrowing() {
        val a = com.sovereignedge.pocketpantry.sync.Addresses
        assertTrue(a.isTailscaleAddr("100.64.0.5:47632"))
        assertTrue(a.isTailscaleAddr("[fd7a:115c:a1e0::1]:47632"))
        assertFalse(a.isTailscaleAddr("192.168.1.20"))
        assertFalse(a.isTailscaleAddr("no-such-host.invalid:47632"))
        assertFalse(a.isTailscaleAddr("192.168.1.20:65536"))
        assertTrue(a.isValid("192.168.1.20"))
        assertTrue(a.isValid("phone.tailnet.ts.net:47632"))
        assertFalse(a.isValid("192.168.1.20:65536"))
        assertFalse(a.isValid("192.168.1.20:abc"))
        assertFalse(a.isValid("  "))
    }
}
