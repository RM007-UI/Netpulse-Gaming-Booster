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


### Verified libbox artifact provenance (1.14.4)

The dependency coordinate is backed by the public `singbox-android/libbox` repository, whose build script declares `group = com.github.singbox-android`, `artifactId = libbox`, and `version = 1.14.4`. Its documented build task downloads the AAR from the matching GitHub Release before publishing to Maven Local.

For the currently selected release:

- Release tag: `1.14.4`
- Release asset: `libbox.aar`
- Asset size: `118,293,410` bytes
- GitHub release SHA-256: `d2685924a657705beda44e94c6dc499e781df1ad20d65474e3591ec2f8b96465`
- Release page: https://github.com/singbox-android/libbox/releases/tag/1.14.4
- Source repository: https://github.com/singbox-android/libbox

This verifies the published asset identity and the repository's declared Maven coordinates. The Android CI now compares the resolved Gradle-cache AAR against this SHA-256 after the build and fails if the bytes differ, preventing an unexpected artifact from silently entering the APK.

### Confirmed libbox Android callback contract (upstream SFA reference)

The upstream Android implementation confirms the following API shapes for the 1.14.x binding:

- `PlatformInterface` is implemented by a platform wrapper; the VPN service separately overrides `openTun(options: TunOptions): Int`.
- `openTun` reads address iterators (`inet4Address`, `inet6Address`), DNS server iterators, route iterators, excluded routes, and include/exclude package iterators from `TunOptions`.
- The service creates the Android TUN with `VpnService.Builder.establish()`, retains the resulting `ParcelFileDescriptor`, and returns its file descriptor to libbox.
- `autoDetectInterfaceControl(fd)` in the VPN service calls `protect(fd)` so the core's outbound control socket does not loop back into its own TUN.
- The command lifecycle is based on `CommandServer(handler, platformInterface)`, `start()`, then `startOrReloadService(profileJson, OverrideOptions())`. A production implementation also needs the matching stop/close lifecycle; starting the command server alone is not a connected tunnel.
- The upstream platform wrapper exposes default-network monitoring, interface enumeration, connection-owner lookup, Wi-Fi state, local DNS transport, and optional neighbor/shell/bridge/auto-redirect callbacks.

### No-root implementation boundary (confirmed from upstream source)

NetPulse must not port root-dependent behavior from the reference client. The following capabilities are explicitly excluded from the first no-root implementation:

- Platform shell and SFTP server lookup
- Root bridge creation
- Platform auto-redirect
- Neighbor-table monitoring backed by privileged/root services

For the initial implementation, report platform bridge and auto-redirect as unsupported, and do not request root access. Implement public Android networking callbacks only where required by the selected libbox configuration. `findConnectionOwner` may use Android's public `ConnectivityManager.getConnectionOwnerUid` on API 29+, with a documented fallback or unsupported behavior on older API levels.

### TUN correctness requirements

- Preserve the TUN descriptor as a service-owned resource and close it exactly once on stop, revoke, startup failure, and service destruction.
- Translate IPv4 and IPv6 addresses, DNS servers, routes, route exclusions, and per-app allow/deny lists from `TunOptions`; do not silently drop options.
- Use `protect(fd)` for the engine's own sockets before network traffic starts.
- Handle `establish()` returning null as a hard startup failure.
- Do not claim connection success until `startOrReloadService` completes and the command server reports a started state.
- Serialize startup and teardown with WireGuard: never establish a sing-box TUN while WireGuard is UP, and never start WireGuard until sing-box is confirmed stopped.
- Keep profile JSON and secrets out of logs and notifications.

Reference implementation reviewed: `SagerNet/sing-box-for-android`, `VPNService.kt`, `PlatformInterfaceWrapper.kt`, and `BoxService.kt`. This is implementation guidance from upstream source, not a claim that NetPulse has already integrated those components.

### Android service and permission review

The upstream Android client declares its sing-box VPN component as a non-exported `VpnService` protected by `android.permission.BIND_VPN_SERVICE`, with a foreground-service type. It also has a separate notification/foreground-service lifecycle managed by its service controller. NetPulse must implement its own minimal notification channel and foreground notification rather than importing the upstream application's service framework.

Before enabling a sing-box service, review the target-SDK-specific foreground-service requirements and notification permission behavior for Android 13–15. Add only permissions that the implemented no-root feature actually uses; do not copy the upstream manifest wholesale. In particular, Wi-Fi/location, package visibility, boot receiver, special-use proxy, root bridge, camera, and storage permissions are not justified by the first TUN-only integration.

