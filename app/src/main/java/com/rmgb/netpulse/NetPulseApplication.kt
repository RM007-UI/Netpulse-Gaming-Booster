package com.rmgb.netpulse

import android.app.Application
import android.util.Log
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.util.Locale

class NetPulseApplication : Application() {
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
