package com.socks5.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONObject

/**
 * Secure app preferences using EncryptedSharedPreferences.
 *
 * Stores sensitive values (SSH password, key passphrase) encrypted.
 */
class AppPreferences(context: Context) {

    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        "socks5_secure_prefs",
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // Non-sensitive preferences use regular SharedPreferences
    private val normalPrefs: SharedPreferences =
        context.getSharedPreferences("socks5_prefs", Context.MODE_PRIVATE)

    var lastUsedProfileId: Long
        get() = normalPrefs.getLong("last_profile_id", -1)
        set(value) = normalPrefs.edit().putLong("last_profile_id", value).apply()

    var autoConnect: Boolean
        get() = normalPrefs.getBoolean("auto_connect", false)
        set(value) = normalPrefs.edit().putBoolean("auto_connect", value).apply()

    var startOnBoot: Boolean
        get() = normalPrefs.getBoolean("start_on_boot", false)
        set(value) = normalPrefs.edit().putBoolean("start_on_boot", value).apply()

    var dnsServer: String
        get() = normalPrefs.getString("dns_server", "1.1.1.1") ?: "1.1.1.1"
        set(value) = normalPrefs.edit().putString("dns_server", value).apply()

    var keepAliveInterval: Long
        get() = normalPrefs.getLong("keep_alive_interval", 30_000L)
        set(value) = normalPrefs.edit().putLong("keep_alive_interval", value).apply()

    var localSocksPort: Int
        get() = normalPrefs.getInt("local_socks_port", 1080)
        set(value) = normalPrefs.edit().putInt("local_socks_port", value).apply()

    // --- Custom hosts (hostname → IP mappings) ---

    /**
     * Get all custom host mappings as an immutable map.
     */
    fun getCustomHosts(): Map<String, String> {
        val json = normalPrefs.getString("custom_hosts", "{}") ?: "{}"
        return try {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, String>()
            for (key in obj.keys()) {
                map[key] = obj.getString(key)
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * Add or update a custom host mapping.
     */
    fun addCustomHost(hostname: String, ip: String) {
        val json = try {
            JSONObject(normalPrefs.getString("custom_hosts", "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        json.put(hostname.trim().lowercase(), ip.trim())
        normalPrefs.edit().putString("custom_hosts", json.toString()).apply()
    }

    /**
     * Remove a custom host mapping by hostname.
     */
    fun removeCustomHost(hostname: String) {
        val json = try {
            JSONObject(normalPrefs.getString("custom_hosts", "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        json.remove(hostname.trim().lowercase())
        normalPrefs.edit().putString("custom_hosts", json.toString()).apply()
    }

    /**
     * Store an SSH password for a profile (encrypted).
     */
    fun setPassword(profileId: Long, password: String) {
        prefs.edit().putString("password_$profileId", password).apply()
    }

    /**
     * Get the stored SSH password for a profile.
     */
    fun getPassword(profileId: Long): String? {
        return prefs.getString("password_$profileId", null)
    }

    /**
     * Store a key passphrase (encrypted).
     */
    fun setKeyPassphrase(keyId: Long, passphrase: String) {
        prefs.edit().putString("passphrase_$keyId", passphrase).apply()
    }

    /**
     * Get a stored key passphrase.
     */
    fun getKeyPassphrase(keyId: Long): String? {
        return prefs.getString("passphrase_$keyId", null)
    }

    /**
     * Remove sensitive data for a profile.
     */
    fun clearProfileSecrets(profileId: Long) {
        prefs.edit()
            .remove("password_$profileId")
            .apply()
    }

    /**
     * Remove sensitive data for a key.
     */
    fun clearKeySecrets(keyId: Long) {
        prefs.edit()
            .remove("passphrase_$keyId")
            .apply()
    }
}
