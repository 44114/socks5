# Socks5 Proxy

An Android SSH client that provides a SOCKS5 proxy via VPN (Android VpnService), routing all device traffic through an encrypted SSH tunnel. Supports RSA, ECDSA, and Ed25519 key exchange algorithms with both password and SSH private key authentication. Also includes an interactive terminal/CLI for direct shell access to the remote server.

## Features

- **VPN-Based Traffic Routing** — Routes all device IPv4 traffic through an SSH tunnel using Android's built-in VpnService API. No root required.
- **SOCKS5 Proxy** — Built-in RFC 1928 compliant SOCKS5 server on `127.0.0.1:1080` for app-level proxy use.
- **SSH Key Algorithms** — Full support for RSA (up to 4096-bit), ECDSA (P-256/P-384/P-521), and Ed25519.
- **Dual Authentication** — Password login and SSH private key authentication, with encrypted passphrase support.
- **Interactive Terminal / CLI** — Open shell sessions to the remote server directly within the app. Supports PTY resize and multiple concurrent sessions.
- **DNS over TCP** — Intercepts UDP DNS queries (port 53) from the TUN device and resolves them via DNS-over-TCP through the SSH tunnel.
- **Secure Key Storage** — SSH private keys are encrypted with AES-256-GCM using the Android Keystore (hardware-backed on supported devices).
- **Encrypted Preferences** — Passwords and key passphrases stored in EncryptedSharedPreferences.
- **Connection Profiles** — Save multiple server configurations with different authentication methods. Quick-switch between profiles.
- **Auto-Reconnect** — Exponential backoff reconnection on connection loss. Configurable keep-alive interval.
- **Auto-Start on Boot** — Optionally start the VPN automatically when the device boots.
- **Traffic Statistics** — Real-time upload/download byte counters displayed in the notification and main screen.
- **Foreground Service** — Persistent notification with connection status and traffic info.
- **Material 3 UI** — Modern Android UI with bottom navigation, dark mode support, and Material Design 3 components.

## Architecture

```
Device Apps → TUN Device → PacketForwarder → TcpConnection (per-connection TCP state machine)
→ SSH direct-tcpip channel → Remote SSH Server → Internet
```

The app creates a TUN network interface (via `VpnService`) at `10.0.0.1/24`, routing all device traffic (`0.0.0.0/0`) through it. A custom TCP state machine terminates TCP connections locally — fabricating SYN-ACK, ACK, DATA, FIN/RST packets — and forwards application-layer data through SSH `direct-tcpip` channels. This approach requires no NDK/JNI: the entire VPN engine is pure Kotlin.

DNS queries (UDP port 53) are intercepted from the TUN device, parsed, and resolved via DNS-over-TCP through the SSH tunnel, with an LRU cache for recent resolutions.

The SSH socket is protected via `VpnService.protect()` to prevent it from being routed through the VPN itself (loopback prevention).

### Project Structure

```
socks5/
├── app/src/main/java/com/socks5/
│   ├── Socks5Application.kt          # Application subclass, JSch + Bouncy Castle init
│   ├── crypto/
│   │   └── KeyStoreManager.kt        # Android Keystore AES-256-GCM encryption
│   ├── data/
│   │   ├── db/                        # Room database (AppDatabase, ProfileDao, KeyDao)
│   │   ├── model/                     # Entities: ConnectionProfile, SshKey, AuthMethod
│   │   ├── preferences/               # EncryptedSharedPreferences + regular prefs
│   │   └── repository/                # ProfileRepository, KeyRepository
│   ├── receiver/
│   │   └── BootReceiver.kt            # Auto-start on device boot
│   ├── socks/
│   │   ├── Socks5Handler.kt           # Per-client SOCKS5 CONNECT handler
│   │   └── Socks5Server.kt            # Local SOCKS5 proxy server (RFC 1928)
│   ├── ssh/
│   │   ├── HostKeyManager.kt          # SSH host key verification + fingerprint
│   │   ├── ShellSessionManager.kt     # Interactive shell session management
│   │   └── SshConnectionManager.kt    # SSH session lifecycle, auth, channels
│   ├── ui/
│   │   ├── MainActivity.kt            # Bottom navigation host
│   │   ├── MainViewModel.kt           # Central ViewModel (StateFlow-based)
│   │   ├── connection/                # Connection status + quick connect
│   │   ├── keys/                      # Key management (list, generate, import)
│   │   ├── profiles/                  # Profile CRUD
│   │   └── settings/                  # App settings
│   ├── util/
│   │   ├── ChecksumUtils.kt           # IP/TCP/UDP checksum calculation
│   │   ├── NotificationHelper.kt      # Foreground service notification builder
│   │   └── TrafficStats.kt            # AtomicLong-based traffic counters
│   └── vpn/
│       ├── ConnectionPool.kt          # 5-tuple connection tracking (256 max)
│       ├── DnsResolver.kt             # DNS-over-TCP through SSH tunnel
│       ├── IpPacket.kt                # IPv4/IPv6 header parse + build
│       ├── PacketForwarder.kt         # TUN read loop, protocol dispatch
│       ├── Socks5VpnService.kt        # Android VpnService subclass
│       ├── TcpConnection.kt           # TCP state machine + SSH channel relay
│       ├── TcpPacket.kt               # TCP header parse + build + checksum
│       └── UdpPacket.kt               # UDP header parse + build
```

