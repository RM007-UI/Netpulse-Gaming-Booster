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


## URLTest activation and custom endpoint checks

The Servers screen can switch the profile's default route to an existing sing-box urltest group. This delegates node selection to sing-box's URLTest implementation and configured test URL/interval/tolerance. NetPulse does not invent a fastest-node result or run a background speed test. A profile without a urltest group must be edited by its owner before this action is available.

The Servers screen also accepts a user-supplied host and TCP port and measures five TCP connection-establishment attempts over the device's current default network. The result reports successes, median connection time, sequential sample variation and failures. If a VPN is the active default network, the socket follows that route; otherwise it uses the physical/default route. This is a TCP endpoint reachability diagnostic, not ICMP ping, UDP game-server latency, or in-match packet loss. It does not compare two paths simultaneously.

## WireGuard Game-only routing

WireGuard Game-only is also available through Android's included-application allowlist. NetPulse creates a copied WireGuard configuration with the selected package list and preserves the peer configuration, interface addresses, DNS, MTU and listen port. The original imported profile remains unchanged. Android's VpnService app allowlist is full-device scoped to that VPN user; apps not selected continue on the ordinary network. IKEv2 remains full-device only.

## Per-game outbound assignment

The Devices screen can save an outbound tag for an individual selected game. On the next sing-box start, NetPulse prepends package-name route rules for those assignments while preserving the profile's existing rules. The assignment is only valid when the outbound exists in the loaded profile. These are routing choices, not automatic measurements of the game's match server.

## Automatic node selection

NetPulse can add a sing-box URLTest group that references existing endpoint outbounds. The sing-box core performs periodic health checks using the configured HTTP test URL and interval after the tunnel starts. This is core-managed endpoint selection, not a direct measurement of game-server latency. Profiles need at least two selectable endpoint outbounds.

## User-supplied game endpoint diagnostics

A user can enter a host and TCP port to compare five connection-establishment samples over the physical network and the VPN Network while Game-only is active. DNS resolution is performed before timing on the physical network, and both paths use the same resolved address. The UI reports successful attempts, median, variation and failures. This is an endpoint TCP reachability test; it is not UDP game traffic or an in-match ping.
