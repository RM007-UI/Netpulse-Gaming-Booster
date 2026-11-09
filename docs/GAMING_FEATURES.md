# NetPulse gaming-focused features

## Game-only VPN routing

The Devices screen lists launcher-visible applications, prioritizes apps that Android categorizes as games, and lets the user save a package allowlist locally. When Game-only is enabled, NetPulse applies that list to a copy of the imported sing-box TUN profile. NetPulse itself is temporarily included so its UDP diagnostics can use the VPN path.

Only selected packages are routed through sing-box; other apps retain Android's ordinary network route. This is per-app split tunneling, not a latency accelerator. Disconnect and reconnect sing-box after changing the package selection. Profiles without a TUN inbound are rejected for Game-only mode. WireGuard remains full-device routing.

Per-app traffic counters use Android UID statistics where exposed by the device. A `—` value means Android did not provide that counter.

## Server manager

The Servers screen reads the loaded sing-box profile and shows server outbounds plus `selector` and `urltest` groups. It displays only endpoint metadata (tag, protocol type, host and port); credentials, UUIDs, passwords and keys are never sent to the WebView.

Selecting a node updates only `route.final` in the in-memory profile, then validates the resulting JSON with libbox. Custom route rules and the rest of the profile are preserved. The selection is applied when sing-box starts again; disconnect first if a tunnel is active. A rule that explicitly routes traffic elsewhere can still override the default outbound.

## Multi-sample TCP diagnostics

The connection test performs six TCP connection attempts to `1.1.1.1:443`. It reports minimum, median and maximum successful connection-establishment time, variation between sequential successful samples, and the percentage of attempts that failed.

These values describe TCP connection setup to the probe endpoint. They are not ICMP ping, game-server UDP latency, packet loss, or a measurement of the actual match route.

## Direct versus VPN UDP comparison

The Speed screen compares five UDP STUN Binding request/response samples over two Android Network handles: the physical network captured before sing-box establishes its TUN and the VPN Network itself. Both paths use the same resolved IPv4 address for `stun.l.google.com:19302`; DNS resolution happens over the physical network before timing begins.

This is available only while sing-box is connected and Game-only routing is enabled. NetPulse is temporarily added to the allowlist so its diagnostic datagrams traverse the VPN. The test validates the STUN response type, magic cookie and transaction ID, and reports median/minimum/maximum, sample variation, and unanswered or invalid attempts for each path.

STUN is a generic UDP path indicator. It does not measure a particular game's server, in-match latency, or gameplay packet loss.

## Claims and limitations

NetPulse does not claim to lower ping, increase FPS, prioritize game packets, or change Android's scheduler. A VPN can change routing and may improve or worsen latency depending on the VPN server, operator routing, and network conditions. DNS mainly affects name resolution; it does not continuously reduce in-match latency.

The implementation avoids automatic MTU changes, arbitrary packet marking, hidden bypass behavior, and fabricated network-quality scores. Measurements and their limitations are shown directly in the UI.
