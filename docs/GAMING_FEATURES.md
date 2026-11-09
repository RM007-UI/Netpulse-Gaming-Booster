# NetPulse gaming-focused features

## Game-only VPN routing

The Devices screen can list launcher-visible apps, let the user select games/apps, and save the selected package names locally. When Game-only is enabled, NetPulse modifies a copy of the imported sing-box profile and adds an Android `include_package` allowlist to its TUN inbound. The imported profile kept in process memory is not overwritten.

Only the selected packages are routed through the sing-box VPN. Other apps use Android's ordinary network connection. This is per-app split tunneling, not a latency accelerator. The VPN must be disconnected and reconnected after changing the selection. The feature requires a sing-box JSON profile with a TUN inbound; unsupported profiles fail validation instead of silently starting with the wrong routing. WireGuard remains full-device routing in this version.

Android application traffic counters are read by UID where the platform exposes them. A `—` value means Android did not provide a counter for that app.

## Multi-sample connection diagnostics

The connection test performs six TCP connection attempts to `1.1.1.1:443`. It reports the minimum, median and maximum successful connection-establishment time, the mean absolute difference between sequential successful samples, and the percentage of attempts that failed.

These values describe TCP connection setup to the probe endpoint. They are not ICMP ping, game-server UDP latency, packet loss, or a measurement of the actual match route. A future game-specific measurement needs a reachable endpoint controlled by the game server or a compatible UDP echo service.

## Claims and limitations

NetPulse does not claim to lower ping, increase FPS, prioritize game packets, or change Android's scheduler. A VPN can change routing and may improve or worsen latency depending on the server, operator routing and network conditions. DNS changes mainly affect name resolution and do not continuously reduce in-match latency.

The implementation deliberately avoids unsafe automatic MTU changes, arbitrary packet marking, hidden bypass behavior and fabricated network-quality scores. User-selected app routing and measured diagnostics are exposed directly, with their limitations stated in the UI.

## Direct versus VPN UDP comparison

The Speed screen can compare five UDP STUN Binding request/response samples over two explicitly selected Android Network handles: the physical network captured immediately before sing-box establishes its TUN, and the VPN Network itself. Both paths target the same resolved IPv4 address for `stun.l.google.com:19302`. The physical network performs the DNS lookup so that DNS resolution is not included in either timing set.

This is available only while sing-box is connected and Game-only routing is enabled. The app adds its own package to the temporary per-app allowlist so its diagnostic datagrams can traverse the VPN Network; selected game packages remain included. Turning Game-only off restores the imported profile's normal routing when the tunnel is next connected.

The test uses STUN Binding messages over UDP and validates the response type, magic cookie and 96-bit transaction ID before recording a sample. It reports median/min/max response time, sample variation and unanswered/invalid attempts for each path, plus the median difference. It does not measure a specific game's server, gameplay packet loss, or match latency. Google WebRTC examples use `stun.l.google.com:19302` as a STUN endpoint; STUN Binding request/response structure and transaction-ID validation follow RFC 8489.
