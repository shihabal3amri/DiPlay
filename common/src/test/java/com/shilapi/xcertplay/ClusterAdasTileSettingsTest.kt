package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydOutputSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

/** The ADAS tile card keeps the cluster's small tile on request, and never hides anything. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1000dp-h700dp-land")
class ClusterAdasTileSettingsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val opened = mutableListOf<DiPlayActivity>()

    @After fun tearDown() {
        opened.forEach { it.finish() }
        AppAppearanceRuntime.resetForTest()
        BydOutputSettings.setKeepAdasTileSmall(context, false)
    }

    private fun installBydSettingsPackage() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.byd.carsettings"
            applicationInfo = ApplicationInfo().apply {
                packageName = "com.byd.carsettings"
                flags = ApplicationInfo.FLAG_SYSTEM
            }
        })
    }

    private fun advancedSettings(receiver: Boolean, showSmall: () -> Boolean = { true }): DiPlayActivity {
        val screen = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).create().get()
        opened += screen
        screen.adasTileAvailable = { receiver }
        screen.adasTileShowSmall = { showSmall() }
        ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.ADVANCED)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        return screen
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun texts(screen: DiPlayActivity): List<String> =
        descendants(screen.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()

    private fun button(screen: DiPlayActivity, title: Int): Button =
        descendants(screen.window.decorView).filterIsInstance<Button>()
            .first { it.text.toString() == screen.getString(title) }

    private fun keepSmallSwitch(screen: DiPlayActivity): Switch {
        val title = descendants(screen.window.decorView).filterIsInstance<TextView>()
            .first { it.text.toString() == screen.getString(R.string.settings_adas_tile_keep_small) }
        var row = title.parent as ViewGroup
        while (descendants(row).none { it is Switch }) row = row.parent as ViewGroup
        return descendants(row).filterIsInstance<Switch>().first()
    }

    @Test fun anotherHeadUnitIsNotOfferedTheCard() {
        val screen = advancedSettings(receiver = true)
        assertFalse(texts(screen).toString(), screen.getString(R.string.settings_adas_tile_title) in texts(screen))
    }

    @Test fun aBydHeadUnitWithoutTheReceiverShowsWhyAndDisablesTheControls() {
        installBydSettingsPackage()
        val screen = advancedSettings(receiver = false)
        assertTrue(screen.getString(R.string.settings_adas_tile_unavailable) in texts(screen))
        assertFalse(keepSmallSwitch(screen).isEnabled)
        assertFalse(button(screen, R.string.settings_adas_tile_show_small).isEnabled)
    }

    @Test fun theDescriptionSaysNothingIsHiddenAndItNeedsAdb() {
        installBydSettingsPackage()
        val screen = advancedSettings(receiver = true)
        val description = screen.getString(R.string.settings_adas_tile_keep_small_description)
        assertTrue(texts(screen).toString(), description in texts(screen))
        assertTrue("Nothing is hidden" in description && "network ADB" in description)
    }

    @Test fun turningItOnIsSavedAndOffByDefault() {
        installBydSettingsPackage()
        val screen = advancedSettings(receiver = true)
        val switch = keepSmallSwitch(screen)
        assertFalse(switch.isChecked)
        assertFalse(BydOutputSettings.keepAdasTileSmall(context))
        switch.performClick()
        assertTrue(BydOutputSettings.keepAdasTileSmall(context))
    }

    @Test fun theButtonAsksForTheSmallTileNow() {
        installBydSettingsPackage()
        var asked = 0
        val screen = advancedSettings(receiver = true, showSmall = { asked++; true })
        button(screen, R.string.settings_adas_tile_show_small).performClick()
        assertEquals(1, asked)
        assertEquals(screen.getString(R.string.settings_adas_tile_sent), ShadowToast.getTextOfLatestToast())
    }

    @Test fun aRequestThatCannotBeSentSaysSo() {
        installBydSettingsPackage()
        val screen = advancedSettings(receiver = true, showSmall = { false })
        button(screen, R.string.settings_adas_tile_show_small).performClick()
        assertEquals(screen.getString(R.string.settings_adas_tile_send_failed), ShadowToast.getTextOfLatestToast())
    }
}
