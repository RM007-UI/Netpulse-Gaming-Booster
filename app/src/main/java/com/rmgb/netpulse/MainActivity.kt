package com.rmgb.netpulse

import android.app.Activity
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.Color
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import com.rmgb.netpulse.protocol.ProtocolCatalog
import com.rmgb.netpulse.protocol.ProtocolFamily
import com.rmgb.netpulse.protocol.EngineState
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import io.nekohasekai.libbox.Libbox
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val bg = Color.rgb(9, 15, 28)
    private val cyan = Color.rgb(48, 220, 230)
    private val muted = Color.rgb(165, 180, 198)
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var backend: GoBackend
    private lateinit var status: TextView
    private lateinit var connectButton: Button
    private lateinit var singBoxStatus: TextView
    private lateinit var singBoxButton: Button
    private var singBoxConnected = false
    private var singBoxStarting = false
    private var singBoxReceiverRegistered = false
    private val singBoxStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != SingBoxVpnService.ACTION_STATE) return
            singBoxConnected = intent.getBooleanExtra(SingBoxVpnService.EXTRA_CONNECTED, false)
            singBoxStarting = false
            if (!::singBoxButton.isInitialized || !::singBoxStatus.isInitialized) return
            singBoxButton.isEnabled = true
            singBoxButton.text = if (singBoxConnected) "Disconnect sing-box" else "Connect sing-box"
            singBoxStatus.text = if (singBoxConnected) "sing-box connected" else "sing-box disconnected"
        }
    }
    private var loadedConfig: Config? = null
    private var connected = false

    private lateinit var refreshRootStatus: TextView
    private lateinit var refreshStatus: TextView
    private lateinit var refreshRateContainer: LinearLayout
    private lateinit var autoRefreshButton: Button
    private val refreshHandler = Handler(Looper.getMainLooper())
    private var refreshRootGranted = false

    private val wireGuardTunnel: Tunnel get() = (application as NetPulseApplication).wireGuardTunnel
    private val stateListener: (Tunnel.State) -> Unit = { newState ->
        runOnUiThread {
            if (!isDestroyed && ::status.isInitialized && ::connectButton.isInitialized) {
                connected = newState == Tunnel.State.UP
                if (newState == Tunnel.State.DOWN) VpnEngineCoordinator.release(VpnEngineCoordinator.Engine.WIREGUARD)
                status.text = if (connected) "WireGuard connected"
                    else if (loadedConfig != null) "Profile validated · ready to connect"
                    else "No profile loaded"
                connectButton.text = if (connected) "Disconnect" else "Connect"
                connectButton.isEnabled = connected || loadedConfig != null
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as NetPulseApplication
        loadedConfig = app.loadedWireGuardConfig
        backend = app.wireGuardBackend
        app.wireGuardStateListener = stateListener

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 28, 22, 24)
            setBackgroundColor(bg)
        }
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        body.addView(label("NETPULSE", 27f, cyan, true))
        body.addView(label("Gaming network toolkit", 14f, muted))

        body.addView(label("Display Refresh Rate", 21f, Color.WHITE, true).apply { setPadding(0, 28, 0, 8) })
        body.addView(label("Lock the screen to a refresh mode exposed by the display firmware.", 13f, muted))
        refreshRootStatus = label("Checking root access…", 13f, muted).apply { setPadding(0, 10, 0, 8) }
        body.addView(refreshRootStatus)
        refreshRateContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(refreshRateContainer)
        autoRefreshButton = Button(this).apply {
            text = "Auto · Restore previous settings"
            isEnabled = false
            setOnClickListener { restoreAutomaticRefreshRate() }
        }
        body.addView(autoRefreshButton)
        refreshStatus = label("Reading supported display modes…", 13f, muted).apply { setPadding(0, 8, 0, 12) }
        body.addView(refreshStatus)
        body.addView(label("WireGuard", 21f, Color.WHITE, true).apply { setPadding(0, 28, 0, 8) })
        body.addView(label("Import a WireGuard .conf file supplied by your VPN provider. The profile stays in memory and is not written to app storage.", 13f, muted))
        body.addView(Button(this).apply {
            text = "Import WireGuard profile (.conf)"
            setOnClickListener { openProfilePicker() }
        })
        status = label(if (loadedConfig != null) "Profile validated · ready to connect" else "No profile loaded", 13f, muted).apply { setPadding(0, 12, 0, 12) }
        body.addView(status)
        connectButton = Button(this).apply {
            text = "Connect"
            isEnabled = loadedConfig != null
            setOnClickListener { toggleWireGuard() }
        }
        body.addView(connectButton)

        body.addView(label("sing-box", 21f, Color.WHITE, true).apply { setPadding(0, 28, 0, 8) })
        body.addView(label("Import a sing-box JSON configuration. The native core validates it before accepting it.", 13f, muted))
        body.addView(Button(this).apply {
            text = "Import sing-box JSON"
            setOnClickListener { openSingBoxPicker() }
        })
        singBoxStatus = label(if (app.loadedSingBoxProfile != null) "Configuration validated · ready to connect" else "No sing-box configuration loaded", 13f, muted).apply { setPadding(0, 12, 0, 12) }
        body.addView(singBoxStatus)
        singBoxButton = Button(this).apply {
            text = "Connect sing-box"
            isEnabled = app.loadedSingBoxProfile != null
            setOnClickListener { toggleSingBox() }
        }
        body.addView(singBoxButton)

        body.addView(label("Protocol catalog", 20f, Color.WHITE, true).apply { setPadding(0, 28, 0, 8) })
        body.addView(label("Only integrated engines are marked as available. Other entries remain inactive until their engines are implemented and tested.", 13f, muted))
        addFamily(body, "VPN TUNNELS", ProtocolFamily.VPN_TUNNEL)
        addFamily(body, "PROXY PROTOCOLS", ProtocolFamily.PROXY)
        addFamily(body, "TRANSPORTS", ProtocolFamily.TRANSPORT)
        addFamily(body, "DNS", ProtocolFamily.DNS)
        setContentView(root)
        initializeRefreshRateControls()
    }


    private fun initializeRefreshRateControls() {
        updateRefreshRateSummary()
        renderRefreshRateButtons()
        refreshRootStatus.setOnClickListener { checkRefreshRootPermission() }
        checkRefreshRootPermission()
    }

    private fun checkRefreshRootPermission() {
        refreshRootStatus.text = "Checking root permission…"
        worker.execute {
            val rootAvailable = runCatching { RefreshRateController.hasRoot() }.getOrDefault(false)
            runOnUiThread {
                if (!isDestroyed) {
                    refreshRootGranted = rootAvailable
                    refreshRootStatus.text = if (rootAvailable) {
                        "Root access: granted"
                    } else {
                        "Root access: unavailable or denied. Tap here to check again."
                    }
                    renderRefreshRateButtons()
                }
            }
        }
    }

    private fun renderRefreshRateButtons() {
        if (!::refreshRateContainer.isInitialized) return
        refreshRateContainer.removeAllViews()
        val supported = runCatching {
            RefreshRateController.supportedModes(windowManager.defaultDisplay)
        }.getOrDefault(emptyList())
        val supportedRates = supported.map { it.hz }.distinct().sorted()
        val visibleRates = (listOf(60, 90, 120) + supportedRates).distinct().sorted()

        visibleRates.chunked(3).forEach { group ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            group.forEach { hz ->
                val available = supportedRates.contains(hz)
                val button = Button(this).apply {
                    text = if (available) hz.toString() + " Hz" else hz.toString() + " Hz · N/A"
                    isEnabled = available && refreshRootGranted
                    setOnClickListener { applyRefreshRate(hz) }
                }
                row.addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 5
                })
            }
            refreshRateContainer.addView(row)
        }

        if (supportedRates.isEmpty()) {
            refreshRateContainer.addView(label("No supported display modes could be read.", 12f, muted))
        }

        val prefs = getSharedPreferences(REFRESH_PREFS, MODE_PRIVATE)
        autoRefreshButton.isEnabled = refreshRootGranted && prefs.getBoolean("snapshot_saved", false)
    }

    private fun updateRefreshRateSummary() {
        if (!::refreshStatus.isInitialized) return
        val display = windowManager.defaultDisplay
        val active = RefreshRateController.currentRate(display)
        val supportedRates = RefreshRateController.supportedModes(display).map { it.hz }.distinct().sorted()
        refreshStatus.text = "Active now: " + active + " Hz   ·   Available: " +
            (if (supportedRates.isEmpty()) "unknown" else supportedRates.joinToString(" / ") + " Hz")
    }

    private fun applyRefreshRate(hz: Int) {
        val mode = RefreshRateController.supportedModes(windowManager.defaultDisplay)
            .firstOrNull { it.hz == hz }
        if (mode == null) {
            Toast.makeText(this, "This refresh rate is not exposed by the display firmware.", Toast.LENGTH_LONG).show()
            return
        }
        if (!refreshRootGranted) {
            Toast.makeText(this, "Root access is required for this action.", Toast.LENGTH_LONG).show()
            return
        }

        refreshStatus.text = "Applying " + hz + " Hz and checking the live display mode…"
        autoRefreshButton.isEnabled = false
        worker.execute {
            try {
                val prefs = getSharedPreferences(REFRESH_PREFS, MODE_PRIVATE)
                if (!prefs.getBoolean("snapshot_saved", false)) {
                    val snapshot = RefreshRateController.captureSnapshot()
                    prefs.edit()
                        .putString("snapshot", snapshot.encode())
                        .putBoolean("snapshot_saved", true)
                        .apply()
                }

                val result = RefreshRateController.runRoot(RefreshRateController.buildApplyScript(mode))
                check(result.exitCode == 0) { "Root command returned exit code " + result.exitCode }

                runOnUiThread {
                    if (!isDestroyed) {
                        renderRefreshRateButtons()
                        refreshStatus.text = "Requested " + hz + " Hz. Verifying the active panel mode…"
                        refreshHandler.postDelayed({
                            if (!isDestroyed) {
                                val active = RefreshRateController.currentRate(windowManager.defaultDisplay)
                                refreshStatus.text = if (active == hz) {
                                    "Verified: the panel is running at " + active + " Hz."
                                } else {
                                    "Requested " + hz + " Hz, but the panel currently reports " + active +
                                        " Hz. HyperOS policy may be limiting the change."
                                }
                                renderRefreshRateButtons()
                            }
                        }, 1400)
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isDestroyed) {
                        refreshStatus.text = "Could not apply the refresh rate: " + error.javaClass.simpleName
                        renderRefreshRateButtons()
                        Toast.makeText(this, "Refresh-rate change failed. Check the root permission.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun restoreAutomaticRefreshRate() {
        val prefs = getSharedPreferences(REFRESH_PREFS, MODE_PRIVATE)
        val encoded = prefs.getString("snapshot", null)
        if (encoded.isNullOrBlank()) {
            autoRefreshButton.isEnabled = false
            return
        }

        autoRefreshButton.isEnabled = false
        refreshStatus.text = "Restoring the display settings saved before the first change…"
        worker.execute {
            try {
                val snapshot = RefreshSettingsSnapshot.decode(encoded)
                val result = RefreshRateController.runRoot(RefreshRateController.buildRestoreScript(snapshot))
                check(result.exitCode == 0) { "Restore command returned exit code " + result.exitCode }
                prefs.edit().remove("snapshot").putBoolean("snapshot_saved", false).apply()
                runOnUiThread {
                    if (!isDestroyed) {
                        updateRefreshRateSummary()
                        refreshStatus.text = "Previous settings restored. HyperOS can manage refresh rate automatically again."
                        renderRefreshRateButtons()
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isDestroyed) {
                        refreshStatus.text = "Could not restore settings: " + error.javaClass.simpleName
                        renderRefreshRateButtons()
                    }
                }
            }
        }
    }

    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_PROFILE_BYTES) {
                throw IllegalArgumentException("Profile exceeds the 2 MiB import limit")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun openSingBoxPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
        startActivityForResult(intent, REQUEST_SINGBOX_PROFILE)
    }

    private fun updateSingBoxStatus(message: String) { singBoxStatus.text = message }

    private fun toggleSingBox() {
        if (singBoxConnected || singBoxStarting) {
            singBoxButton.isEnabled = false
            singBoxStatus.text = "Stopping sing-box…"
            stopService(Intent(this, SingBoxVpnService::class.java))
            return
        }
        val profile = (application as NetPulseApplication).loadedSingBoxProfile ?: return
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) {
            startActivityForResult(permissionIntent, REQUEST_SINGBOX_VPN_PERMISSION)
        } else {
            startSingBoxService(profile)
        }
    }

    private fun startSingBoxService(profile: String? = (application as NetPulseApplication).loadedSingBoxProfile) {
        if (profile.isNullOrBlank()) return
        if (!VpnEngineCoordinator.acquire(VpnEngineCoordinator.Engine.SING_BOX)) {
            singBoxStatus.text = "Another VPN engine is active. Disconnect it first."
            Toast.makeText(this, "Another VPN engine is active", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, SingBoxVpnService::class.java).putExtra(SingBoxVpnService.EXTRA_PROFILE, profile)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            singBoxStarting = true
            singBoxButton.isEnabled = false
            singBoxButton.text = "Starting…"
            singBoxStatus.text = "Starting sing-box…"
        } catch (error: Exception) {
            VpnEngineCoordinator.release(VpnEngineCoordinator.Engine.SING_BOX)
            singBoxConnected = false
            singBoxStarting = false
            singBoxButton.isEnabled = true
            singBoxButton.text = "Connect sing-box"
            singBoxStatus.text = "sing-box failed to start"
            Toast.makeText(this, "sing-box could not start", Toast.LENGTH_LONG).show()
        }
    }

    private fun openProfilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "application/octet-stream"))
        }
        startActivityForResult(intent, REQUEST_PROFILE)
    }

    @Deprecated("Handled for compatibility with the current minimal Activity scaffold")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SINGBOX_VPN_PERMISSION) {
            if (resultCode == RESULT_OK) startSingBoxService()
            return
        }
        if (requestCode == REQUEST_VPN_PERMISSION) {
            val app = application as NetPulseApplication
            val config = app.pendingWireGuardConfig
            app.pendingWireGuardConfig = null
            if (resultCode == RESULT_OK && config != null) connect(config)
            return
        }
        if (requestCode == REQUEST_SINGBOX_PROFILE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            worker.execute {
                try {
                    val bytes = contentResolver.openInputStream(uri)?.use { readLimited(it) }
                        ?: throw IllegalArgumentException("Unable to open selected JSON profile")
                    if (bytes.size > MAX_PROFILE_BYTES) throw IllegalArgumentException("Profile exceeds the 2 MiB import limit")
                    val content = ProfileTextDecoder.decodeUtf8(bytes)
                    if (content.isBlank()) throw IllegalArgumentException("Configuration is empty")
                    Libbox.checkConfig(content)
                    runOnUiThread {
                        (application as NetPulseApplication).loadedSingBoxProfile = content
                        updateSingBoxStatus("Configuration validated · ready to connect")
                        singBoxButton.isEnabled = true
                        Toast.makeText(this, "sing-box configuration validated", Toast.LENGTH_SHORT).show()
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        updateSingBoxStatus("sing-box configuration rejected")
                        Toast.makeText(this, "Configuration rejected. Check the profile locally and try again.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            return
        }
        if (requestCode != REQUEST_PROFILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        worker.execute {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { readLimited(it) }
                    ?: throw IllegalArgumentException("Unable to open the selected profile")
                val parsed = bytes.inputStream().use { Config.parse(it) }
                runOnUiThread {
                    (application as NetPulseApplication).loadedWireGuardConfig = parsed
                    loadedConfig = parsed
                    status.text = "Profile validated · ${parsed.peers.size} peer(s)"
                    connectButton.isEnabled = true
                    Toast.makeText(this, "WireGuard profile imported", Toast.LENGTH_SHORT).show()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    (application as NetPulseApplication).loadedWireGuardConfig = null
                    loadedConfig = null
                    connectButton.isEnabled = false
                    status.text = "Profile could not be parsed"
                    Toast.makeText(this, "WireGuard profile rejected. Check the file and try again.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun toggleWireGuard() {
        if (connected) {
            connectButton.isEnabled = false
            worker.execute {
                try {
                    backend.setState(wireGuardTunnel, Tunnel.State.DOWN, null)
                } catch (error: Exception) {
                    showError("Disconnect failed", error)
                }
            }
            return
        }
        val config = loadedConfig ?: return
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) {
            (application as NetPulseApplication).pendingWireGuardConfig = config
            startActivityForResult(permissionIntent, REQUEST_VPN_PERMISSION)
        } else {
            connect(config)
        }
    }

    private fun connect(config: Config) {
        if (!VpnEngineCoordinator.acquire(VpnEngineCoordinator.Engine.WIREGUARD)) {
            status.text = "Another VPN engine is active. Disconnect it first."
            Toast.makeText(this, "Another VPN engine is active", Toast.LENGTH_LONG).show()
            return
        }
        connectButton.isEnabled = false
        status.text = "Starting WireGuard…"
        worker.execute {
            try {
                backend.setState(wireGuardTunnel, Tunnel.State.UP, config)
                runOnUiThread { connectButton.isEnabled = true }
            } catch (error: Exception) {
                VpnEngineCoordinator.release(VpnEngineCoordinator.Engine.WIREGUARD)
                showError("Connection failed", error)
            }
        }
    }

    private fun showError(prefix: String, error: Exception) {
        runOnUiThread {
            status.text = "$prefix · ${error.javaClass.simpleName}"
            connectButton.isEnabled = loadedConfig != null
            Toast.makeText(this, "$prefix (${error.javaClass.simpleName})", Toast.LENGTH_LONG).show()
        }
    }

    private fun addFamily(parent: LinearLayout, title: String, family: ProtocolFamily) {
        parent.addView(label(title, 14f, cyan, true).apply { setPadding(0, 22, 0, 8) })
        ProtocolCatalog.options.filter { it.family == family }.forEach { option ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14, 12, 14, 12)
                setBackgroundColor(Color.rgb(18, 29, 46))
            }
            card.addView(label(option.title, 16f, Color.WHITE, true))
            card.addView(label(option.description, 12f, muted))
            card.addView(label(ProtocolCatalog.stateLabel(option.engineState), 11f, if (option.engineState == EngineState.AVAILABLE) cyan else Color.rgb(255, 190, 95)))
            parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 })
        }
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    override fun onStart() {
        super.onStart()
        if (!singBoxReceiverRegistered) {
            val filter = IntentFilter(SingBoxVpnService.ACTION_STATE)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(singBoxStateReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(singBoxStateReceiver, filter)
            }
            singBoxReceiverRegistered = true
        }
    }

    override fun onStop() {
        if (singBoxReceiverRegistered) {
            unregisterReceiver(singBoxStateReceiver)
            singBoxReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        singBoxConnected = SingBoxVpnService.isRunning
        singBoxStarting = false
        if (::singBoxButton.isInitialized && ::singBoxStatus.isInitialized) {
            singBoxButton.text = if (singBoxConnected) "Disconnect sing-box" else "Connect sing-box"
            singBoxButton.isEnabled = true
            singBoxStatus.text = if (singBoxConnected) "sing-box connected" else if ((application as NetPulseApplication).loadedSingBoxProfile != null) "Configuration validated · ready to connect" else "No sing-box configuration loaded"
        }
        if (::backend.isInitialized && ::connectButton.isInitialized) {
            worker.execute {
                runCatching { backend.getState(wireGuardTunnel) == Tunnel.State.UP }
                    .onSuccess { isUp ->
                        if (isUp) VpnEngineCoordinator.acquire(VpnEngineCoordinator.Engine.WIREGUARD)
                        else VpnEngineCoordinator.release(VpnEngineCoordinator.Engine.WIREGUARD)
                        runOnUiThread {
                            connected = isUp
                            connectButton.text = if (isUp) "Disconnect" else "Connect"
                            connectButton.isEnabled = isUp || loadedConfig != null
                            status.text = when {
                                isUp -> "WireGuard connected"
                                loadedConfig != null -> "Profile validated · ready to connect"
                                else -> "No profile loaded"
                            }
                        }
                    }
            }
        }
    }

    override fun onDestroy() {
        if ((application as NetPulseApplication).wireGuardStateListener === stateListener) {
            (application as NetPulseApplication).wireGuardStateListener = null
        }
        refreshHandler.removeCallbacksAndMessages(null)
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_PROFILE = 711
        private const val REQUEST_VPN_PERMISSION = 710
        private const val REQUEST_SINGBOX_PROFILE = 712
        private const val REQUEST_SINGBOX_VPN_PERMISSION = 713
        private const val MAX_PROFILE_BYTES = 2 * 1024 * 1024
        private const val REFRESH_PREFS = "netpulse_refresh_rate"
    }
}
