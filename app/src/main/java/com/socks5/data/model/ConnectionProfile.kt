package com.socks5.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Saved SSH connection profile.
 */
@Entity(tableName = "profiles")
data class ConnectionProfile(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,                    // User-friendly profile name
    val host: String,                    // SSH server hostname or IP
    val port: Int = 22,                  // SSH server port
    val username: String,                // SSH username
    val authMethodType: AuthMethodType,  // PASSWORD or PRIVATE_KEY
    val keyId: Long? = null,             // FK to ssh_keys table (null for password auth)
    val localSocksPort: Int = 1080,      // Port for local SOCKS5 proxy
    val dnsServer: String = "1.1.1.1",   // DNS server for VPN mode
    val keepAliveInterval: Long = 30_000L, // ServerAliveInterval in ms
    val autoReconnect: Boolean = true,    // Auto-reconnect on disconnect
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = 0
)

enum class AuthMethodType {
    PASSWORD,
    PRIVATE_KEY
}
