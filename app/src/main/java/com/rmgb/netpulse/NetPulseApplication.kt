package com.rmgb.netpulse

import android.app.Application
import android.util.Log
import android.os.Process
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.util.Locale

class NetPulseApplication : Application() {
    val wireGuardBackend: GoBackend by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GoBackend(applicationContext)
    }

    @Volatile var wireGuardStateListener: ((Tunnel.State) -> Unit)? = null
    @Volatile var loadedWireGuardConfig: Config? = null
    @Volatile var pendingWireGuardConfig: Config? = null
    @Volatile var loadedWireGuardText: String? = null
    @Volatile var loadedSingBoxProfile: String? = null
    @Volatile var pendingSingBoxServiceProfile: String? = null
    @Volatile var loadedSingBoxProtocol: String? = null
    @Volatile var loadedSingBoxDisplayName: String? = null
    @Volatile var loadedIkev2Config: Ikev2ProfileConfig? = null

    private lateinit var profileStore: SecureProfileStore

    fun markDiagnosticStage(stage: String) {
        runCatching {
            getSharedPreferences(DIAGNOSTICS_PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_STAGE, stage.take(120))
                .apply()
        }
    }

    fun lastCrashReport(): String? =
        getSharedPreferences(DIAGNOSTICS_PREFS, MODE_PRIVATE)
            .getString(KEY_LAST_CRASH, null)

    fun clearCrashReport() {
        getSharedPreferences(DIAGNOSTICS_PREFS, MODE_PRIVATE)
            .edit()
            .remove(KEY_LAST_CRASH)
            .apply()
    }

    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val prefs = getSharedPreferences(DIAGNOSTICS_PREFS, MODE_PRIVATE)
                val stage = prefs.getString(KEY_LAST_STAGE, "unknown") ?: "unknown"
                val report = buildString {
                    append("NetPulse crash diagnostic\n")
                    append("Time: ").append(System.currentTimeMillis()).append('\n')
                    append("Thread: ").append(thread.name.take(80)).append('\n')
                    append("Last operation: ").append(stage).append('\n')
                    append("Error type: ").append(error.javaClass.name).append('\n')
                    error.stackTrace.take(36).forEach { frame ->
                        append("at ").append(frame.toString().take(220)).append('\n')
                    }
                    var cause = error.cause
                    var depth = 0
                    while (cause != null && depth++ < 3) {
                        append("Caused by: ").append(cause.javaClass.name).append('\n')
                        cause.stackTrace.take(8).forEach { frame ->
                            append("at ").append(frame.toString().take(220)).append('\n')
                        }
                        cause = cause.cause
                    }
                }.take(9000)
                prefs.edit().putString(KEY_LAST_CRASH, report).commit()
                Log.e("NetPulseCrash", report)
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    companion object {
        private const val DIAGNOSTICS_PREFS = "netpulse_crash_diagnostics"
        private const val KEY_LAST_STAGE = "last_operation"
        private const val KEY_LAST_CRASH = "last_crash_report"
    }

    val wireGuardTunnel: Tunnel = object : Tunnel {
        override fun getName(): String = "NetPulse-WireGuard"
        override fun onStateChange(newState: Tunnel.State) {
            wireGuardStateListener?.invoke(newState)
        }
    }

    override fun onCreate() {
        super.onCreate()
        installCrashRecorder()
        try {
            val options = SetupOptions().apply {
                basePath = filesDir.absolutePath
                workingPath = filesDir.absolutePath
                tempPath = cacheDir.absolutePath
                debug = false
                logMaxLines = 0
                appVersion = "14"
                appMarketingVersion = "0.6.6"
            }
            Libbox.setLocale(Locale.getDefault().toLanguageTag())
            Libbox.setup(options)
        } catch (error: Exception) {
            Log.e("NetPulse", "sing-box initialization failed (${error.javaClass.simpleName})")
        }

        profileStore = SecureProfileStore(this)
        restoreProfiles()
    }

    /**
     * Persist only validated profiles. SecureProfileStore uses a non-exportable
     * Android Keystore key and AES-GCM authentication; secrets are never logged.
     */
    fun persistProfiles(): Boolean {
        if (!::profileStore.isInitialized) return false
        return runCatching {
            profileStore.save(
                StoredProfiles(
                    wireGuardConfig = loadedWireGuardText,
                    singBoxProfile = loadedSingBoxProfile,
                    singBoxProtocol = loadedSingBoxProtocol,
                    singBoxDisplayName = loadedSingBoxDisplayName,
                    ikev2Config = loadedIkev2Config?.toJson()
                )
            )
        }.onFailure { error ->
            Log.w("NetPulse", "encrypted profile save failed (${error.javaClass.simpleName})")
        }.isSuccess
    }

    fun clearStoredProfiles(): Boolean {
        if (!::profileStore.isInitialized) return false
        return runCatching {
            profileStore.clear()
            loadedWireGuardConfig = null
            pendingWireGuardConfig = null
            loadedWireGuardText = null
            loadedSingBoxProfile = null
            pendingSingBoxServiceProfile = null
            loadedSingBoxProtocol = null
            loadedSingBoxDisplayName = null
            loadedIkev2Config = null
        }.onFailure { error ->
            Log.w("NetPulse", "encrypted profile clear failed (${error.javaClass.simpleName})")
        }.isSuccess
    }

    private fun restoreProfiles() {
        val stored = runCatching { profileStore.load() }
            .onFailure { error ->
                Log.w("NetPulse", "encrypted profile restore failed (${error.javaClass.simpleName})")
            }
            .getOrNull() ?: return

        stored.wireGuardConfig?.let { raw ->
            runCatching { Config.parse(raw.byteInputStream(Charsets.UTF_8)) }
                .onSuccess { parsed ->
                    loadedWireGuardText = raw
                    loadedWireGuardConfig = parsed
                }
                .onFailure { error ->
                    Log.w("NetPulse", "saved WireGuard profile rejected (${error.javaClass.simpleName})")
                }
        }

        stored.ikev2Config?.let { raw ->
            runCatching { Ikev2ProfileConfig.fromJson(raw) }
                .onSuccess { loadedIkev2Config = it }
                .onFailure { error ->
                    Log.w("NetPulse", "saved IKEv2 profile rejected (${error.javaClass.simpleName})")
                }
        }

        stored.singBoxProfile?.let { raw ->
            runCatching { Libbox.checkConfig(raw) }
                .onSuccess {
                    loadedSingBoxProfile = raw
                    loadedSingBoxProtocol = stored.singBoxProtocol
                    loadedSingBoxDisplayName = stored.singBoxDisplayName
                }
                .onFailure { error ->
                    Log.w("NetPulse", "saved sing-box profile rejected (${error.javaClass.simpleName})")
                }
        }
    }
}
