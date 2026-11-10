# NetPulse VPN engines

## Engine overview

| Engine | Runtime | Import format | Android requirement | Routing scope |
|---|---|---|---|---|
| WireGuard | Official WireGuard Android tunnel library | WireGuard .conf | Android 8+ | Full device or Game-only package allowlist |
| sing-box | Pinned libbox 1.14.4 in NetPulse VpnService | sing-box JSON, supported proxy share links, supported .ovpn TLS profiles | Android 8+ | Full device or Game-only package allowlist |
| OpenVPN UDP/TCP | sing-box openvpn-client endpoint | Conservative TLS-mode .ovpn subset | Android 8+ through sing-box | Same as sing-box |
| IKEv2 / IPsec | Android platform VpnManager | NetPulse IKEv2 form | Android 11+ and android.software.ipsec_tunnels | Full device |

Android provides one active VPN slot per user. NetPulse's coordinator prevents its own WireGuard, sing-box and IKEv2 controls from intentionally starting competing tunnels. Android remains the authority for external VPN apps and VPN consent.

## WireGuard Game-only

NetPulse builds a copied WireGuard Config with includedApplications set to the selected package list. The imported peer and interface configuration stays unchanged. Android sends only those selected apps through the WireGuard VpnService; non-selected apps keep their ordinary Android route. The feature requires at least one installed selected package and is applied when the tunnel starts.

WireGuard uses one peer configuration for the entire selected app group. It does not assign a different peer to every application.

## sing-box Game-only and per-game routes

The app requires a TUN inbound with auto_route enabled. It applies include_package to a copied profile and clears stale exclude_package entries because Android VpnService.Builder cannot combine an include and exclude app list. The resulting config is rechecked with libbox.

Per-game outbound assignment uses package_name route rules with action=route and outbound=<tag>. Rules are prepended so an app-specific assignment has priority over the profile's existing route rules. The chosen outbound must exist in the current profile. Existing rules remain preserved after the per-game rules.

NetPulse itself is included in the sing-box allowlist for its own diagnostics. This inclusion does not add unrelated apps to the tunnel.

## Automatic foreground-game detection

Usage Access is an optional special access granted by the user in Android Settings. NetPulse reads UsageEvents foreground transitions and maps package names to launcher-visible installed applications. It does not read screen content, keystrokes or messages. UID-level RX/TX counters are sampled locally while a detected game is active and are shown only where Android exposes them.

Detection runs while the app process remains alive. Android may stop background processes; the feature is therefore not a guaranteed always-on service. It does not automatically connect, disconnect or replace VPNs.

## URLTest relay selection

The Servers screen can create or update the netpulse-auto-urltest sing-box urltest group when at least two concrete server outbounds are present. It uses a periodic HTTP health-check URL, interval and tolerance. The sing-box core evaluates each outbound and chooses a suitable route after the VPN starts.

This measures reachability and latency to the configured HTTP health-check URL through each proxy. It is not latency to the actual game server. Existing route rules can still override the default final outbound.

## OpenVPN importer

NetPulse converts compatible TLS-mode .ovpn files into a sing-box 1.14.4 endpoint. The importer accepts UDP and TCP remotes, inline CA certificates, optional paired client certificate/private-key blocks, TLS control wrapping, username/password, common cipher, MTU/MSS, keepalive, route, redirect-gateway, route-nopull and pull-filter settings. It configures the sing-box OpenVPN DNS adapter for resolvers pushed by the server and sets the profile's default route to the OpenVPN endpoint.

External file references, PKCS#12 bundles, static-key mode, scripts/plugins, management interfaces, compression, external auth files and unsupported directives are rejected rather than silently ignored. This is a conservative subset of OpenVPN, not a full directive interpreter.

## Native IKEv2

IKEv2 uses Android's VpnManager and Ikev2VpnProfile, not the sing-box engine. NetPulse provisions the profile, handles system consent, starts/stops the session and polls platform state where exposed. It supports EAP-MSCHAPv2 and PSK. A PEM Root CA can be supplied for EAP; a custom Root CA with PSK is rejected.

