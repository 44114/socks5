package com.socks5.socks

import com.socks5.ssh.SshConnectionManager
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * RFC 1928 SOCKS5 per-client handler.
 *
 * Handles the SOCKS5 protocol for a single client connection:
 *   1. Greeting (auth method negotiation)
 *   2. Request (CONNECT command)
 *   3. Relay (bidirectional data forwarding through SSH)
 */
class Socks5Handler(
    private val clientSocket: Socket,
    private val sshManager: SshConnectionManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    // SOCKS5 constants
    companion object {
        const val VERSION = 0x05
        const val METHOD_NO_AUTH = 0x00
        const val METHOD_USERNAME_PASSWORD = 0x02
        const val METHOD_NO_ACCEPTABLE = 0xFF

        const val CMD_CONNECT = 0x01
        const val CMD_BIND = 0x02
        const val CMD_UDP_ASSOCIATE = 0x03

        const val ATYP_IPV4 = 0x01
        const val ATYP_DOMAINNAME = 0x03
        const val ATYP_IPV6 = 0x04

        const val REP_SUCCESS = 0x00
        const val REP_GENERAL_FAILURE = 0x01
        const val REP_CONNECTION_NOT_ALLOWED = 0x02
        const val REP_NETWORK_UNREACHABLE = 0x03
        const val REP_HOST_UNREACHABLE = 0x04
        const val REP_CONNECTION_REFUSED = 0x05
        const val REP_TTL_EXPIRED = 0x06
        const val REP_COMMAND_NOT_SUPPORTED = 0x07
        const val REP_ADDRESS_TYPE_NOT_SUPPORTED = 0x08
    }

    suspend fun handle() = withContext(Dispatchers.IO) {
        try {
            clientSocket.soTimeout = 30_000 // 30s SOCKS5 handshake timeout

            val input = clientSocket.getInputStream()
            val output = clientSocket.getOutputStream()

            // Step 1: Greeting / Auth Method Negotiation
            val version = input.read()
            if (version != VERSION) {
                clientSocket.close()
                return@withContext
            }

            val nMethods = input.read()
            if (nMethods < 1) {
                clientSocket.close()
                return@withContext
            }

            val methods = ByteArray(nMethods)
            input.readNBytes(methods, 0, nMethods)

            // We support "no auth required" and "username/password"
            val selectedMethod = when {
                methods.contains(METHOD_NO_AUTH.toByte()) -> METHOD_NO_AUTH
                methods.contains(METHOD_USERNAME_PASSWORD.toByte()) -> METHOD_USERNAME_PASSWORD
                else -> METHOD_NO_ACCEPTABLE
            }

            // Send method selection
            output.write(byteArrayOf(VERSION.toByte(), selectedMethod.toByte()))
            output.flush()

            if (selectedMethod == METHOD_NO_ACCEPTABLE) {
                clientSocket.close()
                return@withContext
            }

            // Handle username/password authentication if selected
            if (selectedMethod == METHOD_USERNAME_PASSWORD) {
                val authResult = handleUsernamePasswordAuth(input, output)
                if (!authResult) {
                    clientSocket.close()
                    return@withContext
                }
            }

            // Step 2: Request
            val reqVersion = input.read()
            if (reqVersion != VERSION) {
                sendReply(output, REP_GENERAL_FAILURE)
                clientSocket.close()
                return@withContext
            }

            val command = input.read()
            input.read() // RSV (0x00)
            val addressType = input.read()

            // Only CONNECT is supported for now
            if (command != CMD_CONNECT) {
                sendReply(output, REP_COMMAND_NOT_SUPPORTED)
                clientSocket.close()
                return@withContext
            }

            // Parse destination address
            val (host, port) = when (addressType) {
                ATYP_IPV4 -> {
                    val addr = ByteArray(4)
                    input.readNBytes(addr, 0, 4)
                    val p1 = input.read()
                    val p2 = input.read()
                    if (p1 < 0 || p2 < 0) throw Exception("Incomplete request")
                    InetAddress.getByAddress(addr).hostAddress to ((p1 shl 8) or p2)
                }
                ATYP_DOMAINNAME -> {
                    val len = input.read()
                    if (len < 1) throw Exception("Invalid domain name length")
                    val domain = ByteArray(len)
                    input.readNBytes(domain, 0, len)
                    val p1 = input.read()
                    val p2 = input.read()
                    if (p1 < 0 || p2 < 0) throw Exception("Incomplete request")
                    String(domain, Charsets.UTF_8) to ((p1 shl 8) or p2)
                }
                ATYP_IPV6 -> {
                    val addr = ByteArray(16)
                    input.readNBytes(addr, 0, 16)
                    val p1 = input.read()
                    val p2 = input.read()
                    if (p1 < 0 || p2 < 0) throw Exception("Incomplete request")
                    InetAddress.getByAddress(addr).hostAddress to ((p1 shl 8) or p2)
                }
                else -> {
                    sendReply(output, REP_ADDRESS_TYPE_NOT_SUPPORTED)
                    clientSocket.close()
                    return@withContext
                }
            }

            // Step 3: Open SSH channel to the requested destination
            try {
                val channel = sshManager.createChannel(host, port)
                val channelInput = channel.inputStream
                val channelOutput = channel.outputStream

                // Send success reply
                // Bind address: 0.0.0.0:0 (we don't have a meaningful bind address)
                sendReply(output, REP_SUCCESS, "0.0.0.0", 0)

                // Reset timeout for data relay
                clientSocket.soTimeout = 0

                // Step 4: Bidirectional relay
                relay(clientSocket, channelInput, channelOutput)
            } catch (e: Exception) {
                val replyCode = when {
                    e.message?.contains("refused") == true -> REP_CONNECTION_REFUSED
                    e.message?.contains("unreachable") == true -> REP_HOST_UNREACHABLE
                    else -> REP_GENERAL_FAILURE
                }
                sendReply(output, replyCode)
            }
        } catch (_: Exception) {
            // Connection error or timeout
        } finally {
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    /**
     * Handle RFC 1929 Username/Password authentication.
     */
    private fun handleUsernamePasswordAuth(input: InputStream, output: OutputStream): Boolean {
        try {
            val authVersion = input.read()
            if (authVersion != 0x01) return false

            val usernameLen = input.read()
            if (usernameLen < 1 || usernameLen > 255) return false
            val username = ByteArray(usernameLen)
            input.readNBytes(username, 0, usernameLen)

            val passwordLen = input.read()
            if (passwordLen < 1 || passwordLen > 255) return false
            val password = ByteArray(passwordLen)
            input.readNBytes(password, 0, passwordLen)

            // For SOCKS5 local proxy, we accept any credentials
            // (authentication is handled at the SSH layer)
            output.write(byteArrayOf(0x01, 0x00)) // Version, Status (0=success)
            output.flush()
            return true
        } catch (_: Exception) {
            output.write(byteArrayOf(0x01, 0xFF.toByte())) // Failure
            output.flush()
            return false
        }
    }

    /**
     * Send a SOCKS5 reply.
     */
    private fun sendReply(
        output: OutputStream,
        replyCode: Int,
        bindHost: String = "0.0.0.0",
        bindPort: Int = 0
    ) {
        val addr = InetAddress.getByName(bindHost) as? Inet4Address
            ?: InetAddress.getByName("0.0.0.0") as Inet4Address
        val addrBytes = addr.address

        val reply = byteArrayOf(
            VERSION.toByte(),
            replyCode.toByte(),
            0x00,               // RSV
            ATYP_IPV4.toByte(),  // Address type = IPv4
            addrBytes[0], addrBytes[1], addrBytes[2], addrBytes[3], // Bind address
            ((bindPort shr 8) and 0xFF).toByte(),
            (bindPort and 0xFF).toByte()
        )

        output.write(reply)
        output.flush()
    }

    /**
     * Bidirectional relay between the SOCKS5 client and the SSH channel.
     * Uses two coroutines: client→SSH and SSH→client.
     */
    private suspend fun relay(
        clientSocket: Socket,
        channelInput: InputStream,
        channelOutput: OutputStream
    ) = coroutineScope {
        val clientInput = clientSocket.getInputStream()
        val clientOutput = clientSocket.getOutputStream()

        val clientToSsh = launch(Dispatchers.IO) {
            try {
                val buffer = ByteArray(8192)
                while (isActive) {
                    val len = clientInput.read(buffer)
                    if (len == -1) break
                    channelOutput.write(buffer, 0, len)
                    channelOutput.flush()
                }
            } catch (_: Exception) {}
        }

        val sshToClient = launch(Dispatchers.IO) {
            try {
                val buffer = ByteArray(8192)
                while (isActive) {
                    val len = channelInput.read(buffer)
                    if (len == -1) break
                    clientOutput.write(buffer, 0, len)
                    clientOutput.flush()
                }
            } catch (_: Exception) {}
        }

        // Wait for either direction to finish
        joinAll(clientToSsh, sshToClient)

        // Cleanup
        clientToSsh.cancel()
        sshToClient.cancel()
    }
}
