package com.socks5.vpn

import com.socks5.util.ChecksumUtils
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

// TCP flag constants (RFC 793)
const val TCP_FIN = 0x01
const val TCP_SYN = 0x02
const val TCP_RST = 0x04
const val TCP_PSH = 0x08
const val TCP_ACK = 0x10
const val TCP_URG = 0x20

/**
 * Parsed TCP header representation.
 */
data class TcpHeader(
    val srcPort: Int,
    val dstPort: Int,
    val sequenceNumber: Long,   // 32-bit unsigned, stored in Long
    val ackNumber: Long,        // 32-bit unsigned, stored in Long
    val dataOffset: Int,        // TCP header length in bytes (min 20)
    val flags: Int,             // FIN, SYN, RST, PSH, ACK, URG
    val windowSize: Int,
    val checksum: Int,
    val urgentPointer: Int,
    val options: ByteArray?,    // TCP options (if dataOffset > 20)
    val payload: ByteArray      // Application data
) {
    val syn: Boolean get() = (flags and TCP_SYN) != 0
    val ack: Boolean get() = (flags and TCP_ACK) != 0
    val fin: Boolean get() = (flags and TCP_FIN) != 0
    val rst: Boolean get() = (flags and TCP_RST) != 0
    val psh: Boolean get() = (flags and TCP_PSH) != 0

    /**
     * Length of application data in this segment.
     */
    val dataLength: Int get() = payload.size

    /**
     * Next expected sequence number (SEQ + data length + SYN/FIN).
     */
    val nextSeq: Long get() {
        var len = dataLength.toLong()
        if (syn) len++
        if (fin) len++
        return (sequenceNumber + len) and 0xFFFFFFFFL
    }

    companion object {
        const val MIN_HEADER_LENGTH = 20

        /**
         * Parse a TCP header from raw bytes (IP payload).
         *
         * Format (20 bytes minimum):
         *   0                   1                   2                   3
         *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
         *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         *  |          Source Port          |       Destination Port         |
         *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         *  |                        Sequence Number                         |
         *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         *  |                     Acknowledgment Number                      |
         *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         *  |  Data |       |U|A|P|R|S|F|                                  |
         *  | Offset| Rsrvd |R|C|S|S|Y|I|           Window                  |
         *  |       |       |G|K|H|T|N|N|                                  |
         *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         *  |           Checksum            |       Urgent Pointer           |
         *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         *  |                     Options (if Data Offset > 5)               |
         *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         *  |                     Application Data                           |
         *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         */
        fun parse(data: ByteArray): TcpHeader {
            if (data.size < MIN_HEADER_LENGTH) {
                throw IllegalArgumentException(
                    "TCP data too short: ${data.size} bytes (min $MIN_HEADER_LENGTH)"
                )
            }

            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

            val srcPort = buf.getShort().toInt() and 0xFFFF
            val dstPort = buf.getShort().toInt() and 0xFFFF
            val seqNum = buf.getInt().toLong() and 0xFFFFFFFFL
            val ackNum = buf.getInt().toLong() and 0xFFFFFFFFL

            val dataOffsetAndReserved = buf.getShort().toInt() and 0xFFFF
            val dataOffset = ((dataOffsetAndReserved shr 12) and 0xF) * 4
            val flags = dataOffsetAndReserved and 0x3F

            val window = buf.getShort().toInt() and 0xFFFF
            val checksum = buf.getShort().toInt() and 0xFFFF
            val urgent = buf.getShort().toInt() and 0xFFFF

            // Parse options if present
            val optionsLen = dataOffset - MIN_HEADER_LENGTH
            val options = if (optionsLen > 0) {
                val opts = ByteArray(optionsLen.coerceAtMost(data.size - MIN_HEADER_LENGTH))
                System.arraycopy(data, MIN_HEADER_LENGTH, opts, 0, opts.size)
                opts
            } else null

            // Payload starts after the TCP header
            val payloadStart = dataOffset.coerceAtMost(data.size)
            val payloadLen = (data.size - payloadStart).coerceAtLeast(0)
            val payload = ByteArray(payloadLen)
            if (payloadLen > 0) {
                System.arraycopy(data, payloadStart, payload, 0, payloadLen)
            }

            return TcpHeader(
                srcPort = srcPort,
                dstPort = dstPort,
                sequenceNumber = seqNum,
                ackNumber = ackNum,
                dataOffset = dataOffset,
                flags = flags,
                windowSize = window,
                checksum = checksum,
                urgentPointer = urgent,
                options = options,
                payload = payload
            )
        }
    }
}

