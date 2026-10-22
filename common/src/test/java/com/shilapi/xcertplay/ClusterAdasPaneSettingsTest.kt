package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.host.R
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
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

/** The ADAS pane card hides the cluster pane only after a confirmation and only while the car is known to be parked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1000dp-h700dp-land")
class ClusterAdasPaneSettingsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val opened = mutableListOf<DiPlayActivity>()

    @After fun tearDown() {
        opened.forEach { it.finish() }
        AppAppearanceRuntime.resetForTest()
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

    private fun advancedSettings(receiver: Boolean, parked: Boolean?, restore: () -> Boolean = { true }): DiPlayActivity {
        val screen = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).create().get()
        opened += screen
        screen.adasPaneAvailable = { receiver }
        screen.adasParkedCheck = { parked }
        screen.adasRestore = { restore() }
        screen.adasBackground = { it() }
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

    private fun frames(): List<String?> = shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents
        .filter { it.action == "com.byd.cluster.spi" }.map { it.getStringExtra("wholeFrame") }

    private fun confirmHide(screen: DiPlayActivity) {
        button(screen, R.string.settings_adas_pane_hide).performClick()
        val dialog: AlertDialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun anotherHeadUnitIsNotOfferedTheCard() {
        val screen = advancedSettings(receiver = true, parked = true)
        assertFalse(texts(screen).toString(), screen.getString(R.string.settings_adas_pane_title) in texts(screen))
    }

    @Test fun aBydHeadUnitWithoutTheReceiverShowsWhyAndDisablesBothButtons() {
        installBydSettingsPackage()
        val screen = advancedSettings(receiver = false, parked = true)
        assertTrue(screen.getString(R.string.settings_adas_pane_unavailable) in texts(screen))
        assertFalse(button(screen, R.string.settings_adas_pane_hide).isEnabled)
        assertFalse(button(screen, R.string.settings_adas_pane_restore).isEnabled)
    }

    @Test fun hidingNeedsTheConfirmationAndSendsOnlyTheHideFrame() {
        installBydSettingsPackage()
        val screen = advancedSettings(receiver = true, parked = true)
        button(screen, R.string.settings_adas_pane_hide).performClick()
        assertTrue(frames().isEmpty())
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(List(50) { "43,20,00,00,01,01" }, frames())
        assertEquals(screen.getString(R.string.settings_adas_pane_hide_sent), ShadowToast.getTextOfLatestToast())
    }

    @Test fun cancellingTheConfirmationSendsNothing() {
        installBydSettingsPackage()
        val screen = advancedSettings(receiver = true, parked = true)
        button(screen, R.string.settings_adas_pane_hide).performClick()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertTrue(frames().isEmpty())
    }

    @Test fun notBeingInParkSendsNothing() {
        installBydSettingsPackage()
        val screen = advancedSettings(receiver = true, parked = false)
        confirmHide(screen)
        assertTrue(frames().isEmpty())
        assertEquals(screen.getString(R.string.settings_adas_pane_not_parked), ShadowToast.getTextOfLatestToast())
    }

    @Test fun anUnreadableGearSendsNothing() {
        installBydSettingsPackage()
        val screen = advancedSettings(receiver = true, parked = null)
        confirmHide(screen)
        assertTrue(frames().isEmpty())
        assertEquals(screen.getString(R.string.settings_adas_pane_gear_unknown), ShadowToast.getTextOfLatestToast())
    }

    @Test fun restoringNeedsNeitherTheGearNorAConfirmationAndSendsNoFrames() {
        installBydSettingsPackage()
        var restored = 0
        val screen = advancedSettings(receiver = true, parked = false, restore = { restored++; true })
        button(screen, R.string.settings_adas_pane_restore).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, restored)
        assertTrue(frames().isEmpty())
        assertEquals(screen.getString(R.string.settings_adas_pane_restore_sent), ShadowToast.getTextOfLatestToast())
    }

    @Test fun aRestoreTheCarDoesNotAcceptSaysSo() {
        installBydSettingsPackage()
        val screen = advancedSettings(receiver = true, parked = true, restore = { false })
        button(screen, R.string.settings_adas_pane_restore).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(screen.getString(R.string.settings_adas_pane_restore_failed), ShadowToast.getTextOfLatestToast())
    }
}
