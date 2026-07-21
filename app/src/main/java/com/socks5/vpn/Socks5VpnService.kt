package com.socks5.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.socks5.R
import com.socks5.Socks5Application
import com.socks5.ssh.SshConnectionManager
import com.socks5.ui.MainActivity
import com.socks5.util.NotificationHelper
import com.socks5.util.TrafficStats
import kotlinx.coroutines.*

/**
 * Android VpnService that creates a TUN device and routes all traffic
 * through the SSH tunnel.
 */
class Socks5VpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunInterface: ParcelFileDescriptor? = null
    private var packetForwarder: PacketForwarder? = null

    private val notificationHelper by lazy { NotificationHelper(this) }
    private val trafficStats = TrafficStats()

    // Retrieved from Application — set by MainViewModel before starting
    private val sshManager: SshConnectionManager?
        get() = (application as? Socks5Application)?.activeSshManager

    companion object {
        const val ACTION_CONNECT = "com.socks5.action.CONNECT"
        const val ACTION_DISCONNECT = "com.socks5.action.DISCONNECT"
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_DNS_SERVER = "dns_server"
        const val EXTRA_CUSTOM_HOSTS = "custom_hosts"

        // VPN parameters
        const val VPN_ADDRESS = "10.0.0.1"
        const val VPN_PREFIX_LENGTH = 24
        const val VPN_MTU = 1500
    }

    override fun onCreate() {
        super.onCreate()
    }

    // DNS server from preferences (configurable via Settings)
    private var dnsServer: String = "1.1.1.1"

    // Custom hosts mappings (configurable via Settings)
    private var customHosts: Map<String, String> = emptyMap()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Read DNS server from intent extra before starting
        intent?.getStringExtra(EXTRA_DNS_SERVER)?.let { dns ->
            if (dns.isNotBlank()) dnsServer = dns
        }

        // Read custom hosts from intent extra
        intent?.getStringExtra(EXTRA_CUSTOM_HOSTS)?.let { json ->
            try {
                val obj = org.json.JSONObject(json)
                val hosts = mutableMapOf<String, String>()
                for (key in obj.keys()) {
                    hosts[key] = obj.getString(key)
                }
                customHosts = hosts
            } catch (_: Exception) {
                customHosts = emptyMap()
            }
        }

        when (intent?.action) {
            ACTION_CONNECT -> startVpn()
            ACTION_DISCONNECT -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        val manager = sshManager
        if (manager == null || !manager.isConnected()) {
            stopSelf()
            return
        }

        scope.launch {
            try {
                // Build VPN configuration
                val builder = Builder()
                    .setSession("Socks5 Proxy")
                    .setMtu(VPN_MTU)
                    .addAddress(VPN_ADDRESS, VPN_PREFIX_LENGTH)
                    .addRoute("0.0.0.0", 0) // Route ALL IPv4 traffic
                    .addDnsServer(dnsServer)
                    .setBlocking(false)

                // Establish TUN interface
                tunInterface = withContext(Dispatchers.IO) {
                    builder.establish()
                } ?: run {
                    stopSelf()
                    return@launch
                }

                // Protect the SSH socket from being routed through the VPN
                // This prevents an infinite loop
                val socket = manager.getSessionSocket()
                if (socket != null) {
                    protect(socket)
                }

                // Start packet forwarding with configured DNS server
                packetForwarder = PacketForwarder(
                    tunFd = tunInterface!!,
                    sshManager = manager,
                    trafficStats = trafficStats,
                    scope = scope
                ).also {
                    it.dnsServer = dnsServer
                    it.customHosts = customHosts
                }
                packetForwarder?.start()
                trafficStats.reset()

                // Start foreground service with notification
                val openIntent = PendingIntent.getActivity(
                    this@Socks5VpnService,
                    0,
                    Intent(this@Socks5VpnService, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val disconnectIntent = PendingIntent.getService(
                    this@Socks5VpnService,
                    1,
                    Intent(this@Socks5VpnService, Socks5VpnService::class.java).apply {
                        action = ACTION_DISCONNECT
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notification = notificationHelper.buildVpnNotification(
                    status = getString(R.string.status_connected),
                    host = manager.state.value.let {
                        (it as? SshConnectionManager.ConnectionState.Connected)?.host ?: ""
                    },
                    traffic = "Initializing...",
                    openIntent = openIntent,
                    disconnectIntent = disconnectIntent
                )

                startForeground(NotificationHelper.NOTIFICATION_VPN_ID, notification)

                // Monitor traffic stats and update notification periodically
                monitorTraffic(openIntent, disconnectIntent)

            } catch (e: Exception) {
                stopVpn()
            }
        }
    }

    private fun monitorTraffic(openIntent: PendingIntent, disconnectIntent: PendingIntent) {
        scope.launch {
            trafficStats.stats.collect { stats ->
                val host = sshManager?.state?.value?.let {
                    (it as? SshConnectionManager.ConnectionState.Connected)?.host ?: ""
                } ?: ""

                val traffic = "↓ ${TrafficStats.formatBytes(stats.bytesDown)} ↑ ${TrafficStats.formatBytes(stats.bytesUp)}"

                val notification = notificationHelper.buildVpnNotification(
                    status = getString(R.string.status_connected),
                    host = host,
                    traffic = traffic,
                    openIntent = openIntent,
                    disconnectIntent = disconnectIntent
                )

                val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.notify(NotificationHelper.NOTIFICATION_VPN_ID, notification)
            }
        }
    }

    private fun stopVpn() {
        packetForwarder?.stop()
        packetForwarder = null

        tunInterface?.close()
        tunInterface = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        // User revoked VPN permission from system settings
        stopVpn()
        sshManager?.let { manager ->
            scope.launch { manager.disconnect() }
        }
    }

    override fun onDestroy() {
        stopVpn()
        scope.cancel()
        super.onDestroy()
    }
}
