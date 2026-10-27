package com.socks5.ui.keys

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.socks5.data.model.SshKey
import com.socks5.databinding.ItemKeyBinding

class KeyAdapter(
    private val onDelete: (SshKey) -> Unit,
    private val onCopyPublicKey: (SshKey) -> Unit
) : ListAdapter<SshKey, KeyAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemKeyBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), onDelete, onCopyPublicKey)
    }

    class ViewHolder(private val binding: ItemKeyBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(
            key: SshKey,
            onDelete: (SshKey) -> Unit,
            onCopyPublicKey: (SshKey) -> Unit
        ) {
            binding.keyName.text = key.name
            binding.keyAlgorithm.text = if (key.keySize > 0) {
                "${key.algorithm} ${key.keySize}bit"
            } else {
                key.algorithm
            }
            binding.keyFingerprint.text = key.fingerprint

            binding.copyButton.isEnabled = key.publicKey.isNotBlank()
            binding.copyButton.setOnClickListener { onCopyPublicKey(key) }
            binding.deleteButton.setOnClickListener { onDelete(key) }

            // Tapping the card copies the public key as well.
            binding.root.setOnClickListener {
                if (key.publicKey.isNotBlank()) onCopyPublicKey(key)
            }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<SshKey>() {
        override fun areItemsTheSame(old: SshKey, new: SshKey): Boolean =
            old.id == new.id

        override fun areContentsTheSame(old: SshKey, new: SshKey): Boolean =
            old == new
    }
}
