package com.socks5.ui.profiles

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.socks5.R
import com.socks5.data.model.AuthMethodType
import com.socks5.data.model.ConnectionProfile
import com.socks5.databinding.FragmentProfileDetailBinding
import com.socks5.ui.MainViewModel
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class ProfileDetailFragment : Fragment(R.layout.fragment_profile_detail) {

    private var _binding: FragmentProfileDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by viewModels()
    private var editingProfile: ConnectionProfile? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupAuthMethodSpinner()
        setupKeySpinner()

        viewModel.selectedProfile.value?.let { profile ->
            editingProfile = profile
            populateForm(profile)
        }

        binding.saveButton.setOnClickListener { saveProfile() }
    }

    private fun setupAuthMethodSpinner() {
        val authMethods = AuthMethodType.entries.map { it.name }
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            authMethods
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.authMethodSpinner.setAdapter(adapter)

        binding.authMethodSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val isKey = AuthMethodType.entries[position] == AuthMethodType.PRIVATE_KEY
                binding.keySelectionGroup.visibility = if (isKey) View.VISIBLE else View.GONE
                binding.passwordField.visibility = if (isKey) View.GONE else View.VISIBLE
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupKeySpinner() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.keys.collect { keys ->
                val keyNames = keys.map { it.name }
                val adapter = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_spinner_item,
                    keyNames
                )
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.keySpinner.setAdapter(adapter)
            }
        }
    }

    private fun populateForm(profile: ConnectionProfile) {
        binding.nameInput.setText(profile.name)
        binding.hostInput.setText(profile.host)
        binding.portInput.setText(profile.port.toString())
        binding.usernameInput.setText(profile.username)

        val authIndex = AuthMethodType.entries.indexOf(profile.authMethodType)
        if (authIndex >= 0) {
            binding.authMethodSpinner.setSelection(authIndex)
        }

        profile.keyId?.let { keyId ->
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.keys.collect { keys ->
                    val keyIndex = keys.indexOfFirst { it.id == keyId }
                    if (keyIndex >= 0) {
                        binding.keySpinner.setSelection(keyIndex)
                    }
                }
            }
        }
    }

    private fun saveProfile() {
        val name = binding.nameInput.text.toString().trim()
        val host = binding.hostInput.text.toString().trim()
        val port = binding.portInput.text.toString().toIntOrNull() ?: 22
        val username = binding.usernameInput.text.toString().trim()
        val authMethodType = AuthMethodType.entries.firstOrNull {
            it.name == binding.authMethodSpinner.text.toString()
        } ?: AuthMethodType.PASSWORD
        val password = binding.passwordInput.text.toString()

        if (name.isEmpty() || host.isEmpty() || username.isEmpty()) {
            Snackbar.make(binding.root, "Please fill in all required fields", Snackbar.LENGTH_SHORT).show()
            return
        }

        if (authMethodType == AuthMethodType.PASSWORD && password.isEmpty() && editingProfile == null) {
            Snackbar.make(binding.root, "Password is required", Snackbar.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val keyId = if (authMethodType == AuthMethodType.PRIVATE_KEY) {
                val keyName = binding.keySpinner.text.toString()
                viewModel.keys.value.firstOrNull { it.name == keyName }?.id
            } else null

            val profile = ConnectionProfile(
                id = editingProfile?.id ?: 0,
                name = name,
                host = host,
                port = port,
                username = username,
                authMethodType = authMethodType,
                keyId = keyId
            )

            viewModel.saveProfile(profile)

            // Store password if provided
            if (authMethodType == AuthMethodType.PASSWORD && password.isNotEmpty()) {
                // Password will be stored after connection
            }

            findNavController().popBackStack()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
