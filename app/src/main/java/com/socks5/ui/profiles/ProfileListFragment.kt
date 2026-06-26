package com.socks5.ui.profiles

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.socks5.R
import com.socks5.data.model.ConnectionProfile
import com.socks5.databinding.FragmentProfileListBinding
import com.socks5.ui.MainViewModel
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class ProfileListFragment : Fragment(R.layout.fragment_profile_list) {

    private var _binding: FragmentProfileListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: ProfileAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ProfileAdapter(
            onConnect = { profile ->
                viewModel.connect(profile)
                findNavController().navigate(R.id.action_profiles_to_connection)
            },
            onEdit = { profile ->
                viewModel.selectProfile(profile)
                findNavController().navigate(R.id.action_profiles_to_detail)
            },
            onDelete = { profile ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Delete Profile")
                    .setMessage("Delete \"${profile.name}\"?")
                    .setPositiveButton("Delete") { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            viewModel.deleteProfile(profile)
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )

        binding.profileList.layoutManager = LinearLayoutManager(requireContext())
        binding.profileList.adapter = adapter

        binding.addProfileButton.setOnClickListener {
            viewModel.selectProfile(null) // Clear selection for new profile
            findNavController().navigate(R.id.action_profiles_to_detail)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.profiles.collect { profiles ->
                adapter.submitList(profiles)
                binding.emptyText.visibility =
                    if (profiles.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
