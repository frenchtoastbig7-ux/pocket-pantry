package com.sovereignedge.pocketpantry.sync

import android.content.Context
import android.os.Build
import com.sovereignedge.pocketpantry.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

data class HouseholdState(val joined: Boolean, val code: String?, val deviceId: String, val deviceName: String, val backgroundSync: Boolean)

/**
 * The household is a shared secret and nothing else: no account, no server. Every device that knows the code derives
 * the same household id (public, used to find each other) and the same encryption key (used to talk). The secret is
 * kept in the Keystore-encrypted [SecretStore]; the rest of the state is ordinary preferences.
 */
class Household(context: Context, private val secrets: SecretStore, prefsName: String = "household", private val secretName: String = "household_secret") {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    val deviceId: String = prefs.getString(K_DEVICE, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(K_DEVICE, it).apply() }

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<HouseholdState> = _state
    val current: HouseholdState get() = _state.value

    private fun secret(): ByteArray? = secrets.secret(secretName)?.let { runCatching { Base32.decode(it) }.getOrNull() }

    val isJoined: Boolean get() = secrets.secret(secretName) != null

    /** 20 random bytes as a 32-character code, shown in groups of four. */
    fun create(): String {
        val bytes = ByteArray(SECRET_BYTES).also { SecureRandom().nextBytes(it) }
        secrets.putSecret(secretName, Base32.encode(bytes))
        refresh()
        return formatCode(Base32.encode(bytes))
    }

    /** Accepts the typed code (with or without dashes/spaces) or the QR payload. Throws on a malformed code. */
    fun join(input: String): JoinInfo {
        val info = parse(input)
        secrets.putSecret(secretName, info.code)
        refresh()
        return info
    }

    fun leave() {
        secrets.removeSecret(secretName)
        refresh()
    }

    fun setDeviceName(name: String) {
        prefs.edit().putString(K_NAME, name.trim().ifBlank { defaultName() }).apply()
        refresh()
    }

    fun setBackgroundSync(on: Boolean) {
        prefs.edit().putBoolean(K_BACKGROUND, on).apply()
        refresh()
    }

    /** Public identifier derived from the secret; safe to advertise on the network. */
    fun householdId(): String? = secret()?.let { hex(sha256("pocketpantry-id".toByteArray() + it)).take(32) }

    /** 256-bit transport key derived from the secret. */
    fun encryptionKey(): ByteArray? = secret()?.let { sha256("pocketpantry-enc".toByteArray() + it) }

    /** What the QR code carries: the code plus how to reach this phone right now, so the joiner can sync immediately. */
    fun qrPayload(addrs: List<String>): String? {
        val code = secrets.secret(secretName) ?: return null
        return "pocketpantry://join?c=$code&d=$deviceId&n=${android.net.Uri.encode(current.deviceName)}&a=${android.net.Uri.encode(addrs.joinToString(","))}"
    }

    data class JoinInfo(val code: String, val peerDevice: String?, val peerName: String?, val peerAddrs: List<String>)

    private fun refresh() {
        _state.value = snapshot()
    }

    private fun snapshot() = HouseholdState(
        joined = isJoined,
        code = secrets.secret(secretName)?.let(::formatCode),
        deviceId = deviceId,
        deviceName = prefs.getString(K_NAME, null) ?: defaultName(),
        backgroundSync = prefs.getBoolean(K_BACKGROUND, false),
    )

    private fun defaultName(): String = Build.MODEL.ifBlank { "Android" }

    companion object {
        private const val K_DEVICE = "device_id"
        private const val K_NAME = "device_name"
        private const val K_BACKGROUND = "background_sync"
        private const val SECRET_BYTES = 20
        const val CODE_LENGTH = 32

        fun formatCode(code: String): String = code.chunked(4).joinToString("-")

        fun parse(input: String): JoinInfo {
            val text = input.trim()
            if (text.startsWith("pocketpantry://join")) {
                val uri = android.net.Uri.parse(text)
                val code = normalise(uri.getQueryParameter("c") ?: "")
                return JoinInfo(code, uri.getQueryParameter("d"), uri.getQueryParameter("n"), uri.getQueryParameter("a")?.split(',')?.filter { it.isNotBlank() } ?: emptyList())
            }
            return JoinInfo(normalise(text), null, null, emptyList())
        }

        private fun normalise(raw: String): String {
            val code = raw.uppercase().filter { it in Base32.ALPHABET }
            require(code.length == CODE_LENGTH) { "A household code has 32 letters and digits." }
            Base32.decode(code)
            return code
        }

        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}

/** RFC 4648 base32 without padding: unambiguous to type and case-insensitive. */
object Base32 {
    const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }

    fun decode(text: String): ByteArray {
        val out = ArrayList<Byte>()
        var buffer = 0
        var bits = 0
        for (ch in text.uppercase()) {
            val value = ALPHABET.indexOf(ch)
            require(value >= 0) { "Invalid character '$ch'" }
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                out.add(((buffer shr (bits - 8)) and 0xff).toByte())
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}
