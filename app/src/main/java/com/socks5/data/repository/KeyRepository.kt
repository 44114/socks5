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
     * Import an existing SSH private key from PEM/OpenSSH text, optionally
     * protected by [passphrase]. The algorithm and key size are read from the
     * key itself.
     */
    suspend fun importKey(
        name: String,
        keyText: String,
        passphrase: String?
    ): Long = withContext(Dispatchers.IO) {
        val passphraseBytes = passphrase?.toByteArray(Charsets.UTF_8)
        val jSch = JSch()
        val keyPair = try {
            KeyPair.load(jSch, keyText.toByteArray(Charsets.UTF_8), null)
        } catch (e: com.jcraft.jsch.JSchException) {
            // Likely passphrase-protected; retry with the supplied passphrase.
            if (passphraseBytes == null) throw e
            KeyPair.load(
                jSch,
                keyText.toByteArray(Charsets.UTF_8),
                passphraseBytes
            )
        }
        try {
            val (algorithm, keySize) = keyPair.describe()
            keyPair.toSshKey(name, algorithm, keySize, "")
        } finally {
            keyPair.dispose()
        }
    }

    /**
     * Derive the display algorithm name and key size from the key's type string
     * (e.g. "ssh-ed25519", "ecdsa-sha2-nistp256", "ssh-rsa").
     */
    private fun KeyPair.describe(): Pair<String, Int> {
        val typeString = getKeyTypeString()
        val algorithm = when {
            typeString.startsWith("ssh-ed25519") -> "Ed25519"
            typeString.startsWith("ecdsa") -> "ECDSA"
            typeString.startsWith("ssh-rsa") -> "RSA"
            typeString.startsWith("sk-") -> "SK"
            else -> typeString
        }
        val keySize = when {
            algorithm == "Ed25519" -> 256
            algorithm == "ECDSA" -> when {
                typeString.endsWith("nistp384") -> 384
                typeString.endsWith("nistp521") -> 521
                else -> 256
            }
            // Unknown for RSA without decoding the blob; avoid showing a wrong
            // bit count, the fingerprint identifies the key anyway.
            else -> 0
        }
        return algorithm to keySize
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

        // Key generation runs here, off the main thread.
        val keyPair = KeyPair.genKeyPair(jSch, type, keySize)
        try {
            keyPair.toSshKey(name, algorithm, keySize, comment)
        } finally {
            keyPair.dispose()
        }
    }

    /**
     * Encrypt, persist and record a key pair that has already been generated/loaded.
     *
     * Must be called on a background thread.
     */
    private suspend fun KeyPair.toSshKey(
        name: String,
        algorithm: String,
        keySize: Int,
        comment: String
    ): Long = withContext(Dispatchers.IO) {
        // Private key material in OpenSSH PEM format.
        val baos = ByteArrayOutputStream()
        writePrivateKey(baos)
        val privateKeyBytes = baos.toByteArray()
        if (privateKeyBytes.isEmpty()) throw Exception("Failed to generate key")

        val encrypted = keyStoreManager.encrypt(privateKeyBytes)
        val fileName = "key_${System.currentTimeMillis()}.enc"
        val keyFile = File(keysDir, fileName)
        keyFile.writeBytes(encrypted)

        // Public key info. The key is stored as an authorized_keys line
        // ("ssh-rsa AAAA... comment") rather than the raw blob, so it can be
        // copied straight into a server's authorized_keys.
        val publicKeyBlob = publicKeyBlob ?: ByteArray(0)
        val fingerprint = HostKeyManager.computeFingerprint(publicKeyBlob, algorithm)
        val publicKeyOut = ByteArrayOutputStream()
        writePublicKey(publicKeyOut, comment)
        val publicKeyLine = publicKeyOut.toString("US-ASCII").trim()

        val sshKey = SshKey(
            name = name,
            algorithm = algorithm,
            keySize = keySize,
            fingerprint = fingerprint,
            encryptedKeyPath = keyFile.absolutePath,
            publicKey = publicKeyLine,
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
