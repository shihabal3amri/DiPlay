package com.shilapi.xcertplay

import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Looper
import android.os.ParcelUuid
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * Head units keep DiPlay in memory between drives: each drive asks again. Off screen, a question
 * from a tap on Connect stops, and one from an automatic start connects the last iPhone used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class StartupPhonePickerLifecycleTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var controller: ActivityController<DiPlayActivity>
    private lateinit var activity: DiPlayActivity
    private lateinit var alex: BluetoothDevice
    private lateinit var sam: BluetoothDevice

    @Before fun setUp() {
        clearSettings()
        controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        activity = controller.get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        controller.setup()
        val adapter = activity.getSystemService(BluetoothManager::class.java).adapter
        fun iphone(address: String, name: String): BluetoothDevice = adapter.getRemoteDevice(address).also {
            shadowOf(it).setName(name)
            shadowOf(it).setUuids(arrayOf(ParcelUuid.fromString(StartupPhonePicker.IAP2_IPHONE_UUID)))
        }
        alex = iphone("AA:00:00:00:00:01", "Alex’s iPhone")
        sam = iphone("AA:00:00:00:00:02", "Sam’s iPhone")
        shadowOf(adapter).setEnabled(true)
        shadowOf(adapter).setBondedDevices(setOf(alex, sam))
    }

    @After fun tearDown() {
        runCatching { controller.pause().stop() }
        controller.destroy()
        StartupPhonePicker.lastOutcome = null
        StartupPhonePicker.connectionStateReadable = null
        clearSettings()
    }

    private fun clearSettings() {
        for (name in listOf("diplay", "xcertplay_airplay", "diplay_car_hotspot")) {
            context.getSharedPreferences(name, 0).edit().clear().commit()
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun picker(): AlertDialog? = ReflectionHelpers.getField(activity, "startupPickerDialog")

    private fun showPicker(lastUsed: String?, automatic: Boolean) {
        val ask = StartupPhonePicker.decide(listOf(alex, sam).map {
            StartupPhonePicker.Candidate(it.address, it.name, listOf(StartupPhonePicker.IAP2_IPHONE_UUID), isPhone = true, connected = false)
        }, lastUsed) as StartupPhonePicker.Decision.Ask
        DiPlayActivity::class.java.getDeclaredMethod(
            "showStartupPhonePicker", StartupPhonePicker.Decision.Ask::class.java, List::class.java, Boolean::class.java,
        ).apply { isAccessible = true }.invoke(activity, ask, listOf(alex, sam), automatic)
        idle()
    }

    private fun connectWireless() {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "connect", ClassParameter.from(Boolean::class.java, true))
        idle()
    }

    private fun allViews(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(allViews(view.getChildAt(i)))
    }

    @Test fun showingDiPlayAgainAsksAgainAndLeavingTheScreenStopsATappedQuestion() {
        // The next drive: DiPlay comes back on screen and the earlier answer no longer holds.
        ReflectionHelpers.setField(activity, "iphoneQuestionAnswered", true)
        controller.pause().stop().start().resume()
        assertFalse(ReflectionHelpers.getField<Boolean>(activity, "iphoneQuestionAnswered"))

        showPicker(lastUsed = alex.address, automatic = false)
        assertNotNull(picker())

        // Leaving the screen closes the question without answering it.
        controller.pause().stop()
        idle()
        assertNull(picker())
        assertFalse(ReflectionHelpers.getField<Boolean>(activity, "iphoneQuestionAnswered"))
        assertEquals(StartupPhonePicker.Outcome.COVERED_DROPPED, StartupPhonePicker.lastOutcome)

        // Past the countdown, nothing was chosen or connected in the background.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertNull(DiPlayPreferences.phoneAddress(activity))
    }

    @Test fun anAutomaticStartThatGetsCoveredConnectsTheLastIphoneUsed() {
        // Connect when DiPlay opens showed the question, then the car's home screen came over DiPlay.
        showPicker(lastUsed = alex.address, automatic = true)
        assertNotNull(picker())
        controller.pause().stop()
        idle()

        assertNull(picker())
        assertEquals(alex.address, DiPlayPreferences.phoneAddress(activity))
        assertTrue(ReflectionHelpers.getField<Boolean>(activity, "iphoneQuestionAnswered"))
        assertEquals(StartupPhonePicker.Outcome.COVERED_CONNECTED_LAST_USED, StartupPhonePicker.lastOutcome)
    }

    @Test fun anAutomaticStartWithoutALastIphoneConnectsNothingWhenCovered() {
        showPicker(lastUsed = null, automatic = true)
        controller.pause().stop()
        idle()
        assertNull(picker())
        assertNull(DiPlayPreferences.phoneAddress(activity))
        assertEquals(StartupPhonePicker.Outcome.COVERED_DROPPED, StartupPhonePicker.lastOutcome)
    }

    @Test fun decidingAgainReplacesTheQuestionOnScreen() {
        // Robolectric has no MFi setup; a real head unit gets this far.
        ReflectionHelpers.setField(activity, "setupError", null)
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.EXISTING_WIFI)
        DiPlayPreferences.saveAskWhichIphone(context, true)
        DiPlayPreferences.savePhone(context, alex.address, "Alex’s iPhone")

        connectWireless()
        val first = picker()
        assertNotNull(first)
        assertEquals(StartupPhonePicker.Outcome.ASKED, StartupPhonePicker.lastOutcome)

        // Connect when the selected iPhone reconnects calls connect(true) again while the question is up.
        connectWireless()
        val second = picker()
        assertNotNull(second)
        assertFalse(first!!.isShowing)
        assertTrue(second!!.isShowing)

        // The first question's buttons no longer act: they would stop a session and reconnect.
        allViews(first.window!!.decorView).filterIsInstance<TextView>()
            .first { it.text.toString() == "Sam’s iPhone" }.performClick()
        idle()
        assertSame(second, picker())
        assertEquals(alex.address, DiPlayPreferences.phoneAddress(context))

        // Only the question on screen counts down, and it connects the last iPhone used.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertFalse(second.isShowing)
        assertNull(picker())
        assertEquals(StartupPhonePicker.Outcome.COUNTDOWN, StartupPhonePicker.lastOutcome)
        assertEquals(alex.address, DiPlayPreferences.phoneAddress(context))
    }

    @Test fun theQuestionIsOffByDefault() {
        ReflectionHelpers.setField(activity, "setupError", null)
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.EXISTING_WIFI)
        DiPlayPreferences.savePhone(context, alex.address, "Alex’s iPhone")
        connectWireless()
        assertNull(picker())
        assertNull(StartupPhonePicker.lastOutcome)
    }
}
