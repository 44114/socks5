package com.socks5.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.socks5.R

class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_VPN = "vpn_status"
        const val NOTIFICATION_VPN_ID = 1
    }

    init {
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_VPN,
                context.getString(R.string.notification_channel_vpn),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN connection status"
                setShowBadge(false)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    fun buildVpnNotification(
        status: String,
        host: String,
        traffic: String,
        openIntent: PendingIntent,
        disconnectIntent: PendingIntent
    ): Notification {
        return NotificationCompat.Builder(context, CHANNEL_VPN)
            .setContentTitle("Socks5 Proxy: $status")
            .setContentText("$host — $traffic")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", disconnectIntent)
            .build()
    }

    fun buildConnectingNotification(
        host: String,
        openIntent: PendingIntent
    ): Notification {
        return NotificationCompat.Builder(context, CHANNEL_VPN)
            .setContentTitle("Connecting...")
            .setContentText(context.getString(R.string.notification_vpn_connecting, host))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .build()
    }
}
