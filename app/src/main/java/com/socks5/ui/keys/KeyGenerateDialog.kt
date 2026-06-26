package com.socks5.ui.keys

import android.app.Dialog
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.socks5.R
import com.socks5.databinding.DialogKeyGenerateBinding
import com.socks5.ui.MainViewModel
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class KeyGenerateDialog : DialogFragment() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogKeyGenerateBinding.inflate(layoutInflater)

        setupAlgorithmSpinner(binding)

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle("Generate SSH Key")
            .setView(binding.root)
            .setPositiveButton("Generate") { _, _ ->
                generateKey(binding)
            }
            .setNegativeButton("Cancel", null)
            .create()
    }

    private fun setupAlgorithmSpinner(binding: DialogKeyGenerateBinding) {
        val algorithms = listOf("RSA", "ECDSA", "Ed25519")
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            algorithms
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.algorithmSpinner.setAdapter(adapter)

        binding.algorithmSpinner.onItemSelectedListener = null
        // Default key sizes update based on selection
    }

    private fun generateKey(binding: DialogKeyGenerateBinding) {
        val name = binding.keyNameInput.text.toString().trim().ifEmpty { "Key ${System.currentTimeMillis()}" }
        val algorithm = binding.algorithmSpinner.text.toString().ifEmpty { "RSA" }
        val keySize = when (algorithm) {
            "RSA" -> binding.keySizeInput.text.toString().toIntOrNull() ?: 4096
            "ECDSA" -> 256
            "Ed25519" -> 256
            else -> 2048
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                viewModel.generateKey(name, algorithm, keySize)
            } catch (e: Exception) {
                // Error shown via ViewModel error flow
            }
        }
    }
}
