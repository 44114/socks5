package com.socks5.vpn

import com.socks5.util.ChecksumUtils
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parsed UDP header representation (RFC 768).
 */
data class UdpHeader(
    val srcPort: Int,
    val dstPort: Int,
    val length: Int,        // Total UDP datagram length (header + data)
    val checksum: Int,
    val payload: ByteArray  // Application data (e.g., DNS query)
) {
    companion object {
        const val HEADER_LENGTH = 8

        /**
         * Parse a UDP header from raw bytes (IP payload).
         *
         * Format:
         *   0      7 8     15 16    23 24    31
         *  +--------+--------+--------+--------+
         *  |     Source Port   |  Destination Port|
         *  +--------+--------+--------+--------+
         *  |     Length        |     Checksum     |
         *  +--------+--------+--------+--------+
         *  |          data octets ...
         *  +---------------- ...
         */
        fun parse(data: ByteArray): UdpHeader {
            if (data.size < HEADER_LENGTH) {
                throw IllegalArgumentException(
                    "UDP data too short: ${data.size} bytes (min $HEADER_LENGTH)"
                )
            }

            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

            val srcPort = buf.getShort().toInt() and 0xFFFF
            val dstPort = buf.getShort().toInt() and 0xFFFF
            val length = buf.getShort().toInt() and 0xFFFF
            val checksum = buf.getShort().toInt() and 0xFFFF

            val payloadLen = (length - HEADER_LENGTH).coerceAtMost(data.size - HEADER_LENGTH).coerceAtLeast(0)
            val payload = ByteArray(payloadLen)
            if (payloadLen > 0) {
                System.arraycopy(data, HEADER_LENGTH, payload, 0, payloadLen)
            }

            return UdpHeader(
                srcPort = srcPort,
                dstPort = dstPort,
                length = length,
                checksum = checksum,
                payload = payload
            )
        }
    }
}

/**
 * Builder for constructing UDP response packets.
 */
class UdpPacketBuilder {
    var srcPort: Int = 0
    var dstPort: Int = 0
    var srcIp: InetAddress? = null
    var dstIp: InetAddress? = null
    var payload: ByteArray = ByteArray(0)

    /**
     * Build a complete UDP datagram with checksum.
     */
    fun build(): ByteArray {
        val src = srcIp ?: throw IllegalStateException("srcIp not set")
        val dst = dstIp ?: throw IllegalStateException("dstIp not set")

        val totalLen = UdpHeader.HEADER_LENGTH + payload.size
        val buffer = ByteArray(totalLen)
        val buf = ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN)

        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort(totalLen.toShort())
        buf.putShort(0) // Checksum placeholder

        if (payload.isNotEmpty()) {
            buf.put(payload)
        }

        // Compute UDP checksum (with pseudo-header)
        val checksum = ChecksumUtils.udpChecksum(
            srcIp = src.address,
            dstIp = dst.address,
            udpDatagram = buffer,
            offset = 0,
            length = totalLen
        )
        buffer[6] = ((checksum shr 8) and 0xFF).toByte()
        buffer[7] = (checksum and 0xFF).toByte()

        return buffer
    }
}
