package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
@Config(sdk = [29], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SplitResizeBridgeTest {
    @Test fun newerResizeReplacesOldDeadlineAndMissingFramesAlwaysExpire() {
        val bridge = SplitResizeBridge(RuntimeEnvironment.getApplication())
        bridge.show(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)) { false }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
        bridge.show(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)) { false }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertEquals(View.VISIBLE, bridge.visibility)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
        assertEquals(View.GONE, bridge.visibility)
        assertNull(bridge.drawable)
    }
    @Test fun freshPresentationAfterMinimumWaitReleasesSnapshot() {
        val bridge = SplitResizeBridge(RuntimeEnvironment.getApplication())
        var ready = false
        bridge.show(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)) { ready }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertEquals(View.VISIBLE, bridge.visibility)
        ready = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        assertEquals(View.GONE, bridge.visibility)
        assertNull(bridge.drawable)
        assertFalse(bridge.isClickable)
    }
    @Test fun queuedOldSizeIsReplacedByTheCurrentWindowBeforeDispatch() {
        val activity = org.robolectric.Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val current = View(activity).apply { layout(0, 0, 600, 720) }
        org.robolectric.util.ReflectionHelpers.setField(activity, "videoView", current)
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(activity, "scheduleDisplaySize",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 400),
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 720))
        val dispatch = org.robolectric.util.ReflectionHelpers.getField<Runnable>(activity, "applyDisplaySize")
        dispatch.run()
        val pending = org.robolectric.util.ReflectionHelpers.getField<Any>(activity, "pendingDisplaySize")
        assertEquals(600, org.robolectric.util.ReflectionHelpers.getField<Int>(pending, "width"))
        assertEquals(720, org.robolectric.util.ReflectionHelpers.getField<Int>(pending, "height"))
        assertNull(org.robolectric.util.ReflectionHelpers.getField<Any?>(activity, "controller"))
        org.robolectric.util.ReflectionHelpers.getField<android.os.Handler>(activity, "mainHandler").removeCallbacks(dispatch)
    }

    @Test fun bridgeReleasesOldTouchAndOnlyAFreshPressAfterRevealReachesCarPlay() {
        val activity = org.robolectric.Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val controller = org.mockito.Mockito.mock(com.shilapi.xcertplay.orchestration.CarPlayController::class.java)
        val bridge = SplitResizeBridge(activity)
        val view = View(activity).apply { layout(0, 0, 400, 400) }
        org.robolectric.util.ReflectionHelpers.setField(activity, "controller", controller)
        org.robolectric.util.ReflectionHelpers.setField(activity, "resizeBridge", bridge)
        val texture = org.mockito.Mockito.mock(android.view.TextureView::class.java)
        org.mockito.Mockito.`when`(texture.bitmap).thenReturn(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
        org.robolectric.util.ReflectionHelpers.setField(activity, "videoView", texture)
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(activity, "showSplitResizeBridge")
        org.mockito.Mockito.verify(controller).sendTouch(emptyList())
        fun touch(action: Int) {
            val event = android.view.MotionEvent.obtain(0, 0, action, 100f, 100f, 0)
            try {
                org.robolectric.util.ReflectionHelpers.callInstanceMethod<Boolean>(activity, "onHostTouch",
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(View::class.java, view),
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(android.view.MotionEvent::class.java, event))
            } finally { event.recycle() }
        }
        fun forwarded() = org.mockito.Mockito.mockingDetails(controller).invocations.count { it.method.name == "sendTouch" }
        touch(android.view.MotionEvent.ACTION_DOWN)
        touch(android.view.MotionEvent.ACTION_MOVE)
        assertEquals(1, forwarded())
        bridge.clear()
        touch(android.view.MotionEvent.ACTION_MOVE)
        touch(android.view.MotionEvent.ACTION_UP)
        assertEquals("the interrupted sequence stays suppressed after reveal", 1, forwarded())
        touch(android.view.MotionEvent.ACTION_DOWN)
        touch(android.view.MotionEvent.ACTION_UP)
        assertEquals("a fresh sequence is restored after reveal", 3, forwarded())
    }

    @Test fun nullAndRecycledSnapshotsNeverCoverLiveVideo() {
        val bridge = SplitResizeBridge(RuntimeEnvironment.getApplication())
        assertTrue(bridge.show(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)) { false })
        assertFalse(bridge.show(null) { false })
        assertEquals(View.GONE, bridge.visibility)
        assertNull(bridge.drawable)
        val recycled = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { recycle() }
        assertFalse(bridge.show(recycled) { false })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_300))
        assertEquals(View.GONE, bridge.visibility)
        assertNull(bridge.drawable)
    }

    @Test fun missingHostSnapshotDoesNotReleaseOrSuppressOrdinaryCarPlayTouches() {
        val activity = org.robolectric.Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val controller = org.mockito.Mockito.mock(com.shilapi.xcertplay.orchestration.CarPlayController::class.java)
        val bridge = SplitResizeBridge(activity)
        val view = View(activity).apply { layout(0, 0, 400, 400) }
        org.robolectric.util.ReflectionHelpers.setField(activity, "controller", controller)
        org.robolectric.util.ReflectionHelpers.setField(activity, "resizeBridge", bridge)
        org.robolectric.util.ReflectionHelpers.setField(activity, "videoView", view)
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(activity, "showSplitResizeBridge")
        assertEquals(View.GONE, bridge.visibility)
        org.mockito.Mockito.verifyNoInteractions(controller)
        val event = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 100f, 100f, 0)
        try {
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Boolean>(activity, "onHostTouch",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(View::class.java, view),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(android.view.MotionEvent::class.java, event))
        } finally { event.recycle() }
        assertEquals(1, org.mockito.Mockito.mockingDetails(controller).invocations.count { it.method.name == "sendTouch" })
    }

    @Test fun historyKeepsThreeDistinctSizesAndOrientationSeparate() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("diplay_split_screen", 0).edit().clear().commit()
        for (width in listOf(.5f, .33f, .67f, .4f, .401f)) SplitScreenSettings.saveWindow(context, false, width, .84f)
        assertEquals(3, SplitScreenSettings.history(context, false).size)
        assertEquals(.4f, SplitScreenSettings.window(context, false).first, .0001f)
        assertTrue(SplitScreenSettings.history(context, true).isEmpty())
        SplitScreenSettings.saveWindow(context, false, Float.NaN, .8f)
        assertEquals(3, SplitScreenSettings.history(context, false).size)
    }
    @Test fun rendersLocalSyntheticSnapshotInCompactAndFullWindows() {
        for ((width, height) in listOf(400 to 260, 1280 to 720)) {
            val frame = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(frame)
            canvas.drawColor(Color.rgb(24, 39, 50))
            val paint = android.graphics.Paint().apply { color = Color.WHITE; textSize = 81f; textAlign = android.graphics.Paint.Align.CENTER }
            canvas.drawText("分屏衔接示意", 640f, 360f, paint)
            val bridge = SplitResizeBridge(RuntimeEnvironment.getApplication())
            bridge.show(frame) { false }; bridge.layout(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bridge.draw(Canvas(bitmap))
            val directory = File("build/reports/split-resize").apply { mkdirs() }
            File(directory, "resize-${width}x${height}-font1.5-synthetic.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bridge.clear()
        }
    }
}
