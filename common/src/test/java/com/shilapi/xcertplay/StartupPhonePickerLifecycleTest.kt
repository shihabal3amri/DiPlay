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
import com.shilapi.xcertplay.setup.SetupGuide
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
 * drive asks again. Off screen nothing starts: a question from a tap on Connect stops, and one from an
 * automatic start is asked again when DiPlay is back, unless CarPlay came on screen in between.
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
        // DiPlay opens on its home page, where Connect when DiPlay opens runs, not on the setup guide.
        SetupGuide.markSeen(context)

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
        StartupPhonePicker.coveredAutomaticQuestion = false
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

    /** How many CarPlay screens DiPlay started since the last call. Robolectric keeps one list for the app. */
    private fun carPlayStarts(): Int =
        generateSequence { shadowOf(activity).nextStartedActivity }.count { intent ->
            intent.component?.className in setOf(CarPlayHostActivity::class.java.name, SameTaskCarPlayHostActivity::class.java.name)
        }

    private fun startedCarPlay(): Boolean = carPlayStarts() > 0

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

    /** Connect when DiPlay opens asked, then the car's home screen, a camera or a call came over DiPlay. */
    private fun coverAutomaticQuestion() {
        connectWireless(automatic = true)
        assertNotNull(picker())
        controller.pause().stop()
        idle()
        assertNull(picker())
        assertEquals(StartupPhonePicker.Outcome.COVERED_WAITING, StartupPhonePicker.lastOutcome)
        assertTrue(StartupPhonePicker.coveredAutomaticQuestion)
    }

    private fun countdownText(seconds: Int) = activity.getString(
        com.shilapi.xcertplay.host.R.string.startup_picker_countdown, "Alex’s iPhone", seconds)

    @Test fun aCoveredAutomaticQuestionWaitsForDiPlayAndAsksAgain() {
        DiPlayPreferences.savePhone(context, alex.address, "Alex’s iPhone")
        coverAutomaticQuestion()
        // Nothing starts while covered, however long the camera or call stays in front.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertEquals(0, carPlayStarts())

        // Back on screen: the question returns as an automatic one, with a fresh countdown.
        controller.start().resume()
        idle()
        val again = picker()
        assertNotNull(again)
        assertTrue(ReflectionHelpers.getField<Boolean>(activity, "startupPickerAutomatic"))
        assertTrue(countdownText(8) in texts(again!!))
        assertFalse(StartupPhonePicker.coveredAutomaticQuestion)
        assertEquals(0, carPlayStarts())

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals(StartupPhonePicker.Outcome.COUNTDOWN, StartupPhonePicker.lastOutcome)
        assertEquals(1, carPlayStarts())
    }

    @Test fun aCoveredQuestionWithoutALastIphoneAlsoComesBack() {
        coverAutomaticQuestion()
        assertEquals(0, carPlayStarts())
        controller.start().resume()
        idle()
        val again = picker()
        assertNotNull(again)
        assertTrue(activity.getString(com.shilapi.xcertplay.host.R.string.startup_picker_tap) in texts(again!!))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertTrue(again.isShowing)
        assertNull(DiPlayPreferences.phoneAddress(context))
        assertEquals(0, carPlayStarts())
    }

    @Test fun aCoveredQuestionDoesNotComeBackAfterCarPlayOpenedAndClosed() {
        DiPlayPreferences.savePhone(context, alex.address, "Alex’s iPhone")
        coverAutomaticQuestion()
        // The iPhone was plugged in: CarPlay ran over USB, then the driver unplugged it.
        openCarPlayScreen()
        assertFalse(StartupPhonePicker.coveredAutomaticQuestion)
        screens.single().pause().stop().destroy()
        assertNull(CarPlayScreen.current())

        controller.start().resume()
        idle()
        assertNull(picker())
        assertEquals(0, carPlayStarts())
    }

    @Test fun aReusedCarPlayScreenDropsACoveredQuestion() {
        DiPlayPreferences.savePhone(context, alex.address, "Alex’s iPhone")
        // The CarPlay screen is open behind DiPlay, waiting for USB with no session.
        openCarPlayScreen()
        screens.single().pause().stop()
        coverAutomaticQuestion()
        // The iPhone is plugged in: the USB attach brings the same screen back instead of creating one.
        screens.single().start()
        assertFalse(StartupPhonePicker.coveredAutomaticQuestion)
        screens.single().stop()

        controller.start().resume()
        idle()
        assertNull(picker())
        assertEquals(0, carPlayStarts())
    }

    @Test fun aStoredSessionDropsACoveredQuestion() {
        coverAutomaticQuestion()
        CarPlayBackgroundSession.store(org.mockito.Mockito.mock(com.shilapi.xcertplay.orchestration.CarPlayController::class.java),
            org.mockito.Mockito.mock(com.shilapi.xcertplay.media.AndroidMediaSink::class.java), 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, android.view.Surface.ROTATION_0, false, false, 800, 480)) { it() }
        assertFalse(StartupPhonePicker.coveredAutomaticQuestion)
    }

    @Test fun aReturnWhileCarPlayRunsDoesNotAsk() {
        DiPlayPreferences.savePhone(context, alex.address, "Alex’s iPhone")
        coverAutomaticQuestion()
        // The driver opens DiPlay from the launcher while a session runs in the background.
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", { done: () -> Unit -> done() })
        controller.start().resume()
        idle()
        assertNull(picker())
        assertEquals(0, carPlayStarts())
    }

    @Test fun aReturnAfterTheSwitchWasTurnedOffStartsNothing() {
        DiPlayPreferences.savePhone(context, alex.address, "Alex’s iPhone")
        coverAutomaticQuestion()
        DiPlayPreferences.saveAskWhichIphone(context, false)
        controller.start().resume()
        idle()
        assertNull(picker())
        assertEquals(0, carPlayStarts())
        assertFalse(StartupPhonePicker.coveredAutomaticQuestion)
    }

    @Test fun aReturnToASettingsShortcutDoesNotAsk() {
        DiPlayPreferences.savePhone(context, alex.address, "Alex’s iPhone")
        coverAutomaticQuestion()
        controller.start()
        controller.newIntent(android.content.Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"))
        controller.resume()
        idle()
        assertNull(picker())
        assertEquals(0, carPlayStarts())
    }

    @Test fun aPickInChooseIphoneDropsACoveredQuestion() {
        DiPlayPreferences.savePhone(context, alex.address, "Alex’s iPhone")
        coverAutomaticQuestion()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "choosePhone")
        idle()
        shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(1)
        idle()
        assertFalse(StartupPhonePicker.coveredAutomaticQuestion)
        controller.start().resume()
        idle()
        assertNull(picker())
        assertEquals(sam.address, DiPlayPreferences.phoneAddress(context))
        assertEquals(0, carPlayStarts())
    }

    @Test fun aBluetoothStartOnReturnStartsCarPlayOnce() {
        DiPlayPreferences.savePhone(context, alex.address, "Alex’s iPhone")
        DiPlayPreferences.saveConnectOnPhoneBluetooth(context, true)
        coverAutomaticQuestion()
        // Alex's iPhone joins the car's Bluetooth and its start arrives as DiPlay comes back.
        shadowOf(alex).setConnected(true)
        controller.start()
        controller.newIntent(android.content.Intent(context, DiPlayActivity::class.java)
            .putExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT, PhoneBluetoothReceiver.launchToken))
        controller.resume()
        idle()
        assertEquals(StartupPhonePicker.Outcome.CONNECTED_ONLY_IPHONE_ON_BLUETOOTH, StartupPhonePicker.lastOutcome)
        assertEquals(1, carPlayStarts())
        assertNull(picker())
    }

    @Test fun connectWhenDiPlayOpensOnReturnStartsCarPlayOnce() {
        DiPlayPreferences.savePhone(context, alex.address, "Alex’s iPhone")
        coverAutomaticQuestion()
        // DiPlay is recreated in the background, so its next onResume runs Connect when DiPlay opens.
        DiPlayPreferences.saveAutoConnect(context, true)
        DiPlayPreferences.saveDefaultConnectionMode(context, DefaultConnectionMode.WIRELESS)
        shadowOf(alex).setConnected(true)
        controller.recreate()
        activity = controller.get()
        controller.start().resume()
        idle()
        assertEquals(1, carPlayStarts())
        assertNull(picker())
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

    /** USB_DEVICE_ATTACHED opened the CarPlay screen; it may still wait for consent, with no session yet. */
    private fun openCarPlayScreen() {
        screens += Robolectric.buildActivity(CarPlayHostActivity::class.java).setup()
        assertSame(screens.single().get(), CarPlayScreen.current())
        assertFalse(CarPlayBackgroundSession.hasSession())
    }

    @Test fun aCountdownBesideAnOpenCarPlayScreenStartsNothing() {
        DiPlayPreferences.savePhone(context, alex.address, "Alex’s iPhone")
        connectWireless(automatic = true)
        val question = picker()
        assertNotNull(question)
        openCarPlayScreen()

        // DiPlay in a split or card window is not stopped: the countdown ends without starting anything.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertFalse(question!!.isShowing)
        assertNull(picker())
        assertEquals(StartupPhonePicker.Outcome.CARPLAY_ALREADY_OPEN, StartupPhonePicker.lastOutcome)
        assertFalse(startedCarPlay())
    }

    @Test fun aCarPlayScreenThatCoversTheQuestionIsLeftAlone() {
        DiPlayPreferences.savePhone(context, alex.address, "Alex’s iPhone")
        connectWireless(automatic = true)
        assertNotNull(picker())
        openCarPlayScreen()

        controller.pause().stop()
        idle()
        assertNull(picker())
        assertEquals(StartupPhonePicker.Outcome.CARPLAY_ALREADY_OPEN, StartupPhonePicker.lastOutcome)
        assertFalse(startedCarPlay())
    }

    @Test fun noQuestionWhileACarPlayScreenIsOpen() {
        DiPlayPreferences.savePhone(context, alex.address, "Alex’s iPhone")
        openCarPlayScreen()
        connectWireless(automatic = true)
        assertNull(picker())
        assertNull(StartupPhonePicker.lastOutcome)
    }

    @Test fun anAnsweredQuestionIsNotTreatedAsCoveredWhileItsDismissIsPending() {
        DiPlayPreferences.savePhone(context, alex.address, "Alex’s iPhone")
        connectWireless(automatic = true)
        val question = picker()!!
        // The driver taps Sam's iPhone; DiPlay stops before the dialog's dismiss listener has run.
        allViews(question.window!!.decorView).filterIsInstance<TextView>()
            .first { it.text.toString() == "Sam’s iPhone" }.performClick()
        assertSame(question, picker())
        assertFalse(question.isShowing)
        assertTrue(startedCarPlay())
        controller.pause().stop()
        idle()
        assertEquals(StartupPhonePicker.Outcome.TAPPED, StartupPhonePicker.lastOutcome)
        assertEquals(sam.address, DiPlayPreferences.phoneAddress(context))
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
        assertFalse(StartupPhonePicker.coveredAutomaticQuestion)
        assertFalse(startedCarPlay())
    }

    @Test fun finishingDropsAQuestionThatWasAlreadyCovered() {
        coverAutomaticQuestion()
        activity.finish()
        controller.destroy()
        assertFalse(StartupPhonePicker.coveredAutomaticQuestion)
    }

    private fun recreate(): DiPlayActivity {
        val old = activity
        controller.recreate()
        idle()
        activity = controller.get()
        assertFalse(old === activity)
        return activity
    }

    @Test fun aRecreatedDiPlayAsksAgainInsteadOfStarting() {
        DiPlayPreferences.savePhone(context, alex.address, "Alex’s iPhone")
        // The Bluetooth start: its intent extra is used up, so only the saved state brings the question back.
        connectWireless(automatic = true)
        assertNotNull(picker())

        val recreated = recreate()
        assertEquals(0, carPlayStarts())
        val again = picker(recreated)
        assertNotNull(again)
        assertTrue(again!!.isShowing)
        assertTrue(ReflectionHelpers.getField<Boolean>(recreated, "startupPickerAutomatic"))
        assertEquals(StartupPhonePicker.Outcome.ASKED, StartupPhonePicker.lastOutcome)
    }

    @Test fun aRecreatedDiPlayStartsCarPlayOnceWithConnectWhenDiPlayOpens() {
        DiPlayPreferences.savePhone(context, alex.address, "Alex’s iPhone")
        DiPlayPreferences.saveAutoConnect(context, true)
        DiPlayPreferences.saveDefaultConnectionMode(context, DefaultConnectionMode.WIRELESS)
        connectWireless(automatic = true)
        assertNotNull(picker())
        // Alex's iPhone joins the car's Bluetooth while DiPlay is recreated: the new decision connects it.
        shadowOf(alex).setConnected(true)

        recreate()
        assertEquals(1, carPlayStarts())
        assertEquals(StartupPhonePicker.Outcome.CONNECTED_ONLY_IPHONE_ON_BLUETOOTH, StartupPhonePicker.lastOutcome)
    }

    @Test fun aRecreatedDiPlayKeepsTheWirelessQuestionWhenItsOwnStartIsUsb() {
        DiPlayPreferences.savePhone(context, alex.address, "Alex’s iPhone")
        DiPlayPreferences.saveAutoConnect(context, true)
        DiPlayPreferences.saveDefaultConnectionMode(context, DefaultConnectionMode.USB)
        AirPlayPersistence.saveWirelessEnabled(context, true)
        // The Bluetooth start asked; Connect when DiPlay opens would start USB in a new instance.
        connectWireless(automatic = true)

        val recreated = recreate()
        assertNotNull(picker(recreated))
        assertEquals(0, carPlayStarts())
        assertTrue(AirPlayPersistence.loadWirelessEnabled(context))
    }

    @Test fun beforeAndroid9TheQuestionIsSavedWhileStillOnScreen() {
        // Android 7–8 save the state before onStop, while the question is still up.
        DiPlayPreferences.savePhone(context, alex.address, "Alex’s iPhone")
        connectWireless(automatic = true)
        assertTrue(picker()!!.isShowing)
        val state = android.os.Bundle()
        ReflectionHelpers.setField(android.app.Activity::class.java, activity, "mChangingConfigurations", true)
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "onSaveInstanceState",
                ClassParameter.from(android.os.Bundle::class.java, state))
        } finally {
            ReflectionHelpers.setField(android.app.Activity::class.java, activity, "mChangingConfigurations", false)
        }
        assertTrue(state.getBoolean("startup_picker_ask_again_automatic"))

        // A question from a tap on Connect is not saved.
        connectWireless(automatic = false)
        val tapped = android.os.Bundle()
        ReflectionHelpers.setField(android.app.Activity::class.java, activity, "mChangingConfigurations", true)
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "onSaveInstanceState",
                ClassParameter.from(android.os.Bundle::class.java, tapped))
        } finally {
            ReflectionHelpers.setField(android.app.Activity::class.java, activity, "mChangingConfigurations", false)
        }
        assertFalse(tapped.getBoolean("startup_picker_ask_again_automatic"))
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
