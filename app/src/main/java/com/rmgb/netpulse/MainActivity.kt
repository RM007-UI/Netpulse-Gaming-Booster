package com.rmgb.netpulse

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.Color
import android.widget.Toast
import com.rmgb.netpulse.protocol.ProtocolCatalog
import com.rmgb.netpulse.protocol.ProtocolFamily
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
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
    private var loadedConfig: Config? = null
    private var pendingConfig: Config? = null
    private var connected = false

    private val wireGuardTunnel = object : Tunnel {
        override fun getName(): String = "NetPulse-WireGuard"
        override fun onStateChange(newState: Tunnel.State) {
            runOnUiThread {
                connected = newState == Tunnel.State.UP
                status.text = if (connected) "WireGuard connected" else "WireGuard disconnected"
                connectButton.text = if (connected) "Disconnect" else "Connect"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backend = GoBackend(applicationContext)

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
        body.addView(label("WireGuard", 21f, Color.WHITE, true).apply { setPadding(0, 28, 0, 8) })
        body.addView(label("Import a WireGuard .conf file supplied by your VPN provider. The profile stays in memory and is not written to app storage.", 13f, muted))
        body.addView(Button(this).apply {
            text = "Import WireGuard profile (.conf)"
            setOnClickListener { openProfilePicker() }
        })
        status = label("No profile loaded", 13f, muted).apply { setPadding(0, 12, 0, 12) }
        body.addView(status)
        connectButton = Button(this).apply {
            text = "Connect"
            isEnabled = false
            setOnClickListener { toggleWireGuard() }
        }
        body.addView(connectButton)

        body.addView(label("Protocol catalog", 20f, Color.WHITE, true).apply { setPadding(0, 28, 0, 8) })
        body.addView(label("Only integrated engines are marked as available. Other entries remain inactive until their engines are implemented and tested.", 13f, muted))
        addFamily(body, "VPN TUNNELS", ProtocolFamily.VPN_TUNNEL)
        addFamily(body, "PROXY PROTOCOLS", ProtocolFamily.PROXY)
        addFamily(body, "TRANSPORTS", ProtocolFamily.TRANSPORT)
        addFamily(body, "DNS", ProtocolFamily.DNS)
        setContentView(root)
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
        if (requestCode != REQUEST_PROFILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        worker.execute {
            try {
                val parsed = contentResolver.openInputStream(uri)?.use { Config.parse(it) }
                    ?: throw IllegalArgumentException("Unable to open the selected profile")
                runOnUiThread {
                    loadedConfig = parsed
                    status.text = "Profile validated · ${parsed.peers.size} peer(s)"
                    connectButton.isEnabled = true
                    Toast.makeText(this, "WireGuard profile imported", Toast.LENGTH_SHORT).show()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    loadedConfig = null
                    connectButton.isEnabled = false
                    status.text = "Profile could not be parsed"
                    Toast.makeText(this, "Invalid WireGuard profile: ${error.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun toggleWireGuard() {
        if (connected) {
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
            pendingConfig = config
            startActivityForResult(permissionIntent, REQUEST_VPN_PERMISSION)
        } else {
            connect(config)
        }
    }

    @Deprecated("Handled for compatibility with the current minimal Activity scaffold")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN_PERMISSION) {
            val config = pendingConfig
            pendingConfig = null
            if (resultCode == RESULT_OK && config != null) connect(config)
            return
        }
        if (requestCode != REQUEST_PROFILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        worker.execute {
            try {
                val parsed = contentResolver.openInputStream(uri)?.use { Config.parse(it) }
                    ?: throw IllegalArgumentException("Unable to open the selected profile")
                runOnUiThread {
                    loadedConfig = parsed
                    status.text = "Profile validated · ${parsed.peers.size} peer(s)"
                    connectButton.isEnabled = true
                    Toast.makeText(this, "WireGuard profile imported", Toast.LENGTH_SHORT).show()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    loadedConfig = null
                    connectButton.isEnabled = false
                    status.text = "Profile could not be parsed"
                    Toast.makeText(this, "Invalid WireGuard profile: ${error.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun connect(config: Config) {
        connectButton.isEnabled = false
        status.text = "Starting WireGuard…"
        worker.execute {
            try {
                backend.setState(wireGuardTunnel, Tunnel.State.UP, config)
                runOnUiThread { connectButton.isEnabled = true }
            } catch (error: Exception) {
                showError("Connection failed", error)
            }
        }
    }

    private fun showError(prefix: String, error: Exception) {
        runOnUiThread {
            status.text = "$prefix · ${error.javaClass.simpleName}"
            connectButton.isEnabled = loadedConfig != null
            Toast.makeText(this, "$prefix: ${error.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
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
            card.addView(label(ProtocolCatalog.stateLabel(option.engineState), 11f, if (option.engineState == com.rmgb.netpulse.protocol.EngineState.AVAILABLE) cyan else Color.rgb(255, 190, 95)))
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

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }

    companion object {
        private const val REQUEST_PROFILE = 711
        private const val REQUEST_VPN_PERMISSION = 710
    }
}
