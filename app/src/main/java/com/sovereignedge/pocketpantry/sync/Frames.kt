package com.sovereignedge.pocketpantry.sync

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Length-prefixed frames encrypted with AES-256-GCM under the household key. The household id is bound in as
 * associated data, so a frame from a different household (or a tampered one) fails authentication and the
 * connection is dropped. Possession of the code is the only credential, by design.
 */
class Frames(key: ByteArray, private val aad: ByteArray) {
    private val spec = SecretKeySpec(key, "AES")
    private val random = SecureRandom()

    fun seal(plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, spec, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        return iv + cipher.doFinal(plaintext)
    }

    fun open(frame: ByteArray): ByteArray {
        require(frame.size > IV_BYTES) { "short frame" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, spec, GCMParameterSpec(TAG_BITS, frame, 0, IV_BYTES))
        cipher.updateAAD(aad)
        return cipher.doFinal(frame, IV_BYTES, frame.size - IV_BYTES)
    }

    fun write(out: DataOutputStream, plaintext: String) {
        val sealed = seal(plaintext.toByteArray(Charsets.UTF_8))
        out.writeInt(sealed.size)
        out.write(sealed)
        out.flush()
    }

    fun read(input: DataInputStream): String {
        val size = input.readInt()
        if (size <= 0 || size > MAX_FRAME) throw IOException("bad frame size $size")
        val frame = ByteArray(size)
        input.readFully(frame)
        return String(open(frame), Charsets.UTF_8)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val MAX_FRAME = 8 * 1024 * 1024
    }
}
