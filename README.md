# NetPulse Gaming Booster

Native Android project for network diagnostics and game-oriented routing experiments.

## Current state
The repository contains the Android application foundation, typed protocol catalog, VPN permission boundary, and GitHub Actions debug build.

No VPN protocol engine is active yet. The catalog distinguishes integration-required engines, planned features, and transport-only protocols. Listing a protocol is not establishing a tunnel. The VPN service does not create an interface until a real engine is integrated.

## Protocol roadmap
- WireGuard: maintained Android tunnel library and profile validation.
- OpenVPN UDP/TCP: maintained OpenVPN 3-compatible Android engine.
- VLESS, VMess, Trojan, Shadowsocks: maintained Xray-compatible core and configuration validation.
- SOCKS5: proxy mode; full-device routing requires a TUN adapter.
- IKEv2/IPsec: evaluate a maintained implementation before advertising support.
- DNS: classic DNS, DoH, DoT and DoQ with leak and fallback tests.
- TCP/UDP/QUIC/HTTP/2/HTTP/3 are transports, not independent VPN engines.

## Build
GitHub Actions runs gradle assembleDebug on pushes and pull requests to main. The debug APK is uploaded as the netpulse-debug workflow artifact.
Local requirements: JDK 17 and Android SDK 35.

## Safety and privacy
- Uses Android user-approved VpnService permission.
- Does not request root access.
- Does not request QUERY_ALL_PACKAGES; launcher app discovery is scoped.
- Does not bundle fabricated public VPN endpoints or credentials.
