package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.PixelSize
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayHostDisplaySizeTest {
    private lateinit var activity: CarPlayHostActivity
    private val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
    private val ownerField = CarPlayBackgroundSession::class.java.getDeclaredField("owner").apply {
        isAccessible = true
    }

    @Before fun setUp() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        ownerField.set(CarPlayBackgroundSession, activity)
        setField("activeDisplaySize", size(1920, 990))
    }

    @After fun tearDown() {
        (getField("shuttingDown") as AtomicBoolean).set(true)
        (getField("teardownExecutor") as ExecutorService).shutdownNow()
        (getField("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        CarPlayBackgroundSession.clear()
    }

    @Test fun surroundViewOpenAndCloseKeepsTheFixedResolutionSession() {
        startSession(PixelSize(1920, 990))

        applySize(1920, 942)
        assertEquals(size(1920, 942), getField("activeDisplaySize"))
        assertEquals(0, getField("restartGeneration"))
        assertFalse(getField("handshakeResetInProgress") as Boolean)

        applySize(1920, 990)
        assertEquals(size(1920, 990), getField("activeDisplaySize"))
        assertEquals(0, getField("restartGeneration"))
        assertEquals(PixelSize(1920, 990), getField("sessionCanvas"))
        assertEquals(2, keepLogs())
    }

    @Test fun rotationRenegotiates() {
        startSession(PixelSize(1920, 990))

        applySize(990, 1920)

        assertEquals(1, getField("restartGeneration"))
        assertTrue(getField("handshakeResetInProgress") as Boolean)
        assertEquals(0, keepLogs())
    }

    @Test fun withoutARunningSessionADisplayChangeStillRebuilds() {
        applySize(1920, 942)

        assertEquals(1, getField("restartGeneration"))
        assertTrue(ShadowLog.getLogsForTag("xcertplay-usb").any {
            it.msg.contains("Display changed 1920x990 -> 1920x942; rebuilding stack")
        })
    }

    @Test fun initialDisplayDetectionIsNotTreatedAsASurroundViewChange() {
        startSession(PixelSize(1920, 990))
        setField("activeDisplaySize", null)

        applySize(1920, 990)

        assertEquals(size(1920, 990), getField("activeDisplaySize"))
        assertEquals(0, getField("restartGeneration"))
        assertEquals(0, keepLogs())
    }

    @Test fun resolutionBaseIgnoresASurroundViewShrink() {
        setField("activeDisplaySize", null)
        applySize(1920, 990)
        startSession(PixelSize(1920, 990))
        applySize(1920, 942)

        val base = activity.javaClass.getDeclaredMethod("resolveResolutionBase", sizeClass)
            .apply { isAccessible = true }.invoke(activity, size(1920, 942))
        assertEquals(PixelSize(1920, 990), base)
    }

    private fun startSession(canvas: PixelSize) {
        val layout = activity.javaClass.getDeclaredMethod("barLayout")
            .apply { isAccessible = true }.invoke(activity)
        setField("sessionBase", canvas)
        setField("sessionCanvas", canvas)
        setField("sessionLayout", layout)
    }

    private fun keepLogs(): Int = ShadowLog.getLogsForTag("xcertplay-usb").count {
        it.msg.contains("keeping fixed-resolution CarPlay session")
    }

    private fun size(width: Int, height: Int): Any =
        sizeClass.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(width, height)

    private fun applySize(width: Int, height: Int) {
        activity.javaClass.getDeclaredMethod("applyDisplaySize", sizeClass)
            .apply { isAccessible = true }.invoke(activity, size(width, height))
    }

    private fun getField(name: String): Any? = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)

    private fun setField(name: String, value: Any?) {
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }
}
