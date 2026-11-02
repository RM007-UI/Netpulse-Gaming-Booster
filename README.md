# NetPulse Gaming Booster

Native Android network diagnostics and game-oriented routing toolkit.

## Current implementation

- WireGuard userspace tunnel integration using the official embeddable Android tunnel library.
- Imports a user-provided WireGuard `.conf` profile through Android's document picker.
- Imports and validates sing-box JSON profiles with libbox; validation only, not yet an active sing-box VPN service.
- CI verifies the resolved sing-box 1.14.4 AAR against the upstream release SHA-256 before publishing the debug APK.
- Limits imported profile files to 2 MiB before parsing and rejects malformed UTF-8 instead of silently replacing invalid bytes.
- Validates the profile before enabling the connection control.
- Requests Android VPN consent only when the user taps Connect.
- Keeps the parsed profile in memory; it is not written to app storage.
- Does not bundle public endpoints, credentials, or sample profiles.
- Other protocol catalog entries remain marked as planned or requiring integration.

A successful build verifies compilation and packaging only. A real tunnel still requires a valid provider-issued profile and device/network testing.

## Protocol roadmap

- WireGuard: integrated; device-level connection testing remains required.
- OpenVPN UDP/TCP: integrate a maintained OpenVPN 3-compatible Android engine.
- VLESS, VMess, Trojan, Shadowsocks: planned through one embedded sing-box engine; see [the integration plan](docs/SING_BOX_INTEGRATION.md).
- SOCKS5: proxy mode; full-device routing requires a TUN adapter.
- IKEv2/IPsec: evaluate a maintained implementation before advertising support.
- DNS: classic DNS, DoH, DoT and DoQ with leak and fallback tests.
- TCP/UDP/QUIC/HTTP/2/HTTP/3 are transports, not independent VPN engines.

## Build

GitHub Actions runs `gradle assembleDebug` on pushes and pull requests to `main`. The debug APK is uploaded as the `netpulse-debug` workflow artifact.

Local requirements: JDK 17 and Android SDK 35.

## Safety and privacy

- Uses Android's user-approved `VpnService` permission.
- Does not request root access.
- Does not request `QUERY_ALL_PACKAGES`.
- Disables Android Auto Backup to avoid silently backing up sensitive VPN profile material.
- Does not log profile contents or bundle fabricated VPN endpoints or credentials.
