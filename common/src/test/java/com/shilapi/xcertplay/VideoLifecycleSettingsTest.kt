package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.graphics.Bitmap
import android.graphics.Canvas
import com.shilapi.xcertplay.host.R
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "en", manifest = Config.NONE)
class VideoLifecycleSettingsTest {
    @Test fun lifecycleGuardDefaultsOffAndOnlyChangesItsOwnPreference() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        val prefs = activity.getSharedPreferences("xcertplay_airplay", 0)
        prefs.edit().clear().commit()
        ReflectionHelpers.setField(activity, "settingsSectionFilter", setOf(SettingsSection.ADVANCED_MEDIA))
        val content = LinearLayout(activity)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "allSettingsSections",
            ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, content))
        val popup = switches(content).singleOrNull { it.contentDescription == activity.getString(R.string.settings_video_lifecycle_guard) }
        assertNotNull("Advanced media must expose the lifecycle opt-in", popup)
        assertFalse(popup!!.isChecked)
        val before = prefs.all.toMap()
        popup.isChecked = true
        assertEquals(before + ("video_lifecycle_guard" to true), prefs.all)
        popup.isChecked = false
        assertEquals(before + ("video_lifecycle_guard" to false), prefs.all)
        assertFalse(PendingReconnect.isPending(null))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "zh-rCN-w320dp-h800dp-xhdpi")
    fun compactLargeFontGuardRowRendersWithoutClipping() = renderGuardRow("compact-zh", 320)

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "ar-ldrtl-w600dp-h800dp-xhdpi")
    fun fullWidthArabicLargeFontGuardRowRendersWithoutClipping() = renderGuardRow("full-ar", 600)

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "zh-rCN-w1280dp-h800dp-xhdpi")
    fun fullWidthLargeFontGuardRowRendersWithoutClipping() = renderGuardRow("full-zh", 1280)

    private fun renderGuardRow(name: String, widthDp: Int) {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.applicationInfo.flags = activity.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        val configuration = android.content.res.Configuration(activity.resources.configuration).apply { fontScale = 1.6f }
        @Suppress("DEPRECATION")
        activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
        ReflectionHelpers.setField(activity, "settingsSectionFilter", setOf(SettingsSection.ADVANCED_MEDIA))
        val content = LinearLayout(activity)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "allSettingsSections",
            ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, content))
        val popup = switches(content).single { it.contentDescription == activity.getString(R.string.settings_video_lifecycle_guard) }
        val row = popup.parent as ViewGroup
        row.layoutDirection = configuration.layoutDirection
        val width = (widthDp * activity.resources.displayMetrics.density).toInt()
        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        row.layout(0, 0, width, row.measuredHeight)
        assertTrue(popup.right <= width && popup.left >= 0)
        assertTrue(popup.bottom <= row.height && row.height > 0)
        val bitmap = Bitmap.createBitmap(width, row.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.rgb(28, 32, 42))
        row.draw(canvas)
        val directory = File("build/reports/video-lifecycle-settings").apply { mkdirs() }
        val output = File(directory, "$name.png")
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun switches(view: View): List<Switch> = when (view) {
        is Switch -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { switches(view.getChildAt(it)) }
        else -> emptyList()
    }
}
