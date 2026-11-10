package com.rmgb.netpulse

/**
 * Android exposes one active VpnService tunnel per user/profile. A foreign VPN
 * must be stopped before NetPulse starts its own tunnel; otherwise route handoff
 * and permission changes can race the native engine startup.
 */
internal object VpnConnectionPolicy {
    fun isForeignVpnActive(hasAnyVpnNetwork: Boolean, netPulseOwnsVpn: Boolean): Boolean =
        hasAnyVpnNetwork && !netPulseOwnsVpn
}
