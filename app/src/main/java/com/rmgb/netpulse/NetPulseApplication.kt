package com.rmgb.netpulse

import android.app.Application
import android.util.Log
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import java.util.Locale

class NetPulseApplication : Application() {
    val wireGuardBackend: GoBackend by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { GoBackend(applicationContext) }
    @Volatile var wireGuardStateListener: ((Tunnel.State) -> Unit)? = null
    val wireGuardTunnel: Tunnel = object : Tunnel {
        override fun getName(): String = "NetPulse-WireGuard"
        override fun onStateChange(newState: Tunnel.State) { wireGuardStateListener?.invoke(newState) }
    }
    override fun onCreate() {
        super.onCreate()
        try {
            val options = SetupOptions().apply {
                basePath = filesDir.absolutePath
                workingPath = filesDir.absolutePath
                tempPath = cacheDir.absolutePath
                debug = false
                logMaxLines = 0
                appVersion = "2"
                appMarketingVersion = "0.2.0"
            }
            Libbox.setLocale(Locale.getDefault().toLanguageTag())
            Libbox.setup(options)
        } catch (error: Exception) {
            Log.e("NetPulse", "sing-box initialization failed", error)
        }
    }
}
