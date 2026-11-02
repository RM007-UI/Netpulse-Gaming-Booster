package com.rmgb.netpulse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.net.ProxyInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.LinkProperties
import android.net.IpPrefix
import java.net.InetAddress
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import io.nekohasekai.libbox.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.net.NetworkInterface

/**
 * Minimal no-root sing-box VPN host. Unsupported root/platform integrations fail closed.
 * The profile is supplied in-memory by the Activity and is never logged or persisted here.
 */
class SingBoxVpnService : VpnService(), PlatformInterface, CommandServerHandler {
    private var commandServer: CommandServer? = null
    private var tunDescriptor: ParcelFileDescriptor? = null
    private val stopping = AtomicBoolean(false)
    private var started = false
    private val networkCallbacks = ConcurrentHashMap<InterfaceUpdateListener, ConnectivityManager.NetworkCallback>()
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val profile = intent?.getStringExtra(EXTRA_PROFILE)
        if (profile.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        startForeground(NOTIFICATION_ID, buildNotification("Starting sing-box…"))
        Thread({
            try {
                if (!VpnEngineCoordinator.acquire(VpnEngineCoordinator.Engine.SING_BOX)) {
                    throw IllegalStateException("Another VPN engine owns Android VPN")
                }
                val server = CommandServer(this, this)
                commandServer = server
                server.start()
                server.startOrReloadService(profile, OverrideOptions())
                started = true
                isRunning = true
                updateNotification("sing-box active")
                sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra(EXTRA_CONNECTED, true))
            } catch (error: Exception) {
                Log.e(TAG, "sing-box startup failed (${error.javaClass.simpleName})")
                stopEngine()
                stopSelf(startId)
            }
        }, "NetPulse-sing-box-start").start()
        return START_NOT_STICKY
    }

    override fun openTun(options: TunOptions): Int {
        check(!stopping.get()) { "VPN service is stopping" }
        check(prepare(this) == null) { "VPN permission is missing" }
        val builder = Builder().setSession("NetPulse sing-box").setMtu(options.mtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        fun addPrefixes(iterator: RoutePrefixIterator, route: Boolean) {
            while (iterator.hasNext()) {
                val prefix = iterator.next()
                if (route && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    builder.addRoute(prefix.address(), prefix.prefix())
                } else if (!route) {
                    builder.addAddress(prefix.address(), prefix.prefix())
                } else {
                    builder.addRoute(prefix.address(), prefix.prefix())
                }
            }
        }

        val hasV4Address = options.inet4Address.hasNext()
        val hasV6Address = options.inet6Address.hasNext()
        addPrefixes(options.inet4Address, false)
        addPrefixes(options.inet6Address, false)
        if (options.isHTTPProxyEnabled) {
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "HTTP proxy integration requires Android 10 or newer"
            }
            val bypassDomains = mutableListOf<String>()
            val bypass = options.httpProxyBypassDomain
            while (bypass.hasNext()) bypassDomains += bypass.next()
            val host = options.httpProxyServer
            val port = options.httpProxyServerPort
            require(host.isNotBlank() && port in 1..65535) { "Invalid HTTP proxy endpoint" }
            builder.setHttpProxy(ProxyInfo.buildDirectProxy(host, port, bypassDomains))
        }

        if (options.autoRoute) {
            val dns = options.dnsServerAddress
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                while (dns.hasNext()) builder.addDnsServer(dns.next())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val v4 = options.inet4RouteAddress
                val v6 = options.inet6RouteAddress
                if (v4.hasNext()) addPrefixes(v4, true)
                else if (hasV4Address) builder.addRoute("0.0.0.0", 0)
                if (v6.hasNext()) addPrefixes(v6, true)
                else if (hasV6Address) builder.addRoute("::", 0)
                fun excludePrefixes(iterator: RoutePrefixIterator) {
                    while (iterator.hasNext()) {
                        val prefix = iterator.next()
                        builder.excludeRoute(IpPrefix(InetAddress.getByName(prefix.address()), prefix.prefix()))
                    }
                }
                excludePrefixes(options.inet4RouteExcludeAddress)
                excludePrefixes(options.inet6RouteExcludeAddress)
            } else {
                addPrefixes(options.inet4RouteRange, true)
                addPrefixes(options.inet6RouteRange, true)
            }
            val include = options.includePackage
            while (include.hasNext()) builder.addAllowedApplication(include.next())
            val exclude = options.excludePackage
            while (exclude.hasNext()) builder.addDisallowedApplication(exclude.next())
        }
        val descriptor = builder.establish() ?: error("Android did not establish the VPN interface")
        synchronized(this) {
            tunDescriptor?.close()
            tunDescriptor = descriptor
        }
        return descriptor.fd
    }

    override fun usePlatformAutoDetectInterfaceControl() = true
    override fun autoDetectInterfaceControl(fd: Int) {
        check(protect(fd)) { "Unable to protect sing-box outbound socket" }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        closeDefaultInterfaceMonitor(listener)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = publishDefaultNetwork(network, listener)
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                publishDefaultNetwork(network, listener, linkProperties)
            override fun onLost(network: Network) {
                val active = connectivity.activeNetwork
                if (active == null || active == network) {
                    listener.updateDefaultInterface("", -1, false, false)
                } else {
                    publishDefaultNetwork(active, listener)
                }
            }
        }
        networkCallbacks[listener] = callback
        connectivity.registerDefaultNetworkCallback(callback)
        connectivity.activeNetwork?.let { publishDefaultNetwork(it, listener) }
            ?: listener.updateDefaultInterface("", -1, false, false)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        networkCallbacks.remove(listener)?.let { callback ->
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
    }

    private fun publishDefaultNetwork(
        network: Network,
        listener: InterfaceUpdateListener,
        suppliedProperties: LinkProperties? = null
    ) {
        val properties = suppliedProperties ?: connectivity.getLinkProperties(network)
        val name = properties?.interfaceName
        if (name.isNullOrBlank()) {
            listener.updateDefaultInterface("", -1, false, false)
            return
        }
        val index = runCatching { NetworkInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
        val capabilities = connectivity.getNetworkCapabilities(network)
        val metered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
        listener.updateDefaultInterface(name, index, metered, false)
    }
    override fun getInterfaces(): NetworkInterfaceIterator {
        val active = connectivity.activeNetwork
        val activeProperties = active?.let { connectivity.getLinkProperties(it) }
        val activeName = activeProperties?.interfaceName
        val capabilities = active?.let { connectivity.getNetworkCapabilities(it) }
        val dnsServers = activeProperties?.dnsServers.orEmpty().map { it.hostAddress ?: "" }.filter { it.isNotBlank() }
        val gateways = activeProperties?.routes.orEmpty()
            .filter { it.isDefaultRoute && it.gateway != null }
            .mapNotNull { it.gateway.hostAddress }
            .distinct()

        val snapshot = java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { networkInterface ->
                runCatching { networkInterface.isUp && !networkInterface.isLoopback }.getOrDefault(false)
            }
            .mapNotNull { networkInterface ->
                runCatching {
                    val item = io.nekohasekai.libbox.NetworkInterface()
                    item.index = networkInterface.index
                    item.name = networkInterface.name
                    item.mtu = networkInterface.mtu
                    item.flags = 0
                    item.type = when {
                        networkInterface.name == activeName && capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ->
                            Libbox.InterfaceTypeWIFI
                        networkInterface.name == activeName && capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true ->
                            Libbox.InterfaceTypeCellular
                        networkInterface.name == activeName && capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true ->
                            Libbox.InterfaceTypeEthernet
                        else -> Libbox.InterfaceTypeOther
                    }
                    val addresses = networkInterface.interfaceAddresses.mapNotNull { entry ->
                        val address = entry.address ?: return@mapNotNull null
                        if (address.isAnyLocalAddress || address.isLoopbackAddress) return@mapNotNull null
                        "${address.hostAddress.substringBefore('%')}/${entry.networkPrefixLength}"
                    }
                    item.addresses = stringIterator(addresses)
                    item.dnsServer = stringIterator(if (networkInterface.name == activeName) dnsServers else emptyList())
                    item.gateway = stringIterator(if (networkInterface.name == activeName) gateways else emptyList())
                    item.metered = networkInterface.name == activeName &&
                        capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
                    item
                }.getOrNull()
            }
        return object : NetworkInterfaceIterator {
            private var position = 0
            override fun hasNext() = position < snapshot.size
            override fun next(): io.nekohasekai.libbox.NetworkInterface {
                if (!hasNext()) throw NoSuchElementException()
                return snapshot[position++]
            }
        }
    }

    private fun stringIterator(values: List<String>): StringIterator = object : StringIterator {
        private var position = 0
        override fun hasNext() = position < values.size
        override fun len() = values.size
        override fun next(): String {
            if (!hasNext()) throw NoSuchElementException()
            return values[position++]
        }
    }
    override fun findConnectionOwner(ipProtocol: Int, sourceAddress: String, sourcePort: Int,
        destinationAddress: String, destinationPort: Int): ConnectionOwner = ConnectionOwner()
    override fun underNetworkExtension() = false
    override fun includeAllNetworks() = false
    override fun clearDNSCache() = Unit
    override fun readWIFIState(): WIFIState? = null
    override fun localDNSTransport(): LocalDNSTransport? = null
    override fun registerMyInterface(name: String?) = Unit
    override fun tailscaleHostname() = Build.MODEL ?: "Android"
    override fun usePlatformShell() = false
    override fun checkPlatformShell() { throw UnsupportedOperationException("Platform shell is unavailable") }
    override fun openShellSession(user: PlatformUser?, command: String?, environ: StringIterator?,
        term: String?, rows: Int, cols: Int): ShellSession =
        throw UnsupportedOperationException("Platform shell is unavailable")
    override fun lookupUser(username: String?): PlatformUser = throw UnsupportedOperationException("Platform user lookup is unavailable")
    override fun readSystemSSHHostKey(): String = throw UnsupportedOperationException("SSH host key is unavailable")
    override fun lookupSFTPServer(): String = throw UnsupportedOperationException("SFTP is unavailable")
    override fun usePlatformBridge() = false
    override fun createBridge(options: BridgeOptions?): BridgeSession =
        throw UnsupportedOperationException("Root bridge is unavailable")
    override fun startNeighborMonitor(listener: NeighborUpdateListener?) =
        throw UnsupportedOperationException("Neighbor monitoring is unavailable")
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) = Unit
    override fun sendNotification(notification: io.nekohasekai.libbox.Notification) = Unit
    override fun cancelNotification(identifier: String, typeID: Int) = Unit

    override fun connectSSHAgent(): Int = -1
    override fun getSystemProxyStatus() = SystemProxyStatus().apply {
        available = false
        enabled = false
    }
    override fun serviceReload() = Unit
    override fun serviceStop() { stopEngine(); stopSelf() }
    override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit
    override fun triggerNativeCrash() = Unit
    override fun writeDebugMessage(message: String) = Unit

    override fun onRevoke() {
        stopEngine()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopEngine()
        super.onDestroy()
    }

    private fun stopEngine() {
        if (!stopping.compareAndSet(false, true)) return
        networkCallbacks.keys.toList().forEach(::closeDefaultInterfaceMonitor)
        runCatching { commandServer?.closeService() }
        runCatching { commandServer?.close() }
        commandServer = null
        synchronized(this) {
            runCatching { tunDescriptor?.close() }
            tunDescriptor = null
        }
        started = false
        isRunning = false
        VpnEngineCoordinator.release(VpnEngineCoordinator.Engine.SING_BOX)
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra(EXTRA_CONNECTED, false))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    private fun buildNotification(message: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VPN connection", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("NetPulse")
                .setContentText(message)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("NetPulse")
                .setContentText(message)
                .setOngoing(true)
                .build()
        }
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(message))
    }

    companion object {
        @Volatile var isRunning: Boolean = false
            private set
        const val EXTRA_PROFILE = "com.rmgb.netpulse.extra.SINGBOX_PROFILE"
        const val ACTION_STATE = "com.rmgb.netpulse.action.SINGBOX_STATE"
        const val EXTRA_CONNECTED = "com.rmgb.netpulse.extra.CONNECTED"
        private const val TAG = "NetPulseSingBox"
        private const val CHANNEL_ID = "netpulse_vpn"
        private const val NOTIFICATION_ID = 4102
    }
}
