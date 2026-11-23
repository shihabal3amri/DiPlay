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
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * The start-up question across DiPlay's lifecycle. Head units keep DiPlay in memory between drives, so each
 * drive asks again. Off screen, a question from a tap on Connect stops; one from an automatic start starts
 * the last iPhone used, unless CarPlay already opened, DiPlay is closing, or it is being recreated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class StartupPhonePickerLifecycleTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var controller: ActivityController<DiPlayActivity>
    private lateinit var activity: DiPlayActivity
    private lateinit var alex: BluetoothDevice
    private lateinit var sam: BluetoothDevice
    private val screens = mutableListOf<ActivityController<CarPlayHostActivity>>()
    private var bootstrapReady = false

    @Before fun setUp() {
        clearSettings()
        // Robolectric has no MFi setup; a real head unit gets this far.
        bootstrapReady = ReflectionHelpers.getField(DiPlayBootstrap, "ready")
        ReflectionHelpers.setField(DiPlayBootstrap, "ready", true)
        CarPlayBackgroundSession.clear()
        ReflectionHelpers.setField(CarPlayScreen, "open", null)
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.EXISTING_WIFI)
        DiPlayPreferences.saveAskWhichIphone(context, true)

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
        screens.forEach { if (!it.get().isDestroyed) runCatching { it.pause().stop().destroy() } }
        runCatching { controller.pause().stop() }
        runCatching { controller.destroy() }
        CarPlayBackgroundSession.clear()
        ReflectionHelpers.setField(CarPlayScreen, "open", null)
        ReflectionHelpers.setField(DiPlayBootstrap, "ready", bootstrapReady)
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

    private fun picker(on: DiPlayActivity = activity): AlertDialog? = ReflectionHelpers.getField(on, "startupPickerDialog")

    /** The path Connect, Connect when DiPlay opens and Connect when the selected iPhone reconnects all take. */
    private fun connectWireless(automatic: Boolean) {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "connect",
            ClassParameter.from(Boolean::class.java, true), ClassParameter.from(Boolean::class.java, automatic))
        idle()
    }

    /** Whether DiPlay started a CarPlay screen since the last call. */
    private fun startedCarPlay(from: DiPlayActivity = activity): Boolean =
        generateSequence { shadowOf(from).nextStartedActivity }.any { intent ->
            intent.component?.className in setOf(CarPlayHostActivity::class.java.name, SameTaskCarPlayHostActivity::class.java.name)
        }

    private fun texts(dialog: AlertDialog): List<String> =
        allViews(dialog.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()

    private fun allViews(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(allViews(view.getChildAt(i)))
    }

    @Test fun theQuestionIsOffByDefault() {
        DiPlayPreferences.saveAskWhichIphone(context, false)
        DiPlayPreferences.savePhone(context, alex.address, "Alex’s iPhone")
        connectWireless(automatic = true)
        assertNull(picker())
        assertNull(StartupPhonePicker.lastOutcome)
        assertTrue(startedCarPlay())
    }

    @Test fun showingDiPlayAgainAsksAgainAndLeavingTheScreenStopsATappedQuestion() {
        // The next drive: DiPlay comes back on screen and the earlier answer no longer holds.
        ReflectionHelpers.setField(activity, "iphoneQuestionAnswered", true)
        controller.pause().stop().start().resume()
        assertFalse(ReflectionHelpers.getField<Boolean>(activity, "iphoneQuestionAnswered"))

        DiPlayPreferences.savePhone(context, alex.address, "Alex’s iPhone")
        connectWireless(automatic = false)
        assertNotNull(picker())

        // Leaving the screen closes the question without answering it.
        controller.pause().stop()
        idle()
        assertNull(picker())
        assertFalse(ReflectionHelpers.getField<Boolean>(activity, "iphoneQuestionAnswered"))
        assertEquals(StartupPhonePicker.Outcome.COVERED_DROPPED, StartupPhonePicker.lastOutcome)

        // Past the countdown, nothing was started in the background.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertFalse(startedCarPlay())
    }

    @Test fun anAutomaticStartThatGetsCoveredStartsCarPlayWithTheLastIphoneUsed() {
        // Connect when DiPlay opens asked, then the car's home screen came over DiPlay.
        DiPlayPreferences.savePhone(context, alex.address, "Alex’s iPhone")
        connectWireless(automatic = true)
        assertNotNull(picker())
        assertFalse(startedCarPlay())

        controller.pause().stop()
        idle()
        assertNull(picker())
        assertEquals(StartupPhonePicker.Outcome.COVERED_STARTED_LAST_USED, StartupPhonePicker.lastOutcome)
        assertEquals(alex.address, DiPlayPreferences.phoneAddress(context))
        assertTrue(startedCarPlay())
    }

    @Test fun anAutomaticStartWithoutALastIphoneStartsNothingWhenCovered() {
        connectWireless(automatic = true)
        assertNotNull(picker())
        controller.pause().stop()
        idle()
        assertNull(picker())
        assertNull(DiPlayPreferences.phoneAddress(context))
        assertEquals(StartupPhonePicker.Outcome.COVERED_DROPPED, StartupPhonePicker.lastOutcome)
        assertFalse(startedCarPlay())
    }

    @Test fun aSessionThatStartedDuringTheQuestionIsLeftAlone() {
        DiPlayPreferences.savePhone(context, alex.address, "Alex’s iPhone")
        connectWireless(automatic = true)
        assertNotNull(picker())
        // The iPhone was plugged in: a wired session runs when DiPlay leaves the screen.
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", { done: () -> Unit -> done() })
        AirPlayPersistence.saveWirelessEnabled(context, false)

        controller.pause().stop()
        idle()
        assertEquals(StartupPhonePicker.Outcome.CARPLAY_ALREADY_OPEN, StartupPhonePicker.lastOutcome)
        assertFalse(startedCarPlay())
        assertFalse(AirPlayPersistence.loadWirelessEnabled(context))
    }

    @Test fun aCarPlayScreenThatOpenedDuringTheQuestionIsLeftAlone() {
        DiPlayPreferences.savePhone(context, alex.address, "Alex’s iPhone")
        connectWireless(automatic = true)
        val question = picker()
        assertNotNull(question)
        // USB_DEVICE_ATTACHED opened the CarPlay screen; it may still wait for consent, with no session yet.
        screens += Robolectric.buildActivity(CarPlayHostActivity::class.java).setup()
        assertSame(screens.single().get(), CarPlayScreen.current())

        // DiPlay in a split or card window is not stopped: the countdown ends without starting anything.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertFalse(question!!.isShowing)
        assertEquals(StartupPhonePicker.Outcome.CARPLAY_ALREADY_OPEN, StartupPhonePicker.lastOutcome)
        assertFalse(startedCarPlay())

        // Covered by that screen instead, the question also starts nothing.
        connectWireless(automatic = true)
        assertNotNull(picker())
        controller.pause().stop()
        idle()
        assertEquals(StartupPhonePicker.Outcome.CARPLAY_ALREADY_OPEN, StartupPhonePicker.lastOutcome)
        assertFalse(startedCarPlay())
    }

    @Test fun closingDiPlayStartsNothing() {
        DiPlayPreferences.savePhone(context, alex.address, "Alex’s iPhone")
        connectWireless(automatic = true)
        activity.finish()
        controller.pause().stop()
        idle()
        assertNull(picker())
        assertEquals(StartupPhonePicker.Outcome.COVERED_DROPPED, StartupPhonePicker.lastOutcome)
        assertFalse(startedCarPlay())
    }

    @Test fun aRecreatedDiPlayAsksAgainInsteadOfStarting() {
        DiPlayPreferences.savePhone(context, alex.address, "Alex’s iPhone")
        // The Bluetooth start: its intent extra is used up, so only the saved state brings the question back.
        connectWireless(automatic = true)
        val old = activity
        assertNotNull(picker(old))

        controller.recreate()
        idle()
        activity = controller.get()
        assertFalse(old === activity)
        assertFalse(startedCarPlay(old))
        assertFalse(startedCarPlay(activity))
        val again = picker(activity)
        assertNotNull(again)
        assertTrue(again!!.isShowing)
        assertTrue(ReflectionHelpers.getField<Boolean>(activity, "startupPickerAutomatic"))
        assertEquals(StartupPhonePicker.Outcome.ASKED, StartupPhonePicker.lastOutcome)
    }

    @Test fun anIphoneChosenInChooseIphoneIsNotOverridden() {
        // Both drivers are in the car; only Alex's iPhone is on the car's Bluetooth, and Sam picks theirs.
        shadowOf(alex).setConnected(true)
        DiPlayPreferences.savePhone(context, alex.address, "Alex’s iPhone")
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "choosePhone")
        idle()
        shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(1)
        idle()
        assertEquals(sam.address, DiPlayPreferences.phoneAddress(context))

        connectWireless(automatic = false)
        assertNull(picker())
        assertEquals(sam.address, DiPlayPreferences.phoneAddress(context))
        assertTrue(startedCarPlay())
    }

    @Test fun iphonesWithTheSameNameShowTheEndOfTheirAddress() {
        shadowOf(alex).setName("iPhone")
        shadowOf(sam).setName("iPhone")
        DiPlayPreferences.savePhone(context, alex.address, "iPhone")
        connectWireless(automatic = false)
        val texts = texts(picker()!!)
        assertTrue(texts.toString(), "iPhone · 00:01" in texts)
        assertTrue(texts.toString(), "iPhone · 00:02" in texts)
        assertTrue(texts.toString(), activity.getString(com.shilapi.xcertplay.host.R.string.startup_picker_countdown, "iPhone · 00:01", 8) in texts)
    }

    @Test fun decidingAgainReplacesTheQuestionOnScreen() {
        DiPlayPreferences.savePhone(context, alex.address, "Alex’s iPhone")
        connectWireless(automatic = false)
        val first = picker()
        assertNotNull(first)
        assertEquals(StartupPhonePicker.Outcome.ASKED, StartupPhonePicker.lastOutcome)

        // Connect when the selected iPhone reconnects calls connect(true) again while the question is up.
        connectWireless(automatic = true)
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
        assertFalse(startedCarPlay())

        // Only the question on screen counts down, and it starts the last iPhone used.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertFalse(second.isShowing)
        assertNull(picker())
        assertEquals(StartupPhonePicker.Outcome.COUNTDOWN, StartupPhonePicker.lastOutcome)
        assertEquals(alex.address, DiPlayPreferences.phoneAddress(context))
        assertTrue(startedCarPlay())
    }
}
