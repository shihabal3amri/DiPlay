package com.shilapi.xcertplay.setup

import android.content.Intent
import com.shilapi.xcertplay.DiPlayActivity
import com.shilapi.xcertplay.GeekModeManager
import com.shilapi.xcertplay.SettingsCategory
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en", manifest = Config.NONE)
class DiLinkProfileTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After fun cleanup() {
        DiLinkProfile.setManualOverride(app, null)
        GeekModeManager.setEnabled(app, false)
    }

    @Test fun controllerVersionMapsToCorrectPlatformProfiles() {
        assertEquals(DiLinkProfile.DILINK_150, DiLinkProfile.fromControllerVersion("31.2.0.2401010.1"))
        assertEquals(DiLinkProfile.DILINK_150, DiLinkProfile.fromControllerVersion("34.1.0"))
        assertEquals(DiLinkProfile.DILINK_100, DiLinkProfile.fromControllerVersion("23.1.2.2307180.1"))
        assertEquals(DiLinkProfile.DILINK_50_4, DiLinkProfile.fromControllerVersion("16.1.0"))
        assertEquals(DiLinkProfile.DILINK_50_4, DiLinkProfile.fromControllerVersion("17.2.1"))
        assertEquals(DiLinkProfile.DILINK_50_4, DiLinkProfile.fromControllerVersion("21.0.1"))
        assertEquals(DiLinkProfile.DILINK_50_3, DiLinkProfile.fromControllerVersion("13.1.0.2012300.1"))
        assertEquals(DiLinkProfile.DILINK_50_3, DiLinkProfile.fromControllerVersion("15.1.0"))
        assertEquals(DiLinkProfile.DILINK_50_3, DiLinkProfile.fromControllerVersion("18.0.0"))
        assertEquals(DiLinkProfile.DILINK_20, DiLinkProfile.fromControllerVersion("2.1.0"))
        assertEquals(DiLinkProfile.DILINK_20, DiLinkProfile.fromControllerVersion("26.1.0"))
        assertNull(DiLinkProfile.fromControllerVersion("99.9.9"))
        assertNull(DiLinkProfile.fromControllerVersion(null))
    }

    @Test fun keyRoundTripAndGenerationMapping() {
        for (profile in DiLinkProfile.entries) {
            assertEquals(profile, DiLinkProfile.fromKey(profile.key))
        }
        assertEquals(DiLinkGeneration.DILINK_5, DiLinkProfile.toGeneration(DiLinkProfile.DILINK_150))
        assertEquals(DiLinkGeneration.DILINK_5, DiLinkProfile.toGeneration(DiLinkProfile.DILINK_100))
        assertEquals(DiLinkGeneration.DILINK_4, DiLinkProfile.toGeneration(DiLinkProfile.DILINK_50_4))
        assertEquals(DiLinkGeneration.DILINK_3, DiLinkProfile.toGeneration(DiLinkProfile.DILINK_50_3))
        assertEquals(DiLinkGeneration.UNKNOWN, DiLinkProfile.toGeneration(DiLinkProfile.DILINK_20))
        assertEquals(DiLinkGeneration.UNKNOWN, DiLinkProfile.toGeneration(DiLinkProfile.GENERIC))
    }

    @Test fun intentOverrideSetsTargetProfileAndOpensSettingsDirectly() {
        val intent = Intent(app, DiPlayActivity::class.java).apply {
            putExtra("dilink_profile", "dilink100")
            putExtra("geek_mode", true)
            putExtra("open_settings", true)
            putExtra("settings_category", "ADVANCED")
        }
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, intent)
        val activity = controller.create().get()

        assertEquals(DiLinkProfile.DILINK_100, DiLinkProfile.current(activity))
        assertTrue(GeekModeManager.isEnabled(activity))
        assertEquals("settings", ReflectionHelpers.getField<String>(activity, "page"))
        assertEquals(SettingsCategory.ADVANCED, ReflectionHelpers.getField<SettingsCategory>(activity, "settingsCategory"))
        controller.destroy()
    }
}
