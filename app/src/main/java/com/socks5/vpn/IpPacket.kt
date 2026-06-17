package com.socks5.vpn

import com.socks5.util.ChecksumUtils
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Protocol constants
const val IPPROTO_TCP = 6
const val IPPROTO_UDP = 17
const val IPPROTO_ICMP = 1

/**
 * Parsed IPv4 header representation.
 */
data class IpHeader(
    val version: Int,           // 4
    val ihl: Int,               // Internet Header Length in bytes (min 20)
    val dscp: Int,              // Differentiated Services Code Point
    val ecn: Int,               // Explicit Congestion Notification
    val totalLength: Int,       // Total packet length including header
    val identification: Int,    // Fragment identification
    val flags: Int,             // DF, MF flags
    val fragmentOffset: Int,    // Fragment offset in 8-byte units
    val ttl: Int,               // Time To Live
    val protocol: Int,          // 6=TCP, 17=UDP, 1=ICMP
    val headerChecksum: Int,    // Header checksum
    val srcIp: InetAddress,     // Source IP
    val dstIp: InetAddress,     // Destination IP
    val options: ByteArray?,    // IP options (if IHL > 20)
    val payload: ByteArray      // Transport layer data (TCP/UDP segment)
) {
    val isFragment: Boolean get() = (flags and 0x1) != 0 || fragmentOffset > 0
    val isDontFragment: Boolean get() = (flags and 0x2) != 0

    companion object {
        /**
         * Parse an IP packet from raw bytes read from the TUN device.
         * Auto-detects IPv4 vs IPv6 via the version nibble.
         */
        fun parse(data: ByteArray, length: Int): IpHeader {
            val version = (data[0].toInt() shr 4) and 0xF
            return when (version) {
                4 -> parseV4(data, length)
                6 -> parseV6(data, length)
                else -> throw IllegalArgumentException("Unknown IP version: $version")
            }
        }

        /**
         * Parse an IPv4 header (RFC 791).
         *
         * Format (20 bytes minimum):
         *   0                   1                   2                   3
         *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
         *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         *  |Version|  IHL  |    DSCP   |ECN|         Total Length          |
         *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         *  |         Identification        |Flags|     Fragment Offset      |
         *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         *  |  Time to Live |    Protocol   |        Header Checksum         |
         *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         *  |                        Source Address                           |
         *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         *  |                     Destination Address                         |
         *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         *  |                     Options (if IHL > 5)                        |
         *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         */
        fun parseV4(data: ByteArray, length: Int): IpHeader {
            val buf = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)

            val versionIhl = buf.get().toInt() and 0xFF
            val version = (versionIhl shr 4) and 0xF
            val ihl = (versionIhl and 0xF) * 4 // Convert 32-bit words to bytes

            val dscpEcn = buf.get().toInt() and 0xFF
            val dscp = (dscpEcn shr 2) and 0x3F
            val ecn = dscpEcn and 0x3

            val totalLength = buf.getShort().toInt() and 0xFFFF
            val identification = buf.getShort().toInt() and 0xFFFF

            val flagsFrag = buf.getShort().toInt() and 0xFFFF
            val flags = (flagsFrag shr 13) and 0x7
            val fragmentOffset = flagsFrag and 0x1FFF

            val ttl = buf.get().toInt() and 0xFF
            val protocol = buf.get().toInt() and 0xFF
            val headerChecksum = buf.getShort().toInt() and 0xFFFF

            val srcBytes = ByteArray(4)
            buf.get(srcBytes)
            val srcIp = InetAddress.getByAddress(srcBytes)

            val dstBytes = ByteArray(4)
            buf.get(dstBytes)
            val dstIp = InetAddress.getByAddress(dstBytes)

            // Parse options if present
            val optionsLen = ihl - 20
            val options = if (optionsLen > 0) {
                val opts = ByteArray(optionsLen)
                buf.get(opts)
                opts
            } else null

            // Remaining data is the payload
            val headerEnd = ihl
            val payloadLen = (totalLength - ihl).coerceAtMost(length - headerEnd).coerceAtLeast(0)
            val payload = ByteArray(payloadLen)
            if (payloadLen > 0) {
                System.arraycopy(data, headerEnd, payload, 0, payloadLen)
            }

            return IpHeader(
                version = version,
                ihl = ihl,
                dscp = dscp,
                ecn = ecn,
                totalLength = totalLength,
                identification = identification,
                flags = flags,
                fragmentOffset = fragmentOffset,
                ttl = ttl,
                protocol = protocol,
                headerChecksum = headerChecksum,
                srcIp = srcIp,
                dstIp = dstIp,
                options = options,
                payload = payload
            )
        }

        /**
         * Parse IPv6 header (RFC 8200).
         * Basic support — handles the fixed 40-byte header.
         * Extension headers are NOT parsed; the Next Header field is checked
         * only for TCP (6) and UDP (17).
         */
        fun parseV6(data: ByteArray, length: Int): IpHeader {
            val buf = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)

            val versionTraffic = buf.getInt()
            val version = (versionTraffic shr 28) and 0xF
            // Traffic class is bits 4-11 of first 32 bits
            val dscp = (versionTraffic shr 22) and 0x3F
            val ecn = (versionTraffic shr 20) and 0x3

            val payloadLength = buf.getShort().toInt() and 0xFFFF
            val nextHeader = buf.get().toInt() and 0xFF
            val hopLimit = buf.get().toInt() and 0xFF

            val srcBytes = ByteArray(16)
            buf.get(srcBytes)
            val srcIp = InetAddress.getByAddress(srcBytes)

            val dstBytes = ByteArray(16)
            buf.get(dstBytes)
            val dstIp = InetAddress.getByAddress(dstBytes)

            // Simplified: assume no extension headers, nextHeader is the transport protocol
            val headerLen = 40
            val payloadLen = payloadLength.coerceAtMost(length - headerLen).coerceAtLeast(0)
            val payload = ByteArray(payloadLen)
            if (payloadLen > 0) {
                System.arraycopy(data, headerLen, payload, 0, payloadLen)
            }

            return IpHeader(
                version = version,
                ihl = headerLen,
                dscp = dscp,
                ecn = ecn,
                totalLength = headerLen + payloadLen,
                identification = 0,
                flags = 0,
                fragmentOffset = 0,
                ttl = hopLimit,
                protocol = nextHeader,
                headerChecksum = 0, // IPv6 has no header checksum
                srcIp = srcIp,
                dstIp = dstIp,
                options = null,
                payload = payload
            )
        }
    }
}

