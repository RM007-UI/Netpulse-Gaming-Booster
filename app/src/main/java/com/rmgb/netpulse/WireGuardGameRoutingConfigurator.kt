package com.rmgb.netpulse

import com.wireguard.config.Config
import com.wireguard.config.Interface

/**
 * Builds a copy of a WireGuard configuration using Android's per-app VPN allowlist.
 * Only the selected game packages are moved into the tunnel; all other apps remain
 * on their normal Android network. The imported Config stays immutable and unchanged.
 */
object WireGuardGameRoutingConfigurator {
    private val packagePattern = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")

    fun withGameOnlyRouting(config: Config, selectedPackages: Set<String>): Config {
        require(selectedPackages.isNotEmpty()) { "Select at least one installed game" }
        require(selectedPackages.size <= 50) { "Select at most 50 games" }
        require(selectedPackages.all(packagePattern::matches)) {
            "The selected app list contains an invalid package name"
        }

        val original = config.getInterface()
        val updatedInterface = Interface.Builder()
            .setKeyPair(original.getKeyPair())
            .addAddresses(original.getAddresses())
            .addDnsServers(original.getDnsServers())
            .addDnsSearchDomains(original.getDnsSearchDomains())
            .apply {
                original.getListenPort().ifPresent { setListenPort(it) }
                original.getMtu().ifPresent { setMtu(it) }
                includeApplications(selectedPackages.sorted())
            }
            .build()

        return Config.Builder()
            .setInterface(updatedInterface)
            .addPeers(config.getPeers())
            .build()
    }
}
