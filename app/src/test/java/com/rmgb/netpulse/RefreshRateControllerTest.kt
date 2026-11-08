package com.rmgb.netpulse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshRateControllerTest {
    @Test
    fun applyScriptPinsAndroidAndHyperOsRefreshControls() {
        val script = RefreshRateController.buildApplyScript(RefreshModeOption(1220, 2712, 120))
        assertTrue(script.contains("settings put system min_refresh_rate 120.0"))
        assertTrue(script.contains("settings put system peak_refresh_rate 120.0"))
        assertTrue(script.contains("settings put secure user_refresh_rate 1"))
        assertTrue(script.contains("settings put secure miui_refresh_rate 120"))
        assertTrue(script.contains("cmd display set-user-preferred-display-mode 1220 2712 120.0 0"))
    }

    @Test
    fun restoreScriptRestoresOriginalValuesAndMode() {
        val original = RefreshSettingsSnapshot(
            systemMin = "60.0",
            systemPeak = "120.0",
            secureMiuiRate = "60",
            secureUserRate = "1",
            preferredDisplayMode = "User preferred display mode: 1220 2712 60.0"
        )
        val script = RefreshRateController.buildRestoreScript(original)
        assertTrue(script.contains("settings put system min_refresh_rate 60.0"))
        assertTrue(script.contains("settings put secure miui_refresh_rate 60"))
        assertTrue(script.contains("cmd display set-user-preferred-display-mode 1220 2712 60.0 0"))
        assertFalse(script.contains("cmd display clear-user-preferred-display-mode"))
    }

    @Test
    fun restoreScriptClearsOnlyValuesThatWerePreviouslyUnset() {
        val original = RefreshSettingsSnapshot("null", "null", "null", "null", "null")
        val script = RefreshRateController.buildRestoreScript(original)
        assertTrue(script.contains("settings delete system min_refresh_rate"))
        assertTrue(script.contains("settings delete secure miui_refresh_rate"))
        assertTrue(script.contains("cmd display clear-user-preferred-display-mode"))
    }

    @Test
    fun snapshotRoundTripsWithoutLosingValues() {
        val original = RefreshSettingsSnapshot("null", "120.0", "60", "1", "User preferred display mode: null")
        assertTrue(RefreshSettingsSnapshot.decode(original.encode()) == original)
    }

    @Test(expected = IllegalArgumentException::class)
    fun applyScriptRejectsInvalidDimensions() {
        RefreshRateController.buildApplyScript(RefreshModeOption(1, 2712, 90))
    }
}
