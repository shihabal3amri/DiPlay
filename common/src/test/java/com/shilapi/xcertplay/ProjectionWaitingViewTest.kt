package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.res.Configuration
import android.os.Looper
import android.view.View
import java.io.File
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN", manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectionWaitingViewTest {
    @Test fun firstPresentationAndDeadlineBothYieldToTheMap() {
        val view = ProjectionWaitingView(RuntimeEnvironment.getApplication())
        view.waitForFrame()
        assertEquals(View.VISIBLE, view.visibility)
        view.presented()
        assertEquals(View.GONE, view.visibility)
        view.waitForFrame()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_001))
        assertEquals(View.GONE, view.visibility)
    }

    @Test fun staleDeadlineCannotHideANewDisconnectedPage() {
        val view = ProjectionWaitingView(RuntimeEnvironment.getApplication())
        view.waitForFrame()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4_000))
        view.waitForFrame(hideAtDeadline = false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_001))
        assertEquals(View.VISIBLE, view.visibility)
        assertFalse(view.isClickable)
    }

    @Test fun writesSyntheticLargeFontPreviews() {
        val app = RuntimeEnvironment.getApplication()
        val before = Configuration(app.resources.configuration)
        try {
            app.resources.updateConfiguration(Configuration(before).apply { fontScale = 1.5f }, app.resources.displayMetrics)
            for ((width, height) in listOf(400 to 260, 1280 to 720)) {
                val view = ProjectionWaitingView(app)
                view.waitForFrame(hideAtDeadline = false)
                view.layout(0, 0, width, height)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                val dir = File("build/reports/projection-waiting").apply { mkdirs() }
                File(dir, "waiting-${width}x${height}-font1.5.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                bitmap.recycle()
                view.presented()
            }
        } finally { app.resources.updateConfiguration(before, app.resources.displayMetrics) }
    }
}
