package com.socks5.vpn

import com.jcraft.jsch.ChannelDirectTCPIP
import com.socks5.ssh.SshConnectionManager
import kotlinx.coroutines.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress

/**
 * TCP connection state machine that terminates TCP locally and
 * relays application data through an SSH direct-tcpip channel.
 *
 * Sequence number tracking:
 *   - clientSeq/ackToClient: what the app sends and we acknowledge
 *   - serverSeq/ackFromClient: what we send and the app acknowledges
 */
class TcpConnection(
    val key: ConnectionKey,
    private val sshManager: SshConnectionManager,
    private val writeToTun: (ByteArray) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    // TCP state
    @Volatile var tcpState: TcpState = TcpState.CLOSED
        private set

    // Client's sequence space (observed from TUN packets)
    private var clientSeq: Long = 0     // Last SEQ from client
    private var ackToClient: Long = 0   // Next ACK we send to client

    // Our sequence space (for packets we fabricate)
    private var serverSeq: Long = 50000 // Starting sequence in our space
    @Volatile private var ackFromClient: Long = 0 // ACK received from client for our data

    // Initial server sequence (used to detect initial SYN-ACK ack)
    private var initialServerSeq: Long = 0

    // SSH channel
    private var channel: ChannelDirectTCPIP? = null
    private var channelReadJob: Job? = null
    private var channelWriterJob: Job? = null

    // Write queue for ordered, serialized writes to the SSH channel
    private val writeQueue = kotlinx.coroutines.channels.Channel<ByteArray>(kotlinx.coroutines.channels.Channel.UNLIMITED)

    // Buffers for data from SSH → TUN
    private val pendingData = ArrayDeque<ByteArray>()

    // Timestamps
    @Volatile var lastActivityMs: Long = System.currentTimeMillis()
        private set
    private val createdAtMs: Long = System.currentTimeMillis()

    // Buffer for out-of-order segments (simple: just track expected next seq)
    private var expectedNextSeq: Long = 0

    enum class TcpState {
        CLOSED, LISTEN, SYN_RCVD, ESTABLISHED,
        FIN_WAIT_1, FIN_WAIT_2, CLOSE_WAIT, LAST_ACK, TIME_WAIT
    }

    /**
     * Process an incoming TCP packet from the TUN device (app → us).
     */
    suspend fun handlePacket(ipHeader: IpHeader, tcpHeader: TcpHeader) {
        lastActivityMs = System.currentTimeMillis()

        when {
            tcpHeader.rst -> handleRst()
            tcpHeader.syn && !tcpHeader.ack -> handleSyn(ipHeader, tcpHeader)
            tcpHeader.fin -> handleFin(ipHeader, tcpHeader)
            else -> handleData(ipHeader, tcpHeader)
        }
    }

    /**
     * Handle initial SYN from the app.
     * Sets up the SSH channel and sends SYN-ACK back.
     */
    private suspend fun handleSyn(ipHeader: IpHeader, tcpHeader: TcpHeader) {
        if (tcpState != TcpState.CLOSED && tcpState != TcpState.LISTEN) return

        // Record client's initial sequence number
        clientSeq = tcpHeader.sequenceNumber
        expectedNextSeq = TcpSequence.inc(clientSeq)
        ackToClient = TcpSequence.inc(clientSeq) // We ACK seq+1

        // Generate our initial sequence number
        serverSeq = (System.currentTimeMillis() and 0x7FFFFFFF).toLong() % 1000000 + 50000
        initialServerSeq = serverSeq

        try {
            // Open SSH direct-tcpip channel
            val dstHost = (ipHeader.dstIp as? java.net.Inet4Address)?.hostAddress
                ?: ipHeader.dstIp.hostAddress
                ?: throw IOException("Cannot resolve destination IP")

            channel = sshManager.createChannel(dstHost, key.dstPort)

            tcpState = TcpState.SYN_RCVD

            // Send SYN-ACK
            sendTcpResponse(
                ipHeader = ipHeader,
                flags = TCP_SYN or TCP_ACK,
                seqNum = serverSeq,
                ackNum = ackToClient
            )

            // Start reading from SSH channel and writing to SSH channel
            startChannelReader()
            startChannelWriter()

        } catch (e: Exception) {
            // Connection refused — send RST
            sendTcpResponse(
                ipHeader = ipHeader,
                flags = TCP_RST or TCP_ACK,
                seqNum = 0,
                ackNum = ackToClient
            )
            tcpState = TcpState.CLOSED
        }
    }

    /**
     * Handle data/ACK packets from the app.
     */
    private suspend fun handleData(ipHeader: IpHeader, tcpHeader: TcpHeader) {
        val dataLen = tcpHeader.dataLength
        val seq = tcpHeader.sequenceNumber
        val ack = tcpHeader.ackNumber

        when (tcpState) {
            TcpState.SYN_RCVD -> {
                // Expect ACK for our SYN
                if (tcpHeader.ack) {
                    tcpState = TcpState.ESTABLISHED
                    ackFromClient = ack
                    // Send any queued data
                    flushPendingData()
                }
            }
            TcpState.ESTABLISHED, TcpState.FIN_WAIT_1, TcpState.FIN_WAIT_2 -> {
                // Update ACK tracking
                if (tcpHeader.ack) {
                    ackFromClient = ack
                }

                // Handle data payload
                if (dataLen > 0 && channel != null) {
                    // Update expected next sequence
                    clientSeq = TcpSequence.add(seq, dataLen.toLong())
                    ackToClient = TcpSequence.add(seq, dataLen.toLong())

                    // Forward data to SSH channel
                    writeToChannel(tcpHeader.payload)

                    // Send ACK back
                    sendTcpResponse(
                        ipHeader = ipHeader,
                        flags = TCP_ACK,
                        seqNum = serverSeq,
                        ackNum = ackToClient
                    )
                }
            }
            TcpState.LAST_ACK, TcpState.TIME_WAIT -> {
                // Ignore late data
            }
            else -> {
                // Unexpected state — send RST
                sendTcpResponse(
                    ipHeader = ipHeader,
                    flags = TCP_RST,
                    seqNum = ack,
                    ackNum = 0
                )
            }
        }
    }

    /**
     * Handle FIN from the app.
     */
    private suspend fun handleFin(ipHeader: IpHeader, tcpHeader: TcpHeader) {
        if (tcpHeader.dataLength > 0) {
            // Data + FIN: process data first, then FIN
            handleData(ipHeader, tcpHeader)
        }

        clientSeq = TcpSequence.inc(tcpHeader.sequenceNumber)
        ackToClient = TcpSequence.inc(tcpHeader.sequenceNumber) // ACK the FIN

        when (tcpState) {
            TcpState.ESTABLISHED -> {
                tcpState = TcpState.CLOSE_WAIT

                // Send ACK for the FIN
                sendTcpResponse(
                    ipHeader = ipHeader,
                    flags = TCP_ACK,
                    seqNum = serverSeq,
                    ackNum = ackToClient
                )

                // Close the SSH channel write side
                closeChannel()

                // Send our FIN
                sendTcpResponse(
                    ipHeader = ipHeader,
                    flags = TCP_FIN or TCP_ACK,
                    seqNum = serverSeq,
                    ackNum = ackToClient
                )
                serverSeq = TcpSequence.inc(serverSeq)
                tcpState = TcpState.LAST_ACK
            }
            TcpState.FIN_WAIT_1 -> {
                // Got FIN while waiting for ACK of our FIN — send final ACK
                sendTcpResponse(
                    ipHeader = ipHeader,
                    flags = TCP_ACK,
                    seqNum = serverSeq,
                    ackNum = ackToClient
                )
                tcpState = TcpState.TIME_WAIT
            }
            else -> {
                // Already closing — ACK and transition
                sendTcpResponse(
                    ipHeader = ipHeader,
                    flags = TCP_ACK,
                    seqNum = serverSeq,
                    ackNum = ackToClient
                )
            }
        }
    }

    /**
     * Handle RST from the app — immediate teardown.
     */
    private fun handleRst() {
        tcpState = TcpState.CLOSED
        closeChannel()
    }

    /**
     * Write data received from the SSH channel to the TUN as a TCP packet.
     * Called from the channel reader coroutine.
     */
    private fun writeDataToTun(data: ByteArray) {
        if (tcpState != TcpState.ESTABLISHED && tcpState != TcpState.FIN_WAIT_1 && tcpState != TcpState.FIN_WAIT_2) {
            // Queue for later if connection not yet established
            pendingData.addLast(data)
            return
        }

        doWriteDataToTun(data)
    }

    private fun doWriteDataToTun(data: ByteArray) {
        lastActivityMs = System.currentTimeMillis()

        // Build and send a TCP data packet
        val src = key.dstIp
        val dst = key.srcIp
        val srcPort = key.dstPort
        val dstPort = key.srcPort

        val tcpBuilder = TcpPacketBuilder().apply {
            this.srcPort = srcPort
            this.dstPort = dstPort
            this.sequenceNumber = serverSeq
            this.ackNumber = ackToClient
            this.flags = TCP_ACK or TCP_PSH
            this.windowSize = 65535
            this.payload = data
            this.srcIp = src
            this.dstIp = dst
        }
        val tcpSegment = tcpBuilder.build()

        val ipBuilder = IpPacketBuilder().apply {
            this.protocol = IPPROTO_TCP
            this.srcIp = src
            this.dstIp = dst
            this.ttl = 64
            this.identification = (System.currentTimeMillis() and 0xFFFF).toInt()
            this.payload = tcpSegment
        }
        val ipPacket = ipBuilder.build()

        writeToTun(ipPacket)

        // Advance our sequence number
        serverSeq = TcpSequence.add(serverSeq, data.size.toLong())
    }

    /**
     * Send a TCP control packet (SYN-ACK, ACK, FIN, RST) to the TUN.
     */
    private fun sendTcpResponse(
        ipHeader: IpHeader,
        flags: Int,
        seqNum: Long,
        ackNum: Long,
        payload: ByteArray = ByteArray(0)
    ) {
        val src = ipHeader.dstIp
        val dst = ipHeader.srcIp

        val tcpBuilder = TcpPacketBuilder().apply {
            srcPort = key.dstPort
            dstPort = key.srcPort
            sequenceNumber = seqNum
            ackNumber = ackNum
            this.flags = flags
            windowSize = 65535
            this.payload = payload
            srcIp = src
            dstIp = dst
        }
        val tcpSegment = tcpBuilder.build()

        val ipBuilder = IpPacketBuilder().apply {
            protocol = IPPROTO_TCP
            srcIp = src
            dstIp = dst
            ttl = 64
            identification = (System.currentTimeMillis() and 0xFFFF).toInt()
            this.payload = tcpSegment
        }
        val ipPacket = ipBuilder.build()

        writeToTun(ipPacket)
    }

    /**
     * Start the coroutine that reads from the SSH channel and writes to TUN.
     */
    private fun startChannelReader() {
        channelReadJob = scope.launch(Dispatchers.IO) {
            val input: InputStream = channel?.inputStream ?: return@launch
            val buffer = ByteArray(32768)

            try {
                while (isActive && (tcpState == TcpState.SYN_RCVD ||
                        tcpState == TcpState.ESTABLISHED ||
                        tcpState == TcpState.FIN_WAIT_1 ||
                        tcpState == TcpState.FIN_WAIT_2 ||
                        tcpState == TcpState.CLOSE_WAIT)
                ) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead == -1) break
                    if (bytesRead > 0) {
                        writeDataToTun(buffer.copyOf(bytesRead))
                    }
                }
            } catch (_: IOException) {
                // Channel closed
            } catch (_: InterruptedException) {
                // Cancelled
            }

            // SSH channel closed — initiate close from our side
            if (tcpState == TcpState.ESTABLISHED) {
                tcpState = TcpState.FIN_WAIT_1
                // The app will eventually send FIN or timeout
            }
        }
    }

    /**
     * Start the sequential writer coroutine that drains the write queue.
     * Must be called after the SSH channel is opened.
     */
    private fun startChannelWriter() {
        channelWriterJob = scope.launch(Dispatchers.IO) {
            try {
                for (data in writeQueue) {
                    channel?.outputStream?.let { out ->
                        out.write(data)
                        out.flush()
                    }
                }
            } catch (_: IOException) {
                // Channel write error or closed
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Writer cancelled
            }
        }
    }

    /**
     * Enqueue data for ordered, sequential writing to the SSH channel.
     */
    private fun writeToChannel(data: ByteArray) {
        writeQueue.trySend(data)
    }

    /**
     * Flush any data queued before the connection was established.
     */
    private fun flushPendingData() {
        val data = pendingData
        pendingData.clear()
        for (d in data) {
            doWriteDataToTun(d)
        }
    }

    /**
     * Close the SSH channel and cancel all coroutines.
     */
    fun close() {
        tcpState = TcpState.CLOSED
        closeChannel()
    }

    private fun closeChannel() {
        channelReadJob?.cancel()
        channelWriterJob?.cancel()
        writeQueue.close()
        try {
            channel?.disconnect()
        } catch (_: Exception) {}
        channel = null
    }

    /**
     * Check if this connection has expired (idle timeout or stuck in half-open).
     */
    fun isExpired(now: Long, idleTimeout: Long, handshakeTimeout: Long): Boolean {
        return when (tcpState) {
            TcpState.CLOSED, TcpState.TIME_WAIT -> true
            TcpState.SYN_RCVD -> (now - createdAtMs) > handshakeTimeout
            else -> {
                // Idle timeout for established connections
                val idle = now - lastActivityMs
                if (idle > idleTimeout) return true

                // Overall connection timeout (15 minutes max)
                if ((now - createdAtMs) > 900_000L) return true

                false
            }
        }
    }
}