/**
 * Builder for constructing TCP response packets (SYN-ACK, ACK, DATA, FIN, RST).
 */
class TcpPacketBuilder {
    var srcPort: Int = 0
    var dstPort: Int = 0
    var sequenceNumber: Long = 0
    var ackNumber: Long = 0
    var flags: Int = 0
    var windowSize: Int = 65535
    var payload: ByteArray = ByteArray(0)
    var options: ByteArray? = null

    // Source/destination IPs needed for pseudo-header checksum
    var srcIp: InetAddress? = null
    var dstIp: InetAddress? = null

    /**
     * Build a complete TCP segment (header + payload) with correct checksum.
     * Requires srcIp and dstIp to be set for pseudo-header checksum calculation.
     */
    fun build(): ByteArray {
        val src = srcIp ?: throw IllegalStateException("srcIp not set")
        val dst = dstIp ?: throw IllegalStateException("dstIp not set")

        val headerLen = MIN_HEADER_LENGTH + (options?.size ?: 0)
        val totalLen = headerLen + payload.size
        val buffer = ByteArray(totalLen)
        val buf = ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN)

        // Source Port
        buf.putShort(srcPort.toShort())
        // Destination Port
        buf.putShort(dstPort.toShort())
        // Sequence Number
        buf.putInt(sequenceNumber.toInt())
        // Ack Number
        buf.putInt(ackNumber.toInt())
        // Data Offset + Reserved + Flags
        val dataOffsetWords = headerLen / 4
        buf.putShort(((dataOffsetWords shl 12) or (flags and 0x3F)).toShort())
        // Window
        buf.putShort(windowSize.toShort())
        // Checksum (placeholder)
        buf.putShort(0)
        // Urgent Pointer
        buf.putShort(0)

        // Options
        options?.let { buf.put(it) }

        // Payload
        if (payload.isNotEmpty()) {
            buf.put(payload)
        }

        // Compute TCP checksum (with pseudo-header)
        val checksum = ChecksumUtils.tcpChecksum(
            srcIp = src.address,
            dstIp = dst.address,
            tcpSegment = buffer,
            offset = 0,
            length = totalLen
        )
        // Write checksum at offset 16
        buffer[16] = ((checksum shr 8) and 0xFF).toByte()
        buffer[17] = (checksum and 0xFF).toByte()

        return buffer
    }

    companion object {
        const val MIN_HEADER_LENGTH = 20
    }
}

/**
 * Utility to convert a Long sequence number to its 32-bit unsigned representation
 * with proper wraparound handling for comparison.
 */
object TcpSequence {
    /**
     * Compare two 32-bit sequence numbers, accounting for wraparound.
     * Returns true if a < b in TCP sequence space.
     *
     * RFC 1982: Serial Number Arithmetic
     */
    fun lessThan(a: Long, b: Long): Boolean {
        val diff = (b - a) and 0xFFFFFFFFL
        return diff in 1..0x7FFFFFFF
    }

    fun lessThanOrEqual(a: Long, b: Long): Boolean {
        return a == b || lessThan(a, b)
    }

    /** Increment a sequence number by n, handling wraparound */
    fun add(seq: Long, n: Long): Long = (seq + n) and 0xFFFFFFFFL

    /** Increment by 1 */
    fun inc(seq: Long): Long = add(seq, 1)
}
