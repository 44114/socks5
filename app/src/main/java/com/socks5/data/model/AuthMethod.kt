package com.socks5.data.model

/**
 * Authentication method for SSH connections.
 * Separate from Room entities — this is the runtime representation.
 */
sealed class AuthMethod {
    data class Password(val password: String) : AuthMethod()
    data class PrivateKey(
        val keyData: ByteArray,
        val passphrase: String? = null
    ) : AuthMethod() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PrivateKey) return false
            return keyData.contentEquals(other.keyData) && passphrase == other.passphrase
        }

        override fun hashCode(): Int {
            return keyData.contentHashCode() * 31 + (passphrase?.hashCode() ?: 0)
        }
    }
}
