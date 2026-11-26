package ai.zeroclaw.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts secrets kept in settings (the bearer token and an imported PEM
 * key) under an AES-256-GCM key held in the Android Keystore.
 *
 * The Keystore key never leaves this device, so a copy of the settings file,
 * through a backup, a device transfer, or a rooted read, is ciphertext that
 * cannot be opened elsewhere.
 */
object SecretBox {

    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "zeroclaw-settings-secrets-v1"
    private const val PREFIX = "zcs1:"
    private const val TAG_BITS = 128

    fun isSealed(stored: String): Boolean = stored.startsWith(PREFIX)

    /** Seal `plain`; a blank value stays blank. */
    fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getEncoder()
        return PREFIX + encoder.encodeToString(cipher.iv) + ":" + encoder.encodeToString(sealed)
    }

    /**
     * Open a stored value. A value written before encryption existed is
     * returned as-is (and re-sealed by [SettingsStore.migrateSecrets]). A
     * value that no longer opens, because the Keystore key is gone, reads as
     * blank so the app asks to pair again instead of failing on every start.
     */
    fun open(stored: String): String {
        if (!isSealed(stored)) return stored
        return runCatching {
            val (iv, sealed) = stored.removePrefix(PREFIX).split(':', limit = 2)
            val decoder = Base64.getDecoder()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, decoder.decode(iv)))
            String(cipher.doFinal(decoder.decode(sealed)), Charsets.UTF_8)
        }.getOrDefault("")
    }

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
