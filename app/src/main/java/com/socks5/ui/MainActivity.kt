package com.socks5.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.socks5.R
import com.socks5.data.preferences.AppPreferences
import com.socks5.ssh.SshConnectionManager
import com.socks5.vpn.Socks5VpnService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var preferences: AppPreferences
    private lateinit var viewModel: MainViewModel
    private var autoConnectAttempted = false

    // Request VPN permission before starting
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // Permission granted; try auto-connect if configured
            tryAutoConnect()
        } else {
            Toast.makeText(this, R.string.vpn_permission_required, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preferences = AppPreferences(this)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNav.setupWithNavController(navController)

        // Request VPN permission if needed
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            // Already prepared — try auto-connect
            tryAutoConnect()
        }
    }

    /**
     * Attempt auto-connect if the setting is enabled and a profile was previously used.
     */
    private fun tryAutoConnect() {
        if (autoConnectAttempted) return
        if (!preferences.autoConnect) return

        val profileId = preferences.lastUsedProfileId
        if (profileId <= 0) return

        // Don't auto-connect if already connected or connecting
        val currentState = viewModel.connectionState.value
        if (currentState is SshConnectionManager.ConnectionState.Connected ||
            currentState is SshConnectionManager.ConnectionState.Connecting
        ) return

        autoConnectAttempted = true

        lifecycleScope.launch {
            // Wait for profiles to be loaded, then find the matching one
            val profiles = viewModel.profiles.first { it.isNotEmpty() }
            val profile = profiles.find { it.id == profileId } ?: return@launch

            viewModel.connect(profile)
        }
    }

    /**
     * Prepare VPN (call before starting Socks5VpnService).
     * Returns true if already prepared, false if user needs to grant.
     */
    fun prepareVpn(): Boolean {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
            return false
        }
        return true
    }
}