The current NetPulse manifest intentionally does not declare a sing-box service or foreground-service permissions because no sing-box tunnel service exists yet. Those declarations must land in the same change as a real service implementation, along with service startup, notification, revoke, stop, and descriptor cleanup tests.

### Device and API-level test matrix

- Android 8/API 26: baseline TUN establishment, IPv4 routes, DNS, revoke, and teardown.
- Android 10/API 29: connection-owner callback behavior and per-app routing where supported.
- Android 13/API 33: notification permission behavior and route exclusion APIs.
- Android 14/API 34 and Android 15/API 35: foreground-service type and start restrictions.
- Wi-Fi to cellular handoff, cellular to Wi-Fi handoff, airplane mode, IPv6-only/dual-stack networks, app process recreation, force-stop, VPN permission revoke, and simultaneous-start attempts against WireGuard.

CI can validate compilation and unit tests, but only real-device runs can validate the Android VPN prompt, foreground-service policy, route installation, DNS behavior, and traffic flow.


### Verified no-root PlatformInterface surface

The upstream wrapper's current interface implementation was inspected directly. The Android-facing methods that matter to the first no-root TUN adapter are:

| Callback | First implementation |
| --- | --- |
| `usePlatformAutoDetectInterfaceControl` / `autoDetectInterfaceControl(fd)` | Return platform control enabled and call `VpnService.protect(fd)` for core sockets. |
| `openTun(TunOptions)` | Implement in the VPN service; map addresses, MTU, DNS, routes, exclusions and app allow/deny lists into `VpnService.Builder`; retain the resulting `ParcelFileDescriptor`. |
| `findConnectionOwner(...)` | API 29+ via `ConnectivityManager.getConnectionOwnerUid`; earlier versions may use libbox fallback only if its behavior is verified. |
| `startDefaultInterfaceMonitor(listener)` / `closeDefaultInterfaceMonitor(listener)` | Provide a lifecycle-bound connectivity callback and update the default interface without reporting the VPN interface as its own upstream. |
| `getInterfaces()` | Enumerate Android networks and their link properties/capabilities; return libbox interface iterator objects. |
| `underNetworkExtension()` / `includeAllNetworks()` | Return false for Android VpnService. |
| `clearDNSCache()` | Safe no-op unless a real resolver cache is introduced. |
| `readWIFIState()` | Return null or an empty state when platform permission/privacy constraints prevent reading identifiers; do not request location merely for optional metadata. |
| `localDNSTransport()` | Return null initially; add a resolver bridge only after its callback and cancellation behavior are tested. |
| `startNeighborMonitor` / `closeNeighborMonitor` | No-op: upstream implementation uses a privileged root service and is outside the no-root scope. |
| `usePlatformShell`, `checkPlatformShell`, `openShellSession`, `lookupSFTPServer` | Explicitly unsupported; never invoke shell or root APIs. |
| `readSystemSSHHostKey()` | Unsupported. |
| `tailscaleHostname()` | Return a non-sensitive local device label only if the API requires it. |
| `usePlatformBridge`, `createBridge` | Unsupported; upstream bridge is root-backed. |
| `usePlatformAutoRedirect`, `createAutoRedirect` | Unsupported; upstream auto-redirect is root-backed. |
| `lookupUser` | Unsupported unless a concrete no-root use case and Android-safe mapping are established. |
| `registerMyInterface(name)` | No-op for the initial adapter. |

The upstream source also confirms the TUN builder's current option names: `inet4Address`, `inet6Address`, `dnsMode`, `dnsServerAddress`, `autoRoute`, `inet4RouteAddress`, `inet6RouteAddress`, `inet4RouteExcludeAddress`, `inet6RouteExcludeAddress`, `inet4RouteRange`, `inet6RouteRange`, `includePackage`, `excludePackage`, `mtu`, and optional HTTP proxy fields. These names are source-verified for the inspected upstream revision; the actual AAR's generated API must still be compiled against before implementation.

### CommandServer lifecycle verified from upstream

The upstream controller constructs `CommandServer(handler, platformInterface)`, calls `start()`, then invokes `startOrReloadService(profileJson, OverrideOptions())`. Shutdown closes the TUN descriptor, closes the service, then closes the command server. NetPulse must preserve this ordering while also handling startup exceptions, Android VPN revocation, service destruction, and repeated start/stop requests. A successful `CommandServer.start()` alone is not evidence that the tunnel is connected.

The upstream app's full wrapper contains substantial root-specific and app-specific code. It is a reference for the API contract, not code to copy wholesale. The initial adapter must remain small, explicitly no-root, and fail closed for unsupported methods.
