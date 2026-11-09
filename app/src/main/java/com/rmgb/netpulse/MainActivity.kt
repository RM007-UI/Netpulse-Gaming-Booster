package com.rmgb.netpulse

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.provider.Settings
import android.os.Process
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.net.VpnService
import android.net.VpnManager
import android.net.VpnProfileState
import android.os.Build
import android.content.pm.PackageManager
import android.text.InputType
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.RadioButton
import android.app.AlertDialog
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
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
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
    @Volatile private var singBoxConnected = false
    @Volatile private var ikev2Connected = false
    @Volatile private var ikev2Starting = false
    private var ikev2ConsentPending = false
    private var ikev2PollCount = 0
    private var singBoxStarting = false
    private var singBoxReceiverRegistered = false
    @Volatile private var activeGamePackage: String? = null
    @Volatile private var usageAccessSetupPending = false
    private var usageEventCursorMs = 0L
    private val gameDetectionTick = object : Runnable {
        override fun run() {
            detectForegroundGame()
            if (isAutoGameDetectionEnabled() && isUsageAccessGranted()) {
                refreshHandler.postDelayed(this, GAME_DETECTION_INTERVAL_MS)
            }
        }
    }
    private val singBoxStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != SingBoxVpnService.ACTION_STATE) return
            singBoxConnected = intent.getBooleanExtra(SingBoxVpnService.EXTRA_CONNECTED, false)
            singBoxStarting = false
            val startupError = intent.getStringExtra(SingBoxVpnService.EXTRA_ERROR)
            if (!::singBoxButton.isInitialized || !::singBoxStatus.isInitialized) return
            singBoxButton.isEnabled = true
            singBoxButton.text = if (singBoxConnected) "Disconnect sing-box" else "Connect sing-box"
            singBoxStatus.text = when {
                singBoxConnected -> "sing-box connected"
                startupError != null -> "sing-box failed · " + startupError
                else -> "sing-box disconnected"
            }
            if (startupError != null) {
                postWebMessage("راه‌اندازی موتور sing-box ناموفق بود: " + startupError)
            }
            publishNativeState()
        }
    }
    private var loadedConfig: Config? = null
    @Volatile private var connected = false

    private lateinit var refreshRootStatus: TextView
    private lateinit var refreshStatus: TextView
    private lateinit var refreshRateContainer: LinearLayout
    private lateinit var autoRefreshButton: Button
    private val refreshHandler = Handler(Looper.getMainLooper())
    @Volatile private var refreshRootGranted = false
    @Volatile private var liveRefreshEnabled = true

    private lateinit var networkBadge: TextView
    private lateinit var networkSummary: TextView
    private lateinit var downloadRate: TextView
    private lateinit var uploadRate: TextView
    private lateinit var totalTraffic: TextView
    private val trafficRateCalculator = TrafficRateCalculator()
    @Volatile private var networkDownBytesPerSecond = 0L
    @Volatile private var networkUpBytesPerSecond = 0L
    private lateinit var webView: WebView
    private val networkTick = object : Runnable {
        override fun run() {
            updateNetworkMetrics()
            if (liveRefreshEnabled) refreshHandler.postDelayed(this, 1000L)
        }
    }

    private val ikev2StateTick = object : Runnable {
        override fun run() {
            refreshIkev2State()
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
                publishNativeState()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as NetPulseApplication
        loadedConfig = app.loadedWireGuardConfig
        backend = app.wireGuardBackend
        app.wireGuardStateListener = stateListener

        // Native controls are kept as state holders for the existing VPN and refresh-rate engines.
        // The user-facing interface is the approved multi-screen HTML design rendered below.
        status = TextView(this)
        connectButton = Button(this)
        singBoxStatus = TextView(this)
        singBoxButton = Button(this)
        refreshRootStatus = TextView(this)
        refreshStatus = TextView(this)
        refreshRateContainer = LinearLayout(this)
        autoRefreshButton = Button(this)

        webView = WebView(this).apply {
            setBackgroundColor(bg)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.blockNetworkLoads = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?
                ): Boolean {
                    val target = request?.url?.toString() ?: return true
                    return !target.startsWith("file:///android_asset/netpulse/")
                }
            }
            addJavascriptInterface(NetPulseBridge(), "NetPulse")
        }
        // Android 15 / targetSdk 35 enforces edge-to-edge. Keep system bars outside
        // the HTML viewport, while leaving their surfaces in the app's dark palette.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        setContentView(webView)
        WindowCompat.getInsetsController(window, webView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(webView) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(webView)
        webView.loadUrl("file:///android_asset/netpulse/index.html")

        initializeRefreshRateControls()
        updateNetworkMetrics()
        refreshIkev2State()
    }

    inner class NetPulseBridge {
        @JavascriptInterface
        fun getState(): String = nativeState().toString()

        @JavascriptInterface
        fun getGamingAppCatalog(): String = gamingAppCatalogJson()

        @JavascriptInterface
        fun getServerCatalog(): String = serverCatalogJson()

        @JavascriptInterface
        fun perform(action: String, value: String?) {
            runOnUiThread {
                when (action) {
                    "importWireGuard" -> openProfilePicker()
                    "toggleWireGuard" -> toggleWireGuard()
                    "importSingBox" -> openSingBoxPicker()
                    "importSingBoxText" -> importSingBoxText(value)
                    "toggleSingBox" -> toggleSingBox()
                    "applyRefreshRate" -> {
                        val rate = value?.toIntOrNull()
                        if (rate != null) applyRefreshRate(rate)
                        else postWebMessage("نرخ انتخاب‌شده معتبر نیست.")
                    }
                    "restoreRefreshRate" -> restoreAutomaticRefreshRate()
                    "checkRoot" -> checkRefreshRootPermission()
                    "refresh" -> {
                        updateNetworkMetrics()
                        postWebMessage("اطلاعات دستگاه بروزرسانی شد.")
                    }
                    "setLiveRefresh" -> setLiveRefresh(value)
                    "testLatency" -> runTcpLatencyTest()
                    "testUdpRoutes" -> runUdpRouteComparison()
                    "toggleGamingMode" -> setGamingOnlyMode(value)
                    "saveGamingApps" -> saveGamingApps(value)
                    "saveGameRoutes" -> saveGameRoutes(value)
                    "selectServer" -> selectSingBoxServer(value)
                    "autoSelectServer" -> autoSelectFastestServer()
                    "requestGameDetection" -> requestGameDetection()
                    "toggleAutoGameDetection" -> toggleAutoGameDetection()
                    "testGameServer" -> runGameServerComparison(value)
                    "clearProfiles" -> confirmClearSavedProfiles()
                    "configureIkev2" -> showIkev2ProfileDialog()
                    "toggleIkev2" -> toggleIkev2()
                    else -> postWebMessage("این عملیات هنوز پشتیبانی نمی‌شود.")
                }
            }
        }
    }

    private fun nativeState(): JSONObject {
        val manager = getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        val transport = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobile data"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            else -> "Unknown"
        }
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val supported = runCatching {
            RefreshRateController.supportedModes(windowManager.defaultDisplay)
                .map { it.hz }.distinct().sorted()
        }.getOrDefault(emptyList())
        val refresh = runCatching {
            RefreshRateController.currentRate(windowManager.defaultDisplay)
        }.getOrDefault(0)
        return JSONObject()
            .put("networkType", transport)
            .put("hasInternet", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
            .put("validated", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            .put("downloadMbps", if (rx >= 0L) networkDownBytesPerSecond * 8.0 / 1_000_000.0 else JSONObject.NULL)
            .put("uploadMbps", if (tx >= 0L) networkUpBytesPerSecond * 8.0 / 1_000_000.0 else JSONObject.NULL)
            .put("rxBytes", if (rx >= 0L) rx else JSONObject.NULL)
            .put("txBytes", if (tx >= 0L) tx else JSONObject.NULL)
            .put("rxFormatted", formatBytes(rx))
            .put("txFormatted", formatBytes(tx))
            .put("wireguardConnected", connected)
            .put("wireguardProfileReady", loadedConfig != null)
            .put("singboxConnected", singBoxConnected || SingBoxVpnService.isRunning)
            .put("singboxProfileReady", (application as NetPulseApplication).loadedSingBoxProfile != null)
            .put("singboxProtocol", (application as NetPulseApplication).loadedSingBoxProtocol ?: JSONObject.NULL)
            .put("singboxDisplayName", (application as NetPulseApplication).loadedSingBoxDisplayName ?: JSONObject.NULL)
            .put("ikev2Connected", ikev2Connected)
            .put("ikev2Starting", ikev2Starting)
            .put("ikev2ProfileReady", (application as NetPulseApplication).loadedIkev2Config != null)
            .put("ikev2Server", (application as NetPulseApplication).loadedIkev2Config?.serverAddress ?: JSONObject.NULL)
            .put("ikev2Supported", isIkev2Supported())
            .put(
                "ikev2StatusUnverified",
                isIkev2StatusUnverified()
            )
            .put("gameOnlyEnabled", isGamingOnlyEnabled())
            .put("selectedGameCount", selectedGamingPackages().size)
            .put("autoGameDetectionEnabled", isAutoGameDetectionEnabled())
            .put("usageAccessGranted", isUsageAccessGranted())
            .put("activeGamePackage", activeGamePackage ?: JSONObject.NULL)
            .put("activeGameLabel", activeGamePackage?.let { pkg ->
                launchableApps().firstOrNull { it.packageName == pkg }?.label
            } ?: JSONObject.NULL)
            .put("activeGameSelected", activeGamePackage in selectedGamingPackages())
            .put("rootGranted", refreshRootGranted)
            .put("refreshRates", JSONArray(supported))
            .put("currentRefreshRate", refresh)
            .put("deviceModel", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
    }

    private fun publishNativeState() {
        if (!::webView.isInitialized || isDestroyed) return
        val payload = runCatching { nativeState().toString() }.getOrNull() ?: return
        webView.post {
            if (!isDestroyed) {
                webView.evaluateJavascript(
                    "window.netPulseApplyState && window.netPulseApplyState($payload);",
                    null
                )
            }
        }
    }

    private fun postWebMessage(message: String) {
        if (!::webView.isInitialized || isDestroyed) return
        val safe = JSONObject.quote(message)
        webView.post {
            if (!isDestroyed) {
                webView.evaluateJavascript("window.netPulseMessage && window.netPulseMessage($safe);", null)
            }
        }
    }

    private fun setLiveRefresh(rawEnabled: String?) {
        val enabled = rawEnabled.equals("true", ignoreCase = true)
        liveRefreshEnabled = enabled
        refreshHandler.removeCallbacks(networkTick)
        if (enabled && !isDestroyed) {
            updateNetworkMetrics()
            refreshHandler.postDelayed(networkTick, 1000L)
            postWebMessage("بروزرسانی خودکار شبکه فعال شد.")
        } else {
            postWebMessage("بروزرسانی خودکار متوقف شد؛ با زدن بروزرسانی دستی، داده تازه دریافت می‌شود.")
        }
    }

    private fun runTcpLatencyTest() {
        postWebMessage("در حال اجرای ۶ نمونه اتصال TCP به 1.1.1.1:443…")
        worker.execute {
            val samples = ArrayList<Long?>(TCP_PROBE_ATTEMPTS)
            repeat(TCP_PROBE_ATTEMPTS) { index ->
                val started = SystemClock.elapsedRealtime()
                val elapsed = runCatching {
                    Socket().use { socket ->
                        socket.connect(
                            InetSocketAddress(TCP_PROBE_HOST, TCP_PROBE_PORT),
                            TCP_PROBE_TIMEOUT_MS
                        )
                    }
                    SystemClock.elapsedRealtime() - started
                }.getOrNull()
                samples += elapsed
                if (index < TCP_PROBE_ATTEMPTS - 1) {
                    runCatching { Thread.sleep(TCP_PROBE_PAUSE_MS) }
                }
            }
            val stats = TcpProbeSummary.from(samples)
            val payload = JSONObject()
                .put("attempts", stats.attempts)
                .put("successful", stats.successful)
                .put("minMs", stats.minMs ?: JSONObject.NULL)
                .put("medianMs", stats.medianMs ?: JSONObject.NULL)
                .put("maxMs", stats.maxMs ?: JSONObject.NULL)
                .put("variationMs", stats.variationMs ?: JSONObject.NULL)
                .put("failurePercent", stats.failurePercent)
                .put("host", TCP_PROBE_HOST)
                .put("port", TCP_PROBE_PORT)
                .toString()
            runOnUiThread {
                if (isDestroyed || !::webView.isInitialized) return@runOnUiThread
                webView.evaluateJavascript(
                    "window.netPulseLatencyStats && window.netPulseLatencyStats($payload);",
                    null
                )
            }
        }
    }

    private fun runUdpRouteComparison() {
        if (!SingBoxVpnService.isRunning) {
            postWebMessage("برای مقایسه مسیر UDP ابتدا موتور sing-box را وصل کن.")
            return
        }
        if (!isGamingOnlyEnabled()) {
            postWebMessage("برای مقایسه منصفانه مسیرها، Game-only را فعال کن؛ ترافیک آزمایشی NetPulse نیز در این حالت از تونل عبور می‌کند.")
            return
        }

        val manager = getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val networks = manager.allNetworks.toList()
        val vpnNetwork = networks.firstOrNull { network ->
            val capabilities = manager.getNetworkCapabilities(network)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        val physicalNetwork = SingBoxVpnService.underlyingNetworkForDiagnostics
            ?.takeIf { network ->
                manager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true
            }
            ?: networks.firstOrNull { network ->
                val capabilities = manager.getNetworkCapabilities(network)
                capabilities != null &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }

        if (vpnNetwork == null || physicalNetwork == null) {
            postWebMessage("شبکه فیزیکی یا رابط VPN برای مقایسه UDP پیدا نشد. اتصال sing-box را بررسی کن.")
            return
        }

        postWebMessage("در حال مقایسه ۵ نمونه UDP مستقیم با ۵ نمونه از مسیر sing-box…")
        worker.execute {
            val outcome = runCatching {
                val serverAddress = physicalNetwork.getAllByName(UdpStunProbe.SERVER_HOST)
                    .firstOrNull { it is java.net.Inet4Address }
                    ?: physicalNetwork.getAllByName(UdpStunProbe.SERVER_HOST).firstOrNull()
                    ?: throw IllegalStateException("STUN endpoint could not be resolved on the physical network")

                val directStats = UdpStunProbe.measure(
                    physicalNetwork,
                    serverAddress,
                    attempts = UDP_PROBE_ATTEMPTS,
                    timeoutMs = UDP_PROBE_TIMEOUT_MS,
                    pauseMs = UDP_PROBE_PAUSE_MS
                )
                val vpnStats = UdpStunProbe.measure(
                    vpnNetwork,
                    serverAddress,
                    attempts = UDP_PROBE_ATTEMPTS,
                    timeoutMs = UDP_PROBE_TIMEOUT_MS,
                    pauseMs = UDP_PROBE_PAUSE_MS
                )
                JSONObject()
                    .put("ok", true)
                    .put("host", UdpStunProbe.SERVER_HOST)
                    .put("port", UdpStunProbe.SERVER_PORT)
                    .put("direct", udpStatsJson(directStats))
                    .put("vpn", udpStatsJson(vpnStats))
                    .toString()
            }
            val payload = outcome.getOrElse { error ->
                JSONObject()
                    .put("ok", false)
                    .put("message", error.message ?: error.javaClass.simpleName)
                    .toString()
            }
            runOnUiThread {
                if (isDestroyed || !::webView.isInitialized) return@runOnUiThread
                webView.evaluateJavascript(
                    "window.netPulseUdpComparison && window.netPulseUdpComparison($payload);",
                    null
                )
            }
        }
    }

    private fun udpStatsJson(stats: UdpStunStats): JSONObject =
        JSONObject()
            .put("attempts", stats.attempts)
            .put("successful", stats.successful)
            .put("minMs", stats.minMs ?: JSONObject.NULL)
            .put("medianMs", stats.medianMs ?: JSONObject.NULL)
            .put("maxMs", stats.maxMs ?: JSONObject.NULL)
            .put("variationMs", stats.variationMs ?: JSONObject.NULL)
            .put("failurePercent", stats.failurePercent)

    private data class LaunchableApp(
        val packageName: String,
        val label: String,
        val isGame: Boolean,
        val uid: Int
    )

    private val gamingPreferences by lazy {
        getSharedPreferences(GAMING_PREFS, MODE_PRIVATE)
    }

    private fun selectedGamingPackages(): Set<String> =
        gamingPreferences.getStringSet(GAMING_PACKAGES_KEY, emptySet())?.toSet() ?: emptySet()

    private fun isGamingOnlyEnabled(): Boolean =
        gamingPreferences.getBoolean(GAMING_ONLY_KEY, false)

    private fun isAutoGameDetectionEnabled(): Boolean =
        gamingPreferences.getBoolean(AUTO_GAME_DETECTION_KEY, false)

    private fun gameRouteAssignments(): Map<String, String> = runCatching {
        val stored = JSONObject(gamingPreferences.getString(GAME_ROUTE_ASSIGNMENTS_KEY, "{}") ?: "{}")
        stored.keys().asSequence().mapNotNull { key ->
            stored.optString(key).takeIf(String::isNotBlank)?.let { key to it }
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun saveGameRoutes(rawValue: String?) {
        try {
            val payload = JSONObject(rawValue ?: "{}")
            val submitted = payload.optJSONArray("packages") ?: JSONArray()
            val routes = payload.optJSONObject("routes") ?: JSONObject()
            require(submitted.length() <= MAX_GAMING_APPS) {
                "At most $MAX_GAMING_APPS games can be selected"
            }

            val availablePackages = launchableApps().map { it.packageName }.toSet()
            val selected = linkedSetOf<String>()
            for (index in 0 until submitted.length()) {
                val appPackage = submitted.optString(index).trim()
                require(appPackage in availablePackages) {
                    "A selected game is not installed or is no longer visible"
                }
                selected += appPackage
            }

            val availableOutbounds = (application as NetPulseApplication).loadedSingBoxProfile
                ?.let { profile ->
                    val root = JSONObject(profile)
                    val outbounds = root.optJSONArray("outbounds") ?: JSONArray()
                    (0 until outbounds.length()).mapNotNull { index ->
                        outbounds.optJSONObject(index)?.optString("tag")?.takeIf(String::isNotBlank)
                    }.toSet()
                }.orEmpty()

            val assignments = linkedMapOf<String, String>()
            val routeKeys = routes.keys()
            while (routeKeys.hasNext()) {
                val appPackage = routeKeys.next()
                val outboundTag = routes.optString(appPackage).trim()
                require(appPackage in selected) {
                    "A per-game route can only be assigned to a selected game"
                }
                require(outboundTag in availableOutbounds) {
                    "A selected per-game server is not present in the loaded sing-box profile"
                }
                assignments[appPackage] = outboundTag
            }

            val serializedRoutes = JSONObject()
            assignments.forEach { (appPackage, outboundTag) ->
                serializedRoutes.put(appPackage, outboundTag)
            }
            gamingPreferences.edit()
                .putStringSet(GAMING_PACKAGES_KEY, selected)
                .putString(GAME_ROUTE_ASSIGNMENTS_KEY, serializedRoutes.toString())
                .apply()
            if (selected.isEmpty() && isGamingOnlyEnabled()) {
                gamingPreferences.edit().putBoolean(GAMING_ONLY_KEY, false).apply()
            }
            publishNativeState()
            postWebMessage(
                if (assignments.isEmpty()) {
                    "${selected.size} برنامه ذخیره شد؛ مسیر پیش‌فرض sing-box برای همه اعمال می‌شود."
                } else {
                    "${selected.size} برنامه و ${assignments.size} مسیر اختصاصی بازی ذخیره شد. برای اعمال، sing-box را قطع و دوباره وصل کن."
                }
            )
        } catch (error: Exception) {
            postWebMessage("ذخیره تنظیمات بازی ناموفق بود: " + (error.message ?: error.javaClass.simpleName))
        }
    }

    private fun isUsageAccessGranted(): Boolean = runCatching {
        val appOps = getSystemService(AppOpsManager::class.java)
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    private fun requestGameDetection() {
        if (isUsageAccessGranted()) {
            gamingPreferences.edit().putBoolean(AUTO_GAME_DETECTION_KEY, true).apply()
            refreshHandler.removeCallbacks(gameDetectionTick)
            refreshHandler.post(gameDetectionTick)
            publishNativeState()
            postWebMessage("تشخیص خودکار بازی فعال شد؛ شناسایی برنامه فقط روی همین دستگاه انجام می‌شود.")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("اجازه تشخیص بازی")
            .setMessage(
                "برای تشخیص برنامه‌ای که در پیش‌زمینه باز است، Android دسترسی Usage Access می‌خواهد. " +
                    "NetPulse فقط نام بسته برنامه را روی دستگاه می‌خواند؛ محتوای برنامه‌ها جمع‌آوری یا ارسال نمی‌شود. " +
                    "این دسترسی اختیاری است."
            )
            .setNegativeButton("فعلاً نه", null)
            .setPositiveButton("رفتن به تنظیمات") { _, _ ->
                usageAccessSetupPending = true
                runCatching {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }.onFailure {
                    usageAccessSetupPending = false
                    postWebMessage("بازکردن تنظیمات Usage Access ناموفق بود.")
                }
            }
            .show()
    }

    private fun toggleAutoGameDetection() {
        if (isAutoGameDetectionEnabled()) {
            gamingPreferences.edit().putBoolean(AUTO_GAME_DETECTION_KEY, false).apply()
            activeGamePackage = null
            refreshHandler.removeCallbacks(gameDetectionTick)
            publishNativeState()
            postWebMessage("تشخیص خودکار بازی خاموش شد.")
        } else {
            requestGameDetection()
        }
    }

    private fun finishUsageAccessSetup() {
        if (!usageAccessSetupPending) return
        usageAccessSetupPending = false
        if (isUsageAccessGranted()) {
            gamingPreferences.edit().putBoolean(AUTO_GAME_DETECTION_KEY, true).apply()
            usageEventCursorMs = 0L
            refreshHandler.removeCallbacks(gameDetectionTick)
            refreshHandler.post(gameDetectionTick)
            postWebMessage("دسترسی تأیید شد؛ تشخیص خودکار بازی فعال است.")
        } else {
            gamingPreferences.edit().putBoolean(AUTO_GAME_DETECTION_KEY, false).apply()
            postWebMessage("دسترسی Usage Access داده نشد؛ تشخیص خودکار فعال نشد.")
        }
        publishNativeState()
    }

    private fun detectForegroundGame() {
        if (!isAutoGameDetectionEnabled() || !isUsageAccessGranted()) return
        val now = System.currentTimeMillis()
        if (usageEventCursorMs == 0L) usageEventCursorMs = (now - 30_000L).coerceAtLeast(0L)
        val start = (usageEventCursorMs - 2_000L).coerceAtLeast(0L)
        val usageManager = getSystemService(UsageStatsManager::class.java) ?: return
        val events = runCatching { usageManager.queryEvents(start, now) }.getOrNull() ?: return
        val event = UsageEvents.Event()
        var candidate = activeGamePackage
        var observedForegroundChange = false
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val eventPackage = event.packageName ?: continue
            val resumed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            } else {
                @Suppress("DEPRECATION")
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            }
            val paused = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                event.eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                    event.eventType == UsageEvents.Event.ACTIVITY_STOPPED
            } else {
                @Suppress("DEPRECATION")
                event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND
            }
            if (resumed) {
                candidate = eventPackage
                observedForegroundChange = true
            } else if (paused && candidate == eventPackage) {
                candidate = null
                observedForegroundChange = true
            }
        }
        usageEventCursorMs = now

        if (!observedForegroundChange) return
        val app = candidate?.let { pkg -> launchableApps().firstOrNull { it.packageName == pkg } }
        val detectedPackage = app?.takeIf { it.isGame || it.packageName in selectedGamingPackages() }?.packageName
        if (detectedPackage != activeGamePackage) {
            activeGamePackage = detectedPackage
            publishNativeState()
            detectedPackage?.let { pkg ->
                postWebMessage(
                    "بازی پیش‌زمینه شناسایی شد: " +
                        (app?.label ?: pkg) +
                        if (pkg in selectedGamingPackages()) " · در فهرست Game-only است." else " · هنوز در فهرست Game-only انتخاب نشده است."
                )
            }
        }
    }

    private fun launchableApps(): List<LaunchableApp> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { resolveInfo ->
                val appInfo = resolveInfo.activityInfo?.applicationInfo ?: return@mapNotNull null
                val appPackage = appInfo.packageName ?: return@mapNotNull null
                if (appPackage == packageName) return@mapNotNull null
                val label = runCatching {
                    packageManager.getApplicationLabel(appInfo).toString()
                }.getOrDefault(appPackage)
                LaunchableApp(
                    packageName = appPackage,
                    label = label,
                    isGame = appInfo.category == ApplicationInfo.CATEGORY_GAME,
                    uid = appInfo.uid
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(compareByDescending<LaunchableApp> { it.isGame }.thenBy { it.label.lowercase(Locale.ROOT) })
    }

    private fun gamingAppCatalogJson(): String {
        val selected = selectedGamingPackages()
        val apps = JSONArray()
        launchableApps().forEach { app ->
            val rx = TrafficStats.getUidRxBytes(app.uid)
            val tx = TrafficStats.getUidTxBytes(app.uid)
            apps.put(
                JSONObject()
                    .put("packageName", app.packageName)
                    .put("label", app.label)
                    .put("isGame", app.isGame)
                    .put("selected", app.packageName in selected)
                    .put("rxFormatted", formatBytes(rx))
                    .put("txFormatted", formatBytes(tx))
            )
        }
        return apps.toString()
    }

    private fun saveGamingApps(rawPackages: String?) {
        try {
            val submitted = JSONArray(rawPackages ?: "[]")
            require(submitted.length() <= MAX_GAMING_APPS) { "At most $MAX_GAMING_APPS games can be selected" }
            val available = launchableApps().map { it.packageName }.toSet()
            val selected = linkedSetOf<String>()
            for (index in 0 until submitted.length()) {
                val appPackage = submitted.optString(index).trim()
                require(appPackage in available) { "An app is not installed or is no longer visible" }
                selected += appPackage
            }
            if (selected.isEmpty() && isGamingOnlyEnabled()) {
                gamingPreferences.edit().putBoolean(GAMING_ONLY_KEY, false).apply()
            }
            gamingPreferences.edit().putStringSet(GAMING_PACKAGES_KEY, selected).apply()
            publishNativeState()
            postWebMessage(
                if (selected.isEmpty()) "فهرست بازی‌ها خالی شد؛ حالت Game-only خاموش است."
                else "${selected.size} برنامه برای مسیر بازی ذخیره شد. برای اعمال، sing-box را دوباره وصل کن."
            )
        } catch (error: Exception) {
            postWebMessage("ذخیره فهرست برنامه‌ها ناموفق بود: " + (error.message ?: error.javaClass.simpleName))
        }
    }

    private fun setGamingOnlyMode(rawEnabled: String?) {
        val enabled = rawEnabled.equals("true", ignoreCase = true)
        if (enabled && selectedGamingPackages().isEmpty()) {
            postWebMessage("ابتدا حداقل یک بازی را از فهرست برنامه‌ها انتخاب و ذخیره کن.")
            return
        }
        gamingPreferences.edit().putBoolean(GAMING_ONLY_KEY, enabled).apply()
        publishNativeState()
        postWebMessage(
            if (enabled) "حالت Game-only فعال شد؛ بازی‌های انتخاب‌شده و ترافیک تست NetPulse از تونل sing-box عبور می‌کنند."
            else "حالت Game-only خاموش شد؛ مسیر پیش‌فرض پروفایل sing-box اعمال می‌شود."
        )
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
        // The visible dashboard is a WebView. These legacy native TextViews are not
        // attached to the current layout, so telemetry calculation must never depend
        // on their initialization.
        val now = SystemClock.elapsedRealtime()
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val sample = trafficRateCalculator.sample(rx, tx, now)

        if (sample != null) {
            networkDownBytesPerSecond = sample.downloadBytesPerSecond
            networkUpBytesPerSecond = sample.uploadBytesPerSecond
            if (::downloadRate.isInitialized) {
                downloadRate.text = formatRate(networkDownBytesPerSecond)
            }
            if (::uploadRate.isInitialized) {
                uploadRate.text = formatRate(networkUpBytesPerSecond)
            }
            if (::totalTraffic.isInitialized) {
                totalTraffic.text = "↓ " + formatBytes(rx) + "     ↑ " + formatBytes(tx)
            }
        } else {
            networkDownBytesPerSecond = 0L
            networkUpBytesPerSecond = 0L
            trafficRateCalculator.reset()
            if (::downloadRate.isInitialized) downloadRate.text = "Unavailable"
            if (::uploadRate.isInitialized) uploadRate.text = "Unavailable"
            if (::totalTraffic.isInitialized) {
                totalTraffic.text = "Traffic counters are not exposed on this device."
            }
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

        if (::networkBadge.isInitialized) {
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
            networkBadge.setTextColor(
                if (validated) Color.rgb(112, 242, 198) else Color.rgb(255, 198, 116)
            )
        }
        if (::networkSummary.isInitialized) {
            networkSummary.text = when {
                validated -> transport + " · internet access verified by Android"
                hasInternet -> transport + " · internet access is not yet verified"
                else -> "No validated internet connection detected."
            }
        }
        publishNativeState()
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
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/json",
                    "text/plain",
                    "application/octet-stream",
                    "application/x-openvpn-profile",
                    "application/openvpn-profile"
                )
            )
        }
        startActivityForResult(intent, REQUEST_SINGBOX_PROFILE)
    }

    private fun updateSingBoxStatus(message: String) {
        singBoxStatus.text = message
        publishNativeState()
    }

    private fun serverCatalogJson(): String {
        val profile = (application as NetPulseApplication).loadedSingBoxProfile ?: return "[]"
        return runCatching {
            JSONArray().apply {
                SingBoxServerCatalog.list(profile).forEach { entry ->
                    put(
                        JSONObject()
                            .put("tag", entry.tag)
                            .put("type", entry.type)
                            .put("server", entry.server ?: JSONObject.NULL)
                            .put("port", entry.port ?: JSONObject.NULL)
                            .put("selected", entry.selected)
                            .put("group", entry.group)
                            .put("memberCount", entry.memberCount)
                    )
                }
            }.toString()
        }.getOrDefault("[]")
    }

    private fun selectSingBoxServer(rawTag: String?) {
        val tag = rawTag?.trim().orEmpty()
        if (tag.isBlank()) {
            postWebMessage("شناسه سرور معتبر نیست.")
            return
        }
        if (SingBoxVpnService.isRunning || singBoxStarting) {
            postWebMessage("برای تغییر سرور، ابتدا اتصال sing-box را قطع کن.")
            return
        }

        worker.execute {
            try {
                val app = application as NetPulseApplication
                val current = app.loadedSingBoxProfile
                    ?: throw IllegalStateException("ابتدا یک پروفایل sing-box وارد کن.")
                val updated = SingBoxServerCatalog.select(current, tag)
                Libbox.checkConfig(updated)
                val selected = SingBoxServerCatalog.list(updated).firstOrNull { it.tag == tag }
                    ?: throw IllegalStateException("سرور انتخاب‌شده پیدا نشد.")

                // Keep the source in process memory and change only the profile's default route.
                app.loadedSingBoxProfile = updated
                app.loadedSingBoxProtocol = if (selected.group) {
                    selected.type.uppercase(Locale.ROOT) + " GROUP"
                } else {
                    selected.type.uppercase(Locale.ROOT)
                }
                app.loadedSingBoxDisplayName = selected.tag
                val persisted = app.persistProfiles()

                runOnUiThread {
                    if (!isDestroyed) {
                        publishNativeState()
                        val saveStatus = if (persisted) {
                            " ذخیره رمزگذاری‌شده شد."
                        } else {
                            " ذخیره امن انجام نشد."
                        }
                        postWebMessage(
                            "مسیر پیش‌فرض روی «" + selected.tag + "» قرار گرفت." +
                                saveStatus + " برای اعمال تغییر، sing-box را وصل کن."
                        )
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isDestroyed) {
                        postWebMessage("تغییر سرور انجام نشد: " + (error.message ?: error.javaClass.simpleName))
                    }
                }
            }
        }
    }

    private fun isIkev2Supported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_IPSEC_TUNNELS)

    private fun vpnManager(): VpnManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getSystemService(VpnManager::class.java)
        } else {
            null
        }

    private fun showIkev2ProfileDialog() {
        if (!isIkev2Supported()) {
            postWebMessage("IKEv2 بومی به Android 11 یا جدیدتر و پشتیبانی IPsec دستگاه نیاز دارد.")
            return
        }

        val saved = (application as NetPulseApplication).loadedIkev2Config
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        fun input(hintText: String, value: String = "", inputType: Int = InputType.TYPE_CLASS_TEXT): EditText =
            EditText(this).apply {
                hint = hintText
                setText(value)
                this.inputType = inputType
                maxLines = if (hintText.contains("PEM")) 4 else 1
            }

        val server = input("آدرس سرور (مثلاً vpn.example.net)", saved?.serverAddress.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val identity = input("IKE identity / remote identity", saved?.identity.orEmpty())
        val authGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val eap = RadioButton(this).apply { text = "نام کاربری و رمز عبور (EAP-MSCHAPv2)"; id = 1001 }
        val psk = RadioButton(this).apply { text = "کلید مشترک (Pre-Shared Key)"; id = 1002 }
        authGroup.addView(eap)
        authGroup.addView(psk)
        authGroup.check(if (saved?.authMethod == Ikev2AuthMethod.PRE_SHARED_KEY) psk.id else eap.id)

        val username = input("نام کاربری", saved?.username.orEmpty())
        val password = input("رمز عبور", saved?.password.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val sharedKey = input("Pre-Shared Key", saved?.preSharedKey.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val ca = input("گواهی Root CA به فرمت PEM (اختیاری)", saved?.serverRootCaPem.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        val caLabel = TextView(this).apply {
            text = "گواهی CA اختیاری است؛ برای سرورهای دارای CA خصوصی، گواهی ریشه را وارد کن."
            textSize = 12f
            setTextColor(muted)
        }
        form.addView(server)
        form.addView(identity)
        form.addView(authGroup)
        form.addView(username)
        form.addView(password)
        form.addView(sharedKey)
        form.addView(caLabel)
        form.addView(ca)

        fun updateAuthFields() {
            val usePsk = authGroup.checkedRadioButtonId == psk.id
            username.visibility = if (usePsk) android.view.View.GONE else android.view.View.VISIBLE
            password.visibility = if (usePsk) android.view.View.GONE else android.view.View.VISIBLE
            sharedKey.visibility = if (usePsk) android.view.View.VISIBLE else android.view.View.GONE
        }
        authGroup.setOnCheckedChangeListener { _, _ -> updateAuthFields() }
        updateAuthFields()

        val scroll = ScrollView(this).apply {
            addView(form)
            isFillViewport = true
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("پیکربندی IKEv2 / IPsec")
            .setView(scroll)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ذخیره و اتصال", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val authMethod = if (authGroup.checkedRadioButtonId == psk.id) {
                        Ikev2AuthMethod.PRE_SHARED_KEY
                    } else {
                        Ikev2AuthMethod.EAP_MSCHAPV2
                    }
                    val config = Ikev2ProfileConfig(
                        serverAddress = server.text.toString().trim(),
                        identity = identity.text.toString().trim(),
                        authMethod = authMethod,
                        username = if (authMethod == Ikev2AuthMethod.EAP_MSCHAPV2) username.text.toString() else "",
                        password = if (authMethod == Ikev2AuthMethod.EAP_MSCHAPV2) password.text.toString() else "",
                        preSharedKey = if (authMethod == Ikev2AuthMethod.PRE_SHARED_KEY) sharedKey.text.toString() else "",
                        serverRootCaPem = ca.text.toString().trim()
                    )
                    config.validate()
                    (application as NetPulseApplication).loadedIkev2Config = config
                    dialog.dismiss()
                    persistAndProvisionIkev2(config)
                } catch (error: Exception) {
                    Toast.makeText(this, error.message ?: "IKEv2 profile is invalid", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun persistAndProvisionIkev2(config: Ikev2ProfileConfig) {
        if (!isIkev2Supported()) {
            postWebMessage("این دستگاه قابلیت IKEv2 بومی را گزارش نمی‌کند.")
            return
        }
        if (ikev2Connected || ikev2Starting) {
            postWebMessage("برای تغییر تنظیمات IKEv2 ابتدا اتصال فعلی را قطع کن.")
            return
        }

        postWebMessage("در حال اعتبارسنجی و ذخیره امن پروفایل IKEv2…")
        worker.execute {
            val result = runCatching {
                config.validate()
                val platformProfile = config.toPlatformProfile()
                val app = application as NetPulseApplication
                val persisted = app.persistProfiles()
                platformProfile to persisted
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val (platformProfile, persisted) = result.getOrElse { error ->
                    postWebMessage("پروفایل IKEv2 آماده نشد: " + (error.message ?: error.javaClass.simpleName))
                    return@runOnUiThread
                }
                try {
                    val manager = vpnManager() ?: throw IllegalStateException("VpnManager is unavailable")
                    val consent = manager.provisionVpnProfile(platformProfile)
                    if (!persisted) {
                        postWebMessage("پروفایل اعتبارسنجی شد، اما ذخیره رمزگذاری‌شده ناموفق بود.")
                    }
                    if (consent != null) {
                        ikev2ConsentPending = true
                        startActivityForResult(consent, REQUEST_IKEV2_PERMISSION)
                    } else {
                        startProvisionedIkev2()
                    }
                } catch (error: Exception) {
                    postWebMessage("ثبت پروفایل در Android ناموفق بود: " + error.javaClass.simpleName)
                }
            }
        }
    }

    private fun toggleIkev2() {
        if (ikev2Connected || ikev2Starting || isIkev2StatusUnverified()) {
            // stopProvisionedVpnProfile only addresses the IKEv2 profile provisioned
            // by this application; it does not stop another VPN app's service.
            stopIkev2()
            return
        }
        if (isIkev2PlatformActive()) {
            postWebMessage("یک تونل VPN دیگر در Android فعال است؛ ابتدا همان تونل را از تنظیمات سیستم قطع کن.")
            return
        }
        val config = (application as NetPulseApplication).loadedIkev2Config
        if (config == null) showIkev2ProfileDialog() else persistAndProvisionIkev2(config)
    }

    private fun startProvisionedIkev2() {
        val manager = vpnManager()
        if (manager == null || !isIkev2Supported()) {
            postWebMessage("IKEv2 به Android 11 یا جدیدتر و قابلیت IPsec دستگاه نیاز دارد.")
            return
        }
        if (isIkev2PlatformActive()) {
            postWebMessage("یک تونل VPN دیگر در Android فعال است؛ ابتدا همان تونل را قطع کن.")
            return
        }
        if (connected || SingBoxVpnService.isRunning || singBoxStarting ||
            VpnEngineCoordinator.currentOwner() == VpnEngineCoordinator.Engine.WIREGUARD ||
            VpnEngineCoordinator.currentOwner() == VpnEngineCoordinator.Engine.SING_BOX
        ) {
            postWebMessage("ابتدا WireGuard یا sing-box را قطع کن؛ اندروید هم‌زمان فقط یک VPN فعال دارد.")
            return
        }
        if (!VpnEngineCoordinator.acquire(VpnEngineCoordinator.Engine.IKEV2)) {
            postWebMessage("یک موتور VPN دیگر در حال استفاده از تنها اسلات VPN اندروید است. ابتدا آن را قطع کن.")
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.startProvisionedVpnProfileSession()
            } else {
                @Suppress("DEPRECATION")
                manager.startProvisionedVpnProfile()
            }
            ikev2Starting = true
            ikev2Connected = false
            ikev2PollCount = 0
            postWebMessage("درخواست اتصال IKEv2 به Android ارسال شد؛ در حال بررسی وضعیت تونل…")
            refreshHandler.removeCallbacks(ikev2StateTick)
            refreshHandler.postDelayed(ikev2StateTick, 800L)
            publishNativeState()
        } catch (error: Exception) {
            VpnEngineCoordinator.release(VpnEngineCoordinator.Engine.IKEV2)
            ikev2Starting = false
            ikev2Connected = false
            postWebMessage("شروع تونل IKEv2 ناموفق بود: " + error.javaClass.simpleName)
            publishNativeState()
        }
    }

    private fun stopIkev2() {
        refreshHandler.removeCallbacks(ikev2StateTick)
        try {
            vpnManager()?.stopProvisionedVpnProfile()
            ikev2Connected = false
            ikev2Starting = false
            VpnEngineCoordinator.release(VpnEngineCoordinator.Engine.IKEV2)
            postWebMessage("درخواست قطع تونل IKEv2 ارسال شد.")
        } catch (error: Exception) {
            postWebMessage("قطع تونل IKEv2 ناموفق بود: " + error.javaClass.simpleName)
        }
        publishNativeState()
    }

    private fun refreshIkev2State() {
        if (!isIkev2Supported()) {
            if (ikev2Connected || ikev2Starting) {
                ikev2Connected = false
                ikev2Starting = false
                VpnEngineCoordinator.release(VpnEngineCoordinator.Engine.IKEV2)
            }
            publishNativeState()
            return
        }

        val state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { vpnManager()?.provisionedVpnProfileState?.state }.getOrNull()
        } else {
            null
        }

        if (state != null) {
            when (state) {
                VpnProfileState.STATE_CONNECTED -> {
                    ikev2Connected = true
                    ikev2Starting = false
                    VpnEngineCoordinator.acquire(VpnEngineCoordinator.Engine.IKEV2)
                }
                VpnProfileState.STATE_FAILED -> {
                    ikev2Connected = false
                    ikev2Starting = false
                    VpnEngineCoordinator.release(VpnEngineCoordinator.Engine.IKEV2)
                    postWebMessage("تونل IKEv2 با خطای پلتفرم Android متوقف شد.")
                }
                VpnProfileState.STATE_DISCONNECTED -> {
                    if (ikev2Connected) {
                        ikev2Connected = false
                        ikev2Starting = false
                        VpnEngineCoordinator.release(VpnEngineCoordinator.Engine.IKEV2)
                        postWebMessage("اتصال IKEv2 قطع شد.")
                    }
                }
            }
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val vpnActive = hasAnyActiveVpnNetwork()
            if (vpnActive && ikev2Starting) {
                // The Android 11/12 API can see a VPN network but cannot reliably
                // identify its owning provisioned profile. Do not mislabel it as IKEv2.
                ikev2Starting = false
                ikev2Connected = false
                postWebMessage("Android 11/12 یک VPN فعال را گزارش می‌کند، اما نوع/مالک آن را مشخص نمی‌کند. وضعیت در داشبورد نامشخص نمایش داده می‌شود.")
            } else if (!vpnActive && ikev2Connected) {
                ikev2Connected = false
                ikev2Starting = false
                VpnEngineCoordinator.release(VpnEngineCoordinator.Engine.IKEV2)
            }
        }

        if (ikev2Starting) {
            ikev2PollCount++
            if (ikev2PollCount >= IKEV2_CONNECT_TIMEOUT_POLLS) {
                ikev2Starting = false
                ikev2Connected = false
                VpnEngineCoordinator.release(VpnEngineCoordinator.Engine.IKEV2)
                postWebMessage("وضعیت اتصال IKEv2 پس از ۳۰ ثانیه تأیید نشد؛ تنظیمات سرور و شبکه را بررسی کن.")
            } else {
                refreshHandler.removeCallbacks(ikev2StateTick)
                refreshHandler.postDelayed(ikev2StateTick, 1000L)
            }
        } else if (ikev2Connected) {
            refreshHandler.removeCallbacks(ikev2StateTick)
            refreshHandler.postDelayed(ikev2StateTick, 2500L)
        }
        publishNativeState()
    }

    private fun importOpenVpnText(
        rawProfile: String,
        credentials: OpenVpnCredentials? = null
    ) {
        postWebMessage("در حال خواندن و اعتبارسنجی پروفایل OpenVPN…")
        worker.execute {
            val result = runCatching {
                val imported = OpenVpnProfileImporter.importProfile(rawProfile, credentials)
                Libbox.checkConfig(imported.json)
                imported
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val imported = result.getOrElse { error ->
                    if (error is OpenVpnCredentialsRequiredException) {
                        showOpenVpnCredentialsDialog(rawProfile)
                    } else {
                        postWebMessage("پروفایل OpenVPN پذیرفته نشد: " + (error.message ?: error.javaClass.simpleName))
                    }
                    return@runOnUiThread
                }
                val app = application as NetPulseApplication
                app.loadedSingBoxProfile = imported.json
                app.loadedSingBoxProtocol = imported.protocol
                app.loadedSingBoxDisplayName = imported.displayName
                val persisted = app.persistProfiles()
                updateSingBoxStatus(imported.protocol + " · profile validated")
                postWebMessage(
                    imported.protocol + " آماده اتصال شد." +
                        if (persisted) " پروفایل با رمزگذاری ذخیره شد." else " ذخیره امن انجام نشد."
                )
            }
        }
    }

    private fun showOpenVpnCredentialsDialog(rawProfile: String) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        val user = EditText(this).apply { hint = "OpenVPN username" }
        val pass = EditText(this).apply {
            hint = "OpenVPN password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        form.addView(user)
        form.addView(pass)
        val dialog = AlertDialog.Builder(this)
            .setTitle("ورود اطلاعات OpenVPN")
            .setMessage("این پروفایل از احراز هویت نام کاربری و رمز عبور استفاده می‌کند.")
            .setView(form)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ادامه", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val credentials = OpenVpnCredentials(user.text.toString(), pass.text.toString())
                try {
                    credentials.validate()
                    dialog.dismiss()
                    importOpenVpnText(rawProfile, credentials)
                } catch (error: Exception) {
                    Toast.makeText(this, error.message ?: "OpenVPN credentials are invalid", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun looksLikeOpenVpnProfile(value: String): Boolean {
        val trimmed = value.trimStart()
        return trimmed.startsWith("<ca>", ignoreCase = true) ||
            Regex("(?im)^\\s*(?:client|remote|dev\\s+tun|proto\\s+(?:udp|tcp))\\b").containsMatchIn(value)
    }

    private fun hasAnyActiveVpnNetwork(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val networks = manager.allNetworks
        return networks.any { network ->
            manager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    private fun isIkev2StatusUnverified(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            (application as NetPulseApplication).loadedIkev2Config != null &&
            hasAnyActiveVpnNetwork() &&
            !ikev2Connected &&
            !ikev2Starting

    private fun isIkev2PlatformActive(): Boolean {
        if (!isIkev2Supported()) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val state = runCatching { vpnManager()?.provisionedVpnProfileState?.state }.getOrNull()
            state == VpnProfileState.STATE_CONNECTED || state == VpnProfileState.STATE_CONNECTING
        } else {
            // Android 11/12 do not expose VpnProfileState. If a locally provisioned
            // IKEv2 profile exists while any VPN network is active, block a new engine
            // start and report the owner as unknown rather than guessing "connected".
            (application as NetPulseApplication).loadedIkev2Config != null &&
                hasAnyActiveVpnNetwork()
        }
    }

    private fun confirmClearSavedProfiles() {
        if (SingBoxVpnService.isRunning || singBoxStarting || ikev2Connected || ikev2Starting || isIkev2PlatformActive()) {
            postWebMessage("برای پاک‌کردن پروفایل‌ها ابتدا همه تونل‌های VPN را قطع کن.")
            return
        }
        postWebMessage("در حال بررسی وضعیت WireGuard…")
        worker.execute {
            val wireGuardIsUp = runCatching {
                backend.getState(wireGuardTunnel) == Tunnel.State.UP
            }.getOrDefault(connected)

            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (wireGuardIsUp || connected || SingBoxVpnService.isRunning || singBoxStarting ||
                    ikev2Connected || ikev2Starting || isIkev2PlatformActive()
                ) {
                    postWebMessage("برای پاک‌کردن پروفایل‌ها ابتدا همه تونل‌های VPN را قطع کن.")
                    return@runOnUiThread
                }
                showClearProfilesConfirmation()
            }
        }
    }

    private fun showClearProfilesConfirmation() {
        android.app.AlertDialog.Builder(this)
            .setTitle("پاک‌کردن پروفایل‌های ذخیره‌شده")
            .setMessage("پروفایل‌های WireGuard، sing-box و IKEv2، تنظیمات VPN اندروید، کلید رمزگذاری آن‌ها و فهرست بازی‌ها از این دستگاه پاک می‌شوند. این کار قابل بازگشت نیست.")
            .setNegativeButton("انصراف", null)
            .setPositiveButton("پاک‌کردن") { _, _ ->
                val app = application as NetPulseApplication
                val platformProfileRemoved = if (app.loadedIkev2Config != null && isIkev2Supported()) {
                    runCatching { vpnManager()?.deleteProvisionedVpnProfile() }.isSuccess
                } else {
                    true
                }
                if (!platformProfileRemoved) {
                    postWebMessage("پروفایل IKEv2 از تنظیمات سیستم حذف نشد؛ اطلاعات ذخیره‌شده دست‌نخورده باقی ماند.")
                    return@setPositiveButton
                }
                if (app.clearStoredProfiles()) {
                    loadedConfig = null
                    connected = false
                    singBoxConnected = false
                    singBoxStarting = false
                    ikev2Connected = false
                    ikev2Starting = false
                    ikev2ConsentPending = false
                    refreshHandler.removeCallbacks(ikev2StateTick)
                    gamingPreferences.edit().clear().apply()
                    VpnEngineCoordinator.release(VpnEngineCoordinator.Engine.WIREGUARD)
                    VpnEngineCoordinator.release(VpnEngineCoordinator.Engine.SING_BOX)
                    VpnEngineCoordinator.release(VpnEngineCoordinator.Engine.IKEV2)
                    status.text = "No profile loaded"
                    connectButton.isEnabled = false
                    singBoxStatus.text = "No sing-box profile loaded"
                    singBoxButton.isEnabled = false
                    publishNativeState()
                    postWebMessage("پروفایل‌های VPN، اطلاعات رمزگذاری و کلید IKEv2 حذف شدند.")
                } else {
                    postWebMessage("پاک‌کردن اطلاعات ذخیره‌شده ناموفق بود.")
                }
            }
            .show()
    }

    private fun importSingBoxText(rawText: String?) {
        val input = rawText?.trim().orEmpty()
        if (input.isBlank()) {
            postWebMessage("لینک یا پیکربندی خالی است.")
            return
        }
        if (input.toByteArray(Charsets.UTF_8).size > MAX_PROFILE_BYTES) {
            postWebMessage("حجم پروفایل بیشتر از ۲ مگابایت است.")
            return
        }
        if (looksLikeOpenVpnProfile(input)) {
            importOpenVpnText(input)
            return
        }
        postWebMessage("در حال شناسایی و اعتبارسنجی پروفایل…")
        worker.execute {
            try {
                val imported = SingBoxProfileImporter.importProfile(input)
                Libbox.checkConfig(imported.json)
                val app = application as NetPulseApplication
                app.loadedSingBoxProfile = imported.json
                app.loadedSingBoxProtocol = imported.protocol
                app.loadedSingBoxDisplayName = imported.displayName
                val persisted = app.persistProfiles()
                runOnUiThread {
                    if (!isDestroyed) {
                        updateSingBoxStatus(imported.protocol + " · profile validated")
                        singBoxButton.isEnabled = true
                        postWebMessage(
                            imported.protocol + " profile imported and validated." +
                                if (persisted) " به‌صورت رمزگذاری‌شده ذخیره شد." else " ذخیره امن انجام نشد؛ پروفایل فقط در حافظه باقی می‌ماند."
                        )
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isDestroyed) {
                        updateSingBoxStatus("Profile rejected · " + error.javaClass.simpleName)
                        postWebMessage("پروفایل معتبر نیست: " + error.javaClass.simpleName)
                    }
                }
            }
        }
    }

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
        if (profile.isNullOrBlank() || singBoxStarting) return

        // Profile transformation and libbox validation can be expensive for large
        // JSON configs. Keep both off the UI thread so the dashboard remains responsive.
        singBoxStarting = true
        singBoxButton.isEnabled = false
        singBoxButton.text = "Validating…"
        singBoxStatus.text = "Preparing sing-box profile…"

        worker.execute {
            val prepared = runCatching {
                val profileForStart = if (isGamingOnlyEnabled()) {
                    // Android VpnService.Builder rejects packages that were removed since
                    // the user last saved the allowlist. Prune stale selections before TUN setup.
                    val requested = selectedGamingPackages()
                    val installed = launchableApps().map { it.packageName }.toSet()
                    val validSelection = requested.intersect(installed)
                    if (validSelection != requested) {
                        gamingPreferences.edit().putStringSet(GAMING_PACKAGES_KEY, validSelection).apply()
                        publishNativeState()
                        postWebMessage("بعضی برنامه‌های حذف‌شده از فهرست بازی پاک شدند.")
                    }
                    require(validSelection.isNotEmpty()) {
                        "No selected game is currently installed. Choose an installed game first."
                    }
                    // Include NetPulse itself only for its diagnostic sockets, so the
                    // VPN-bound UDP probe can actually traverse this per-app allowlist.
                    GamingProfileConfigurator.withGameOnlyRouting(profile, validSelection + packageName)
                } else {
                    profile
                }
                Libbox.checkConfig(profileForStart)
                profileForStart
            }

            runOnUiThread {
                if (isDestroyed) return@runOnUiThread

                val profileForStart = prepared.getOrElse { error ->
                    singBoxStarting = false
                    singBoxButton.isEnabled = true
                    singBoxButton.text = "Connect sing-box"
                    singBoxStatus.text = "Profile validation failed"
                    postWebMessage(
                        "پروفایل برای اتصال آماده نشد: " +
                            (error.message ?: error.javaClass.simpleName)
                    )
                    return@runOnUiThread
                }

                if (ikev2Connected || ikev2Starting || isIkev2PlatformActive()) {
                    singBoxStarting = false
                    singBoxButton.isEnabled = true
                    singBoxButton.text = "Connect sing-box"
                    postWebMessage("ابتدا تونل IKEv2 را قطع کن؛ اندروید هم‌زمان فقط یک VPN فعال دارد.")
                    return@runOnUiThread
                }
                if (!VpnEngineCoordinator.acquire(VpnEngineCoordinator.Engine.SING_BOX)) {
                    singBoxStarting = false
                    singBoxButton.isEnabled = true
                    singBoxButton.text = "Connect sing-box"
                    singBoxStatus.text = "Another VPN engine is active. Disconnect it first."
                    Toast.makeText(this, "Another VPN engine is active", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }

                val app = application as NetPulseApplication
                app.pendingSingBoxServiceProfile = profileForStart
                val intent = Intent(this, SingBoxVpnService::class.java)
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    singBoxButton.text = "Starting…"
                    singBoxStatus.text = "Starting sing-box…"
                } catch (error: Exception) {
                    app.pendingSingBoxServiceProfile = null
                    VpnEngineCoordinator.release(VpnEngineCoordinator.Engine.SING_BOX)
                    singBoxConnected = false
                    singBoxStarting = false
                    singBoxButton.isEnabled = true
                    singBoxButton.text = "Connect sing-box"
                    singBoxStatus.text = "sing-box failed to start"
                    postWebMessage("شروع سرویس VPN ناموفق بود: " + error.javaClass.simpleName)
                }
            }
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
        if (requestCode == REQUEST_IKEV2_PERMISSION) {
            ikev2ConsentPending = false
            if (resultCode == RESULT_OK) {
                startProvisionedIkev2()
            } else {
                ikev2Starting = false
                ikev2Connected = false
                VpnEngineCoordinator.release(VpnEngineCoordinator.Engine.IKEV2)
                postWebMessage("مجوز اتصال IKEv2 از سوی کاربر تأیید نشد.")
                publishNativeState()
            }
            return
        }
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
                    if (content.isBlank()) throw IllegalArgumentException("Profile is empty")
                    if (looksLikeOpenVpnProfile(content)) {
                        runOnUiThread { if (!isDestroyed) importOpenVpnText(content) }
                        return@execute
                    }
                    val imported = SingBoxProfileImporter.importProfile(content)
                    Libbox.checkConfig(imported.json)
                    val app = application as NetPulseApplication
                    app.loadedSingBoxProfile = imported.json
                    app.loadedSingBoxProtocol = imported.protocol
                    app.loadedSingBoxDisplayName = imported.displayName
                    val persisted = app.persistProfiles()
                    runOnUiThread {
                        updateSingBoxStatus(imported.protocol + " · profile validated")
                        if (!persisted) postWebMessage("پروفایل وارد شد، اما ذخیره امن آن انجام نشد.")
                        singBoxButton.isEnabled = true
                        Toast.makeText(this, imported.protocol + " profile imported", Toast.LENGTH_SHORT).show()
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        updateSingBoxStatus("Profile rejected · " + error.javaClass.simpleName)
                        Toast.makeText(this, "Profile rejected. Check the link or file format.", Toast.LENGTH_LONG).show()
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
                val profileText = ProfileTextDecoder.decodeUtf8(bytes)
                val app = application as NetPulseApplication
                app.loadedWireGuardConfig = parsed
                app.loadedWireGuardText = profileText
                val persisted = app.persistProfiles()
                runOnUiThread {
                    (application as NetPulseApplication).loadedWireGuardConfig = parsed
                    loadedConfig = parsed
                    status.text = "Profile validated · ${parsed.peers.size} peer(s)"
                    connectButton.isEnabled = true
                    Toast.makeText(
                        this,
                        if (persisted) "WireGuard profile imported and encrypted" else "Profile imported; secure save failed",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    // Keep the last known-good profile if a newly selected file is invalid.
                    loadedConfig = (application as NetPulseApplication).loadedWireGuardConfig
                    connectButton.isEnabled = loadedConfig != null
                    status.text = if (loadedConfig != null) {
                        "New profile rejected · previous profile is still ready"
                    } else {
                        "Profile could not be parsed"
                    }
                    Toast.makeText(this, "WireGuard profile rejected. The previous valid profile was kept.", Toast.LENGTH_LONG).show()
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
        if (ikev2Connected || ikev2Starting || isIkev2PlatformActive()) {
            postWebMessage("ابتدا تونل IKEv2 را قطع کن؛ اندروید هم‌زمان فقط یک VPN فعال دارد.")
            return
        }
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
        if (liveRefreshEnabled) refreshHandler.post(networkTick)
        refreshIkev2State()
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
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("NetPulse")
            webView.loadUrl("about:blank")
            webView.destroy()
        }
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
        private const val REQUEST_IKEV2_PERMISSION = 714
        private const val IKEV2_CONNECT_TIMEOUT_POLLS = 30
        private const val MAX_PROFILE_BYTES = 2 * 1024 * 1024
        private const val REFRESH_PREFS = "netpulse_refresh_rate"
        private const val GAMING_PREFS = "netpulse_gaming"
        private const val GAMING_PACKAGES_KEY = "selected_packages"
        private const val GAMING_ONLY_KEY = "game_only_enabled"
        private const val MAX_GAMING_APPS = 50
        private const val TCP_PROBE_HOST = "1.1.1.1"
        private const val TCP_PROBE_PORT = 443
        private const val TCP_PROBE_ATTEMPTS = 6
        private const val TCP_PROBE_TIMEOUT_MS = 1500
        private const val TCP_PROBE_PAUSE_MS = 120L
        private const val UDP_PROBE_ATTEMPTS = 5
        private const val UDP_PROBE_TIMEOUT_MS = 1200
        private const val UDP_PROBE_PAUSE_MS = 80L
    }
}
