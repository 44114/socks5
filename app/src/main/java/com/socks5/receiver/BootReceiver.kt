package com.socks5.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.socks5.data.preferences.AppPreferences

/**
 * Receiver for auto-starting the VPN on device boot (if enabled in settings).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val preferences = AppPreferences(context)
        if (!preferences.startOnBoot) return

        val profileId = preferences.lastUsedProfileId
        if (profileId <= 0) return

        // The app needs to handle the actual connection logic
        // This receiver just ensures the app knows boot happened
        // Connection will be initiated when the user opens the app
        // or if we had a persistent service (but VpnService requires user interaction)
    }
}
