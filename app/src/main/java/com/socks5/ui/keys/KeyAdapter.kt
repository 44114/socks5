package com.socks5.ui.keys

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.socks5.data.model.SshKey
import com.socks5.databinding.ItemKeyBinding

class KeyAdapter(
    private val onDelete: (SshKey) -> Unit
) : ListAdapter<SshKey, KeyAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemKeyBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), onDelete)
    }

    class ViewHolder(private val binding: ItemKeyBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(key: SshKey, onDelete: (SshKey) -> Unit) {
            binding.keyName.text = key.name
            binding.keyAlgorithm.text = "${key.algorithm} ${key.keySize}bit"
            binding.keyFingerprint.text = key.fingerprint

            binding.deleteButton.setOnClickListener { onDelete(key) }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<SshKey>() {
        override fun areItemsTheSame(old: SshKey, new: SshKey): Boolean =
            old.id == new.id

        override fun areContentsTheSame(old: SshKey, new: SshKey): Boolean =
            old == new
    }
}
