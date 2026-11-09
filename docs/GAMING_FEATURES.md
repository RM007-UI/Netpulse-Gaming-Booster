# NetPulse gaming-focused features

## Game-only routing

Game-only is supported by both WireGuard and sing-box:

- WireGuard uses the Android WireGuard library's included-application list on a copied configuration. Peer settings, addresses, DNS, MTU and listen port are preserved.
- sing-box requires an Android TUN inbound with auto_route enabled. NetPulse adds selected packages to include_package and includes its own package for diagnostic sockets. Existing route exclusions are removed only in the copied Game-only profile because Android's VpnService cannot combine allowlists and denylists.
- IKEv2 uses Android's system VPN manager and remains full-device; NetPulse refuses to start IKEv2 while Game-only is enabled.

The selected apps are validated against launcher-visible installed applications before connection. Removing an app from the device prunes the stale selection. Changing the list requires reconnecting the engine.

## Per-game outbound rules

For sing-box profiles, a selected game can use the default outbound or a specific node/group. At connection time, NetPulse creates package_name route rules with action=route and the selected outbound tag, puts them before existing rules, and validates the resulting config with libbox. Unknown tags, unselected app assignments and missing TUN profiles are rejected. Existing non-game route rules remain in place after the per-game rules.

WireGuard uses one peer profile for all selected apps; it does not provide separate server selection per package in this build.

## Automatic foreground-game detection

The user can optionally grant Android Usage Access. NetPulse reads foreground activity package transition events locally, identifies launcher-visible apps categorized as games or explicitly selected by the user, and displays the active package and label.

While the process remains alive, the dashboard samples UID-level RX/TX byte counters for that app when Android exposes them. A shared UID, custom OEM policy, work profile or unavailable TrafficStats counter may make the values approximate or unavailable. The feature does not read screen contents, messages or keystrokes and does not automatically start/stop a VPN.

## URLTest automatic relay selection

The Servers screen can create or update a sing-box urltest outbound over the concrete selectable server nodes in the loaded profile. The group uses a periodic HTTP health-check URL, interval and tolerance; sing-box chooses the candidate according to its URLTest algorithm after the tunnel starts.

This is a real core-managed relay health test, but it is not game-server ping. It tests the configured HTTP URL through each proxy node. It requires at least two supported server outbounds, and more-specific route rules in a user profile can still override route.final.

## Multi-sample TCP and custom game endpoint diagnostics

The standard TCP probe measures six TCP connection attempts to 1.1.1.1:443. The custom endpoint tool lets the user enter a hostname/IP and TCP port. NetPulse resolves the hostname once through the physical network, then compares ten TCP connect attempts over the physical Network and the active sing-box VPN Network using the same IP address.

The results show successful attempts, median connect time, min/max, sequential variation and failed connections. This is useful only for endpoints that accept TCP. It does not measure an application's UDP game session, ICMP ping or in-match packet loss.

## Direct versus VPN UDP comparison

The Speed screen compares five UDP STUN Binding request/response samples over the physical network and VPN Network. Both paths use the same resolved IP for stun.l.google.com:19302. The probe validates the STUN response type, magic cookie and transaction ID and reports median, min/max, RFC 3550-style smoothed RTT jitter, variation and unanswered/invalid probes.

STUN is a generic UDP path indicator. Its timeout ratio is not packet loss at a game server and does not represent an in-match route.

## Claims and limitations

NetPulse does not promise lower ping, higher FPS, packet prioritization or changes to Android's scheduler. VPN selection and routing can make a route better or worse. DNS affects name resolution rather than ongoing in-match latency. Automatic detection reports the foreground game; it does not discover proprietary server addresses or interpret game packets.

A generic third-party app cannot truthfully measure every game's UDP ping or packet loss without a game-specific protocol, a supported telemetry API or an echo/measurement service at the game endpoint. NetPulse therefore labels generic probes honestly.

## UI and reliability

The dashboard provides eight RTL screens, responsive navigation, live network charts, game UID estimates, server selection, local event history, refresh-rate controls and secure profile settings. The automated regression suite checks DOM IDs, JavaScript syntax, native bridge actions, Game-only configuration, endpoint probe wiring and engine safety rules.


## Per-game UDP echo diagnostics

The Speed screen can keep a diagnostic host, UDP port and optional hex payload for each Game-only-selected application. It compares five nonce-bearing diagnostic datagrams over the physical Android Network and active sing-box VPN Network, resolving the hostname once on the physical network so both paths test the same destination IP. A sample is counted only when the endpoint returns the exact request bytes.

The tool requires a purpose-built UDP echo endpoint; it does not emit arbitrary game packets or infer a response from a silent UDP service. NetPulse itself originates the diagnostic socket, so the probe follows NetPulse's default VPN outbound; the selected game name stores/labels the destination but does not exercise the game's package-specific outbound rule. Many commercial game servers do not echo arbitrary datagrams, so this test is not a universal game-server ping or in-match packet-loss measurement. The result is the RTT and timeout behavior to the configured echo service.


## Game session history

When the user enables optional Usage Access and keeps NetPulse in the foreground, the app samples the currently detected game's Android UID traffic counters and records a local session. Each completed record contains the application package/name, start/end timestamps, duration, sample count and approximate average/peak upload/download rates. The dashboard retains the latest 40 sessions and supports JSON export or user-initiated deletion.

The monitor deliberately stops when NetPulse leaves the foreground. It does not claim always-on/background session capture, and it does not capture packets, message contents or game payloads. Byte totals are approximations integrated from sampled rates; Android UID counters can be unavailable on some firmware. Sessions with unavailable counters do not record synthetic zero-rate samples.
