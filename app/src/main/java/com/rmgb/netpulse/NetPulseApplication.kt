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
                appVersion = BuildConfig.VERSION_CODE.toString()
                appMarketingVersion = BuildConfig.VERSION_NAME
            }
            Libbox.setLocale(Locale.getDefault().toLanguageTag())
            Libbox.setup(options)
        } catch (error: Exception) {
            Log.e("NetPulse", "sing-box initialization failed", error)
        }
    }
}
