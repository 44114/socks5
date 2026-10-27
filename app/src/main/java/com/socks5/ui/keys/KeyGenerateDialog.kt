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

/**
 * Dialog that creates a new SSH key pair.
 *
 * The key pair is generated in [MainViewModel]'s scope, never in this dialog's
 * view lifecycle: generation can take several seconds and the dialog view may be
 * torn down (rotation, dismiss) while it is still running.
 */
class KeyGenerateDialog : DialogFragment() {

    private val viewModel: MainViewModel by viewModels()
    private var isGenerating = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogKeyGenerateBinding.inflate(layoutInflater)

        setupAlgorithmSpinner(binding)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.key_generate_title)
            .setView(binding.root)
            .setPositiveButton(R.string.key_generate_confirm, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        // Override the positive button after show(): the default listener dismisses
        // the dialog immediately, but the form values must be read from views that
        // are still attached when the button is pressed.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!isGenerating) generateKey(binding)
            }
        }
        return dialog
    }

    private fun setupAlgorithmSpinner(binding: DialogKeyGenerateBinding) {
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            ALGORITHMS
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.algorithmSpinner.setAdapter(adapter)

        // Only RSA has a configurable key size; ECDSA/Ed25519 sizes are fixed.
        binding.algorithmSpinner.setOnItemClickListener { _, _, position, _ ->
            onAlgorithmSelected(binding, ALGORITHMS[position])
        }
        onAlgorithmSelected(binding, ALGORITHMS.first())
    }

    private fun onAlgorithmSelected(binding: DialogKeyGenerateBinding, algorithm: String) {
        val isRsa = algorithm == "RSA"
        binding.keySizeLayout.hint =
            getString(if (isRsa) R.string.key_size_rsa_hint else R.string.key_size_fixed_hint)
        binding.keySizeInput.setText(if (isRsa) DEFAULT_RSA_SIZE.toString() else FIXED_SIZE.toString())
        binding.keySizeLayout.isEnabled = isRsa
        binding.keySizeInput.isEnabled = isRsa
    }

    private fun generateKey(binding: DialogKeyGenerateBinding) {
        val name = binding.keyNameInput.text?.toString()?.trim()
            ?.ifEmpty { null }
            ?: getString(R.string.key_default_name, System.currentTimeMillis())
        val algorithm = binding.algorithmSpinner.text?.toString()?.ifEmpty { ALGORITHMS.first() }
            ?: ALGORITHMS.first()
        val keySize = if (algorithm == "RSA") {
            binding.keySizeInput.text?.toString()?.toIntOrNull()
                ?.coerceIn(MIN_RSA_SIZE, MAX_RSA_SIZE)
                ?: DEFAULT_RSA_SIZE
        } else {
            FIXED_SIZE
        }

        isGenerating = true
        val dialog = dialog as? AlertDialog
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
        dialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false

        viewModel.generateKey(name, algorithm, keySize) onComplete@{ error ->
            if (!isAdded) return@onComplete

            isGenerating = false
            dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
            dialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = true

            if (error == null) {
                dismissAllowingStateLoss()
            } else {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.key_generate_failed_title)
                    .setMessage(error.message ?: getString(R.string.key_generate_failed_message))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    companion object {
        const val TAG = "generate_key"

        private val ALGORITHMS = listOf("RSA", "ECDSA", "Ed25519")
        private const val DEFAULT_RSA_SIZE = 4096
        private const val MIN_RSA_SIZE = 2048
        private const val MAX_RSA_SIZE = 8192
        private const val FIXED_SIZE = 256

        @JvmStatic
        fun newInstance() = KeyGenerateDialog()
    }
}
