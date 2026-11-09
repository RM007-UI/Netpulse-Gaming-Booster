# NetPulse Gaming Booster

NetPulse is a native Android VPN and gaming-network diagnostics app with a Persian RTL dashboard. It combines WireGuard and sing-box, game-only per-app routing, server selection, traffic telemetry, and network-path measurements.

## Implemented

- **WireGuard:** imports and validates user-provided WireGuard `.conf` files, then connects through the official Android tunnel library.
- **sing-box VPN engine:** embeds the pinned Android libbox 1.14.4 engine in a foreground `VpnService`. It validates imported sing-box JSON and supported share links, establishes a TUN interface, and routes traffic through the selected outbound.
- **Share-link import:** VLESS, VMess, Trojan, Shadowsocks, SOCKS5, Hysteria v1, Hysteria 2, TUIC, AnyTLS, NaiveProxy, and HTTP/HTTPS proxy links. Full sing-box JSON is also accepted for configurations supported by the bundled core.
- **Server manager:** lists selectable server outbounds and selector/urltest groups from the loaded sing-box profile. Choosing a node updates `route.final` while preserving the rest of the config and its routing rules. Disconnect and reconnect sing-box to apply the new default route.
- **Game-only routing:** select launcher-visible games/apps. With sing-box, only the selected package allowlist (plus NetPulse for its own UDP diagnostic sockets) is routed through the VPN. Other apps continue using Android's normal network. WireGuard remains full-device routing.
- **Network diagnostics:** six TCP connection-establishment samples to `1.1.1.1:443`, plus five direct and five VPN UDP STUN samples to `stun.l.google.com:19302` when sing-box and Game-only mode are active.
- **Traffic telemetry:** live device-wide upload/download counters and per-app UID counters where Android exposes them.
- **Display refresh controller:** lists panel modes and can apply/restore supported refresh rates with Root access. It does not invent unsupported panel modes.
- **Dashboard:** eight screens for overview, analytics, devices, servers, alerts, speed tests, logs, and settings; navigation adapts to narrow mobile displays and Android system-bar insets.

## Important limitations

- TCP measurements are connection-establishment times to the named probe endpoint, not ICMP ping.
- STUN measurements are generic UDP path indicators. They do not measure the latency or packet loss of a particular game server or match.
- NetPulse does not promise lower ping, higher FPS, packet prioritization, or a gaming performance gain. A VPN can improve or worsen routing depending on the server and network.
- OpenVPN UDP/TCP and IKEv2/IPsec are **not integrated**.
- Game-only routing requires a sing-box profile with a TUN inbound. Unsupported configurations are rejected for that mode.
- Switching the selected sing-box outbound changes the default route. More-specific route rules in a custom profile can still override it.
- Imported VPN profiles and their credentials stay in process memory only. They are not written to app storage; after Android kills the app process, import the profile again.
- A physical Android device test is still required for VPN consent, foreground-service behavior, traffic routing, DNS, handoffs, and real gameplay.

## Build

- Minimum Android version: Android 8.0 / API 26.
- Target SDK: 35.
- ABI: `arm64-v8a`.
- JDK 17, Android SDK 35, and Gradle 8.9.
- Run `gradle testDebugUnitTest assembleDebug`.
- GitHub Actions also runs the five-pass UI regression checks and verifies the SHA-256 of the pinned libbox AAR before uploading the `netpulse-debug` APK artifact.

## Privacy and security

- VPN access is requested through Android's system consent screen.
- The app does not bundle public VPN endpoints or credentials.
- The sing-box profile is validated before startup and again after Game-only routing is applied.
- Profile JSON, UUIDs, passwords, private keys, and subscription URLs are not written to diagnostic logs.
- Android backup is disabled for the application.
- The WebView dashboard is loaded from the app's bundled assets; network loads and universal file-URL access are disabled.
