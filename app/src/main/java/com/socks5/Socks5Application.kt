package com.socks5

import android.app.Application
import com.jcraft.jsch.JSch
import com.socks5.ssh.SshConnectionManager
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class Socks5Application : Application() {

    val jSch: JSch by lazy { JSch() }

    /**
     * Active SSH connection manager, shared between UI and VpnService.
     * Set by MainViewModel when connecting, accessed by Socks5VpnService.
     */
    @Volatile
    var activeSshManager: SshConnectionManager? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Register Bouncy Castle for Ed25519 and ECDSA support on API < 29
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    companion object {
        lateinit var instance: Socks5Application
            private set
    }
}
