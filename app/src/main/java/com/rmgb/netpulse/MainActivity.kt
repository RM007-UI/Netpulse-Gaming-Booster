package com.rmgb.netpulse

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.Color
import com.rmgb.netpulse.protocol.ProtocolCatalog
import com.rmgb.netpulse.protocol.ProtocolFamily

class MainActivity : Activity() {
    private val bg = Color.rgb(9, 15, 28)
    private val cyan = Color.rgb(48, 220, 230)
    private val muted = Color.rgb(165, 180, 198)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        body.addView(label("Protocol lab", 21f, Color.WHITE, true).apply { setPadding(0, 28, 0, 8) })
        body.addView(label("Catalog entries are not active engines. A connection will only be shown after a real engine is integrated.", 13f, muted))
        addFamily(body, "VPN TUNNELS", ProtocolFamily.VPN_TUNNEL)
        addFamily(body, "PROXY PROTOCOLS", ProtocolFamily.PROXY)
        addFamily(body, "TRANSPORTS", ProtocolFamily.TRANSPORT)
        addFamily(body, "DNS", ProtocolFamily.DNS)
        body.addView(Button(this).apply {
            text = "Request Android VPN permission"
            setOnClickListener {
                VpnService.prepare(this@MainActivity)?.let {
                    startActivityForResult(it, 710)
                }
            }
        })
        body.addView(label("Permission is requested only after your tap. Permission alone does not mean a connection is active.", 12f, muted))
        setContentView(root)
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
            card.addView(label(ProtocolCatalog.stateLabel(option.engineState), 11f, Color.rgb(255, 190, 95)))
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
}
