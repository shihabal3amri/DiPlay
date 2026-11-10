package com.shilapi.xcertplay

import android.content.res.Configuration
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ForceVideoSettingsScreenshotsTest {
    @Test fun forceVideoRowFitsLargeFontsInCompactFullAndRtlLayouts() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        // Match mobile's manifest; this library's test manifest does not declare RTL support.
        activity.applicationInfo.flags = activity.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        try {
            for ((name, widthDp, tag) in listOf(
                Triple("compact-en", 320, "en-US"),
                Triple("full-en", 960, "en-US"),
                Triple("compact-zh-TW", 320, "zh-TW"),
                Triple("full-zh-TW", 960, "zh-TW"),
                Triple("compact-ar-rtl", 320, "ar-SA"),
            )) {
                val locale = Locale.forLanguageTag(tag)
                val configuration = Configuration(activity.resources.configuration).apply {
                    fontScale = 1.5f
                    densityDpi = 320
                    setLocale(locale)
                    setLayoutDirection(locale)
                }
                @Suppress("DEPRECATION")
                activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
                val sections = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutDirection = configuration.layoutDirection
                }
                DiPlayActivity::class.java.getDeclaredMethod("renderSections", LinearLayout::class.java, Set::class.java)
                    .apply { isAccessible = true }
                    .invoke(activity, sections, setOf(SettingsSection.ADVANCED_MEDIA))
                val setting = descendants(sections).filterIsInstance<Switch>().single {
                    it.contentDescription == activity.getString(R.string.settings_force_video_in_car)
                }
                val row = setting.parent as ViewGroup
                val latencyRow = descendants(sections).filterIsInstance<Switch>().single {
                    it.contentDescription == activity.getString(R.string.settings_low_latency_decoder)
                }.parent as ViewGroup
                val card = row.parent as ViewGroup
                assertTrue(latencyRow.parent === card)
                assertEquals(card.indexOfChild(latencyRow) + 1, card.indexOfChild(row))
                row.setBackgroundColor(DiPlayPalette.DARK.surface)
                val width = (widthDp * activity.resources.displayMetrics.density).toInt()
                sections.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                sections.layout(0, 0, width, sections.measuredHeight)
                assertEquals(configuration.layoutDirection, row.layoutDirection)
                val labels = descendants(row).filterIsInstance<TextView>().filter { it !is Switch }.toList()
                assertTrue(labels.any { it.text == activity.getString(R.string.settings_force_video_in_car_description) })
                for (label in labels) {
                    val layout = label.layout ?: error("Missing text layout")
                    assertTrue(label.text.toString(), layout.lineCount > 0)
                    assertEquals(label.text.toString(), label.text.length, layout.getLineEnd(layout.lineCount - 1))
                    for (line in 0 until layout.lineCount) assertEquals(0, layout.getEllipsisCount(line))
                    assertTrue(label.text.toString(), label.height >= layout.height + label.compoundPaddingTop + label.compoundPaddingBottom)
                }
                val bitmap = Bitmap.createBitmap(card.width, row.bottom - latencyRow.top, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(DiPlayPalette.DARK.surface)
                    canvas.translate(0f, -latencyRow.top.toFloat())
                    card.draw(canvas)
                    val folder = File("build/reports/force-video-ui").apply { mkdirs() }
                    FileOutputStream(File(folder, "$name.png")).use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            activity.finish()
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