## Requirements

- **Android 7.0 (API 24)** or higher
- **Target SDK**: 34 (Android 14)
- An SSH server with password or key-based authentication
- Gradle 8.x and Android SDK (for building from source)

## Build

```bash
# Clone the repository
git clone https://github.com/example/socks5-proxy.git
cd socks5-proxy

# Set up the Gradle wrapper (if not included)
gradle wrapper --gradle-version 8.4

# Build the debug APK
./gradlew assembleDebug

# The APK will be at:
# app/build/outputs/apk/debug/app-debug.apk
```

Set `ANDROID_HOME` or create a `local.properties` file pointing to your Android SDK:

```
sdk.dir=/path/to/Android/Sdk
```

## Usage

### Quick Start

1. **Add a Connection Profile** — Go to the Profiles tab, tap the + button, and fill in your SSH server details (host, port 22, username, authentication method).
2. **Add a Key (if using key auth)** — Go to the Keys tab to import an existing private key or generate a new one.
3. **Connect** — From the Connection tab or Profile list, tap Connect. Grant VPN permission when prompted.
4. **Done** — Once connected, all device traffic is routed through the SSH tunnel. The persistent notification shows connection status and traffic statistics.

### SOCKS5 Proxy Mode

For app-level proxy use (without VPN), configure your apps or browser to use:

```
Host: 127.0.0.1
Port: 1080
Type: SOCKS5
```

### Terminal / CLI

Open a shell session to the remote server — useful for administration, file management, or running commands on the server side.

### Settings

| Setting | Description | Default |
|---|---|---|
| Auto-connect on start | Connect to last-used profile on app open | Off |
| Start on boot | Auto-start VPN when device boots | Off |
| DNS Server | DNS server used by the VPN interface | 1.1.1.1 |
| Keep-alive interval | SSH keep-alive message interval (seconds) | 60 |
| Local SOCKS5 port | Port for the built-in SOCKS5 proxy | 1080 |

## Technical Details

### TCP State Machine

Each TCP connection goes through a local state machine:

```
CLOSED → SYN_RCVD → ESTABLISHED → FIN_WAIT_1 → FIN_WAIT_2 → TIME_WAIT → CLOSED
```

Two separate sequence number spaces are tracked:
- **Client sequence space** — observed from the TUN device (what the device sends)
- **Server sequence space** — fabricated (our responses, starting at a random offset)

This avoids the need for a full TCP/IP stack while correctly handling the middlebox scenario.

### Connection Limits

- Maximum 256 concurrent TCP connections
- 30-second handshake timeout (SYN without completion)
- 5-minute idle timeout for established connections
- LRU DNS cache of 256 entries

### Security

- SSH private keys are encrypted with AES-256-GCM using a key stored in the Android Keystore (hardware-backed where available)
- Passwords and key passphrases are stored in EncryptedSharedPreferences (AES-256-SIV key, AES-256-GCM values)
- SSH host keys are verified and cached (known-hosts style)
- All non-SSH traffic is routed through the encrypted tunnel
- The SSH control socket is explicitly excluded from VPN routing via `VpnService.protect()`

## Dependencies

| Library | Purpose |
|---|---|
| [mwiede/jsch](https://github.com/mwiede/jsch) | SSH client (RSA/ECDSA/Ed25519 support) |
| [Bouncy Castle](https://www.bouncycastle.org/) | Ed25519 on API < 29 |
| [dnsjava](https://github.com/dnsjava/dnsjava) | DNS-over-TCP resolution |
| [Room](https://developer.android.com/jetpack/androidx/releases/room) | SQLite persistence |
| [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Async I/O and state management |
| [Jetpack Navigation](https://developer.android.com/guide/navigation) | Fragment navigation |
| [Security Crypto](https://developer.android.com/jetpack/androidx/releases/security) | EncryptedSharedPreferences |
| [Material 3](https://m3.material.io/) | UI components |

## License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.

## Contributing

Contributions are welcome. Please open an issue or submit a pull request.

## Disclaimer

This application routes all device traffic through an SSH tunnel. The security and privacy of your traffic depend on the SSH server you connect to. Use only with servers you trust. The authors assume no liability for any misuse or damage caused by this software.
