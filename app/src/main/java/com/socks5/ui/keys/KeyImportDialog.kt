package com.socks5.ui.keys

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.socks5.R
import com.socks5.databinding.DialogKeyImportBinding
import com.socks5.ui.MainViewModel

/**
 * Dialog that imports a private key the user picked from storage.
 *
 * The key text is passed as a fragment argument and may be encrypted with a
 * passphrase; the passphrase field is optional.
 */
class KeyImportDialog : DialogFragment() {

    private val viewModel: MainViewModel by viewModels()
    private var isImporting = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogKeyImportBinding.inflate(layoutInflater)

        val suggestedName = arguments?.getString(ARG_NAME)
        if (!suggestedName.isNullOrBlank()) {
            binding.keyNameInput.setText(suggestedName)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.key_import_title)
            .setView(binding.root)
            .setPositiveButton(R.string.key_import_confirm, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!isImporting) importKey(binding)
            }
        }
        return dialog
    }

    private fun importKey(binding: DialogKeyImportBinding) {
        val keyText = arguments?.getString(ARG_KEY_TEXT).orEmpty()
        if (keyText.isBlank()) {
            fail(getString(R.string.key_import_empty))
            return
        }

        val name = binding.keyNameInput.text?.toString()?.trim()
            ?.ifEmpty { null }
            ?: getString(R.string.key_default_import_name, System.currentTimeMillis())
        val passphrase = binding.passphraseInput.text?.toString()?.takeIf { it.isNotEmpty() }

        isImporting = true
        val dialog = dialog as? AlertDialog
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
        dialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false

        viewModel.importKey(name, keyText, passphrase) onComplete@{ error ->
            if (!isAdded) return@onComplete

            isImporting = false
            dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
            dialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = true

            if (error == null) {
                dismissAllowingStateLoss()
            } else {
                fail(error.message ?: getString(R.string.key_import_failed_message))
            }
        }
    }

    private fun fail(message: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.key_import_failed_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    companion object {
        const val TAG = "import_key"
        private const val ARG_KEY_TEXT = "key_text"
        private const val ARG_NAME = "key_name"

        fun newInstance(keyText: String, suggestedName: String?): KeyImportDialog =
            KeyImportDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_KEY_TEXT, keyText)
                    putString(ARG_NAME, suggestedName)
                }
            }
    }
}
