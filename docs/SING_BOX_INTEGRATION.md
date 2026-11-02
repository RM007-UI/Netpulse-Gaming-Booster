# sing-box Android engine integration

## Decision

Use one embedded sing-box engine for VLESS, VMess, Trojan, Shadowsocks and SOCKS inbound/outbound support. Do not create separate protocol engines for each of these formats.

The Android integration must use the upstream libbox Android binding (AAR) and a platform adapter. A plain Go CLI binary or the desktop core is not an Android VPN integration.

## Required integration pieces

1. Build or obtain the libbox AAR from a pinned, reviewed upstream sing-box source revision.
2. Keep the AAR and its source revision reproducible in CI; do not download an unpinned "latest" binary during an ordinary app build.
3. Implement the libbox platform interface and a foreground service lifecycle.
4. Use Android VpnService only after explicit user consent. Pass the TUN file descriptor through the supported libbox platform interface; do not create a second competing VPN service.
5. Parse and validate local JSON profiles before starting the engine. Never log profile JSON, UUIDs, passwords, keys, or subscription URLs.
6. Keep profile material private; avoid backup, analytics, crash-report attachments, and plaintext diagnostic logs.
7. Stop the engine cleanly on user disconnect, service teardown, and unrecoverable startup errors.

## Compatibility and safety gates

- App minimum SDK remains 26 unless the chosen libbox artifact requires a higher level.
- Build and package arm64-v8a and armeabi-v7a first; add x86 variants only if the release workflow needs them.
- Do not advertise VLESS, VMess, Trojan, Shadowsocks, or SOCKS5 as available until the libbox AAR is bundled, the platform adapter is implemented, and CI builds pass.
- Do not assume every upstream TUN option is supported by the Android client. Follow the Android-specific feature matrix.
- Never run WireGuard and sing-box VPN services simultaneously; Android permits only one active VPN service per user/profile.

## Acceptance tests

1. Import malformed JSON and verify a safe validation error with no profile contents in logs.
2. Import a minimal valid profile and verify engine startup.
3. Connect, verify Android reports the VPN active, transfer traffic, then disconnect.
4. Verify DNS and IPv4/IPv6 behavior, including failure and fallback paths.
5. Verify service recovery after app backgrounding and process recreation.
6. Verify no profile secrets appear in logcat, crash reports, or Android backup.
7. Test UDP-based traffic separately from TCP; a successful TCP check does not prove UDP support.

## References

- https://sing-box.sagernet.org/clients/android/
- https://sing-box.sagernet.org/clients/android/features/
- https://github.com/SagerNet/sing-box-for-android


## Android adapter implementation notes (reviewed upstream reference)

The upstream Android client separates the libbox platform adapter from its VPN service. NetPulse should preserve that separation, but must not copy the complete upstream application service because it depends on app-specific profile storage, notification, settings, and root integration.

### Minimal no-root scope

- Implement only the platform callbacks required by the selected libbox version. Do not enable platform shell, root bridge, auto-redirect, neighbor-table monitoring, or root-dependent features.
- Implement interface discovery and default-network change monitoring using public Android APIs. If a callback is not required by the chosen core/configuration, return a documented unsupported result rather than inventing behavior.
- The VPN service owns one `ParcelFileDescriptor`. Its TUN callback must translate the supported `TunOptions` addresses, DNS servers, routes, and package allow/deny lists into an Android `VpnService.Builder`, call `establish()`, retain the descriptor for deterministic cleanup, and return the descriptor expected by libbox.
- Protect the core's own outbound sockets through the service's `protect(fd)` callback to prevent routing loops.
- Service startup must be foreground and notification-backed before long-running engine work. Stop must close the libbox command server and TUN descriptor, unregister network callbacks, and stop foreground state even when startup fails.
- Request Android VPN consent in the Activity, then start the service. Do not attempt to show consent from a background service.
- A single app-level connection coordinator must serialize WireGuard and sing-box transitions. Disconnect the active engine and confirm it is down before starting the other one.

### Artifact provenance gate

The current app resolves `com.github.singbox-android:libbox:1.14.4` and compiles calls to `Libbox.setup` and `Libbox.checkConfig`. This proves those symbols are available to the current build, but does not by itself prove that the artifact is byte-for-byte the upstream Android AAR or that its full Android platform API matches the reviewed upstream source revision. Before implementing the adapter, record the resolved artifact checksum and source/release provenance, inspect its generated `PlatformInterface` and `TunOptions` signatures, and pin the verified artifact. Do not implement against guessed method signatures.

### Android service declaration

The VPN service must declare `android.permission.BIND_VPN_SERVICE` and the `android.net.VpnService` intent filter. On Android versions requiring foreground-service declarations, use only the service type and permissions justified by the target SDK and the platform's VPN-service exemption rules. Do not blindly copy the upstream app's entire permission list or its root-related components.

### Current implementation boundary

NetPulse currently validates sing-box JSON only. It does not yet create a sing-box command server, implement `PlatformInterface`, establish a sing-box TUN, or route device traffic through sing-box. The protocol catalog must continue to show sing-box-based protocols as integration-required until those behaviors and device-level traffic tests pass.
