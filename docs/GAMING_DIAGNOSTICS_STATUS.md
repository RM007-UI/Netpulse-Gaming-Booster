# Gaming diagnostics: implemented behavior and remaining work

## Implemented in the Android app

- Live device-wide RX/TX rate sampling from Android TrafficStats. This is not per-game traffic attribution.
- TCP connect-time sampling to 1.1.1.1:443 with min/median/max, sample variation and failed-attempt percentage. This is a reachability/connection diagnostic, not ICMP echo RTT.
- UDP STUN route comparison between the physical network and the sing-box VPN network, with median, variation/jitter and failed response percentage. This measures the STUN path, not the game server.
- User-supplied TCP game endpoint comparison: NetPulse resolves the host once on the physical network, then compares TCP connect times to the same IP and port over direct and VPN networks. This is useful only when the target exposes a TCP listener; it does not measure UDP game traffic or in-match latency.
- Game-only per-app routing through sing-box TUN allowlisting. WireGuard and Android-native IKEv2 remain full-device in this app.
- Optional foreground-game detection using Android Usage Access. Detection is local and opt-in; Android may restrict event visibility depending on OS/OEM background policy.
- Per-game outbound assignments within a sing-box profile.
- sing-box URLTest group creation for generic periodic endpoint health/latency checks. This is not a game-server-aware optimizer and cannot guarantee lower in-game ping.
- Display refresh-rate selection where the device exposes modes and Root permission is granted.

## Safety and correctness fixes

- Game-only can no longer be enabled while a VPN engine is active. This avoids reporting a new allowlist mode while the existing tunnel still uses its previous builder configuration.
- Game-only activation now requires a loaded sing-box profile and selected packages. The UI and native status explicitly state that WireGuard and native IKEv2 are full-device engines here.
- Auto-select profile changes are rolled back in memory if encrypted persistence fails, avoiding a false success state that would disappear after restart.
- Auto-select success/failure messaging now keeps the persistence result and the generic HTTP health-check limitation explicit.

## Not implemented yet

1. Real in-game telemetry: reading live ping, packet loss, frame timing, or server region from arbitrary games is not generally exposed by Android. It needs supported game APIs/SDKs or an opt-in game-specific integration.
2. UDP game-server RTT/loss testing: the current user-supplied endpoint comparison is TCP; the generic UDP test is STUN. A reliable UDP test needs a known echo/measurement endpoint operated by the user or a trusted service.
3. Automatic game server catalog and region discovery: there is no maintained official endpoint catalog for every game/region, and game server addresses can be dynamic or hidden behind relays.
4. Game-aware automatic route selection: URLTest currently checks generic endpoints. A game-aware selector needs known per-game targets, UDP-capable measurements, minimum sample counts, hysteresis and a safe fallback.
5. Per-game traffic shaping/QoS: Android VPN APIs do not provide carrier/router QoS guarantees. Root-based packet marking or device-specific traffic control would need separate opt-in implementation and hardware validation; it must not claim guaranteed prioritization.
6. Full OpenVPN compatibility: current import supports a conservative TLS-mode subset. External file references, PKCS#12, scripts/plugins, compression and advanced interactive authentication remain unsupported.
7. Advanced IKEv2 modes: only the documented EAP-MSCHAPv2 and PSK paths are supported; certificate-based client identity and other EAP methods are not implemented.
8. WireGuard per-app allowlisting: the app's current Game-only implementation is sing-box-only.
9. Release readiness: signed release builds, a managed signing configuration, 32-bit ABI support, Play distribution checks and physical-device acceptance are still separate work.
10. Device acceptance: verify Android 8-15 behavior, OEM battery/background restrictions, VPN consent and replacement, Wi-Fi/cellular handoff, IPv4/IPv6/DNS leak behavior, app allowlisting and real gameplay on supported devices.

## Interpretation rules for the dashboard

- TCP connect time must not be labelled as ICMP ping.
- STUN latency/jitter must not be labelled as game-server latency.
- Device-wide TrafficStats must not be labelled as per-game throughput.
- A generic URLTest result must not be presented as guaranteed game optimization.
- A lower probe result is an observation for that endpoint and time window, not a promise of lower latency in every game.
