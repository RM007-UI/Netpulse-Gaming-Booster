# NetPulse Gaming Booster

NetPulse is a native Android VPN and gaming-network diagnostics app with a Persian RTL dashboard. It combines WireGuard and sing-box, game-only per-app routing, server selection, live traffic telemetry, and network-path measurements.

## Implemented

- **WireGuard:** imports and validates user-provided `.conf` files, then connects through the official Android tunnel library.
- **sing-box VPN engine:** embeds the pinned Android libbox 1.14.4 engine in a foreground `VpnService`. It validates imported sing-box JSON and supported share links, establishes a TUN interface, and routes traffic through the selected outbound.
- **Share-link import:** VLESS, VMess, Trojan, Shadowsocks, SOCKS5, Hysteria v1, Hysteria 2, TUIC, AnyTLS, NaiveProxy, and HTTP/HTTPS proxy links. Full sing-box JSON is also accepted for configurations supported by the bundled core.
- **OpenVPN UDP/TCP:** imports a supported `.ovpn` TLS profile and creates a sing-box 1.14.4 `openvpn-client` endpoint. User/password authentication can be entered in-app; inline CA and optional inline client certificate/key blocks are supported.
- **Encrypted profile storage:** validated WireGuard, sing-box, and IKEv2 profiles are persisted inside the app's no-backup private directory using AES-256-GCM and a non-exportable Android Keystore key. Profiles are revalidated when restored after process recreation. Credentials are never written to logs.
- **Server manager:** lists selectable server outbounds and selector/urltest groups from the loaded sing-box profile. Choosing a node updates `route.final` while preserving the rest of the config and its routing rules. Disconnect and reconnect sing-box to apply the new default route.
- **Game-only routing:** select launcher-visible games/apps. With sing-box, only the selected package allowlist (plus NetPulse for its own UDP diagnostic sockets) is routed through the VPN. Other apps continue using Android's normal network. WireGuard remains full-device routing. The profile must use a TUN inbound with `auto_route: true`; configurations the Android VPN builder cannot enforce are rejected instead of silently weakened.
- **Network diagnostics:** six TCP connection-establishment samples to `1.1.1.1:443`, plus five direct and five VPN UDP STUN samples to `stun.l.google.com:19302` when sing-box and Game-only mode are active.
- **Traffic telemetry:** live device-wide upload/download rates calculated from monotonic Android `TrafficStats` counters, plus per-app UID counters where Android exposes them.
- **Event history:** connection and diagnostic events are stored locally in a bounded 200-entry history and rendered with safe text-only DOM updates; users can search and clear the history.
- **Display refresh controller:** lists panel modes and can apply/restore supported refresh rates with Root access. It does not invent unsupported panel modes.
- **Dashboard:** eight screens for overview, analytics, devices, servers, alerts, speed tests, logs, and settings; navigation adapts to narrow mobile displays and Android system-bar insets.

## Important limitations

- TCP measurements are connection-establishment times to the named probe endpoint, not ICMP ping.
- STUN measurements are generic UDP path indicators. They do not measure the latency or packet loss of a particular game server or match.
- NetPulse does not promise lower ping, higher FPS, packet prioritization, or a gaming performance gain. A VPN can improve or worsen routing depending on the server and network.
- OpenVPN import intentionally supports a conservative TLS-mode subset: external file references, PKCS#12 bundles, scripts/plugins, compression, and external `auth-user-pass` files are rejected. Profiles requiring unsupported directives must be simplified or imported through another compatible sing-box JSON config.
- Native IKEv2 requires Android 11+ and the device feature `android.software.ipsec_tunnels`; unsupported devices keep the control disabled. Android controls IKEv2 as a system VPN, so it cannot run beside another VPN tunnel.
- Game-only routing requires a sing-box profile with a TUN inbound and `auto_route: true`. TUN routing exclusions on Android 12 and earlier are rejected when the core requests them because Android cannot enforce them safely there.
- Android's `VpnService.Builder` cannot combine per-app allowlists and denylists; profiles requesting both are rejected.
- Selecting a sing-box outbound changes the default route. More-specific route rules in a custom profile can still override it.
- The profile encryption key is stored in Android Keystore. If the app is uninstalled, the keystore is cleared, or the key becomes unavailable, the encrypted profiles cannot be recovered and must be imported again.
- A physical Android device test is still required for VPN consent, foreground-service behavior, traffic routing, DNS, handoffs, and real gameplay.

## Build

- Minimum Android version: Android 8.0 / API 26.
- Target SDK: 35.
- ABI: `arm64-v8a`.
- JDK 17, Android SDK 35, and Gradle 8.9.
- Current app version: **0.5.0** (versionCode 7). Run `gradle testDebugUnitTest assembleDebug`.
- GitHub Actions runs the five-pass UI regression suite and unit tests, verifies the SHA-256 of the pinned libbox AAR, inspects its Android API, and uploads the `netpulse-debug` APK artifact.

## Privacy and security

- VPN access is requested through Android's system consent screen.
- The app does not bundle public VPN endpoints or credentials.
- The IKEv2 profile is provisioned through Android's system VPN manager; its credentials are also stored only inside the encrypted app profile store.
- The sing-box profile is validated before startup and again after Game-only routing is applied.
- Profile JSON, UUIDs, passwords, private keys, and subscription URLs are not written to diagnostic logs.
- Imported profiles are stored encrypted with AES-256-GCM; the Keystore key is non-exportable.
- Android backup is disabled and profile files live in the private no-backup directory.
- The WebView dashboard is loaded from the app's bundled assets; network loads and universal file-URL access are disabled.
