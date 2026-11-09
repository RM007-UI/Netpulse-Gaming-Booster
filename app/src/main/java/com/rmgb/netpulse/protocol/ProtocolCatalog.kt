package com.rmgb.netpulse.protocol

enum class EngineState { AVAILABLE, INTEGRATION_REQUIRED, PLANNED, NOT_A_TUNNEL_ENGINE }
enum class ProtocolFamily { VPN_TUNNEL, PROXY, TRANSPORT, DNS }

data class ProtocolOption(
    val id: String,
    val title: String,
    val family: ProtocolFamily,
    val description: String,
    val engineState: EngineState
)

object ProtocolCatalog {
    val options = listOf(
        ProtocolOption("wireguard", "WireGuard", ProtocolFamily.VPN_TUNNEL, "Integrated userspace tunnel; import your own .conf profile", EngineState.AVAILABLE),
        ProtocolOption("openvpn_udp", "OpenVPN UDP", ProtocolFamily.VPN_TUNNEL, "OpenVPN over UDP", EngineState.INTEGRATION_REQUIRED),
        ProtocolOption("openvpn_tcp", "OpenVPN TCP", ProtocolFamily.VPN_TUNNEL, "OpenVPN over TCP", EngineState.INTEGRATION_REQUIRED),
        ProtocolOption("ikev2_ipsec", "IKEv2 / IPsec", ProtocolFamily.VPN_TUNNEL, "IKEv2 VPN tunnel", EngineState.PLANNED),
        ProtocolOption("socks5", "SOCKS5", ProtocolFamily.PROXY, "Integrated through sing-box TUN; import a user profile", EngineState.AVAILABLE),
        ProtocolOption("shadowsocks", "Shadowsocks", ProtocolFamily.PROXY, "Integrated through sing-box TUN; import a user profile", EngineState.AVAILABLE),
        ProtocolOption("vless", "VLESS", ProtocolFamily.PROXY, "Integrated through sing-box TUN; import a user profile", EngineState.AVAILABLE),
        ProtocolOption("vmess", "VMess", ProtocolFamily.PROXY, "Integrated through sing-box TUN; import a user profile", EngineState.AVAILABLE),
        ProtocolOption("trojan", "Trojan", ProtocolFamily.PROXY, "Integrated through sing-box TUN; import a user profile", EngineState.AVAILABLE),
        ProtocolOption("hysteria", "Hysteria", ProtocolFamily.PROXY, "Hysteria v1 link or sing-box JSON; explicit upload/download rates required", EngineState.AVAILABLE),
        ProtocolOption("hysteria2", "Hysteria 2", ProtocolFamily.PROXY, "Integrated through sing-box TUN; import a user profile", EngineState.AVAILABLE),
        ProtocolOption("tuic", "TUIC", ProtocolFamily.PROXY, "Integrated through sing-box TUN; import a user profile", EngineState.AVAILABLE),
        ProtocolOption("anytls", "AnyTLS", ProtocolFamily.PROXY, "AnyTLS share link or sing-box JSON profile", EngineState.AVAILABLE),
        ProtocolOption("ssh", "SSH tunnel", ProtocolFamily.PROXY, "Integrated through sing-box JSON profiles", EngineState.AVAILABLE),
        ProtocolOption("http_proxy", "HTTP CONNECT", ProtocolFamily.PROXY, "Integrated through sing-box TUN; import a user profile", EngineState.AVAILABLE),
        ProtocolOption("naive", "NaiveProxy", ProtocolFamily.PROXY, "NaiveProxy share link or sing-box JSON profile; TLS verification enforced", EngineState.AVAILABLE),
        ProtocolOption("tcp", "TCP", ProtocolFamily.TRANSPORT, "Transport layer; not a VPN by itself", EngineState.NOT_A_TUNNEL_ENGINE),
        ProtocolOption("udp", "UDP", ProtocolFamily.TRANSPORT, "Transport layer; not a VPN by itself", EngineState.NOT_A_TUNNEL_ENGINE),
        ProtocolOption("quic", "QUIC", ProtocolFamily.TRANSPORT, "UDP-based transport", EngineState.NOT_A_TUNNEL_ENGINE),
        ProtocolOption("http2", "HTTP/2", ProtocolFamily.TRANSPORT, "Application transport; not a VPN by itself", EngineState.NOT_A_TUNNEL_ENGINE),
        ProtocolOption("http3", "HTTP/3", ProtocolFamily.TRANSPORT, "HTTP over QUIC; not a VPN by itself", EngineState.NOT_A_TUNNEL_ENGINE),
        ProtocolOption("dns", "Classic DNS", ProtocolFamily.DNS, "Traditional DNS resolver", EngineState.PLANNED),
        ProtocolOption("doh", "DNS over HTTPS", ProtocolFamily.DNS, "Encrypted DNS over HTTPS", EngineState.PLANNED),
        ProtocolOption("dot", "DNS over TLS", ProtocolFamily.DNS, "Encrypted DNS over TLS", EngineState.PLANNED),
        ProtocolOption("doq", "DNS over QUIC", ProtocolFamily.DNS, "Encrypted DNS over QUIC", EngineState.PLANNED)
    )

    fun stateLabel(state: EngineState) = when (state) {
        EngineState.AVAILABLE -> "Integrated in NetPulse · profile required"
        EngineState.INTEGRATION_REQUIRED -> "Engine integration required"
        EngineState.PLANNED -> "Planned"
        EngineState.NOT_A_TUNNEL_ENGINE -> "Transport only"
    }
}
