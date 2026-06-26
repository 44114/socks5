package com.socks5.vpn

import kotlinx.coroutines.*
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks active TCP connections by 5-tuple key.
 */
data class ConnectionKey(
    val srcIp: InetAddress,
    val srcPort: Int,
    val dstIp: InetAddress,
    val dstPort: Int,
    val protocol: Int = IPPROTO_TCP
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConnectionKey) return false
        return srcIp == other.srcIp &&
                srcPort == other.srcPort &&
                dstIp == other.dstIp &&
                dstPort == other.dstPort &&
                protocol == other.protocol
    }

    override fun hashCode(): Int {
        var result = srcIp.hashCode()
        result = 31 * result + srcPort
        result = 31 * result + dstIp.hashCode()
        result = 31 * result + dstPort
        result = 31 * result + protocol
        return result
    }
}

class ConnectionPool(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onCountChanged: ((Int) -> Unit)? = null
) {
    /** Maximum concurrent TCP connections */
    var maxConnections: Int = 256

    /** Idle timeout for established connections (ms) */
    var idleTimeout: Long = 300_000L // 5 minutes

    /** Timeout for half-open connections (ms) */
    var handshakeTimeout: Long = 30_000L // 30 seconds

    private val connections = ConcurrentHashMap<ConnectionKey, TcpConnection>()
    private var cleanupJob: Job? = null

    /**
     * Get an existing connection or create a new one.
     */
    fun getOrCreate(
        key: ConnectionKey,
        factory: () -> TcpConnection
    ): TcpConnection? {
        // Check capacity
        if (connections.size >= maxConnections && !connections.containsKey(key)) {
            return null // Drop; pool is full
        }

        return connections.computeIfAbsent(key) {
            val conn = factory()
            onCountChanged?.invoke(connections.size)
            conn
        }
    }

    /**
     * Get an existing connection without creating.
     */
    fun get(key: ConnectionKey): TcpConnection? = connections[key]

    /**
     * Remove and close a connection.
     */
    fun remove(key: ConnectionKey) {
        connections.remove(key)?.close()
        onCountChanged?.invoke(connections.size)
    }

    /**
     * Close all connections.
     */
    fun closeAll() {
        connections.values.forEach { it.close() }
        connections.clear()
        onCountChanged?.invoke(0)
    }

    /**
     * Start periodic cleanup of expired connections.
     */
    fun startCleanup(intervalMs: Long = 10_000L) {
        cleanupJob?.cancel()
        cleanupJob = scope.launch {
            while (isActive) {
                delay(intervalMs)
                val now = System.currentTimeMillis()
                val iterator = connections.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    val conn = entry.value
                    if (conn.isExpired(now, idleTimeout, handshakeTimeout)) {
                        iterator.remove()
                        conn.close()
                    }
                }
                onCountChanged?.invoke(connections.size)
            }
        }
    }

    /**
     * Stop cleanup and close all.
     */
    fun stop() {
        cleanupJob?.cancel()
        closeAll()
    }

    val size: Int get() = connections.size
}
