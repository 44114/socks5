package com.socks5.vpn

import android.os.ParcelFileDescriptor
import com.socks5.ssh.SshConnectionManager
import com.socks5.util.TrafficStats
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Reads raw IP packets from the TUN device and dispatches them
 * to the appropriate handler (TCP or DNS).
 *
 * This is the main processing loop of the VPN.
 */
class PacketForwarder(
    private val tunFd: ParcelFileDescriptor,
    private val sshManager: SshConnectionManager,
    val trafficStats: TrafficStats = TrafficStats(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val inputStream = FileInputStream(tunFd.fileDescriptor)
    private val outputStream = FileOutputStream(tunFd.fileDescriptor)
    private val connectionPool = ConnectionPool(scope) { count ->
        trafficStats.updateConnections(count.toLong())
    }
    private lateinit var dnsResolver: DnsResolver
    private var running = false
    private var readJob: Job? = null

    // DNS server to use (configurable)
    var dnsServer: String = "1.1.1.1"

    // Custom hosts mappings (hostname → IP)
    var customHosts: Map<String, String> = emptyMap()

    /**
     * Start the packet forwarding loop.
     */
    fun start() {
        if (running) return
        running = true

        dnsResolver = DnsResolver(
            sshManager = sshManager,
            writeToTun = { packet ->
                writePacket(packet)
                trafficStats.recordDown(packet.size.toLong())
            },
            scope = scope,
            defaultDnsServer = dnsServer,
            customHosts = customHosts
        )

        connectionPool.startCleanup()

        readJob = scope.launch(Dispatchers.IO) {
            readLoop()
        }
    }

    /**
     * Stop packet forwarding and clean up.
     */
    fun stop() {
        running = false
        readJob?.cancel()
        connectionPool.stop()
        try {
            inputStream.close()
            outputStream.close()
        } catch (_: IOException) {}
    }

    /**
     * Main read loop — blocks on TUN fd reading IP packets.
     */
    private suspend fun CoroutineScope.readLoop() {
        val buffer = ByteArray(65535) // Max IP packet size

        while (running && isActive) {
            try {
                val length = withContext(Dispatchers.IO) {
                    inputStream.read(buffer)
                }

                if (length <= 0) {
                    if (running) delay(10) // Avoid busy-wait
                    continue
                }

                trafficStats.recordUp(length.toLong())

                val header = try {
                    IpHeader.parse(buffer, length)
                } catch (_: Exception) {
                    continue // Malformed packet, skip
                }

                when (header.protocol) {
                    IPPROTO_TCP -> handleTcpPacket(header)
                    IPPROTO_UDP -> handleUdpPacket(header)
                    // ICMP, IGMP, etc. — silently dropped
                }
            } catch (e: IOException) {
                if (running) {
                    // TUN fd may have been closed; re-throw to stop
                    break
                }
            } catch (e: CancellationException) {
                break
            }
        }
    }

    /**
     * Handle a TCP packet from the TUN.
     */
    private suspend fun handleTcpPacket(ipHeader: IpHeader) {
        val tcpHeader: TcpHeader
        try {
            tcpHeader = TcpHeader.parse(ipHeader.payload)
        } catch (e: Exception) {
            return // Malformed TCP
        }

        val key = ConnectionKey(
            srcIp = ipHeader.srcIp,
            srcPort = tcpHeader.srcPort,
            dstIp = ipHeader.dstIp,
            dstPort = tcpHeader.dstPort
        )

        // For SYN on a new connection, get or create
        if (tcpHeader.syn && !tcpHeader.ack) {
            val conn = connectionPool.getOrCreate(key) {
                TcpConnection(
                    key = key,
                    sshManager = sshManager,
                    writeToTun = { packet ->
                        writePacket(packet)
                        trafficStats.recordDown(packet.size.toLong())
                    },
                    scope = scope
                )
            }

            if (conn != null) {
                conn.handlePacket(ipHeader, tcpHeader)
            }
            // If null, pool is full — the SYN is silently dropped; app will retry
        } else {
            // Existing connection
            val conn = connectionPool.get(key)
            if (conn != null) {
                conn.handlePacket(ipHeader, tcpHeader)
            }
            // If no matching connection, the packet is dropped (stale connection)
        }
    }

    /**
     * Handle a UDP packet from the TUN.
     * Only DNS (port 53) is forwarded; everything else is dropped.
     */
    private fun handleUdpPacket(ipHeader: IpHeader) {
        val udpHeader: UdpHeader
        try {
            udpHeader = UdpHeader.parse(ipHeader.payload)
        } catch (e: Exception) {
            return
        }

        if (udpHeader.dstPort == 53 || udpHeader.srcPort == 53) {
            dnsResolver.handleDnsPacket(ipHeader, udpHeader)
        }
        // All other UDP silently dropped
    }

    /**
     * Write a constructed IP packet to the TUN device.
     * Thread-safe: synchronized on the output stream.
     */
    private fun writePacket(packet: ByteArray) {
        synchronized(outputStream) {
            try {
                outputStream.write(packet)
                outputStream.flush()
            } catch (_: IOException) {
                // TUN write error — likely the interface was closed
            }
        }
    }
}
