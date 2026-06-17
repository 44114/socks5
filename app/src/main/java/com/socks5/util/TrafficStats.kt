package com.socks5.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe traffic statistics tracker.
 */
class TrafficStats {
    private val _bytesUp = AtomicLong(0)
    private val _bytesDown = AtomicLong(0)
    private val _packetsUp = AtomicLong(0)
    private val _packetsDown = AtomicLong(0)
    private val _activeConnections = AtomicLong(0)
    private val _startTime = AtomicLong(System.currentTimeMillis())

    private val _stats = MutableStateFlow(Snapshot())
    val stats: StateFlow<Snapshot> = _stats.asStateFlow()

    data class Snapshot(
        val bytesUp: Long = 0,
        val bytesDown: Long = 0,
        val packetsUp: Long = 0,
        val packetsDown: Long = 0,
        val activeConnections: Long = 0,
        val uptimeMs: Long = 0
    )

    fun recordUp(bytes: Long) {
        _bytesUp.addAndGet(bytes)
        _packetsUp.incrementAndGet()
        emitSnapshot()
    }

    fun recordDown(bytes: Long) {
        _bytesDown.addAndGet(bytes)
        _packetsDown.incrementAndGet()
        emitSnapshot()
    }

    fun updateConnections(count: Long) {
        _activeConnections.set(count)
        emitSnapshot()
    }

    fun reset() {
        _bytesUp.set(0)
        _bytesDown.set(0)
        _packetsUp.set(0)
        _packetsDown.set(0)
        _activeConnections.set(0)
        _startTime.set(System.currentTimeMillis())
        emitSnapshot()
    }

    private fun emitSnapshot() {
        _stats.value = Snapshot(
            bytesUp = _bytesUp.get(),
            bytesDown = _bytesDown.get(),
            packetsUp = _packetsUp.get(),
            packetsDown = _packetsDown.get(),
            activeConnections = _activeConnections.get(),
            uptimeMs = System.currentTimeMillis() - _startTime.get()
        )
    }

    companion object {
        fun formatBytes(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
                bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
                else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
            }
        }
    }
}
