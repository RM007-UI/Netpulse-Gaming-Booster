package com.rmgb.netpulse

import android.view.Display
import java.io.IOException
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

internal data class RefreshModeOption(
    val widthPx: Int,
    val heightPx: Int,
    val hz: Int
)

internal data class RefreshSettingsSnapshot(
    val systemMin: String,
    val systemPeak: String,
    val secureMiuiRate: String,
    val secureUserRate: String,
    val preferredDisplayMode: String
) {
    fun encode(): String {
        val raw = listOf(systemMin, systemPeak, secureMiuiRate, secureUserRate, preferredDisplayMode)
            .joinToString("\n")
        return Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    companion object {
        fun decode(encoded: String): RefreshSettingsSnapshot {
            val decoded = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
            val values = decoded.split("\n")
            require(values.size == 5) { "Invalid refresh-rate snapshot" }
            return RefreshSettingsSnapshot(values[0], values[1], values[2], values[3], values[4])
        }
    }
}

internal data class RootCommandResult(val exitCode: Int, val output: String)

internal object RefreshRateController {
    fun supportedModes(display: Display): List<RefreshModeOption> {
        val current = display.mode
        return display.supportedModes
            .filter {
                it.physicalWidth == current.physicalWidth &&
                    it.physicalHeight == current.physicalHeight
            }
            .map {
                RefreshModeOption(it.physicalWidth, it.physicalHeight, it.refreshRate.roundToInt())
            }
            .distinctBy { it.hz }
            .sortedBy { it.hz }
    }

    fun currentRate(display: Display): Int = display.mode.refreshRate.roundToInt()

    fun hasRoot(): Boolean {
        val result = runRoot("id -u")
        return result.exitCode == 0 && result.output.lineSequence().any { it.trim() == "0" }
    }

    fun captureSnapshot(): RefreshSettingsSnapshot {
        val result = runRoot(CAPTURE_SCRIPT)
        check(result.exitCode == 0) { "Could not read the original display settings" }

        fun value(prefix: String): String =
            result.output.lineSequence()
                .firstOrNull { it.startsWith(prefix) }
                ?.substring(prefix.length)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "null"

        return RefreshSettingsSnapshot(
            systemMin = value("__RR_MIN__"),
            systemPeak = value("__RR_PEAK__"),
            secureMiuiRate = value("__RR_MIUI__"),
            secureUserRate = value("__RR_USER__"),
            preferredDisplayMode = value("__RR_MODE__")
        )
    }

    fun buildApplyScript(mode: RefreshModeOption): String {
        require(mode.widthPx in 100..10000) { "Invalid display width" }
        require(mode.heightPx in 100..10000) { "Invalid display height" }
        require(mode.hz in 1..240) { "Invalid refresh rate" }
        val rate = String.format(Locale.US, "%.1f", mode.hz.toFloat())
        return listOf(
            "settings put system min_refresh_rate " + rate,
            "settings put system peak_refresh_rate " + rate,
            "settings put secure user_refresh_rate 1",
            "settings put secure miui_refresh_rate " + mode.hz,
            "cmd display set-user-preferred-display-mode " +
                mode.widthPx + " " + mode.heightPx + " " + rate + " 0"
        ).joinToString("\n")
    }

    fun buildRestoreScript(snapshot: RefreshSettingsSnapshot): String {
        fun restore(namespace: String, key: String, raw: String): String {
            val value = raw.trim()
            return if (value == "null" || value.isEmpty()) {
                "settings delete " + namespace + " " + key
            } else {
                require(NUMERIC_VALUE.matches(value)) { "Unexpected saved settings value" }
                "settings put " + namespace + " " + key + " " + value
            }
        }

        val modeText = snapshot.preferredDisplayMode
        val modeMatch = DISPLAY_MODE.find(modeText)
        val displayCommand = if (modeText == "null" || modeMatch == null) {
            "cmd display clear-user-preferred-display-mode"
        } else {
            "cmd display set-user-preferred-display-mode " +
                modeMatch.groupValues[1] + " " +
                modeMatch.groupValues[2] + " " +
                modeMatch.groupValues[3] + " 0"
        }

        return listOf(
            restore("system", "min_refresh_rate", snapshot.systemMin),
            restore("system", "peak_refresh_rate", snapshot.systemPeak),
            restore("secure", "miui_refresh_rate", snapshot.secureMiuiRate),
            restore("secure", "user_refresh_rate", snapshot.secureUserRate),
            displayCommand
        ).joinToString("\n")
    }

    fun runRoot(script: String): RootCommandResult {
        val process = try {
            ProcessBuilder("su", "-c", script)
                .redirectErrorStream(true)
                .start()
        } catch (error: IOException) {
            throw IllegalStateException("Root manager (su) is unavailable", error)
        }

        if (!process.waitFor(12, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("Root command timed out")
        }

        val output = process.inputStream.bufferedReader().use { it.readText() }
        return RootCommandResult(process.exitValue(), output)
    }

    private const val CAPTURE_SCRIPT = """
echo "__RR_MIN__$(settings get system min_refresh_rate 2>/dev/null)"
echo "__RR_PEAK__$(settings get system peak_refresh_rate 2>/dev/null)"
echo "__RR_MIUI__$(settings get secure miui_refresh_rate 2>/dev/null)"
echo "__RR_USER__$(settings get secure user_refresh_rate 2>/dev/null)"
echo "__RR_MODE__$(cmd display get-user-preferred-display-mode 0 2>/dev/null)"
"""

    private val NUMERIC_VALUE = Regex("[0-9]+(?:\\.[0-9]+)?")
    private val DISPLAY_MODE =
        Regex(".*?(?:User preferred display mode:\\s*)?(\\d+)\\s+(\\d+)\\s+([0-9]+(?:\\.[0-9]+)?).*")
}
