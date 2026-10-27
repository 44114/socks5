package com.socks5.ui.keys

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.socks5.R
import com.socks5.data.model.SshKey
import com.socks5.databinding.FragmentKeyManagementBinding
import com.socks5.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KeyManagementFragment : Fragment() {

    private var _binding: FragmentKeyManagementBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: KeyAdapter

    /**
     * Import a private key file chosen by the user.
     *
     * The dialog is shown from the fragment (not the picker callback) so it is
     * never lost: the result is delivered after this fragment resumes, and the
     * parsed string is handed straight to [KeyImportDialog].
     */
    private val pickKeyFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null || !isAdded) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            val content = withContext(Dispatchers.IO) {
                runCatching {
                    requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readBytes()
                        if (bytes.size > MAX_KEY_FILE_SIZE) {
                            throw IllegalArgumentException("Key file is too large")
                        }
                        String(bytes, Charsets.UTF_8)
                    }
                }
            }
            if (!isAdded || _binding == null) return@launch

            content.onSuccess { text ->
                if (text.isNullOrBlank()) {
                    Snackbar.make(binding.root, R.string.key_import_empty, Snackbar.LENGTH_LONG).show()
                } else {
                    val suggestedName = uri.lastPathSegment
                        ?.substringAfterLast('/')
                        ?.substringBeforeLast('.')
                        ?.takeIf { it.isNotBlank() }
                    KeyImportDialog.newInstance(text, suggestedName)
                        .show(childFragmentManager, KeyImportDialog.TAG)
                }
            }.onFailure { error ->
                Snackbar.make(
                    binding.root,
                    getString(R.string.key_import_failed, error.message ?: ""),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentKeyManagementBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = KeyAdapter(
            onDelete = { key -> confirmDelete(key) },
            onCopyPublicKey = { key -> copyPublicKey(key) }
        )

        binding.keyList.layoutManager = LinearLayoutManager(requireContext())
        binding.keyList.adapter = adapter

        binding.importKeyButton.setOnClickListener {
            // Pick an existing private key file (PEM / OpenSSH format).
            pickKeyFile.launch(arrayOf("*/*"))
        }

        binding.generateKeyButton.setOnClickListener {
            if (childFragmentManager.findFragmentByTag(KeyGenerateDialog.TAG) == null) {
                KeyGenerateDialog.newInstance().show(childFragmentManager, KeyGenerateDialog.TAG)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.keys.collect { keys ->
                adapter.submitList(keys)
                binding.emptyText.visibility =
                    if (keys.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun confirmDelete(key: SshKey) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.key_delete_title)
            .setMessage(getString(R.string.key_delete_message, key.name))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.deleteKey(key)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun copyPublicKey(key: SshKey) {
        if (key.publicKey.isBlank()) return
        val clipboard = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.key_public_key_label, key.name), key.publicKey)
        )
        Snackbar.make(binding.root, R.string.key_public_key_copied, Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.keyList.adapter = null
        _binding = null
    }

    companion object {
        private const val MAX_KEY_FILE_SIZE = 64 * 1024
    }
}
