package com.shilapi.xcertplay

import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ImageView
import com.shilapi.xcertplay.host.R
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarPlayVideoControlsScreenshotsTest {
    @Test fun backArrowFitsCompactFullHighDensityAndRtlScreensWithLargeFonts() {
        for ((name, widthDp, density, language) in listOf(
            Screen("compact", 320, "mdpi", "zh-rTW"),
            Screen("full", 1280, "mdpi", "zh-rTW"),
            Screen("compact-high-density", 320, "xxhdpi", "zh-rTW"),
            Screen("arabic-rtl", 320, "xhdpi", "ar"),
            Screen("english", 320, "mdpi", "en"),
            Screen("japanese", 320, "mdpi", "ja"),
            Screen("russian", 320, "mdpi", "ru"),
        )) {
            RuntimeEnvironment.setQualifiers("$language-w${widthDp}dp-h640dp-$density")
            val app = RuntimeEnvironment.getApplication()
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(Configuration(app.resources.configuration).apply {
                fontScale = 1.5f
            }, app.resources.displayMetrics)
            val activity = Robolectric.buildActivity(CarPlayVideoActivity::class.java).get()
            activity.applicationInfo.flags = activity.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
            try {
                val controls = ReflectionHelpers.callInstanceMethod<View>(activity, "controlBar")
                controls.layoutDirection = activity.resources.configuration.layoutDirection
                val scale = activity.resources.displayMetrics.density
                val width = (widthDp * scale).roundToInt()
                val height = (640 * scale).roundToInt()
                controls.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                controls.layout(0, 0, width, height)
                val back = descendants(controls).filterIsInstance<TextView>().single {
                    it.text == activity.getString(R.string.video_back_to_carplay)
                }
                val icon = back.compoundDrawablesRelative[0] ?: error("Missing back arrow")
                val arrow = (icon as android.graphics.drawable.InsetDrawable).drawable ?: error("Missing arrow drawable")
                assertTrue("The shared arrow retains automatic RTL mirroring", arrow.isAutoMirrored)
                assertEquals("Icon must grow with large text independently of pixel density",
                    (30f * scale).roundToInt(), icon.bounds.height())
                val arrowBitmap = Bitmap.createBitmap(icon.bounds.width(), icon.bounds.height(), Bitmap.Config.ARGB_8888)
                val originalBounds = android.graphics.Rect(icon.bounds)
                try {
                    icon.setBounds(0, 0, arrowBitmap.width, arrowBitmap.height)
                    icon.draw(Canvas(arrowBitmap))
                    val inkColumns = (0 until arrowBitmap.width).filter { x ->
                        (0 until arrowBitmap.height).any { y -> android.graphics.Color.alpha(arrowBitmap.getPixel(x, y)) > 127 }
                    }
                    assertTrue("Arrow ink starts at its layout edge", inkColumns.first() <= 1)
                    assertTrue("Arrow ink ends at its layout edge", inkColumns.last() >= arrowBitmap.width - 2)
                } finally {
                    icon.bounds = originalBounds
                    arrowBitmap.recycle()
                }
                assertTrue(back.left >= 0 && back.right <= width)
                assertEquals("Single-line label: ${back.text}, size=${back.textSize}, width=${back.layout.width}, measured=${back.paint.measureText(back.text.toString())}",
                    1, back.layout.lineCount)
                assertTrue("The whole label must fit beside the arrow: ${back.text}; measured=${back.paint.measureText(back.text.toString())}; available=${back.width - back.compoundPaddingLeft - back.compoundPaddingRight}; size=${back.textSize}",
                    back.paint.measureText(back.text.toString()) <= back.width - back.compoundPaddingLeft - back.compoundPaddingRight)
                assertTrue(back.height >= back.minimumHeight)
                assertTrue(back.layout.height + back.compoundPaddingTop + back.compoundPaddingBottom <= back.height)
                assertEquals(back.text.length, back.layout.getLineEnd(back.layout.lineCount - 1))
                assertEquals(controls.layoutDirection, back.layoutDirection)
                assertTrue("Remove font padding just like the Settings return button", !back.includeFontPadding)
                val expectedEdgeDp = CarPlayVideoControlsLayout.CONTENT_HORIZONTAL_INSET_DP
                val edge = if (back.layoutDirection == View.LAYOUT_DIRECTION_RTL) width - back.right else back.left
                assertEquals("Move the whole button relative to the window edge",
                    (expectedEdgeDp * scale).roundToInt(), edge)
                assertEquals("Preserve the button's internal padding", (24 * scale).roundToInt(), back.paddingStart)
                assertMatchesSettingsAlignment(activity, back, scale)
                val bitmap = Bitmap.createBitmap(back.width, back.height, Bitmap.Config.ARGB_8888)
                try {
                    back.draw(Canvas(bitmap))
                    val output = File("build/reports/video-controls-ui/$name.png")
                    output.parentFile!!.mkdirs()
                    output.outputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { bitmap.recycle() }
                val screenBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(screenBitmap)
                    canvas.drawColor(0xFF263746.toInt())
                    controls.draw(canvas)
                    File("build/reports/video-controls-ui/$name-window.png").outputStream().use {
                        assertTrue(screenBitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { screenBitmap.recycle() }
                // A window resize must move the container and keep its alignment stable.
                SettingsLayoutPreferences.saveForceFull(activity, true)
                for ((resizedWidthDp, resizedHeightDp) in listOf(
                    640 to 640, 320 to 640, 824 to 384, 549 to 640,
                    550 to 449, 550 to 450, 1280 to 640, widthDp to 640,
                )) {
                    val resizedWidth = (resizedWidthDp * scale).roundToInt()
                    controls.measure(View.MeasureSpec.makeMeasureSpec(resizedWidth, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec((resizedHeightDp * scale).roundToInt(), View.MeasureSpec.EXACTLY))
                    controls.layout(0, 0, resizedWidth, controls.measuredHeight)
                    val actual = if (back.layoutDirection == View.LAYOUT_DIRECTION_RTL) resizedWidth - back.right else back.left
                    assertEquals((expectedEdgeDp * scale).roundToInt(), actual)
                    val compact = resizedWidthDp < 550 || resizedHeightDp < 450
                    val expectedSizes = if (compact) listOf(64, 80, 64) else listOf(80, 96, 80)
                    val circles = descendants(controls).filterIsInstance<ImageView>().toList()
                    assertEquals(3, circles.size)
                    val contentHeight = maxOf(back.layout.height, back.compoundDrawablesRelative[0]!!.bounds.height())
                    assertEquals("Back wraps its content and shared vertical padding",
                        maxOf(back.minimumHeight,
                            contentHeight + back.paddingTop + back.paddingBottom), back.height)
                    assertEquals("The capsule radius must follow its height", back.height / 2f,
                        (back.background as GradientDrawable).cornerRadius, 0.01f)
                    assertTrue("The label must fit the fixed control height",
                        back.layout.height + back.compoundPaddingTop + back.compoundPaddingBottom <= back.height)
                    val ordered = circles.sortedBy { it.left }
                    val expectedGap = ((if (compact) 24 else 48) * scale).roundToInt()
                    for ((left, right) in ordered.zipWithNext()) {
                        assertEquals("Keep space between all three circles after resizing", expectedGap, right.left - left.right)
                    }
                    for ((circle, sizeDp) in circles.zip(expectedSizes)) {
                        assertEquals("Player follows home dimensions even with full Settings enabled",
                            (sizeDp * scale).roundToInt(), circle.width)
                        assertEquals(circle.width, circle.height)
                        val parent = circle.parent as View
                        assertTrue("Circle must fit its row", circle.left >= 0 && circle.right <= parent.width)
                        val bottom = parent.parent as View
                        assertTrue("Circle must stay within the content insets",
                            parent.left + circle.left >= bottom.paddingLeft &&
                                parent.left + circle.right <= bottom.width - bottom.paddingRight)
                    }
                }
            } finally {
                SettingsLayoutPreferences.saveForceFull(activity, false)
                activity.finish()
            }
        }
    }

    private data class Screen(val name: String, val widthDp: Int, val density: String, val language: String)

    private fun assertMatchesSettingsAlignment(activity: CarPlayVideoActivity, back: TextView, scale: Float) {
        val settings = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        settings.setTheme(android.R.style.Theme_Material_NoActionBar)
        try {
            // Compare the real Settings header at the same font/icon size and available bounds.
            val method = DiPlayActivity::class.java.declaredMethods.single { it.name == "headerButton" }
                .apply { isAccessible = true }
            for (title in listOf(back.text.toString(), activity.getString(R.string.back))) {
                val header = method.invoke(settings, title, R.drawable.ic_dp_back, true, {}) as TextView
                val video = IconAlignedTextView(activity).apply {
                    text = title
                    gravity = android.view.Gravity.CENTER
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, back.textSize)
                    setPadding((24 * scale).roundToInt(), 0, (24 * scale).roundToInt(), 0)
                    setCompoundDrawablesRelative(activity.getDrawable(R.drawable.ic_dp_back)!!.apply {
                        setBounds(0, 0, back.compoundDrawablesRelative[0]!!.bounds.width(),
                            back.compoundDrawablesRelative[0]!!.bounds.height())
                    }, null, null, null)
                    compoundDrawablePadding = back.compoundDrawablePadding
                }
                header.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, back.textSize)
                header.compoundDrawablesRelative[0]!!.setBounds(video.compoundDrawablesRelative[0]!!.bounds)
                for (view in listOf(header, video)) {
                    view.measure(View.MeasureSpec.makeMeasureSpec((1280 * scale).roundToInt(), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec((64 * scale).roundToInt(), View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                }
                val settingsOffset = ReflectionHelpers.getField<Float>(header, "contentOffsetY")
                val videoOffset = ReflectionHelpers.getField<Float>(video, "contentOffsetY")
                assertEquals("Use Settings alignment for $title", settingsOffset, videoOffset, 0.01f)
                assertEquals(header.compoundDrawablesRelative[0]!!.bounds.top,
                    video.compoundDrawablesRelative[0]!!.bounds.top)
            }
        } finally { settings.finish() }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
