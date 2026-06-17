package com.socks5.ssh

import com.jcraft.jsch.ChannelShell
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages interactive shell sessions over an SSH connection.
 * Supports multiple concurrent terminal sessions with resize capability.
 */
class ShellSessionManager(
    private val sshManager: SshConnectionManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val sessions = ConcurrentHashMap<String, ShellSession>()

    /**
     * Represents a single interactive shell session.
     */
    class ShellSession(
        val id: String = UUID.randomUUID().toString(),
        val channel: ChannelShell,
        private val sessionScope: CoroutineScope
    ) {
        val inputStream: InputStream = channel.inputStream
        val outputStream: OutputStream = channel.outputStream

        private val _output = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 64)
        val output: SharedFlow<ByteArray> = _output.asSharedFlow()

        private val _isActive = MutableStateFlow(true)
        val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

        private var readJob: Job? = null

        init {
            startReading()
        }

        private fun startReading() {
            readJob = sessionScope.launch(Dispatchers.IO) {
                val buffer = ByteArray(8192)
                try {
                    while (isActive) {
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead == -1) break
                        _output.emit(buffer.copyOf(bytesRead))
                    }
                } catch (e: Exception) {
                    // Channel closed or error
                } finally {
                    _isActive.value = false
                }
            }
        }

        /**
         * Write data to the shell (user input).
         */
        suspend fun write(data: ByteArray) {
            if (!_isActive.value) return
            withContext(Dispatchers.IO) {
                try {
                    outputStream.write(data)
                    outputStream.flush()
                } catch (e: Exception) {
                    _isActive.value = false
                }
            }
        }

        /**
         * Write a string to the shell.
         */
        suspend fun writeString(text: String) {
            write(text.toByteArray(Charsets.UTF_8))
        }

        /**
         * Resize the PTY to new dimensions.
         */
        fun resize(cols: Int, rows: Int) {
            if (!_isActive.value) return
            try {
                channel.setPtySize(cols, rows, 0, 0)
            } catch (e: Exception) {
                // Ignore resize errors
            }
        }

        /**
         * Send a signal to the remote process.
         */
        fun sendSignal(signal: String) {
            if (!_isActive.value) return
            try {
                channel.sendSignal(signal)
            } catch (e: Exception) {
                // Ignore
            }
        }

        /**
         * Close this shell session.
         */
        fun close() {
            _isActive.value = false
            readJob?.cancel()
            try {
                channel.disconnect()
            } catch (_: Exception) {}
        }
    }

    /**
     * Create a new shell session with default terminal dimensions (80x24).
     */
    suspend fun createSession(): ShellSession {
        val channel = sshManager.createShell()
        val session = ShellSession(channel = channel, sessionScope = scope)
        sessions[session.id] = session
        return session
    }

    /**
     * Create a new shell session with specified terminal dimensions.
     */
    suspend fun createSession(cols: Int, rows: Int): ShellSession {
        val channel = sshManager.createShell(cols, rows)
        val session = ShellSession(channel = channel, sessionScope = scope)
        sessions[session.id] = session
        return session
    }

    /**
     * Get an existing session by ID.
     */
    fun getSession(id: String): ShellSession? = sessions[id]

    /**
     * Get all active sessions.
     */
    fun getActiveSessions(): List<ShellSession> = sessions.values.filter { it.isActive.value }

    /**
     * Close a specific session.
     */
    fun closeSession(id: String) {
        sessions.remove(id)?.close()
    }

    /**
     * Close all sessions.
     */
    fun closeAll() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    /**
     * Number of active sessions.
     */
    val sessionCount: Int get() = sessions.count { it.value.isActive.value }
}
