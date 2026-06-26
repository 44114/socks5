package com.socks5.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.socks5.data.db.AppDatabase
import com.socks5.data.model.AuthMethodType
import com.socks5.data.model.ConnectionProfile
import com.socks5.data.model.SshKey
import com.socks5.data.preferences.AppPreferences
import com.socks5.data.repository.KeyRepository
import com.socks5.data.repository.ProfileRepository
import com.socks5.ssh.SshConnectionManager
import com.socks5.ssh.SshConnectionManager.ConnectionState
import com.socks5.util.TrafficStats
import com.socks5.vpn.Socks5VpnService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val preferences = AppPreferences(application)
    private val profileRepository = ProfileRepository(db.profileDao(), preferences)
    val keyRepository = KeyRepository(application, db.keyDao())

    val sshManager = SshConnectionManager(viewModelScope)
    val trafficStats = TrafficStats()

    // Connection state from SSH manager
    val connectionState: StateFlow<ConnectionState> = sshManager.state

    // VPN state
    private val _vpnActive = MutableStateFlow(false)
    val vpnActive: StateFlow<Boolean> = _vpnActive.asStateFlow()

    // Traffic stats
    val trafficSnapshot = trafficStats.stats

    // Profiles and keys
    val profiles: StateFlow<List<ConnectionProfile>> =
        profileRepository.getAllProfiles()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val keys: StateFlow<List<SshKey>> =
        keyRepository.getAllKeys()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Currently selected profile
    private val _selectedProfile = MutableStateFlow<ConnectionProfile?>(null)
    val selectedProfile: StateFlow<ConnectionProfile?> = _selectedProfile.asStateFlow()

    // UI state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableSharedFlow<String>()
    val errorMessage: SharedFlow<String> = _errorMessage.asSharedFlow()

    /**
     * Connect to the SSH server using the given profile.
     */
    fun connect(profile: ConnectionProfile, password: String? = null, keyPassphrase: String? = null) {
        viewModelScope.launch {
            _isLoading.value = true

            try {
                val authMethod = when (profile.authMethodType) {
                    AuthMethodType.PASSWORD -> {
                        val pass = password
                            ?: preferences.getPassword(profile.id)
                            ?: throw IllegalStateException("No password provided")
                        SshConnectionManager.AuthMethod.Password(pass)
                    }
                    AuthMethodType.PRIVATE_KEY -> {
                        val keyId = profile.keyId
                            ?: throw IllegalStateException("No key selected for profile")
                        val keyData = keyRepository.loadKeyData(keyId)
                        val passphrase = keyPassphrase
                            ?: preferences.getKeyPassphrase(keyId)
                        SshConnectionManager.AuthMethod.PrivateKey(keyData, passphrase)
                    }
                }

                val config = SshConnectionManager.SshConfig(
                    host = profile.host,
                    port = profile.port,
                    username = profile.username,
                    authMethod = authMethod,
                    keepAliveInterval = profile.keepAliveInterval,
                    reconnectEnabled = profile.autoReconnect
                )

                val result = sshManager.connect(config)
                if (result.isSuccess) {
                    // Store the active SSH manager so VpnService can access it
                    (getApplication<Application>() as? com.socks5.Socks5Application)
                        ?.let { it.activeSshManager = sshManager }

                    // Save password/passphrase if provided
                    if (profile.authMethodType == AuthMethodType.PASSWORD && password != null) {
                        preferences.setPassword(profile.id, password)
                    }
                    if (profile.authMethodType == AuthMethodType.PRIVATE_KEY && keyPassphrase != null) {
                        profile.keyId?.let { preferences.setKeyPassphrase(it, keyPassphrase) }
                    }

                    profileRepository.markUsed(profile.id)

                    // Start VPN
                    startVpn()
                } else {
                    _errorMessage.emit(
                        result.exceptionOrNull()?.message ?: "Connection failed"
                    )
                }
            } catch (e: Exception) {
                _errorMessage.emit(e.message ?: "Connection error")
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Disconnect SSH and stop VPN.
     */
    fun disconnect() {
        viewModelScope.launch {
            stopVpn()
            sshManager.disconnect()
            (getApplication<Application>() as? com.socks5.Socks5Application)
                ?.let { it.activeSshManager = null }
            _vpnActive.value = false
        }
    }

    /**
     * Start the VPN service.
     */
    private fun startVpn() {
        val context = getApplication<Application>()
        val intent = Intent(context, Socks5VpnService::class.java).apply {
            action = Socks5VpnService.ACTION_CONNECT
        }
        context.startService(intent)
        _vpnActive.value = true
    }

    /**
     * Stop the VPN service.
     */
    private fun stopVpn() {
        val context = getApplication<Application>()
        val intent = Intent(context, Socks5VpnService::class.java).apply {
            action = Socks5VpnService.ACTION_DISCONNECT
        }
        context.startService(intent)
        _vpnActive.value = false
    }

    /**
     * Select a profile for editing/connecting.
     */
    fun selectProfile(profile: ConnectionProfile?) {
        _selectedProfile.value = profile
    }

    /**
     * Save (insert or update) a profile.
     */
    suspend fun saveProfile(profile: ConnectionProfile) {
        if (profile.id == 0L) {
            profileRepository.insert(profile)
        } else {
            profileRepository.update(profile)
        }
    }

    /**
     * Delete a profile.
     */
    suspend fun deleteProfile(profile: ConnectionProfile) {
        profileRepository.delete(profile)
        preferences.clearProfileSecrets(profile.id)
    }

    /**
     * Delete an SSH key.
     */
    suspend fun deleteKey(key: SshKey) {
        keyRepository.deleteKey(key.id)
        preferences.clearKeySecrets(key.id)
    }

    /**
     * Generate a new SSH key.
     */
    suspend fun generateKey(
        name: String,
        algorithm: String,
        keySize: Int,
        comment: String = ""
    ): Long {
        return keyRepository.generateKey(name, algorithm, keySize, comment)
    }

    /**
     * Import an SSH key from raw data.
     */
    suspend fun importKey(
        name: String,
        keyData: ByteArray,
        algorithm: String,
        keySize: Int,
        comment: String = ""
    ): Long {
        return keyRepository.importKey(name, keyData, algorithm, keySize, comment)
    }
}
