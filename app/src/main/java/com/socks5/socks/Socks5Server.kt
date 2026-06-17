package com.socks5.socks

import com.socks5.ssh.SshConnectionManager
import kotlinx.coroutines.*

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Local SOCKS5 proxy server (RFC 1928).
 *
 * Listens on a configurable local port and forwards connections
 * through the SSH tunnel. Useful for apps that support SOCKS5
 * proxy settings directly, or as an alternative to the VPN path.
 */
class Socks5Server(
    private val sshManager: SshConnectionManager,
    private val host: String = "127.0.0.1",
    private val port: Int = 1080,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    @Volatile var isRunning: Boolean = false
        private set

    /**
     * Start the SOCKS5 server.
     */
    suspend fun start(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            serverSocket = ServerSocket()
            serverSocket?.reuseAddress = true
            serverSocket?.bind(InetSocketAddress(host, port))
            isRunning = true

            acceptJob = scope.launch {
                acceptLoop()
            }

            Result.success(serverSocket?.localPort ?: port)
        } catch (e: Exception) {
            isRunning = false
            Result.failure(e)
        }
    }

    /**
     * Stop the SOCKS5 server and close all connections.
     */
    fun stop() {
        isRunning = false
        acceptJob?.cancel()
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
    }

    /**
     * Get the actual port the server is listening on.
     */
    fun getPort(): Int = serverSocket?.localPort ?: port

    /**
     * Accept loop — spawns a handler per client connection.
     */
    private suspend fun CoroutineScope.acceptLoop() {
        val socket = serverSocket ?: return

        while (isActive && isRunning) {
            try {
                val clientSocket = withContext(Dispatchers.IO) {
                    socket.accept()
                }

                // Spawn a handler for this client
                launch {
                    try {
                        val handler = Socks5Handler(
                            clientSocket = clientSocket,
                            sshManager = sshManager,
                            scope = this
                        )
                        handler.handle()
                    } catch (_: Exception) {
                        try { clientSocket.close() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    // Server socket closed unexpectedly
                    break
                }
            }
        }
    }
}
