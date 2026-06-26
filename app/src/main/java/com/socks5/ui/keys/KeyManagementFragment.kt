package com.socks5.ui.keys

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.socks5.R
import com.socks5.databinding.FragmentKeyManagementBinding
import com.socks5.ui.MainViewModel
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class KeyManagementFragment : Fragment(R.layout.fragment_key_management) {

    private var _binding: FragmentKeyManagementBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: KeyAdapter

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

        adapter = KeyAdapter { key ->
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete Key")
                .setMessage("Delete \"${key.name}\"? This cannot be undone.")
                .setPositiveButton("Delete") { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        viewModel.deleteKey(key)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.keyList.layoutManager = LinearLayoutManager(requireContext())
        binding.keyList.adapter = adapter

        binding.importKeyButton.setOnClickListener {
            // TODO: Launch file picker for PEM files
            // For now, show the generate dialog
            KeyGenerateDialog().show(childFragmentManager, "generate_key")
        }

        binding.generateKeyButton.setOnClickListener {
            KeyGenerateDialog().show(childFragmentManager, "generate_key")
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.keys.collect { keys ->
                adapter.submitList(keys)
                binding.emptyText.visibility =
                    if (keys.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
