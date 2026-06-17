package com.socks5.data.repository

import android.content.Context
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.socks5.crypto.KeyStoreManager
import com.socks5.data.db.KeyDao
import com.socks5.data.model.SshKey
import com.socks5.ssh.HostKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

class KeyRepository(
    private val context: Context,
    private val keyDao: KeyDao,
    private val keyStoreManager: KeyStoreManager = KeyStoreManager()
) {
    private val keysDir = File(context.filesDir, "keys").also { it.mkdirs() }

    fun getAllKeys(): Flow<List<SshKey>> = keyDao.getAll()

    suspend fun getById(id: Long): SshKey? = keyDao.getById(id)

    /**
     * Import an existing SSH private key from raw bytes (PEM format).
     */
    suspend fun importKey(
        name: String,
        keyData: ByteArray,
        algorithm: String,
        keySize: Int,
        comment: String = ""
    ): Long = withContext(Dispatchers.IO) {
        // Derive public key and fingerprint
        val jSch = JSch()
        val keyPair = KeyPair.load(jSch, keyData, null)
        val publicKeyBlob = keyPair.publicKeyBlob ?: ByteArray(0)
        val fingerprint = HostKeyManager.computeFingerprint(publicKeyBlob, algorithm)

        // Encrypt and save private key
        val encrypted = keyStoreManager.encrypt(keyData)
        val fileName = "key_${System.currentTimeMillis()}.enc"
        val keyFile = File(keysDir, fileName)
        keyFile.writeBytes(encrypted)

        val sshKey = SshKey(
            name = name,
            algorithm = algorithm,
            keySize = keySize,
            fingerprint = fingerprint,
            encryptedKeyPath = keyFile.absolutePath,
            publicKey = String(publicKeyBlob),
            comment = comment
        )

        keyDao.insert(sshKey)
    }

    /**
     * Generate a new SSH key pair.
     */
    suspend fun generateKey(
        name: String,
        algorithm: String,   // "RSA", "ECDSA", "Ed25519"
        keySize: Int = when (algorithm) {
            "RSA" -> 4096
            "ECDSA" -> 256
            "Ed25519" -> 256
            else -> 2048
        },
        comment: String = ""
    ): Long = withContext(Dispatchers.IO) {
        val jSch = JSch()
        val type = when (algorithm) {
            "RSA" -> KeyPair.RSA
            "ECDSA" -> KeyPair.ECDSA
            "Ed25519" -> KeyPair.ED25519
            else -> throw IllegalArgumentException("Unsupported algorithm: $algorithm")
        }

        val keyPair = KeyPair.genKeyPair(jSch, type, keySize)

        // Get private key bytes
        val baos = ByteArrayOutputStream()
        keyPair.writePrivateKey(baos)
        val privateKeyBytes = baos.toByteArray()
        if (privateKeyBytes.isEmpty()) throw Exception("Failed to generate key")

        // Encrypt and save
        val encrypted = keyStoreManager.encrypt(privateKeyBytes)
        val fileName = "key_${System.currentTimeMillis()}.enc"
        val keyFile = File(keysDir, fileName)
        keyFile.writeBytes(encrypted)

        // Public key info
        val publicKeyBlob = keyPair.publicKeyBlob ?: ByteArray(0)
        val fingerprint = HostKeyManager.computeFingerprint(publicKeyBlob, algorithm)

        val sshKey = SshKey(
            name = name,
            algorithm = algorithm,
            keySize = keySize,
            fingerprint = fingerprint,
            encryptedKeyPath = keyFile.absolutePath,
            publicKey = String(publicKeyBlob),
            comment = comment
        )

        keyDao.insert(sshKey)
    }

    /**
     * Decrypt and load a stored private key.
     */
    suspend fun loadKeyData(keyId: Long): ByteArray = withContext(Dispatchers.IO) {
        val sshKey = keyDao.getById(keyId)
            ?: throw IllegalArgumentException("Key not found: $keyId")

        val keyFile = File(sshKey.encryptedKeyPath)
        if (!keyFile.exists()) {
            throw IllegalStateException("Key file missing: ${sshKey.encryptedKeyPath}")
        }

        val encrypted = keyFile.readBytes()
        keyStoreManager.decrypt(encrypted)
    }

    /**
     * Delete a key and its encrypted file.
     */
    suspend fun deleteKey(keyId: Long) = withContext(Dispatchers.IO) {
        val sshKey = keyDao.getById(keyId) ?: return@withContext
        val keyFile = File(sshKey.encryptedKeyPath)
        if (keyFile.exists()) {
            keyFile.delete()
        }
        keyDao.deleteById(keyId)
    }
}
