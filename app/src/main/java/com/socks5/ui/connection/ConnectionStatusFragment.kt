package com.socks5.ui.connection

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.socks5.R
import com.socks5.data.model.ConnectionProfile
import com.socks5.databinding.FragmentConnectionStatusBinding
import com.socks5.ssh.SshConnectionManager
import com.socks5.ui.MainViewModel
import com.socks5.util.TrafficStats
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class ConnectionStatusFragment : Fragment(R.layout.fragment_connection_status) {

    private var _binding: FragmentConnectionStatusBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConnectionStatusBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        observeState()

        binding.connectButton.setOnClickListener {
            handleConnectDisconnect()
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            launch {
                viewModel.connectionState.collect { state ->
                    updateConnectionUI(state)
                }
            }

            launch {
                viewModel.trafficSnapshot.collect { stats ->
                    binding.trafficDown.text = TrafficStats.formatBytes(stats.bytesDown)
                    binding.trafficUp.text = TrafficStats.formatBytes(stats.bytesUp)
                    binding.activeConnections.text = "${stats.activeConnections} connections"
                }
            }

            launch {
                viewModel.vpnActive.collect { active ->
                    binding.vpnIndicator.isChecked = active
                }
            }
        }
    }

    private fun updateConnectionUI(state: SshConnectionManager.ConnectionState) {
        when (state) {
            is SshConnectionManager.ConnectionState.Disconnected -> {
                binding.statusText.text = getString(R.string.status_disconnected)
                binding.statusIndicator.setBackgroundColor(
                    requireContext().getColor(R.color.status_disconnected)
                )
                binding.connectButton.text = getString(R.string.btn_connect)
                binding.connectButton.isEnabled = true
                binding.serverText.text = "--"
            }
            is SshConnectionManager.ConnectionState.Connecting -> {
                binding.statusText.text = getString(R.string.status_connecting)
                binding.statusIndicator.setBackgroundColor(
                    requireContext().getColor(android.R.color.holo_orange_light)
                )
                binding.connectButton.text = "Connecting..."
                binding.connectButton.isEnabled = false
                binding.serverText.text = state.host
            }
            is SshConnectionManager.ConnectionState.Connected -> {
                binding.statusText.text = getString(R.string.status_connected)
                binding.statusIndicator.setBackgroundColor(
                    requireContext().getColor(R.color.status_connected)
                )
                binding.connectButton.text = getString(R.string.btn_disconnect)
                binding.connectButton.isEnabled = true
                binding.serverText.text = state.host
            }
            is SshConnectionManager.ConnectionState.Error -> {
                binding.statusText.text = state.message
                binding.statusIndicator.setBackgroundColor(
                    requireContext().getColor(R.color.status_error)
                )
                binding.connectButton.text = getString(R.string.btn_connect)
                binding.connectButton.isEnabled = true
            }
        }
    }

    private fun handleConnectDisconnect() {
        val state = viewModel.connectionState.value
        when (state) {
            is SshConnectionManager.ConnectionState.Connected -> {
                viewModel.disconnect()
            }
            else -> {
                // Navigate to profile selection or use last profile
                val lastId = viewModel.profiles.value
                    .firstOrNull()
                    ?.let {
                        // Try to connect with the most recently used profile
                        connectWithProfile(it)
                    }
            }
        }
    }

    private fun connectWithProfile(profile: ConnectionProfile) {
        // For password auth, we need the password from preferences
        viewModel.connect(profile)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
