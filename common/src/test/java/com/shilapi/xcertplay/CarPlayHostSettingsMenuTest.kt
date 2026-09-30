package com.shilapi.xcertplay

import android.view.View
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
class CarPlayHostSettingsMenuTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var menu: View
    private lateinit var gestures: View
    private val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
    private val ownerField = CarPlayBackgroundSession::class.java.getDeclaredField("owner").apply {
        isAccessible = true
    }

    @Before fun setUp() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        ownerField.set(CarPlayBackgroundSession, activity)
        menu = View(activity).apply { visibility = View.GONE }
        gestures = View(activity)
        setField("settingsMenu", menu)
        setField("gestureOverlay", gestures)
        setField("activeDisplaySize", size(1920, 990))
        setField("sessionBase", PixelSize(1920, 990))
        setField("sessionCanvas", PixelSize(1920, 990))
        setField("sessionLayout", call("barLayout"))
    }

    @After fun tearDown() {
        (getField("shuttingDown") as AtomicBoolean).set(true)
        (getField("teardownExecutor") as ExecutorService).shutdownNow()
        (getField("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        CarPlayBackgroundSession.clear()
    }

    @Test fun threeFingerMenuOpensTheOverlayWithoutResettingTheSession() {
        call("openSettingsMenu")

        assertTrue(getField("menuOpen") as Boolean)
        assertEquals(View.VISIBLE, menu.visibility)
        assertEquals(View.GONE, gestures.visibility)
        assertEquals(0, getField("restartGeneration"))
        assertFalse(getField("handshakeResetInProgress") as Boolean)
        assertEquals(PixelSize(1920, 990), getField("sessionCanvas"))
    }

    @Test fun displayChangesWhileTheMenuIsOpenAreOnlyRecorded() {
        call("openSettingsMenu")

        applySize(1920, 942)

        assertEquals(0, getField("restartGeneration"))
        assertEquals(size(1920, 942), getField("activeDisplaySize"))
    }

    @Test fun saveClosesTheOverlayAndRenegotiatesOnce() {
        call("openSettingsMenu")

        call("saveSettingsAndReconnect")

        assertFalse(getField("menuOpen") as Boolean)
        assertEquals(View.GONE, menu.visibility)
        assertEquals(View.VISIBLE, gestures.visibility)
        assertEquals(1, getField("restartGeneration"))
        assertTrue(getField("handshakeResetInProgress") as Boolean)
        assertEquals(null, getField("sessionCanvas"))
    }

    @Test fun savingConsumesALossRecordedWhileTheMenuWasOpen() {
        call("openSettingsMenu")
        setField("reconnectAfterSettings", true)

        call("saveSettingsAndReconnect")

        assertFalse(getField("reconnectAfterSettings") as Boolean)
        assertEquals(1, getField("restartGeneration"))
        assertTrue(ShadowLog.getLogsForTag("xcertplay-usb").none {
            it.msg.contains("reconnecting a session lost while settings were open")
        })
    }

    private fun call(name: String): Any? = activity.javaClass.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(activity)

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
