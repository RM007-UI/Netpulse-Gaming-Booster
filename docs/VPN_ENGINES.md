# NetPulse VPN engines

## Engine overview

| Engine | Runtime | Import format | Android requirement | Routing scope |
|---|---|---|---|---|
| WireGuard | Official WireGuard Android tunnel library | WireGuard .conf | Android 8+ (app baseline) | Full device |
| sing-box | Pinned libbox 1.14.4 in NetPulse VpnService | sing-box JSON, supported proxy share links, supported .ovpn TLS profiles | Android 8+ (app baseline) | Full device or Game-only per-app |
| OpenVPN UDP/TCP | sing-box openvpn-client endpoint | Conservative TLS-mode .ovpn subset | Android 8+ (through sing-box) | Full device or Game-only per-app |
| IKEv2 / IPsec | Android platform VpnManager | NetPulse IKEv2 form | Android 11+ plus android.software.ipsec_tunnels | Full device |

Android provides one active VPN slot per user. NetPulse's coordinator prevents its own WireGuard, sing-box, and IKEv2 controls from intentionally starting competing tunnels. The system remains the authority for external VPN apps and the final connection consent.

## OpenVPN importer

NetPulse converts compatible TLS-mode .ovpn files into a sing-box 1.14.4 endpoint. The import path accepts:

- UDP and TCP remotes, including multiple remote entries.
- Inline CA certificates.
- Optional paired inline client certificate and private key.
- Inline tls-auth, tls-crypt, and tls-crypt-v2 control wrapping.
- Username/password authentication entered in the app, or inline credentials.
- Common TLS minimum, data cipher, auth, MTU, MSS, keepalive, route, redirect-gateway, route-nopull and pull-filter settings.
- DNS resolution through the resolvers pushed by the OpenVPN server, using sing-box's OpenVPN DNS server adapter.
- IPv6 blocking when a default IPv4 tunnel is requested without an IPv6 route, to avoid silently leaking IPv6 traffic outside the tunnel.

The generated route points to the OpenVPN endpoint itself. A profile is not considered ready until libbox accepts the generated JSON.

This is not a full OpenVPN directive interpreter. External file paths, PKCS#12 bundles, static-key mode, shell scripts, plugins, management interfaces, compression, external credential files and unsupported directives are rejected rather than ignored. Authentication flows that require interactive challenge/response beyond basic username/password are not implemented. Use a simplified TLS-mode profile or a complete compatible sing-box JSON config when a directive is outside the supported subset.

## Native IKEv2

IKEv2 does not use the sing-box engine. NetPulse provisions a platform Ikev2VpnProfile through Android VpnManager, handles the system consent activity, starts/stops the provisioned session and polls platform state where the Android API exposes it.

Supported authentication modes are:

- EAP-MSCHAPv2 username/password.
- Pre-shared key (PSK).

A PEM Root CA can be supplied for EAP authentication. The Android platform API does not support attaching a custom root CA to the PSK profile path, so that combination is rejected. Credentials are serialized only into the encrypted profile store.

The feature is not exposed on devices that do not report IPsec tunnel support. On Android 11 and 12, the public API does not provide the same detailed provisioned-profile state that later Android versions expose; the dashboard therefore reports uncertainty conservatively where it cannot determine the owning VPN.

## Per-app gaming mode

Game-only is implemented through Android VpnService.Builder package allowlisting and applies only to sing-box. NetPulse itself is temporarily added to the allowlist for its own diagnostics. WireGuard and native IKEv2 are full-device tunnel engines in this app.

Changing the game allowlist requires disconnecting and reconnecting sing-box. Unsupported or contradictory per-app route configurations are rejected instead of silently changing their meaning.

## Security and limitations

- VPN profiles are stored with AES-256-GCM and a non-exportable Android Keystore key in the app's private no-backup directory.
- Profiles are validated before persistence and when restored.
- Profile secrets are not written to diagnostic logs or exposed to dashboard JavaScript.
- Android backup is disabled.
- No public VPN endpoints or credentials are bundled.
- The debug build is not a substitute for physical-device acceptance testing.

## Verification status

The automated pipeline runs the five-pass UI/bridge regression suite, Kotlin/JVM unit tests, debug APK assembly, pinned libbox AAR checksum verification and Android API inspection. These checks establish that the project compiles, tests pass and the artifact is built from the pinned libbox dependency. They do not prove that every VPN server, carrier, OEM firmware or Android foreground-service policy works on a physical device.

Device acceptance should cover VPN consent, connect/disconnect cycles, Android 11-15 service behavior, IPv4/IPv6, DNS, cellular/Wi-Fi handoff, Game-only isolation, credential persistence, external VPN conflicts and actual gameplay.
