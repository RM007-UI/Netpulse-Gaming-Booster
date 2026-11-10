# NetPulse Gaming Booster

NetPulse is an Android VPN and gaming-network diagnostics app with a Persian RTL dashboard. It combines WireGuard and sing-box, OpenVPN profile import, native IKEv2/IPsec, per-app routing, endpoint tests and live device telemetry.

## Implemented

- **WireGuard:** validates user-provided .conf profiles and connects through the official Android tunnel library.
- **sing-box:** embeds the pinned Android libbox 1.14.4 engine inside a foreground VpnService. Supports sing-box JSON and supported share links for VLESS, VMess, Trojan, Shadowsocks, SOCKS5, Hysteria v1/v2, TUIC, AnyTLS, NaiveProxy and HTTP/HTTPS proxy.
- **OpenVPN UDP/TCP:** converts a supported TLS-mode .ovpn subset into a sing-box openvpn-client endpoint. Supports inline CA and optional client certificate/key, TLS control wrapping, basic username/password and DNS resolvers pushed by the server. Unsupported directives are rejected.
- **Native IKEv2/IPsec:** uses Android VpnManager on Android 11+ devices advertising IPsec tunnel support, with EAP-MSCHAPv2 or PSK authentication. This is a full-device VPN.
- **Encrypted profiles:** WireGuard, sing-box and IKEv2 profiles are stored in the app's no-backup private directory using AES-256-GCM and a non-exportable Android Keystore key. Profiles are revalidated after restore. Settings can securely erase the stored file and key after VPNs are stopped.
- **Server manager:** lists server outbounds and selector/urltest groups. Users can set the default outbound, or create a sing-box URLTest group over at least two supported node outbounds. The sing-box core then periodically tests the configured HTTP health-check URL and picks a suitable endpoint.
- **Game-only routing:** Android per-app allowlisting is supported by both WireGuard and sing-box. Only selected packages enter that VPN tunnel; unselected apps use Android's normal network. IKEv2 remains full-device.
- **Per-game route selection:** in sing-box Game-only mode, each selected app can be assigned an outbound or group. NetPulse adds package-name route rules ahead of existing profile rules and validates every chosen tag.
- **Automatic game detection:** optional Android Usage Access detects which launcher-visible game is in the foreground. The permission is user-controlled; package name and UID counters are read locally and are not uploaded. Detection does not silently start or stop VPN connections.
- **Live game telemetry:** while detection is enabled and the app process remains alive, NetPulse reads UID-level RX/TX counters when Android exposes them and displays estimated per-app rates. Shared UIDs, OEM restrictions and unavailable counters can limit accuracy.
- **Network diagnostics:** multi-sample TCP connection timing, direct-vs-VPN UDP STUN comparison, a custom TCP endpoint comparison, and a per-game custom UDP echo endpoint comparison over explicit physical and VPN Network handles. The UDP echo tool requires an endpoint that mirrors the diagnostic datagram byte-for-byte.
- **Event history:** a local, searchable 200-entry diagnostic history.
- **Display controller:** lists refresh modes reported by the panel and can apply/restore supported rates with Root access.

## Important measurement limits

- TCP endpoint tests measure connection-establishment time, not ICMP ping.
- The custom game endpoint test works only for a host/port that accepts TCP. It reports successes, median, sequential variation and failed connection attempts. Failed TCP connects are not game packet loss.
- STUN tests measure a generic UDP request/response path. Their timeout ratio is not a statistically representative game-server packet-loss result.
- Game session history records while the NetPulse process is alive and optional Usage Access detection is enabled. It can continue when a game is foregrounded, but Android may reclaim the background process; this is not an always-on foreground service. UID traffic counters may be unavailable on some OEM builds, and displayed byte totals are estimates derived from sampled rates.
- Public VPN Gate relays are volunteer-run and may be unstable or slow. VPN Gate's anti-abuse policy says central connection logs are kept for three months or more, and individual relay packet-header logs for two weeks or more; these records can include source IP and destination metadata. Do not use this mode for sensitive accounts or private traffic. Directory ping/speed are server-reported, not measurements from the user's device.
- The per-game UDP echo test is only valid against a dedicated echo endpoint that returns NetPulse's nonce-bearing test datagram unchanged. The probe originates from NetPulse and follows its default VPN outbound; selecting a game labels the target but does not exercise that game's package-specific outbound. It is not compatible with arbitrary commercial game servers or their proprietary packet protocols.
- URLTest uses the HTTP health-check URL configured in the generated sing-box group. It estimates proxy-node health and is not a measurement of latency to the actual match server.
- Android does not provide a universal way for a third-party app to discover every game's UDP server, interpret proprietary game packets, or measure in-match packet loss. A protocol-aware game server or in-game telemetry would be needed for those features.
- NetPulse does not promise lower ping, higher FPS, packet priority or a gaming performance gain. VPN routing can improve or worsen network latency depending on provider, carrier and route.