IKEv2 remains full-device routing. The current Android IKEv2 profile API does not expose a per-app allowlist equivalent to VpnService.Builder. The app refuses to start IKEv2 while Game-only is enabled.

Android 11 and 12 do not expose the same detailed provisioned-profile state as later versions. NetPulse reports an unverified active VPN conservatively and does not label an unknown VPN as its own IKEv2 tunnel.

## Diagnostics

The game-target test measures TCP connection-establishment time to a user-specified host and port via the physical Network and VPN Network. It reuses one resolved IP so the direct and VPN results are comparable. It reports successful attempts, median, sequential variation and failed connects; it is not a generic UDP test.

The UDP test uses STUN Binding request/response samples. RFC 3550-style jitter is estimated from consecutive successful RTT samples. STUN timeout ratio is not actual game packet loss.

No generic third-party API can discover every proprietary game server or interpret every game's UDP protocol. Game-specific UDP ping and in-match packet loss require a compatible server-side echo/measurement endpoint or official game telemetry.

## Security and verification

- Profiles are stored with AES-256-GCM and a non-exportable Android Keystore key in the app's private no-backup directory.
- Profiles are validated before persistence and on restore.
- Credentials are not exposed to the WebView bridge or diagnostic log.
- Android backup is disabled.
- The regression suite verifies JavaScript, DOM IDs, native bridge actions, game-routing helpers and endpoint probe wiring.
- CI builds the debug APK, runs Kotlin unit tests, checks the pinned libbox AAR checksum and inspects the Android binding API.

Automated checks do not replace physical-device acceptance. Verify VPN consent, service lifecycle, Android 11-15 behavior, IPv4/IPv6, DNS, Wi-Fi/mobile handoff, Game-only package isolation, server selection, UID traffic counters, external VPN conflicts and real gameplay on actual devices.


## No-config public relay mode

NetPulse can fetch the official VPN Gate CSV API over HTTPS at runtime. The API publishes volunteer-operated relay metadata and a Base64-encoded OpenVPN profile per relay. The app validates the host/IP relationship, embedded CA certificate, protocol, and remote port before offering a one-tap connection through the existing sing-box OpenVPN adapter. The published shared username/password are used; no user-owned server, private credential, configuration file, or NetPulse backend is required.

The list refresh is user-initiated. Suggested ordering uses VPN Gate's server-reported ping, then reported throughput and session count. These values are not measured from the user's current network and do not predict gameplay latency.

Privacy and reliability warning: VPN Gate relays are operated by volunteers, not NetPulse. VPN Gate's official anti-abuse policy says aggregated connection logs are kept for three months or more, while each relay keeps packet headers for two weeks or more. Connection logs can include the source IP, destination server, protocol, traffic volume, and destination metadata. Relays are volunteer-operated and availability/throughput can change at any time. This is a public community relay for basic connectivity testing, not a trusted privacy provider or a guaranteed gaming route. NetPulse shows a warning and requires explicit confirmation before connecting.

Only OpenVPN is provided in this no-config community mode. WireGuard, IKEv2, Shadowsocks, Trojan, Hysteria2 and TUIC still need an endpoint and provider-issued credentials. More no-config protocols require official provider integrations or a trusted provider account flow; NetPulse does not fabricate server credentials.


## Public relay loading and device stability

The public relay parser streams the CSV response from HTTPS with an 8 MiB cap, validates only the top 160 directory candidates by reported metadata, and returns at most 40 profiles (each decoded profile is capped at 256 KiB) which pass the in-app OpenVPN importer. The dashboard receives transparent parser diagnostics when no profiles pass; a zero result is no longer presented without row/profile counts and rejection categories. The worker and WebView callback are guarded against a destroyed Activity, duplicate requests, stale request results and callback-rendering exceptions.


## Crash diagnostics in 0.6.5

NetPulse stores a bounded crash summary when an uncaught Java/Kotlin exception terminates the process: exception class, thread, last operation stage and a limited set of stack frames. Exception messages, imported profiles and credentials are intentionally omitted. On the next launch the user can copy or clear the report from a dialog. Native process aborts and low-memory kills may not produce a Java crash report.
