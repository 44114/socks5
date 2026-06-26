package com.socks5.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stored SSH key metadata. The actual private key is encrypted on disk.
 */
@Entity(tableName = "ssh_keys")
data class SshKey(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,             // User-friendly name
    val algorithm: String,        // "RSA", "ECDSA", "Ed25519"
    val keySize: Int,             // 2048, 4096 for RSA; 256 for ECDSA; 256 for Ed25519
    val fingerprint: String,      // SHA256 fingerprint for display
    val encryptedKeyPath: String, // Path to encrypted key file in app storage
    val publicKey: String,        // Public key text (for server authorized_keys)
    val comment: String = "",     // Optional comment
    val createdAt: Long = System.currentTimeMillis()
)