## Compatibility and limitations

- Minimum Android: 8.0 / API 26.
- Target SDK: 35.
- Current CI artifact ABI: arm64-v8a.
- IKEv2 requires Android 11+ and android.software.ipsec_tunnels.
- OpenVPN import is a conservative TLS subset; external files, PKCS#12 bundles, static-key mode, scripts/plugins, compression, management interfaces and external auth files are rejected.
- Game-only requires selected, installed packages. Per-game outbound assignment applies only to sing-box; WireGuard uses a single peer configuration for selected apps.
- Usage Access is optional and must be granted by the user in Android Settings. Without it, automatic foreground-game detection is disabled.
- Physical Android testing is still required for VPN consent, service lifecycle, DNS, IPv4/IPv6, handoff, per-app isolation and real gameplay.

## Build and verification

- JDK 17, Android SDK 35 and Gradle 8.9.
- Current app version: **0.6.7** (versionCode 16).
- Run `gradle testDebugUnitTest assembleDebug`.
- GitHub Actions runs the five-pass UI/bridge regression suite, Kotlin unit tests, debug APK assembly, pinned libbox AAR SHA-256 verification and Android API inspection.
- A successful CI build does not replace testing on physical phones and networks.

## Privacy and security

- VPN consent is requested through Android's system UI.
- No NetPulse-owned VPN endpoints or private server credentials are bundled. The optional public-relay mode fetches relay records from the official VPN Gate API and uses that service's published shared credentials.
- Profile credentials are encrypted at rest with AES-256-GCM and Android Keystore.
- Usage Access is opt-in and used only to read foreground package transitions; no screen content, keystrokes or application data are read.
- Game UID traffic counters and event history remain local.
- Profile secrets are not written to logs or exposed to the WebView bridge.
- Android backup is disabled and imported profiles live in a private no-backup directory.
- The dashboard is loaded from bundled assets; WebView network loads and universal file-URL access are disabled.


### 0.6.4 public relay stability and UI
- Reworked the VPN Gate catalog parser to inspect a bounded set of the best reported candidates instead of importing every row in the downloaded CSV. This reduces CPU and memory pressure on mobile devices.
- Exposes local diagnostics (rows received, metadata-valid rows, profiles checked/decoded/accepted, and rejection reasons) when the list is empty.
- Adds guarded worker scheduling, request IDs, a render timeout, stale-response rejection, and safer WebView callback handling.
- Redesigned the public-relay panel with responsive server cards, search, country filter, clear loading/error/empty states, and a collapsible privacy notice.


### 0.6.5 crash diagnostics and relay stability
- Streams the VPN Gate CSV directly from HTTPS instead of loading the complete response plus a second line array into memory.
- Caps responses at 8 MiB, validates at most 160 of the best reported candidates, returns at most 40 importable profiles, and caps each decoded profile at 256 KiB.
- Replaces repeated full-list sorting with a bounded priority queue.
- Records parsing stages and rejection reasons for actionable empty-list diagnostics.
- Adds last-crash recording (exception type and stack frames only, without exception messages, profile contents or credentials). On the next launch, the user can copy the report to help diagnose a repeat crash.
- Preserves the refreshed relay dashboard, filters, error/empty states and stale-response protection.


### 0.6.6 — VPN Gate compatibility filter correction
- Removed the duplicate, stricter-than-the-real-importer profile pre-filter which labeled every candidate as `unsupported-profile` before the supported OpenVPN parser ran.
- The embedded profile is now normalized, then validated by the same `OpenVpnProfileImporter` used by the actual connection path.
- Rejected rows now report the actual importer error (unsupported directive, missing inline CA, remote parsing, etc.) instead of a generic pre-filter result.
- Added a regression test for VPN Gate's fully-qualified `.opengw.net` endpoint form and verified the importer output endpoint.


### 0.6.7 — VPN handoff and connection crash diagnostics
- Refuses to start a NetPulse tunnel while Android reports another app's VPN as the active network. Android provides one active VPN slot; disconnect the existing VPN first.
- The public relay path now checks this conflict before confirmation and before service startup, and displays a direct explanation instead of attempting a conflicting tunnel handoff.
- OpenVPN import, profile validation, VPN consent, foreground-service startup and libbox native startup each persist a short diagnostic stage before proceeding.
- The crash-stage write is synchronous so the last operation is more likely to survive an abrupt native/service failure.
- Added unit tests for the foreign-VPN handoff policy.
