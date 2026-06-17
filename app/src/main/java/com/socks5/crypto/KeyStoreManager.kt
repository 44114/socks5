package com.socks5.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Manages encryption of SSH private keys using Android Keystore.
 *
 * Keys are encrypted with AES-256-GCM. The AES key is stored in
 * the hardware-backed Android Keystore and never leaves it.
 *
 * Encrypted format: [12-byte IV][ciphertext + GCM tag]
 */
class KeyStoreManager {

    companion object {
        private const val KEY_ALIAS = "socks5_ssh_key_encryption_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128 // bits
        private const val IV_LENGTH = 12 // bytes
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    /**
     * Get or create the AES encryption key in Android Keystore.
     */
    fun getOrCreateEncryptionKey(): SecretKey {
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
            return entry.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /**
     * Encrypt SSH private key data.
     * Returns [IV (12 bytes)][ciphertext (includes GCM tag)].
     */
    fun encrypt(data: ByteArray): ByteArray {
        val secretKey = getOrCreateEncryptionKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)

        val iv = cipher.iv ?: throw IllegalStateException("Cipher did not generate IV")
        val ciphertext = cipher.doFinal(data)

        return iv + ciphertext
    }

    /**
     * Decrypt SSH private key data.
     * Expects input format: [IV (12 bytes)][ciphertext (includes GCM tag)].
     */
    fun decrypt(encryptedData: ByteArray): ByteArray {
        if (encryptedData.size < IV_LENGTH + 16) { // min: IV + empty plaintext + GCM tag
            throw IllegalArgumentException("Encrypted data too short")
        }

        val secretKey = getOrCreateEncryptionKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)

        val iv = encryptedData.copyOfRange(0, IV_LENGTH)
        val ciphertext = encryptedData.copyOfRange(IV_LENGTH, encryptedData.size)

        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        return cipher.doFinal(ciphertext)
    }
}
