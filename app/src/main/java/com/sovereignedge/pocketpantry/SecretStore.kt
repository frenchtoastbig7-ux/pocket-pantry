package com.sovereignedge.pocketpantry

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Claude API key, encrypted with an AES-256-GCM key that never leaves the Android Keystore (StrongBox / Titan M2
 * on Pixel when available). Only ciphertext is written to app-private storage, and backups are disabled.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    fun hasApiKey(): Boolean = prefs.contains(API_KEY)
    fun putApiKey(value: String) = put(API_KEY, value.trim())
    fun apiKey(): String? = get(API_KEY)
    fun clearApiKey() = prefs.edit().remove(API_KEY).apply()

    /** Other named secrets, such as the household code. */
    fun putSecret(name: String, value: String) = put(name, value.trim())
    fun secret(name: String): String? = get(name)
    fun removeSecret(name: String) = prefs.edit().remove(name).apply()

    private fun put(name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, b64(cipher.iv) + ":" + b64(ciphertext)).apply()
    }

    private fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        val (iv, ciphertext) = stored.split(":").takeIf { it.size == 2 }?.map { unb64(it) } ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return try {
            generate(strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(text: String) = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "anthropic_api_key_wrap"
        const val API_KEY = "anthropic_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
