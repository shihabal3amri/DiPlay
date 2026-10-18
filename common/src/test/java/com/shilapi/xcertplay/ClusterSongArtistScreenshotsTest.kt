package com.shilapi.xcertplay

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

/** Compact/full and RTL screenshots of the song artist setting at 1.5x text size. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClusterSongArtistScreenshotsTest {
    @Test fun songArtistControlRendersAtLargeFontsAcrossLayouts() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        // common 的测试宿主没有 mobile manifest，补真实应用已声明的 RTL 能力。
        activity.applicationInfo.flags = activity.applicationInfo.flags or
            android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL

        try {
            for ((filename, widthDp, language) in listOf(
                Triple("compact-zh", 320, "zh-CN"),
                Triple("full-en", 960, "en-US"),
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
                val row = DiPlayActivity::class.java.getDeclaredMethod("card")
                    .apply { isAccessible = true }.invoke(activity) as LinearLayout
                row.layoutDirection = config.layoutDirection
                if (language.startsWith("ar")) assertTrue(row.layoutDirection == View.LAYOUT_DIRECTION_RTL)
                DiPlayActivity::class.java.getDeclaredMethod("clusterSongSwitch", LinearLayout::class.java)
                    .apply { isAccessible = true }.invoke(activity, row)
                assertTrue(allViews(row).filterIsInstance<TextView>().any {
                    it.text.toString() == activity.getString(R.string.settings_cluster_song_artist)
                })
                val width = (widthDp * activity.resources.displayMetrics.density).toInt()
                row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                row.layout(0, 0, width, row.measuredHeight)
                val bitmap = Bitmap.createBitmap(width, row.measuredHeight, Bitmap.Config.ARGB_8888)
                try {
                    row.draw(Canvas(bitmap))
                    val directory = File("build/reports/cluster-song-artist-ui").apply { mkdirs() }
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
