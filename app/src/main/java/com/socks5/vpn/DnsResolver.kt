package com.socks5.vpn

import com.socks5.ssh.SshConnectionManager
import kotlinx.coroutines.*
import org.xbill.DNS.*
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Resolves DNS queries intercepted from the TUN device.
 *
 * Apps send DNS queries as UDP packets on port 53.
 * Since our SSH tunnel is TCP-only, we:
 * 1. Parse the DNS query from the UDP packet
 * 2. Resolve it via DNS-over-TCP through an SSH channel
 * 3. Build a UDP DNS response and write it back to the TUN
 */
class DnsResolver(
    private val sshManager: SshConnectionManager,
    private val writeToTun: (ByteArray) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val defaultDnsServer = "1.1.1.1"
    private val defaultDnsPort = 53

    // Cache for recent DNS resolutions (TTL-aware)
    private val cache = object : LinkedHashMap<String, CacheEntry>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > 256
        }
    }

    private data class CacheEntry(
        val addresses: List<InetAddress>,
        val expiresAt: Long
    )

    /**
     * Handle a DNS UDP packet intercepted from the TUN.
     */
    fun handleDnsPacket(ipHeader: IpHeader, udpHeader: UdpHeader) {
        // Determine the DNS server from the destination
        val dnsServer = (ipHeader.dstIp as? Inet4Address)?.hostAddress ?: defaultDnsServer

        scope.launch {
            try {
                val response = resolve(ipHeader, udpHeader, dnsServer)
                if (response != null) {
                    writeUdpResponse(ipHeader, udpHeader, response)
                }
            } catch (_: Exception) {
                // Drop silently; the app will retry
            }
        }
    }

    /**
     * Resolve a DNS query and return the raw DNS response bytes.
     */
    private suspend fun resolve(
        ipHeader: IpHeader,
        udpHeader: UdpHeader,
        dnsServer: String
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            // Parse the DNS query
            val query = Message(udpHeader.payload)
            val question = query.question

            // Check cache
            val cached = lookupCache(question)
            if (cached != null) return@withContext cached

            // Perform DNS over TCP through SSH
            val channel = sshManager.createChannel(dnsServer, defaultDnsPort)
            val output = DataOutputStream(channel.outputStream)
            val input = DataInputStream(channel.inputStream)

            // DNS over TCP: 2-byte length prefix
            output.writeShort(udpHeader.payload.size)
            output.write(udpHeader.payload)
            output.flush()

            // Read response
            val responseLen = input.readUnsignedShort()
            val responseBytes = ByteArray(responseLen)
            input.readFully(responseBytes)

            channel.disconnect()

            responseBytes
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Check the cache for a matching DNS response.
     * Returns a cached response with updated ID, or null if not found/expired.
     */
    private fun lookupCache(question: Record?): ByteArray? {
        if (question == null) return null

        val name = question.name.toString().lowercase()
        val type = question.type
        val entry = cache[name] ?: return null

        if (System.currentTimeMillis() > entry.expiresAt) {
            cache.remove(name)
            return null
        }

        // Build a response from cached addresses
        return buildDnsResponse(question, entry.addresses)
    }

    /**
     * Cache DNS response data for future queries.
     */
    private fun cacheResponse(question: Record?, response: ByteArray) {
        if (question == null) return

        try {
            val msg = Message(response)
            val addresses = msg.getSection(Section.ANSWER)
                .filterIsInstance<ARecord>()
                .map { it.address }

            if (addresses.isNotEmpty()) {
                val ttl = msg.getSection(Section.ANSWER)
                    .filterIsInstance<ARecord>()
                    .minOfOrNull { it.ttl } ?: 60L
                cache[msg.question.name.toString().lowercase()] = CacheEntry(
                    addresses = addresses,
                    expiresAt = System.currentTimeMillis() + ttl * 1000
                )
            }
        } catch (_: Exception) {}
    }

    /**
     * Write a UDP DNS response packet back to the TUN device.
     */
    private fun writeUdpResponse(inIp: IpHeader, inUdp: UdpHeader, dnsResponse: ByteArray) {
        try {
            // Build UDP payload
            val udpBuilder = UdpPacketBuilder().apply {
                srcPort = inUdp.dstPort  // Swap src/dst
                dstPort = inUdp.srcPort
                srcIp = inIp.dstIp       // Swap IPs
                dstIp = inIp.srcIp
                payload = dnsResponse
            }
            val udpPayload = udpBuilder.build()

            // Build IP packet
            val ipBuilder = IpPacketBuilder().apply {
                protocol = IPPROTO_UDP
                srcIp = inIp.dstIp
                dstIp = inIp.srcIp
                ttl = 64
                identification = (System.currentTimeMillis() and 0xFFFF).toInt()
                payload = udpPayload
            }
            val ipPacket = ipBuilder.build()

            // Write to TUN
            writeToTun(ipPacket)
        } catch (_: Exception) {}
    }

    /**
     * Build a minimal DNS response from cached addresses.
     */
    private fun buildDnsResponse(question: Record, addresses: List<InetAddress>): ByteArray? {
        if (addresses.isEmpty()) return null

        try {
            val response = Message()
            response.header = Header()
            response.header.setFlag(Flags.QR.toInt()) // This is a response
            response.addRecord(question, Section.QUESTION)

            for (addr in addresses) {
                val aRecord = ARecord(question.name, question.dClass, 60, addr as Inet4Address)
                response.addRecord(aRecord, Section.ANSWER)
            }

            return response.toWire()
        } catch (_: Exception) {
            return null
        }
    }
}
