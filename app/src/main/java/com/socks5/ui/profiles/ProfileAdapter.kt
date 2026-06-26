package com.socks5.ui.profiles

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.socks5.data.model.ConnectionProfile
import com.socks5.databinding.ItemProfileBinding

class ProfileAdapter(
    private val onConnect: (ConnectionProfile) -> Unit,
    private val onEdit: (ConnectionProfile) -> Unit,
    private val onDelete: (ConnectionProfile) -> Unit
) : ListAdapter<ConnectionProfile, ProfileAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemProfileBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), onConnect, onEdit, onDelete)
    }

    class ViewHolder(private val binding: ItemProfileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(
            profile: ConnectionProfile,
            onConnect: (ConnectionProfile) -> Unit,
            onEdit: (ConnectionProfile) -> Unit,
            onDelete: (ConnectionProfile) -> Unit
        ) {
            binding.profileName.text = profile.name
            binding.profileHost.text = "${profile.username}@${profile.host}:${profile.port}"
            binding.profileAuth.text = profile.authMethodType.name

            binding.connectButton.setOnClickListener { onConnect(profile) }
            binding.editButton.setOnClickListener { onEdit(profile) }
            binding.deleteButton.setOnClickListener { onDelete(profile) }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<ConnectionProfile>() {
        override fun areItemsTheSame(old: ConnectionProfile, new: ConnectionProfile): Boolean {
            return old.id == new.id
        }

        override fun areContentsTheSame(old: ConnectionProfile, new: ConnectionProfile): Boolean {
            return old == new
        }
    }
}
