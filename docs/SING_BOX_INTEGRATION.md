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
