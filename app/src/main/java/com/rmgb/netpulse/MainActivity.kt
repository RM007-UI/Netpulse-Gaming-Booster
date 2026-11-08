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
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.SystemClock
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
import java.util.Locale

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

    private lateinit var networkBadge: TextView
    private lateinit var networkSummary: TextView
    private lateinit var downloadRate: TextView
    private lateinit var uploadRate: TextView
    private lateinit var totalTraffic: TextView
    private var lastNetworkRx = -1L
    private var lastNetworkTx = -1L
    private var lastNetworkSampleAt = 0L
    private val networkTick = object : Runnable {
        override fun run() {
            updateNetworkMetrics()
            refreshHandler.postDelayed(this, 1000L)
        }
    }

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
            setBackgroundColor(bg)
        }
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            setPadding(0, 0, 0, dp(14))
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(24))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val mark = label("N", 23f, bg, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(cyan, Color.rgb(113, 101, 255))
            ).apply { cornerRadius = dp(16).toFloat() }
        }
        header.addView(mark, LinearLayout.LayoutParams(dp(48), dp(48)))
        val brandText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(label("NetPulse", 23f, Color.WHITE, true))
            addView(label("GAMING NETWORK CONTROL", 9f, muted, true))
        }
        header.addView(brandText, LinearLayout.LayoutParams(0, -2, 1f))
        val livePill = label("● LIVE", 10f, Color.rgb(112, 242, 198), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = roundedBackground(Color.rgb(18, 54, 55), 40f, Color.rgb(31, 91, 91))
        }
        header.addView(livePill)
        body.addView(header)

        val overview = panel()
        overview.addView(label("NETWORK OVERVIEW  /  LIVE TELEMETRY", 10f, cyan, true))
        overview.addView(label("Your connection,\nunder control.", 25f, Color.WHITE, true).apply {
            setPadding(0, dp(8), 0, dp(8))
        })
        networkBadge = label("CHECKING", 10f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = roundedBackground(Color.rgb(48, 57, 74), 40f, Color.rgb(66, 83, 108))
        }
        overview.addView(networkBadge, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(9) })
        networkSummary = label("Checking active network…", 13f, muted)
        overview.addView(networkSummary)
        addPanel(body, overview)

        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val downloadCard = panel().apply {
            addView(label("DOWNLOAD", 10f, muted, true))
            downloadRate = label("—", 22f, Color.WHITE, true).apply { setPadding(0, dp(7), 0, 0) }
            addView(downloadRate)
        }
        val uploadCard = panel().apply {
            addView(label("UPLOAD", 10f, muted, true))
            uploadRate = label("—", 22f, Color.WHITE, true).apply { setPadding(0, dp(7), 0, 0) }
            addView(uploadRate)
        }
        metrics.addView(downloadCard, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        metrics.addView(uploadCard, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        body.addView(metrics, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        val trafficCard = panel()
        trafficCard.addView(label("DEVICE TRAFFIC", 10f, cyan, true))
        totalTraffic = label("↓ —    ↑ —", 13f, Color.WHITE, true).apply { setPadding(0, dp(7), 0, 0) }
        trafficCard.addView(totalTraffic)
        addPanel(body, trafficCard)

        body.addView(sectionTitle("CONNECTION ENGINES"))
        val wireCard = panel()
        wireCard.addView(label("WireGuard", 18f, Color.WHITE, true))
        wireCard.addView(label("Import a provider-issued .conf profile. Profile data stays in memory.", 12f, muted).apply {
            setPadding(0, dp(4), 0, dp(10))
        })
        wireCard.addView(styledButton("Import WireGuard profile  ·  .conf", false) { openProfilePicker() })
        status = label(
            if (loadedConfig != null) "Profile validated · ready to connect" else "No profile loaded",
            12f, muted
        ).apply { setPadding(0, dp(10), 0, dp(8)) }
        wireCard.addView(status)
        connectButton = styledButton("Connect WireGuard", true) { toggleWireGuard() }.apply {
            isEnabled = loadedConfig != null
        }
        wireCard.addView(connectButton)
        addPanel(body, wireCard)

        val singCard = panel()
        singCard.addView(label("sing-box", 18f, Color.WHITE, true))
        singCard.addView(label("Import and validate a sing-box JSON configuration before connecting.", 12f, muted).apply {
            setPadding(0, dp(4), 0, dp(10))
        })
        singCard.addView(styledButton("Import sing-box JSON", false) { openSingBoxPicker() })
        singBoxStatus = label(
            if (app.loadedSingBoxProfile != null) "Configuration validated · ready to connect"
            else "No sing-box configuration loaded",
            12f, muted
        ).apply { setPadding(0, dp(10), 0, dp(8)) }
        singCard.addView(singBoxStatus)
        singBoxButton = styledButton("Connect sing-box", true) { toggleSingBox() }.apply {
            isEnabled = app.loadedSingBoxProfile != null
        }
        singCard.addView(singBoxButton)
        addPanel(body, singCard)

        body.addView(sectionTitle("DISPLAY REFRESH RATE"))
        val refreshCard = panel()
        refreshCard.addView(label("Pin a mode supported by your display firmware.", 12f, muted))
        refreshRootStatus = label("Checking root access…", 12f, muted).apply {
            setPadding(0, dp(10), 0, dp(8))
        }
        refreshCard.addView(refreshRootStatus)
        refreshRateContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        refreshCard.addView(refreshRateContainer)
        autoRefreshButton = styledButton("AUTO  ·  Restore previous settings", false) {
            restoreAutomaticRefreshRate()
        }.apply { isEnabled = false }
        refreshCard.addView(autoRefreshButton)
        refreshStatus = label("Reading supported display modes…", 12f, muted).apply {
            setPadding(0, dp(9), 0, 0)
        }
        refreshCard.addView(refreshStatus)
        addPanel(body, refreshCard)

        body.addView(sectionTitle("PROTOCOL CATALOG"))
        body.addView(label(
            "Only integrated engines are marked available. Other protocols remain inactive until their engines are implemented and tested.",
            12f, muted
        ).apply { setPadding(dp(2), 0, dp(2), dp(6)) })
        addFamily(body, "VPN TUNNELS", ProtocolFamily.VPN_TUNNEL)
        addFamily(body, "PROXY PROTOCOLS", ProtocolFamily.PROXY)
        addFamily(body, "TRANSPORTS", ProtocolFamily.TRANSPORT)
        addFamily(body, "DNS", ProtocolFamily.DNS)

        body.addView(label(
            "NETPULSE  ·  Local diagnostics only. VPN profiles are never bundled.",
            10f, muted
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(4))
        })
        setContentView(root)
        initializeRefreshRateControls()
        updateNetworkMetrics()
    }


    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun roundedBackground(color: Int, radiusDp: Float, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
            setStroke(dp(1), stroke)
        }

    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(17), dp(16), dp(17), dp(16))
        background = roundedBackground(Color.rgb(16, 27, 47), 22f, Color.rgb(34, 51, 76))
    }

    private fun addPanel(parent: LinearLayout, view: LinearLayout) {
        parent.addView(view, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(12)
        })
    }

    private fun sectionTitle(value: String): TextView =
        label(value, 11f, cyan, true).apply {
            setPadding(dp(3), dp(11), dp(3), dp(9))
        }

    private fun styledButton(
        value: String,
        primary: Boolean,
        action: () -> Unit
    ): Button = Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 13f
        setTextColor(if (primary) Color.rgb(7, 19, 31) else Color.WHITE)
        background = roundedBackground(
            if (primary) cyan else Color.rgb(27, 43, 66),
            14f,
            if (primary) Color.rgb(84, 237, 242) else Color.rgb(54, 75, 105)
        )
        minimumHeight = dp(48)
        setPadding(dp(14), dp(7), dp(14), dp(7))
        setOnClickListener { action() }
    }

    private fun updateNetworkMetrics() {
        if (!::networkSummary.isInitialized) return

        val now = SystemClock.elapsedRealtime()
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        if (rx >= 0L && tx >= 0L) {
            val elapsed = now - lastNetworkSampleAt
            val downBytesPerSecond = if (lastNetworkRx >= 0L && elapsed > 0L) {
                ((rx - lastNetworkRx).coerceAtLeast(0L) * 1000L) / elapsed
            } else 0L
            val upBytesPerSecond = if (lastNetworkTx >= 0L && elapsed > 0L) {
                ((tx - lastNetworkTx).coerceAtLeast(0L) * 1000L) / elapsed
            } else 0L
            downloadRate.text = formatRate(downBytesPerSecond)
            uploadRate.text = formatRate(upBytesPerSecond)
            totalTraffic.text = "↓ " + formatBytes(rx) + "     ↑ " + formatBytes(tx)
            lastNetworkRx = rx
            lastNetworkTx = tx
            lastNetworkSampleAt = now
        } else {
            downloadRate.text = "Unavailable"
            uploadRate.text = "Unavailable"
            totalTraffic.text = "Traffic counters are not exposed on this device."
        }

        val manager = getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        val transport = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN tunnel"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobile data"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            else -> "No active network"
        }
        val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        networkBadge.text = when {
            validated -> "ONLINE"
            hasInternet -> "CHECK CONNECTION"
            else -> "OFFLINE"
        }
        networkBadge.background = roundedBackground(
            if (validated) Color.rgb(18, 62, 58) else Color.rgb(64, 48, 34),
            40f,
            if (validated) Color.rgb(36, 112, 97) else Color.rgb(116, 83, 50)
        )
        networkBadge.setTextColor(if (validated) Color.rgb(112, 242, 198) else Color.rgb(255, 198, 116))
        networkSummary.text = when {
            validated -> transport + " · internet access verified by Android"
            hasInternet -> transport + " · internet access is not yet verified"
            else -> "No validated internet connection detected."
        }
    }

    private fun formatRate(bytesPerSecond: Long): String {
        val megabits = bytesPerSecond * 8.0 / 1_000_000.0
        return if (megabits >= 1.0) {
            String.format(Locale.US, "%.2f Mbps", megabits)
        } else {
            String.format(Locale.US, "%.0f Kbps", bytesPerSecond * 8.0 / 1000.0)
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 0L) return "—"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var amount = bytes.toDouble()
        var unit = 0
        while (amount >= 1024.0 && unit < units.lastIndex) {
            amount /= 1024.0
            unit++
        }
        return String.format(Locale.US, "%.1f %s", amount, units[unit])
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
                // Capture the state before every attempt so a partially failed shell command
                // can be rolled back without losing the original Auto baseline.
                val beforeAttempt = RefreshRateController.captureSnapshot()
                if (!prefs.getBoolean("snapshot_saved", false)) {
                    prefs.edit()
                        .putString("snapshot", beforeAttempt.encode())
                        .putBoolean("snapshot_saved", true)
                        .apply()
                }

                try {
                    val result = RefreshRateController.runRoot(RefreshRateController.buildApplyScript(mode))
                    check(result.exitCode == 0) { "Root command returned exit code " + result.exitCode }
                } catch (applyError: Exception) {
                    val rollback = RefreshRateController.runRoot(
                        RefreshRateController.buildRestoreScript(beforeAttempt)
                    )
                    check(rollback.exitCode == 0) {
                        "Apply failed and rollback returned exit code " + rollback.exitCode
                    }
                    throw applyError
                }

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
        parent.addView(sectionTitle(title))
        ProtocolCatalog.options.filter { it.family == family }.forEach { option ->
            val card = panel()
            card.addView(label(option.title, 15f, Color.WHITE, true))
            card.addView(label(option.description, 11f, muted).apply {
                setPadding(0, dp(4), 0, dp(6))
            })
            val ready = option.engineState == EngineState.AVAILABLE
            card.addView(label(
                ProtocolCatalog.stateLabel(option.engineState),
                10f,
                if (ready) Color.rgb(112, 242, 198) else Color.rgb(255, 190, 95),
                true
            ))
            addPanel(parent, card)
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
        refreshHandler.removeCallbacks(networkTick)
        refreshHandler.post(networkTick)
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

    override fun onPause() {
        refreshHandler.removeCallbacks(networkTick)
        super.onPause()
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