/**
 * Builder for constructing IPv4 response packets to write back to TUN.
 */
class IpPacketBuilder {
    var dscp: Int = 0
    var ecn: Int = 0
    var identification: Int = 0
    var ttl: Int = 64
    var protocol: Int = IPPROTO_TCP
    var srcIp: InetAddress = InetAddress.getByName("0.0.0.0")
    var dstIp: InetAddress = InetAddress.getByName("0.0.0.0")
    var dontFragment: Boolean = true
    var payload: ByteArray = ByteArray(0)

    /**
     * Build a complete IPv4 packet (header + payload) with correct checksums.
     * NOTE: The transport layer checksum is NOT computed here; it must be
     * set in the payload before calling this method.
     */
    fun build(): ByteArray {
        val ihl = 20 // No IP options
        val totalLength = ihl + payload.size
        val buffer = ByteArray(totalLength)

        val buf = ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN)

        // Version + IHL
        buf.put(((4 shl 4) or (ihl / 4)).toByte())

        // DSCP + ECN
        buf.put(((dscp shl 2) or (ecn and 0x3)).toByte())

        // Total Length
        buf.putShort(totalLength.toShort())

        // Identification
        buf.putShort(identification.toShort())

        // Flags + Fragment Offset
        var flagsFrag = 0
        if (dontFragment) flagsFrag = flagsFrag or (0x2 shl 13)
        buf.putShort(flagsFrag.toShort())

        // TTL
        buf.put(ttl.toByte())

        // Protocol
        buf.put(protocol.toByte())

        // Header Checksum (placeholder, computed below)
        buf.putShort(0)

        // Source IP
        buf.put(srcIp.address)

        // Destination IP
        buf.put(dstIp.address)

        // Payload
        buf.put(payload)

        // Compute IP header checksum
        val checksum = ChecksumUtils.ipChecksum(buffer, 0, ihl)
        buffer[10] = ((checksum shr 8) and 0xFF).toByte()
        buffer[11] = (checksum and 0xFF).toByte()

        return buffer
    }
}
