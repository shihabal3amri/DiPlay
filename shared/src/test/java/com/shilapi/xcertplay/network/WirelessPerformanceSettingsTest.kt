package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class WirelessPerformanceSettingsTest {
    @Test fun existingUsersStayOffUntilTheyOptIn() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        assertFalse(WirelessPerformanceSettings.enabled(app))
        WirelessPerformanceSettings.save(app, true)
        assertTrue(WirelessPerformanceSettings.enabled(app))
        WirelessPerformanceSettings.save(app, false)
        assertFalse(WirelessPerformanceSettings.enabled(app))
    }
}
