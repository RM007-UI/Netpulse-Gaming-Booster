# sing-box Android engine integration

## Current implementation

NetPulse embeds the Android libbox 1.14.4 AAR and runs sing-box through its own Android `VpnService`.

The current path is:

1. The Activity accepts a user-supplied sing-box JSON configuration or one supported share link and checks the size/encoding.
2. `SingBoxProfileImporter` normalizes supported share links into a TUN profile. Custom JSON is kept as a full profile; a TUN inbound is added only when one is missing.
3. `Libbox.checkConfig` validates the imported profile before it is stored in process memory.
4. The user taps Connect, Android VPN consent is requested if needed, and the app starts its foreground VPN service.
5. `SingBoxVpnService` starts a `CommandServer`, implements `PlatformInterface` and `CommandServerHandler`, establishes the TUN file descriptor through `VpnService.Builder`, and starts the core service.
6. `VpnEngineCoordinator` prevents NetPulse's WireGuard and sing-box engines from intentionally claiming the same Android VPN slot.
7. Disconnect, permission revoke, startup failure, and service destruction close the command server and TUN descriptors and release the coordinator claim.

The imported source profile and credentials remain in application process memory only. They are not saved to app storage. After Android kills the app process, the user must import the profile again.

## Included share-link formats

The importer has direct share-link normalization for VLESS, VMess, Trojan, Shadowsocks, SOCKS5, Hysteria v1, Hysteria 2, TUIC, AnyTLS, NaiveProxy, and HTTP/HTTPS proxy links. Full sing-box JSON can also use outbound types supported by the pinned core and the Android platform adapter.

OpenVPN UDP/TCP and IKEv2/IPsec are not integrated. TCP, UDP, QUIC, HTTP/2, and HTTP/3 are transports, not independent VPN engines.

Reality is represented as VLESS TLS security configuration; it is not a standalone protocol.

## TUN and Android platform behavior

The service translates the selected libbox `TunOptions` into Android's `VpnService.Builder`:

- IPv4 and IPv6 interface addresses.
- MTU and DNS server addresses when libbox DNS mode is enabled.
- IPv4/IPv6 routes, automatic default routes, and route exclusions where supported by the Android API level.
- Per-app include and exclude package lists when automatic routing is enabled.
- HTTP proxy settings on Android 10+; match-domain constraints are rejected because Android's VPN HTTP proxy API cannot enforce them.
- Core outbound sockets are protected with `VpnService.protect(fd)` to prevent routing loops.
- The TUN descriptor is retained and explicitly closed during engine teardown.

For Game-only mode, NetPulse applies an `include_package` allowlist to the TUN profile and removes stale `exclude_package` entries. The imported profile is copied before modification and revalidated with `Libbox.checkConfig`. This is Android per-app split tunneling, not a traffic-priority feature. WireGuard remains full-device routing.

## Platform callbacks intentionally outside the no-root scope

The adapter does not enable platform shell, root bridges, SFTP lookup, auto-redirect, SSH agent integration, or privileged neighbor monitoring. Optional Wi-Fi identity and local-DNS callbacks are not implemented. Connection-owner lookup currently returns an empty owner object; profile features that require advanced connection-owner attribution need additional implementation and device testing.

The app does not request root permission to operate the VPN engine. Root is used only by the separate display refresh controller.

## Artifact provenance

The Gradle dependency is `com.github.singbox-android:libbox:1.14.4`.

- Release tag: `1.14.4`
- Release asset: `libbox.aar`
- Expected asset size: `118,293,410` bytes
- SHA-256: `d2685924a657705beda44e94c6dc499e781df1ad20d65474e3591ec2f8b96465`
- Source/release: `https://github.com/singbox-android/libbox/releases/tag/1.14.4`

CI compares the resolved AAR bytes to this checksum and inspects the Android binding API. The build fails if the downloaded artifact differs.

## Automated verification

GitHub Actions runs:

- Five-pass dashboard/navigation/WebView regression checks.
- `testDebugUnitTest`, including importer, package-routing, UDP STUN codec, server catalog and coordinator tests.
- `assembleDebug`.
- Pinned libbox AAR SHA-256 verification.
- libbox Android API inspection.
- Debug APK artifact upload.

A successful CI run verifies compilation, local unit tests, the dashboard's static wiring, and package assembly. It does not prove that VPN consent, foreground-service policy, routing, DNS, traffic handoff or gameplay work correctly on every phone.

## Required device acceptance matrix

Before calling a build production-ready, verify on physical Android devices:

- API 26 baseline: VPN consent, TUN creation, IPv4 routing, DNS and teardown.
- API 29+: per-app routing and connection-owner callback behavior where applicable.
- API 33+: notification permission behavior and route exclusions.
- API 34/35: foreground-service start restrictions and service type behavior.
- Wi-Fi to cellular and cellular to Wi-Fi handoff.
- IPv4, IPv6 and dual-stack networks.
- VPN permission revoke, app process recreation and repeated start/stop.
- Switching between WireGuard and sing-box, including simultaneous start attempts.
- UDP and TCP traffic separately.
- Game-only allowlist behavior with selected and unselected applications.
- Custom profile routing rules, DNS and server selection.
- Absence of profile credentials in logcat, notifications, and diagnostic output.

## References

- https://sing-box.sagernet.org/clients/android/
- https://sing-box.sagernet.org/clients/android/features/
- https://github.com/SagerNet/sing-box-for-android
- https://github.com/singbox-android/libbox
