package com.socks5.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import com.socks5.R
import com.socks5.data.preferences.AppPreferences
import com.socks5.databinding.FragmentSettingsBinding
import com.socks5.ui.MainViewModel
import java.net.InetAddress

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by viewModels()
    private lateinit var preferences: AppPreferences

    companion object {
        private const val BITCOIN_ADDRESS = "bc1qsvfjdwmw0278pxse666hrgxzyghdls7v9wdqpq"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        preferences = AppPreferences(requireContext())

        loadPreferences()
        setupListeners()
    }

    private fun loadPreferences() {
        binding.autoConnectSwitch.isChecked = preferences.autoConnect
        binding.bootStartSwitch.isChecked = preferences.startOnBoot
        binding.dnsServerInput.setText(preferences.dnsServer)
        binding.socksPortInput.setText(preferences.localSocksPort.toString())
        refreshCustomHostsList()
    }

    private fun setupListeners() {
        binding.autoConnectSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferences.autoConnect = isChecked
        }

        binding.bootStartSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferences.startOnBoot = isChecked
        }

        binding.dnsServerInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.trim() ?: ""
                if (text.isNotEmpty()) {
                    preferences.dnsServer = text
                }
            }
        })

        binding.socksPortInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.trim() ?: ""
                val port = text.toIntOrNull()
                if (port != null && port in 1..65535) {
                    preferences.localSocksPort = port
                }
            }
        })

        binding.batterySettingsButton.setOnClickListener {
            openBatteryOptimizationSettings()
        }

        binding.addHostButton.setOnClickListener {
            showAddHostDialog()
        }

        binding.donateCopyButton.setOnClickListener {
            copyBitcoinAddress()
        }
    }

    // --- Custom Hosts ---

    /**
     * Rebuild the custom hosts list from preferences.
     */
    private fun refreshCustomHostsList() {
        val container = binding.customHostsContainer
        container.removeAllViews()

        val hosts = preferences.getCustomHosts()
        if (hosts.isEmpty()) {
            binding.customHostsEmptyText.visibility = View.VISIBLE
            container.visibility = View.GONE
        } else {
            binding.customHostsEmptyText.visibility = View.GONE
            container.visibility = View.VISIBLE
            for ((hostname, ip) in hosts) {
                addHostRow(container, hostname, ip)
            }
        }
    }

    /**
     * Add a single host entry row to the container.
     */
    private fun addHostRow(container: LinearLayout, hostname: String, ip: String) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 4
                bottomMargin = 4
            }
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val textView = TextView(requireContext()).apply {
            text = "$hostname  →  $ip"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            setPadding(8, 8, 8, 8)
        }

        val deleteButton = MaterialButton(requireContext()).apply {
            text = "✕"
            textSize = 16f
            setPadding(4, 0, 4, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            contentDescription = getString(R.string.custom_host_delete_description, hostname)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.status_error))
            setOnClickListener {
                preferences.removeCustomHost(hostname)
                refreshCustomHostsList()
            }
        }

        row.addView(textView)
        row.addView(deleteButton)
        container.addView(row)

        // Add a subtle divider
        val divider = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            )
            setBackgroundColor(0x1A000000.toInt())
        }
        container.addView(divider)
    }

    /**
     * Show a dialog for adding a new custom host entry.
     */
    private fun showAddHostDialog() {
        val context = requireContext()
        val dialogLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 0)
        }

        val hostnameLayout = TextInputLayout(context).apply {
            hint = getString(R.string.custom_host_hostname_label)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val hostnameInput = EditText(context).apply {
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        hostnameLayout.addView(hostnameInput)
        dialogLayout.addView(hostnameLayout)

        val ipLayout = TextInputLayout(context).apply {
            hint = getString(R.string.custom_host_ip_label)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val ipInput = EditText(context).apply {
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        ipLayout.addView(ipInput)
        dialogLayout.addView(ipLayout)

        AlertDialog.Builder(context)
            .setTitle(R.string.custom_host_dialog_title)
            .setView(dialogLayout)
            .setPositiveButton(R.string.custom_host_add_button) { dialog, _ ->
                val hostname = hostnameInput.text.toString().trim()
                val ip = ipInput.text.toString().trim()

                // Validate hostname
                if (hostname.isEmpty() || !isValidHostname(hostname)) {
                    Toast.makeText(context, R.string.custom_host_invalid_hostname, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // Validate IP
                if (!isValidIpv4(ip)) {
                    Toast.makeText(context, R.string.custom_host_invalid_ip, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // Check duplicate
                val existing = preferences.getCustomHosts()
                if (existing.containsKey(hostname.lowercase())) {
                    // Allow overwrite — just warn via the replace
                }

                preferences.addCustomHost(hostname, ip)
                refreshCustomHostsList()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun isValidHostname(hostname: String): Boolean {
        return hostname.length in 1..253 &&
                hostname.matches(Regex("^([a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?\\.)*[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?$"))
    }

    private fun isValidIpv4(ip: String): Boolean {
        return try {
            val addr = InetAddress.getByName(ip)
            addr.hostAddress == ip && addr is java.net.Inet4Address
        } catch (_: Exception) {
            false
        }
    }

    // --- Battery Optimization ---

    private fun openBatteryOptimizationSettings() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${requireContext().packageName}")
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Unable to open battery settings",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // --- Bitcoin Donation ---

    private fun copyBitcoinAddress() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Bitcoin Address", BITCOIN_ADDRESS)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), R.string.setting_donate_copied, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
