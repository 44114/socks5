package com.socks5.util

/**
 * IP and TCP/UDP checksum calculation utilities.
 *
 * Checksums use the one's complement of the one's complement sum
 * of 16-bit words, as specified in RFC 791 (IP) and RFC 793 (TCP).
 */
object ChecksumUtils {

    /**
     * Calculate the IP header checksum (RFC 791).
     * The checksum field itself (bytes 10-11) must be zeroed before calling.
     *
     * @param data Full IP packet data
     * @param offset Start of IP header
     * @param length Length of IP header in bytes (IHL * 4)
     */
    fun ipChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length

        // Sum 16-bit words
        while (i < end - 1) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word.toLong()
            i += 2
        }

        // Handle odd byte
        if (i < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8).toLong()
        }

        return foldChecksum(sum)
    }

    /**
     * Calculate TCP checksum (RFC 793) including the IPv4 pseudo-header.
     *
     * Pseudo-header fields:
     *   - Source IP address (4 bytes)
     *   - Destination IP address (4 bytes)
     *   - Zero (1 byte)
     *   - Protocol (1 byte) = 6 for TCP
     *   - TCP segment length (2 bytes) = TCP header + payload
     *
     * @param srcIp Source IPv4 address (4 bytes, network order)
     * @param dstIp Destination IPv4 address (4 bytes, network order)
     * @param tcpSegment TCP header + payload data
     * @param offset Start of TCP segment within the data array
     * @param length Length of TCP segment (header + payload)
     */
    fun tcpChecksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        tcpSegment: ByteArray,
        offset: Int,
        length: Int
    ): Int {
        var sum = 0L

        // Pseudo-header: source IP
        sum += (((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)).toLong()
        sum += (((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)).toLong()

        // Pseudo-header: destination IP
        sum += (((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)).toLong()
        sum += (((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)).toLong()

        // Pseudo-header: protocol (6 = TCP)
        sum += 6L

        // Pseudo-header: TCP length
        sum += length.toLong()

        // TCP segment
        var i = offset
        val end = offset + length
        while (i < end - 1) {
            val word = ((tcpSegment[i].toInt() and 0xFF) shl 8) or (tcpSegment[i + 1].toInt() and 0xFF)
            sum += word.toLong()
            i += 2
        }

        // Handle odd byte
        if (i < end) {
            sum += ((tcpSegment[i].toInt() and 0xFF) shl 8).toLong()
        }

        return foldChecksum(sum)
    }

    /**
     * Calculate UDP checksum (RFC 768) including the IPv4 pseudo-header.
     * Same pseudo-header as TCP but protocol = 17.
     */
    fun udpChecksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        udpDatagram: ByteArray,
        offset: Int,
        length: Int
    ): Int {
        var sum = 0L

        // Pseudo-header
        sum += (((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)).toLong()
        sum += (((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)).toLong()
        sum += (((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)).toLong()
        sum += (((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)).toLong()
        sum += 17L // UDP protocol
        sum += length.toLong()

        var i = offset
        val end = offset + length
        while (i < end - 1) {
            val word = ((udpDatagram[i].toInt() and 0xFF) shl 8) or (udpDatagram[i + 1].toInt() and 0xFF)
            sum += word.toLong()
            i += 2
        }
        if (i < end) {
            sum += ((udpDatagram[i].toInt() and 0xFF) shl 8).toLong()
        }

        return foldChecksum(sum)
    }

    /**
     * Fold a 32-bit sum into a 16-bit one's complement checksum.
     */
    private fun foldChecksum(sum: Long): Int {
        var s = sum
        // Fold carries
        while (s shr 16 > 0) {
            s = (s and 0xFFFF) + (s shr 16)
        }
        // One's complement
        return (s.toInt() xor 0xFFFF) and 0xFFFF
    }

    /**
     * Validate an IP header checksum.
     * The entire header (including the checksum field) should sum to 0xFFFF.
     */
    fun verifyIpChecksum(data: ByteArray, offset: Int, length: Int): Boolean {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i < end - 1) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word.toLong()
            i += 2
        }
        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.toInt() and 0xFFFF) == 0xFFFF
    }
}
