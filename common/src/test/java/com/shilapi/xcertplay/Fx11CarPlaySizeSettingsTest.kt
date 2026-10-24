package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.airplay.CarPlaySize
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, qualifiers = "zh-rCN")
class Fx11CarPlaySizeSettingsTest {
    private lateinit var activity: DiPlayActivity

    @Before fun setUp() {
        activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().apply()
    }

    @Test fun newChoicesSaveAndReopenWithoutChangingTheResolutionOrDefault() {
        assertEquals(300, AirPlayPersistence.loadWidthPhysicalMm(activity))
        AirPlayPersistence.saveDisplayScalePercent(activity, 160)
        for ((preset, label) in listOf(CarPlaySize.EXTRA_LARGE to "超大", CarPlaySize.LARGEST to "最大")) {
            val row = control()
            row.performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val index = CarPlaySize.entries.indexOf(preset)
            assertEquals("$label · ${preset.widthMillimeters} mm", dialog.listView.adapter.getItem(index).toString())
            shadowOf(dialog).clickOnItem(index)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(preset.widthMillimeters, AirPlayPersistence.loadWidthPhysicalMm(activity))
            assertEquals(160, AirPlayPersistence.loadDisplayScalePercent(activity))
            val reopened = control()
            assertTrue(reopened.text.toString().endsWith(" · $label · ${preset.widthMillimeters} mm"))
            reopened.performClick()
            val next = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(index, next.listView.checkedItemPosition)
            next.dismiss()
        }
    }

    @Test fun insertingNewChoicesPreservesOldSelectionAndCancelDoesNotSave() {
        for (oldWidth in listOf(250, 300, 350)) {
            AirPlayPersistence.saveWidthPhysicalMm(activity, oldWidth)
            control().performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(CarPlaySize.entries.indexOf(CarPlaySize.exact(oldWidth)),
                dialog.listView.checkedItemPosition)
            shadowOf(dialog).clickOnItem(CarPlaySize.entries.indexOf(CarPlaySize.LARGEST))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(oldWidth, AirPlayPersistence.loadWidthPhysicalMm(activity))
        }
    }

    private fun control(): TextView {
        val parent = LinearLayout(activity)
        DiPlayActivity::class.java.getDeclaredMethod("carPlaySizeControl", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, parent)
        return parent.getChildAt(0) as TextView
    }
}
