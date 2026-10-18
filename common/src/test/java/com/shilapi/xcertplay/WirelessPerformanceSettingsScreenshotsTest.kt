package com.shilapi.xcertplay

// 本次合成无线性能开关的大字体布局截图。
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Synthetic native renders of the performance control at 1.5x text size. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WirelessPerformanceSettingsScreenshotsTest {
    @Test fun wirelessPerformanceRendersAtLargeFontsInThreeLayouts() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.applicationInfo.flags = activity.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
        try {
            for ((filename, widthDp, language) in listOf(
                Triple("compact-zh", 320, "zh-CN"),
                Triple("full-zh", 960, "zh-CN"),
                Triple("compact-ar-rtl", 320, "ar-SA"),
            )) {
                val locale = Locale.forLanguageTag(language)
                val config = Configuration(activity.resources.configuration).apply {
                    fontScale = 1.5f
                    setLocale(locale)
                    setLayoutDirection(locale)
                }
                @Suppress("DEPRECATION")
                activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(DiPlayPalette.DARK.surface)
                    layoutDirection = config.layoutDirection
                }
                DiPlayActivity::class.java.getDeclaredMethod("wirelessPerformanceControls", LinearLayout::class.java)
                    .apply { isAccessible = true }.invoke(activity, row)
                assertTrue(allViews(row).filterIsInstance<TextView>().any {
                    it.text.toString() == activity.getString(R.string.settings_wireless_performance)
                })
                val width = (widthDp * activity.resources.displayMetrics.density).toInt()
                row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                row.layout(0, 0, width, row.measuredHeight)
                val bitmap = Bitmap.createBitmap(width, row.measuredHeight, Bitmap.Config.ARGB_8888)
                try {
                    row.draw(Canvas(bitmap))
                    val directory = File("build/reports/wireless-performance-ui").apply { mkdirs() }
                    FileOutputStream(File(directory, "$filename.png")).use { output ->
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            activity.finish()
        }
    }

    private fun allViews(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(allViews(view.getChildAt(i)))
    }
}
