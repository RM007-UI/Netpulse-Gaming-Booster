# NetPulse Gaming Booster

Native Android network diagnostics, game-oriented routing, and display refresh-rate control.

## Current implementation

- WireGuard userspace tunnel integration using the official embeddable Android tunnel library.
- Imports a user-provided WireGuard `.conf` profile through Android's document picker.
- Imports and validates sing-box JSON profiles with libbox; validation only, not yet an active sing-box VPN service.
- CI verifies the resolved sing-box 1.14.4 AAR against the upstream release SHA-256 before publishing the debug APK.
- Limits imported profile files to 2 MiB before parsing and rejects malformed UTF-8 instead of silently replacing invalid bytes.
- Validates profiles before enabling the connection controls.
- Requests Android VPN consent only when the user taps Connect.
- Keeps parsed profile material in memory; it is not written to app storage.
- Includes a rooted display refresh controller for Android/HyperOS: reads the display's supported modes, applies only a mode actually exposed by the panel, verifies the live rate, and restores the settings captured before the first change, and rolls back a failed attempt to the state from immediately before that attempt.
- The refresh controller uses Android's display-mode preference plus the Android min/peak refresh settings and Xiaomi's HyperOS refresh-rate settings. Root is requested only when the refresh controller checks access.
- Does not bundle public endpoints, credentials, or sample VPN profiles.
- Other protocol catalog entries remain marked as planned or requiring integration.

A successful build verifies compilation and packaging only. VPN functionality still requires provider-issued profiles and device/network testing. Refresh-rate behavior must be verified on the target phone because HyperOS policies can differ between device models and builds.

## Display refresh rate

Open **Display Refresh Rate** and grant the root prompt from Magisk/KernelSU when it appears. The app lists the panel's refresh rates at its current resolution. Select an available rate to pin it, or use **Auto** to restore the values captured before the first change. Failed shell operations trigger an automatic rollback, and restoration targets the internal display ID explicitly.

The POCO X7 Pro is specified for up to 120 Hz. The app will not try to invent a 90 Hz hardware mode: 90 Hz is enabled only if the installed display firmware exposes it as a supported mode. A 120 Hz panel setting also does not force an app or game to render 120 FPS.

If the active panel rate does not match the requested rate, the app reports the actual value rather than showing a false success. Battery saver, thermal policy, app-specific limits, and firmware policy can still affect the result.

## Protocol roadmap

- WireGuard: integrated; device-level connection testing remains required.
- OpenVPN UDP/TCP: integrate a maintained OpenVPN 3-compatible Android engine.
- VLESS, VMess, Trojan, Shadowsocks: planned through one embedded sing-box engine; see [the integration plan](docs/SING_BOX_INTEGRATION.md).
- SOCKS5: proxy mode; full-device routing requires a TUN adapter.
- IKEv2/IPsec: evaluate a maintained implementation before advertising support.
- DNS: classic DNS, DoH, DoT and DoQ with leak and fallback tests.
- TCP/UDP/QUIC/HTTP/2/HTTP/3 are transports, not independent VPN engines.

## Build

GitHub Actions runs `gradle testDebugUnitTest assembleDebug` on pushes and pull requests to `main`. The debug APK is uploaded as the `netpulse-debug` workflow artifact.

Local requirements: JDK 17 and Android SDK 35.

## Safety and privacy

- Uses Android's user-approved `VpnService` permission for VPN tunnels.
- The refresh controller uses root only to change and restore display preferences.
- Does not request `QUERY_ALL_PACKAGES`.
- Disables Android Auto Backup to avoid silently backing up sensitive VPN profile material.
- Does not log profile contents or bundle fabricated VPN endpoints or credentials.
