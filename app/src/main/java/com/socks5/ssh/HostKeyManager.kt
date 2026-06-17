package com.socks5.ssh

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.security.MessageDigest

/**
 * Manages SSH host key verification and caching.
 *
 * On first connection to a host, the host key fingerprint is stored.
 * Subsequent connections verify against the cached key.
 * If the key changes, the connection is rejected (potential MITM attack).
 */
class HostKeyManager(
    private val context: Context,
    private val onUnknownHost: suspend (host: String, fingerprint: String, keyType: String) -> Boolean = { _, _, _ -> false }
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("host_keys", Context.MODE_PRIVATE)

    data class HostKeyInfo(
        val host: String,
        val port: Int,
        val keyType: String,
        val fingerprint: String,
        val key: String // Base64 encoded key
    )

    /**
     * Check if a host key is known and matches.
     * Returns null if not known, true if matches, false if mismatch.
     */
    fun checkHostKey(host: String, port: Int, keyType: String, key: ByteArray): Boolean? {
        val keyB64 = Base64.encodeToString(key, Base64.NO_WRAP)
        val savedKey = prefs.getString(key(host, port, keyType), null) ?: return null
        return savedKey == keyB64
    }

    /**
     * Store a host key after user acceptance.
     */
    fun storeHostKey(host: String, port: Int, keyType: String, key: ByteArray) {
        val keyB64 = Base64.encodeToString(key, Base64.NO_WRAP)
        prefs.edit()
            .putString(key(host, port, keyType), keyB64)
            .apply()
    }

    /**
     * Remove a stored host key (e.g., on key rotation).
     */
    fun removeHostKey(host: String, port: Int, keyType: String) {
        prefs.edit()
            .remove(key(host, port, keyType))
            .apply()
    }

    /**
     * Remove all stored host keys for a given host:port.
     */
    fun removeAllForHost(host: String, port: Int) {
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith("$host:$port:") }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    /**
     * Get all known host keys.
     */
    fun getAllKnownHosts(): List<HostKeyInfo> {
        return prefs.all.mapNotNull { (key, value) ->
            val parts = key.split(":", limit = 3)
            if (parts.size != 3 || value !is String) return@mapNotNull null
            HostKeyInfo(
                host = parts[0],
                port = parts[1].toIntOrNull() ?: return@mapNotNull null,
                keyType = parts[2],
                fingerprint = computeFingerprint(Base64.decode(value, Base64.NO_WRAP), parts[2]),
                key = value
            )
        }
    }

    companion object {
        private fun key(host: String, port: Int, keyType: String): String {
            return "$host:$port:$keyType"
        }

        /**
         * Compute the SHA-256 fingerprint of a host key, formatted like OpenSSH.
         */
        fun computeFingerprint(key: ByteArray, keyType: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(key)
            val b64 = Base64.encodeToString(digest, Base64.NO_WRAP).trimEnd('=')
            return "SHA256:$b64"
        }

        /**
         * Compute the MD5 fingerprint (legacy format, colon-separated hex).
         */
        fun computeMd5Fingerprint(key: ByteArray): String {
            val digest = MessageDigest.getInstance("MD5").digest(key)
            return digest.joinToString(":") { "%02x".format(it) }
        }
    }
}
