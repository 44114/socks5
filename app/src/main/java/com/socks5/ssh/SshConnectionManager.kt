package com.socks5.ssh

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.socks5.Socks5Application
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

class SshConnectionManager(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val jSch: JSch get() = Socks5Application.instance.jSch
    private var session: Session? = null
    private var keepAliveJob: Job? = null
    private var reconnectJob: Job? = null
    private var currentConfig: SshConfig? = null

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    data class SshConfig(
        val host: String,
        val port: Int = 22,
        val username: String,
        val authMethod: AuthMethod,
        val connectTimeout: Long = 15_000L,
        val keepAliveInterval: Long = 30_000L,
        val reconnectEnabled: Boolean = true
    )

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

    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data class Connecting(val host: String) : ConnectionState()
        data class Connected(val host: String) : ConnectionState()
        data class Error(val message: String, val cause: Throwable? = null) : ConnectionState()
    }

    /**
     * Connect to an SSH server with the given configuration.
     */
    suspend fun connect(config: SshConfig): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            _state.value = ConnectionState.Connecting(config.host)
            currentConfig = config

            val newSession = jSch.getSession(
                config.username,
                config.host,
                config.port
            )

            // Configure session
            newSession.setConfig("StrictHostKeyChecking", "no")
            newSession.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")
            newSession.setConfig("server_host_key",
                "ssh-rsa,ssh-dss,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,ssh-ed25519"
            )
            newSession.setConfig("PubkeyAcceptedAlgorithms",
                "ssh-rsa,rsa-sha2-256,rsa-sha2-512,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,ssh-ed25519"
            )
            newSession.setTimeout(config.connectTimeout.toInt())
            newSession.setServerAliveInterval(config.keepAliveInterval.toInt())
            newSession.setServerAliveCountMax(3)

            // Set authentication
            when (val auth = config.authMethod) {
                is AuthMethod.Password -> {
                    newSession.setPassword(auth.password)
                }
                is AuthMethod.PrivateKey -> {
                    val passphraseBytes = auth.passphrase?.toByteArray(Charsets.UTF_8)
                    jSch.addIdentity(
                        "key-${System.currentTimeMillis()}",
                        auth.keyData,
                        null,            // public key (derived from private)
                        passphraseBytes
                    )
                }
            }

            // Connect
            newSession.connect()

            session = newSession
            _state.value = ConnectionState.Connected(config.host)

            // Start keep-alive
            startKeepAlive()

            Result.success(Unit)
        } catch (e: Exception) {
            _state.value = ConnectionState.Error(
                message = e.message ?: "Connection failed",
                cause = e
            )
            Result.failure(e)
        }
    }

    /**
     * Disconnect from the SSH server.
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        runCatching {
            keepAliveJob?.cancel()
            keepAliveJob = null
            reconnectJob?.cancel()
            reconnectJob = null
            session?.disconnect()
            session = null
            currentConfig = null
        }
        _state.value = ConnectionState.Disconnected
    }

    /**
     * Open a direct-tcpip channel through the SSH tunnel to the given host:port.
     * This is the core tunneling primitive used by the VPN engine.
     */
    suspend fun createChannel(host: String, port: Int): ChannelDirectTCPIP = withContext(Dispatchers.IO) {
        val currentSession = session
            ?: throw IOException("SSH session not connected")

        try {
            val channel = currentSession.openChannel(
                "direct-tcpip"
            ) as ChannelDirectTCPIP

            channel.setHost(host)
            channel.setPort(port)
            // Set source host/port for the channel
            channel.setOrgIPAddress("127.0.0.1")
            channel.setOrgPort(0)
            channel.connect()

            channel
        } catch (e: Exception) {
            if (e.message?.contains("session is down") == true ||
                e.message?.contains("channel is not opened") == true
            ) {
                _state.value = ConnectionState.Error("SSH session lost", e)
                if (currentConfig?.reconnectEnabled == true) {
                    startReconnect()
                }
            }
            throw e
        }
    }

    /**
     * Get the underlying SSH socket for VPN protection.
     * Must be called to prevent routing the SSH connection through the VPN itself.
     */
    fun getSessionSocket(): java.net.Socket? {
        return session?.let {
            val socketField = Session::class.java.getDeclaredField("socket")
            socketField.isAccessible = true
            socketField.get(it) as? java.net.Socket
        }
    }

    /**
     * Open a shell channel for interactive terminal sessions.
     * Returns a ChannelShell ready for I/O.
     */
    suspend fun createShell(): ChannelShell = withContext(Dispatchers.IO) {
        val currentSession = session
            ?: throw IOException("SSH session not connected")

        try {
            val channel = currentSession.openChannel("shell") as ChannelShell

            // Configure terminal
            channel.setPtyType("xterm-256color", 80, 24, 0, 0)
            channel.setXForwarding(false)

            channel.connect()

            channel
        } catch (e: Exception) {
            if (e.message?.contains("session is down") == true) {
                _state.value = ConnectionState.Error("SSH session lost", e)
                if (currentConfig?.reconnectEnabled == true) {
                    startReconnect()
                }
            }
            throw e
        }
    }

    /**
     * Open a shell channel with specified terminal dimensions.
     */
    suspend fun createShell(cols: Int, rows: Int): ChannelShell = withContext(Dispatchers.IO) {
        val currentSession = session
            ?: throw IOException("SSH session not connected")

        val channel = currentSession.openChannel("shell") as ChannelShell
        channel.setPtyType("xterm-256color", cols, rows, 0, 0)
        channel.setXForwarding(false)
        channel.connect()

        channel
    }

    /**
     * Execute a single command on the remote server and return the output.
     * For non-interactive command execution.
     */
    suspend fun executeCommand(command: String): CommandResult = withContext(Dispatchers.IO) {
        val currentSession = session
            ?: throw IOException("SSH session not connected")

        var channel: ChannelExec? = null
        try {
            channel = currentSession.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            channel.setErrStream(System.err, true)

            val inputStream = channel.inputStream
            val errorStream = channel.errStream

            channel.connect()

            val stdout = inputStream.bufferedReader().readText()
            val stderr = errorStream?.bufferedReader()?.readText() ?: ""
            val exitStatus = channel.exitStatus

            CommandResult(
                stdout = stdout,
                stderr = stderr,
                exitStatus = exitStatus
            )
        } finally {
            channel?.disconnect()
        }
    }

    /**
     * Returns true if the SSH session is currently connected and authenticated.
     */
    fun isConnected(): Boolean {
        return session?.isConnected == true
    }

    /**
     * Result of a remote command execution.
     */
    data class CommandResult(
        val stdout: String,
        val stderr: String,
        val exitStatus: Int
    ) {
        val isSuccess: Boolean get() = exitStatus == 0
    }

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            val interval = currentConfig?.keepAliveInterval ?: 30_000L
            while (isActive) {
                delay(interval)
                runCatching {
                    session?.sendKeepAliveMsg()
                }.onFailure {
                    // Session might be dead; try reconnect
                    if (currentConfig?.reconnectEnabled == true) {
                        startReconnect()
                    }
                }
            }
        }
    }

    private fun startReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val config = currentConfig ?: return@launch
            var delay = 1000L
            val maxDelay = 8000L

            while (isActive && !isConnected()) {
                connect(config)
                if (isConnected()) break
                delay(delay)
                delay = (delay * 2).coerceAtMost(maxDelay)
            }
        }
    }
}
