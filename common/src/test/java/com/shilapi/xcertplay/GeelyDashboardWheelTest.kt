package com.shilapi.xcertplay

import android.app.Activity
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Looper
import com.geely.lib.oneosapi.input.IInputListener
import com.geely.lib.oneosapi.input.IInputManager
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.ultra.UltraVehicleSource
import com.shilapi.xcertplay.ultra.VehicleReading
import com.shilapi.xcertplay.ultra.VehicleSignal
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class GeelyDashboardWheelTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var cancel: (() -> Unit)? = null
    private val originalMenuFactory = GeelyWheelKeys.menuMonitorFactory
    private class Menu : Fx11MenuMonitor {
        var current = Fx11WheelMode.UNKNOWN
        var closed = false
        override fun start() = Unit
        override fun mode() = current
        override fun diagnosticReport() = "Test DIM menu mode=$current"
        override fun close() { closed = true; current = Fx11WheelMode.UNKNOWN }
    }
    private lateinit var menu: Menu
    private class Input(private val failRegistration: Boolean = false) : IInputManager.Stub() {
        val registrations = mutableListOf<Pair<IInputListener, List<Int>>>()
        val released = mutableListOf<IInputListener>()
        var interceptions = 0
        override fun interceptKeyCode(keyCode: Int, packageName: String): Boolean { interceptions++; return false }
        override fun releaseKeyCode(keyCode: Int, packageName: String) = false
        override fun registerListener(listener: IInputListener, packageName: String, keyCodes: IntArray) {
            registrations.add(listener to keyCodes.toList())
            check(!failRegistration) { "Test registration failure" }
        }
        override fun unregisterListener(listener: IInputListener, packageName: String) { released.add(listener) }
        override fun getControlIndex() = 0
        val listener get() = registrations.last().first
    }
    private fun connect(input: Input = Input()): Input {
        val connection = ReflectionHelpers.getField<ServiceConnection>(GeelyWheelKeys, "connection")
        connection.onServiceConnected(ComponentName("com.geely.inputservice", "InputService"), input.asBinder())
        assertTrue(input.registrations.isNotEmpty())
        return input
    }
    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }

    @Before fun prepare() {
        GeelyWheelKeys.stop()
        GeelyWheelKeys.stopDashboard()
        menu = Menu()
        GeelyWheelKeys.menuMonitorFactory = { menu }
        BoyueLSettings.select(app, true)
        BoyueLSettings.setWheel(app, true)
        app.getSharedPreferences("native_dashboard",0).edit().remove("mode_key").remove("page_key").apply()
        NativeDashboardSettings.setStyle(app, DashboardStyle.MIDNIGHT)
        NativeDashboardSettings.setMode(app, DashboardMode.MUSIC)
        NativeDashboardSettings.setPageKey(app, 0)
    }

    @After fun cleanup() {
        cancel?.invoke()
        GeelyWheelKeys.stop()
        GeelyWheelKeys.stopDashboard()
        GeelyWheelKeys.menuMonitorFactory = originalMenuFactory
    }

    @Test fun removedMenuAndVoiceBindingsCannotDispatchHiddenDashboardActions() {
        for (key in BoyueLSettings.originalOnlyKeys) for (longPress in listOf(false, true))
            BoyueLSettings.setAction(app, key, BoyueWheelAction.TOGGLE_DASHBOARD, longPress)
        GeelyWheelKeys.startDashboard(app)
        val input = connect()
        shadowOf(app).clearStartedServices()
        for (key in BoyueLSettings.originalOnlyKeys) {
            input.listener.onShortClick(key, 0); idle()
            input.listener.onLongPressTriggered(key, 0); idle()
        }
        assertNull(shadowOf(app).nextStartedService)
        assertEquals(DashboardMode.MUSIC, NativeDashboardSettings.mode(app))
        assertEquals(0, input.interceptions)
        assertTrue(BoyueLSettings.editableKeys(app).none { it in BoyueLSettings.originalOnlyKeys })
    }

    @Test fun configurableShortAndLongGesturesWorkOfflineWithoutDoubleDispatch() {
        val mode = NativeDashboardSettings.modeKey(app)
        val play = GeelyWheelKeyCodes.KEY_PLAY_PAUSE
        BoyueLSettings.setAction(app, mode, BoyueWheelAction.NONE)
        BoyueLSettings.setAction(app, mode, BoyueWheelAction.CYCLE_DASHBOARD, true)
        BoyueLSettings.setAction(app, play, BoyueWheelAction.TOGGLE_DASHBOARD)
        GeelyWheelKeys.startDashboard(app)
        val input = connect()
        shadowOf(app).clearStartedServices()
        input.listener.onShortClick(mode, 0); idle()
        assertNull(shadowOf(app).nextStartedService)
        input.listener.onHoldingPressStarted(mode, 0)
        input.listener.onLongPressTriggered(mode, 0); idle()
        assertEquals(DashboardMode.NAVIGATION, NativeDashboardSettings.mode(app))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
        input.listener.onLongPressTriggered(mode, 0)
        input.listener.onHoldingPressStopped(mode, 0)
        input.listener.onShortClick(mode, 0); idle()
        assertEquals(DashboardMode.NAVIGATION, NativeDashboardSettings.mode(app))
        input.listener.onShortClick(play, 0); idle()
        assertEquals("com.shilapi.xcertplay.TOGGLE_NATIVE_DASHBOARD_PROJECTION", shadowOf(app).nextStartedService?.action)
        assertEquals(0, input.interceptions)
    }

    @Test fun longPageBindingIsRegisteredWithBluetoothAndDoesNotFallThroughToMenuShortAction() {
        val play = GeelyWheelKeyCodes.KEY_PLAY_PAUSE
        val next = GeelyWheelKeyCodes.KEY_NEXT
        AirPlayPersistence.saveCarBluetoothAudio(app, true)
        BoyueLSettings.setAction(app, play, BoyueWheelAction.CYCLE_DASHBOARD, true)
        BoyueLSettings.setAction(app, next, BoyueWheelAction.CYCLE_DASHBOARD, true)
        GeelyWheelKeys.startDashboard(app)
        val input = connect()
        assertTrue(play in input.registrations.last().second)
        input.listener.onLongPressTriggered(play, 0); idle()
        assertEquals(DashboardMode.NAVIGATION, NativeDashboardSettings.mode(app))
        menu.current = Fx11WheelMode.DASHBOARD
        input.listener.onLongPressTriggered(next, 0)
        input.listener.onShortClick(next, 0); idle()
        assertEquals(DashboardMode.TIRES, NativeDashboardSettings.mode(app))
    }

    @Test fun confirmedPageKeyCyclesOnceWhileModeDoesNotCycleThePage() {
        NativeDashboardSettings.setPageKey(app, 200600)
        GeelyWheelKeys.startDashboard(app)
        val input = connect()
        assertEquals(listOf(200110, 200600, 200087, 200088), input.registrations.last().second)
        input.listener.onShortClick(200110, 0)
        idle()
        assertEquals(DashboardMode.MUSIC, NativeDashboardSettings.mode(app))
        input.listener.onKeyCodeEvent(200600, 0, 0)
        input.listener.onKeyCodeEvent(200600, 1, 0)
        input.listener.onHoldingPressStarted(200600, 0)
        input.listener.onLongPressTriggered(200600, 0)
        input.listener.onHoldingPressStopped(200600, 0)
        input.listener.onDoubleClick(200600, 0)
        idle()
        assertEquals(DashboardMode.MUSIC, NativeDashboardSettings.mode(app))
        input.listener.onShortClick(200600, 0)
        input.listener.onShortClick(200600, 0)
        idle()
        assertEquals(DashboardMode.NAVIGATION, NativeDashboardSettings.mode(app))
        assertEquals(0, input.interceptions)
    }

    @Test fun mediaDisconnectPreservesBothDashboardKeysWithoutIphone() {
        NativeDashboardSettings.setPageKey(app, 200600)
        GeelyWheelKeys.startDashboard(app)
        GeelyWheelKeys.start(app)
        GeelyWheelKeys.stop()
        val input = connect()
        assertEquals(listOf(200110, 200600, 200087, 200088), input.registrations.last().second)
        input.listener.onShortClick(200600, 0)
        idle()
        assertEquals(DashboardMode.NAVIGATION, NativeDashboardSettings.mode(app))
    }

    @Test fun calibratedModeShortClickOnlyProjectsAndNeverPagesOrSendsMedia() {
        val controller=mock(CarPlayController::class.java)
        val previous=ReflectionHelpers.getField<CarPlayController?>(CarPlayMediaKeys,"controller")
        ReflectionHelpers.setField(CarPlayMediaKeys,"controller",controller)
        try {
            NativeDashboardSettings.setModeKey(app,200400)
            NativeDashboardSettings.setPageKey(app,200082)
            GeelyWheelKeys.startDashboard(app)
            GeelyWheelKeys.start(app)
            val input=connect()
            assertTrue(200400 in input.registrations.last().second)
            assertFalse(200110 in input.registrations.last().second)
            shadowOf(app).clearStartedServices()
            input.listener.onKeyCodeEvent(200400,0,0)
            input.listener.onKeyCodeEvent(200400,1,0)
            input.listener.onLongPressTriggered(200400,0)
            idle()
            assertNull(shadowOf(app).nextStartedService)
            input.listener.onShortClick(200400,0)
            input.listener.onShortClick(200400,0)
            idle()
            assertEquals("com.shilapi.xcertplay.TOGGLE_NATIVE_DASHBOARD_PROJECTION",shadowOf(app).nextStartedService?.action)
            assertNull(shadowOf(app).nextStartedService)
            assertEquals(DashboardMode.MUSIC,NativeDashboardSettings.mode(app))
            verifyNoInteractions(controller)
            assertTrue(GeelyWheelKeys.diagnosticReport().contains("modeKey=200400 pageKey=200082"))
        } finally { ReflectionHelpers.setField(CarPlayMediaKeys,"controller",previous) }
    }

    @Test fun calibratedModeCannotBeLearnedAsThePageKey() {
        NativeDashboardSettings.setModeKey(app,200400)
        val learned=mutableListOf<Int>()
        cancel=GeelyWheelKeys.learnDashboardPageKey(app) { learned.add(it) }
        val input=connect()
        assertFalse(200400 in input.registrations.last().second)
        input.listener.onShortClick(200400,0) // A stale or misbehaving OEM listener still cannot learn MODE.
        idle()
        assertTrue(learned.isEmpty())
        input.listener.onShortClick(200082,0)
        idle()
        assertEquals(listOf(200082),learned)
        assertEquals(0,NativeDashboardSettings.pageKey(app))
    }

    @Test fun calibrationRejectsConflictsAndInvalidCodesWithoutChangingSavedKeys() {
        assertEquals(200110,NativeDashboardSettings.modeKey(app))
        NativeDashboardSettings.setPageKey(app,200400)
        assertThrows(IllegalArgumentException::class.java) { NativeDashboardSettings.setModeKey(app,200400) }
        assertEquals(200110,NativeDashboardSettings.modeKey(app))
        assertEquals(200400,NativeDashboardSettings.pageKey(app))
        NativeDashboardSettings.setPageKey(app,0)
        NativeDashboardSettings.setModeKey(app,200400)
        assertThrows(IllegalArgumentException::class.java) { NativeDashboardSettings.setPageKey(app,200400) }
        for(code in listOf(0,200024,210007,999999)) {
            assertThrows(IllegalArgumentException::class.java) { NativeDashboardSettings.setModeKey(app,code) }
        }
        assertEquals(200400,NativeDashboardSettings.modeKey(app))
        assertEquals(0,NativeDashboardSettings.pageKey(app))
    }

    @Test fun staleConflictingPageBindingIsSuppressedAndMalformedCalibrationFallsBackSafely() {
        val prefs=app.getSharedPreferences("native_dashboard",0)
        prefs.edit().putInt("mode_key",200400).putInt("page_key",200400).apply()
        assertEquals(200400,NativeDashboardSettings.modeKey(app))
        assertEquals(0,NativeDashboardSettings.pageKey(app))
        GeelyWheelKeys.startDashboard(app)
        val input=connect()
        shadowOf(app).clearStartedServices()
        input.listener.onShortClick(200400,0)
        idle()
        assertEquals("com.shilapi.xcertplay.TOGGLE_NATIVE_DASHBOARD_PROJECTION",shadowOf(app).nextStartedService?.action)
        assertEquals(DashboardMode.MUSIC,NativeDashboardSettings.mode(app))
        prefs.edit().putInt("mode_key",210007).putInt("page_key",200110).apply()
        assertEquals(200110,NativeDashboardSettings.modeKey(app))
        assertEquals(0,NativeDashboardSettings.pageKey(app))
        prefs.edit().putString("mode_key","bad").putString("page_key","bad").apply()
        assertEquals(200110,NativeDashboardSettings.modeKey(app))
        assertEquals(0,NativeDashboardSettings.pageKey(app))
    }

    @Test fun learningObservesOneCandidateWithoutSavingOrPerformingDashboardActions() {
        GeelyWheelKeys.startDashboard(app)
        val learned = mutableListOf<Int>()
        cancel = GeelyWheelKeys.learnDashboardPageKey(app) { learned.add(it) }
        val input = connect()
        assertEquals(DashboardWheelKeyPolicy.learningCandidates, input.registrations.last().second)
        input.listener.onLongPressTriggered(200082, 0)
        input.listener.onShortClick(200110, 0)
        input.listener.onShortClick(200024, 0)
        input.listener.onShortClick(19, 0)
        idle()
        assertTrue(learned.isEmpty())
        input.listener.onShortClick(200082, 77)
        input.listener.onShortClick(200082, 77)
        input.listener.onShortClick(GeelyWheelKeyCodes.KEY_NEXT, 0)
        idle()
        assertEquals(listOf(200082), learned)
        assertEquals(0, NativeDashboardSettings.pageKey(app))
        assertEquals(DashboardMode.MUSIC, NativeDashboardSettings.mode(app))
        assertTrue(GeelyWheelKeys.learningStatus.contains("function=77"))
        assertEquals(0, input.interceptions)
    }

    @Test fun cancelAndTimeoutClearLearningAndRestoreNormalSubscriptions() {
        val learned = mutableListOf<Int>()
        cancel = GeelyWheelKeys.learnDashboardPageKey(app) { learned.add(it) }
        val first = connect()
        first.listener.onShortClick(200082, 0)
        cancel!!.invoke() // Cancels a callback already queued to the UI thread.
        idle()
        assertTrue(learned.isEmpty())
        first.listener.onShortClick(200082, 0)
        idle()
        assertTrue(learned.isEmpty())
        GeelyWheelKeys.startDashboard(app)
        cancel = GeelyWheelKeys.learnDashboardPageKey(app) { learned.add(it) }
        val second = connect()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(GeelyWheelKeys.LEARN_TIMEOUT_MILLIS + 1))
        assertTrue(GeelyWheelKeys.learningStatus.contains("timed out"))
        second.listener.onShortClick(200082, 0)
        idle()
        assertTrue(learned.isEmpty())
        val normal = connect()
        assertEquals(listOf(200110) + DashboardWheelKeyPolicy.learningCandidates, normal.registrations.last().second)
    }

    @Test fun staleListenerAndStaleConnectionNeverRouteIntoNewRegistration() {
        NativeDashboardSettings.setPageKey(app, 200600)
        GeelyWheelKeys.startDashboard(app)
        val oldConnection = ReflectionHelpers.getField<ServiceConnection>(GeelyWheelKeys, "connection")
        val previous = connect()
        val oldListener = previous.listener
        GeelyWheelKeys.refresh()
        val active = connect()
        oldListener.onShortClick(200600, 0)
        oldConnection.onServiceDisconnected(null)
        oldConnection.onServiceConnected(null, previous.asBinder())
        idle()
        assertEquals(1, previous.registrations.size)
        assertEquals(DashboardMode.MUSIC, NativeDashboardSettings.mode(app))
        active.listener.onShortClick(200600, 0)
        idle()
        assertEquals(DashboardMode.NAVIGATION, NativeDashboardSettings.mode(app))
    }

    @Test fun previousLearningDialogCannotCancelANewerLearningSession() {
        var oldCalls = 0
        val oldCancel = GeelyWheelKeys.learnDashboardPageKey(app) { oldCalls++ }
        val old = connect()
        val learned = mutableListOf<Int>()
        cancel = GeelyWheelKeys.learnDashboardPageKey(app) { learned.add(it) }
        val current = connect()
        oldCancel()
        old.listener.onShortClick(200082, 0)
        current.listener.onShortClick(200600, 0)
        idle()
        assertEquals(0, oldCalls)
        assertEquals(listOf(200600), learned)
        assertEquals(0, NativeDashboardSettings.pageKey(app))
    }

    @Test fun dimDisplayCandidateIsObservedButOnlyShortClickCanBeLearned() {
        val learned = mutableListOf<Int>()
        cancel = GeelyWheelKeys.learnDashboardPageKey(app) { learned.add(it) }
        val input = connect()
        assertTrue(300050 in input.registrations.last().second)
        input.listener.onKeyCodeEvent(300050, 0, 37)
        input.listener.onKeyCodeEvent(300050, 1, 37)
        input.listener.onHoldingPressStarted(300050, 37)
        input.listener.onLongPressTriggered(300050, 37)
        input.listener.onHoldingPressStopped(300050, 37)
        input.listener.onDoubleClick(300050, 37)
        idle()
        assertTrue(learned.isEmpty())
        assertEquals(0, NativeDashboardSettings.pageKey(app))
        assertEquals(DashboardMode.MUSIC, NativeDashboardSettings.mode(app))
        val before = GeelyWheelKeys.diagnosticReport()
        listOf("raw", "holdingStarted", "long", "holdingStopped", "double").forEach {
            assertTrue(before.contains("type=$it key=300050"))
        }
        assertTrue(before.contains("event=0 function=37 uptimeMs="))
        assertTrue(before.contains("event=1 function=37 uptimeMs="))
        input.listener.onShortClick(300050, 37)
        idle()
        assertEquals(listOf(300050), learned)
        assertTrue(GeelyWheelKeys.diagnosticReport().contains("type=short key=300050 event=n/a function=37"))
        assertEquals(0, NativeDashboardSettings.pageKey(app))
        assertEquals(DashboardMode.MUSIC, NativeDashboardSettings.mode(app))
        assertEquals(0, input.interceptions)
    }

    @Test fun callbackEvidenceIsBoundedAndOldListenerCannotChangeIt() {
        cancel = GeelyWheelKeys.learnDashboardPageKey(app) {}
        val input = connect()
        repeat(GeelyWheelKeys.DIAGNOSTIC_EVENT_LIMIT + 20) {
            input.listener.onKeyCodeEvent(300050, 1000 + it, 53)
        }
        val events = GeelyWheelKeys.diagnosticReport().lineSequence().filter { it.startsWith("type=") }.toList()
        assertEquals(GeelyWheelKeys.DIAGNOSTIC_EVENT_LIMIT, events.size)
        assertTrue(events.first().contains("event=1020 function=53"))
        assertTrue(events.last().contains("event=1083 function=53"))
        GeelyWheelKeys.refresh()
        connect()
        val afterRefresh = GeelyWheelKeys.diagnosticReport()
        input.listener.onKeyCodeEvent(300050, 9000, 99)
        input.listener.onShortClick(300050, 99)
        input.listener.onHoldingPressStarted(300050, 99)
        input.listener.onHoldingPressStopped(300050, 99)
        input.listener.onLongPressTriggered(300050, 99)
        input.listener.onDoubleClick(300050, 99)
        idle()
        assertEquals(afterRefresh, GeelyWheelKeys.diagnosticReport())
        assertEquals(DashboardMode.MUSIC, NativeDashboardSettings.mode(app))
        assertEquals(0, NativeDashboardSettings.pageKey(app))
    }

    @Test fun unboundCandidatesAreOnlyObservedWhileModeStillRequestsProjection() {
        GeelyWheelKeys.startDashboard(app)
        val input = connect()
        assertEquals(listOf(200110) + DashboardWheelKeyPolicy.learningCandidates, input.registrations.last().second)
        shadowOf(app).clearStartedServices()
        for (code in DashboardWheelKeyPolicy.learningCandidates) {
            input.listener.onKeyCodeEvent(code, 0, 17)
            input.listener.onKeyCodeEvent(code, 1, 17)
            input.listener.onShortClick(code, 17)
            input.listener.onLongPressTriggered(code, 17)
        }
        idle()
        assertEquals(0, NativeDashboardSettings.pageKey(app))
        assertEquals(DashboardMode.MUSIC, NativeDashboardSettings.mode(app))
        assertTrue(GeelyWheelKeys.diagnosticReport().contains("passiveCandidates=true"))
        assertTrue(GeelyWheelKeys.diagnosticReport().contains("learning=false"))
        assertNull(shadowOf(app).nextStartedService)
        input.listener.onShortClick(200110, 0)
        idle()
        assertEquals("com.shilapi.xcertplay.TOGGLE_NATIVE_DASHBOARD_PROJECTION", shadowOf(app).nextStartedService?.action)
        assertEquals(DashboardMode.MUSIC, NativeDashboardSettings.mode(app))
        assertEquals(0, input.interceptions)
    }

    @Test fun passiveObservationKeepsMediaRoutingAndBluetoothExclusion() {
        val controller = mock(CarPlayController::class.java)
        val previous = ReflectionHelpers.getField<CarPlayController?>(CarPlayMediaKeys, "controller")
        val previousBluetooth = AirPlayPersistence.loadCarBluetoothAudio(app)
        ReflectionHelpers.setField(CarPlayMediaKeys, "controller", controller)
        AirPlayPersistence.saveCarBluetoothAudio(app, false)
        try {
            GeelyWheelKeys.startDashboard(app)
            GeelyWheelKeys.start(app)
            val input = connect()
            input.listener.onShortClick(300050, 0)
            input.listener.onKeyCodeEvent(GeelyWheelKeyCodes.KEY_NEXT, 0, 0)
            input.listener.onKeyCodeEvent(GeelyWheelKeyCodes.KEY_NEXT, 1, 0)
            idle()
            verifyNoInteractions(controller)
            input.listener.onShortClick(GeelyWheelKeyCodes.KEY_NEXT, 0)
            idle()
            verify(controller, times(1)).sendMediaButton(CarPlayMediaButton.NEXT)
            AirPlayPersistence.saveCarBluetoothAudio(app, true)
            input.listener.onShortClick(GeelyWheelKeyCodes.KEY_NEXT, 0)
            input.listener.onShortClick(300050, 0)
            idle()
            verify(controller, times(1)).sendMediaButton(CarPlayMediaButton.NEXT)
            assertEquals(0, NativeDashboardSettings.pageKey(app))
            assertEquals(DashboardMode.MUSIC, NativeDashboardSettings.mode(app))
        } finally {
            ReflectionHelpers.setField(CarPlayMediaKeys, "controller", previous)
            AirPlayPersistence.saveCarBluetoothAudio(app, previousBluetooth)
        }
    }

    @Test fun menuFeedbackRoutesTracksToPagesAndClosingMenuRestoresMusic() {
        val controller = mock(CarPlayController::class.java)
        val previous = ReflectionHelpers.getField<CarPlayController?>(CarPlayMediaKeys, "controller")
        val bluetooth = AirPlayPersistence.loadCarBluetoothAudio(app)
        ReflectionHelpers.setField(CarPlayMediaKeys, "controller", controller)
        AirPlayPersistence.saveCarBluetoothAudio(app, false)
        try {
            GeelyWheelKeys.startDashboard(app)
            GeelyWheelKeys.start(app)
            val input = connect()
            assertFalse(GeelyWheelKeys.isMenuPagingActive(app))
            // Merely observing MENU does not toggle a guessed state.
            input.listener.onShortClick(200082, 0)
            input.listener.onShortClick(200087, 0)
            idle()
            assertEquals(DashboardMode.MUSIC, NativeDashboardSettings.mode(app))
            verify(controller).sendMediaButton(CarPlayMediaButton.NEXT)
            clearInvocations(controller)
            menu.current = Fx11WheelMode.DASHBOARD
            assertTrue(GeelyWheelKeys.isMenuPagingActive(app))
            input.listener.onShortClick(200087, 0)
            input.listener.onShortClick(200087, 0)
            idle()
            assertEquals(DashboardMode.NAVIGATION, NativeDashboardSettings.mode(app))
            input.listener.onShortClick(200088, 0)
            idle()
            assertEquals(DashboardMode.MUSIC, NativeDashboardSettings.mode(app))
            input.listener.onLongPressTriggered(200087, 0)
            input.listener.onKeyCodeEvent(200087, 0, 0)
            input.listener.onKeyCodeEvent(200087, 1, 0)
            idle()
            assertEquals(DashboardMode.MUSIC, NativeDashboardSettings.mode(app))
            verifyNoInteractions(controller)
            menu.current = Fx11WheelMode.MEDIA
            assertFalse(GeelyWheelKeys.isMenuPagingActive(app))
            input.listener.onShortClick(200088, 0)
            idle()
            verify(controller).sendMediaButton(CarPlayMediaButton.PREVIOUS)
            assertEquals(DashboardMode.MUSIC, NativeDashboardSettings.mode(app))
            assertEquals(0, input.interceptions)
        } finally {
            ReflectionHelpers.setField(CarPlayMediaKeys, "controller", previous)
            AirPlayPersistence.saveCarBluetoothAudio(app, bluetooth)
        }
    }

    @Test fun menuPagingWorksOfflineWithLearnedOtherKeyAndBluetoothAudio() {
        val bluetooth = AirPlayPersistence.loadCarBluetoothAudio(app)
        AirPlayPersistence.saveCarBluetoothAudio(app, true)
        try {
            NativeDashboardSettings.setPageKey(app, 200600)
            GeelyWheelKeys.startDashboard(app)
            val input = connect()
            assertTrue(input.registrations.last().second.containsAll(listOf(200087, 200088)))
            menu.current = Fx11WheelMode.DASHBOARD
            input.listener.onShortClick(200088, 0)
            idle()
            assertEquals(DashboardMode.TIRES, NativeDashboardSettings.mode(app))
            input.listener.onShortClick(200087, 0)
            idle()
            assertEquals(DashboardMode.MUSIC, NativeDashboardSettings.mode(app))
            GeelyWheelKeys.stopDashboard()
            assertTrue(menu.closed)
            input.listener.onShortClick(200087, 0)
            idle()
            assertEquals(DashboardMode.MUSIC, NativeDashboardSettings.mode(app))
        } finally { AirPlayPersistence.saveCarBluetoothAudio(app, bluetooth) }
    }

    @Test fun menuDoesNotOverrideLearningAndModeStillTogglesProjection() {
        NativeDashboardSettings.setModeKey(app, 200400)
        GeelyWheelKeys.startDashboard(app)
        menu.current = Fx11WheelMode.DASHBOARD
        val learned = mutableListOf<Int>()
        cancel = GeelyWheelKeys.learnDashboardPageKey(app) { learned.add(it) }
        val learningInput = connect()
        learningInput.listener.onShortClick(200087, 0)
        idle()
        assertEquals(listOf(200087), learned)
        assertEquals(DashboardMode.MUSIC, NativeDashboardSettings.mode(app))
        cancel!!.invoke()
        val input = connect()
        shadowOf(app).clearStartedServices()
        input.listener.onShortClick(200400, 0)
        idle()
        assertEquals("com.shilapi.xcertplay.TOGGLE_NATIVE_DASHBOARD_PROJECTION", shadowOf(app).nextStartedService?.action)
        assertEquals(DashboardMode.MUSIC, NativeDashboardSettings.mode(app))
    }

    @Test fun panoramaPagesKeepMapAndPreviousWrapsInEveryStyle() {
        for (style in listOf(DashboardStyle.MIDNIGHT, DashboardStyle.PANORAMA, DashboardStyle.REPLICA)) {
            val choices = DashboardMode.choices(style)
            for ((index, page) in choices.withIndex()) {
                assertEquals(choices[(index + 1) % choices.size], page.step(style, 1))
                assertEquals(choices[(index - 1 + choices.size) % choices.size], page.step(style, -1))
                assertEquals(page, page.step(style, 1).step(style, -1))
            }
        }
    }

    @Test fun pendingTrackIsDiscardedIfMenuChangedBeforeDispatch() {
        GeelyWheelKeys.startDashboard(app)
        val input = connect()
        menu.current = Fx11WheelMode.DASHBOARD
        input.listener.onShortClick(200087, 0)
        menu.current = Fx11WheelMode.MEDIA
        idle()
        assertEquals(DashboardMode.MUSIC, NativeDashboardSettings.mode(app))
        assertTrue(GeelyWheelKeys.diagnosticReport().contains("ignore-mode-changed-before-dispatch"))
    }

    @Test fun oldTrackPageBindingDoesNotBypassTheMenuStateGate() {
        NativeDashboardSettings.setPageKey(app, 200087)
        GeelyWheelKeys.startDashboard(app)
        val input = connect()
        input.listener.onShortClick(200087, 0)
        idle()
        assertEquals(DashboardMode.MUSIC, NativeDashboardSettings.mode(app))
        menu.current = Fx11WheelMode.DASHBOARD
        input.listener.onShortClick(200087, 0)
        idle()
        assertEquals(DashboardMode.NAVIGATION, NativeDashboardSettings.mode(app))
        BoyueLSettings.setWheel(app, false)
        input.listener.onShortClick(200088, 0)
        idle()
        assertEquals(DashboardMode.NAVIGATION, NativeDashboardSettings.mode(app))
    }

    @Test fun pagingHintRequiresLiveRegistrationAndHidesDuringLearningOrUnavailableInput() {
        GeelyWheelKeys.startDashboard(app)
        menu.current = Fx11WheelMode.DASHBOARD
        assertFalse("Waiting for input registration", GeelyWheelKeys.isMenuPagingActive(app))
        connect()
        assertTrue(GeelyWheelKeys.isMenuPagingActive(app))
        for (mode in listOf(Fx11WheelMode.UNKNOWN, Fx11WheelMode.MEDIA, Fx11WheelMode.PHONE)) {
            menu.current = mode
            assertFalse(GeelyWheelKeys.isMenuPagingActive(app))
        }
        menu.current = Fx11WheelMode.DASHBOARD
        BoyueLSettings.setWheel(app, false)
        assertFalse(GeelyWheelKeys.isMenuPagingActive(app))
        BoyueLSettings.setWheel(app, true)
        NativeDashboardSettings.setStyle(app, DashboardStyle.OFF)
        assertFalse(GeelyWheelKeys.isMenuPagingActive(app))
        NativeDashboardSettings.setStyle(app, DashboardStyle.REPLICA)
        cancel = GeelyWheelKeys.learnDashboardPageKey(app) { }
        connect()
        assertFalse("Learning must never show the paging hint", GeelyWheelKeys.isMenuPagingActive(app))
        cancel!!.invoke()
        connect()
        assertTrue(GeelyWheelKeys.isMenuPagingActive(app))
        connect(Input(failRegistration = true))
        assertFalse("A failed registration must not retain the prior subscription", GeelyWheelKeys.isMenuPagingActive(app))
        connect()
        assertTrue(GeelyWheelKeys.isMenuPagingActive(app))
        val connection = ReflectionHelpers.getField<ServiceConnection>(GeelyWheelKeys, "connection")
        connection.onServiceDisconnected(ComponentName("com.geely.inputservice", "InputService"))
        assertFalse("The cached tab alone is insufficient", GeelyWheelKeys.isMenuPagingActive(app))
        GeelyWheelKeys.stopDashboard()
        assertFalse(GeelyWheelKeys.isMenuPagingActive(app))
    }

    @Test fun allThreeLiveViewsRefreshMenuHintWithoutCarplayAndClearItOnDetach() {
        val oldSource = NativeDashboardRuntime.sourceFactory
        val oldLights = NativeDashboardRuntime.exteriorLightFactory
        val emptySource = { object : UltraVehicleSource {
            override fun start() = Unit
            override fun snapshot(): Map<VehicleSignal, VehicleReading> = emptyMap()
            override fun close() = Unit
        } }
        NativeDashboardRuntime.sourceFactory = emptySource
        NativeDashboardRuntime.exteriorLightFactory = { emptySource() }
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            GeelyWheelKeys.startDashboard(app)
            val input = connect()
            for (style in listOf(DashboardStyle.MIDNIGHT, DashboardStyle.PANORAMA, DashboardStyle.REPLICA)) {
                NativeDashboardSettings.setStyle(app, style)
                menu.current = Fx11WheelMode.UNKNOWN
                val view = NativeDashboardView(activity.get())
                activity.get().setContentView(view)
                fun tick() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
                tick()
                assertEquals(style, view.artworkView.style)
                assertFalse(view.artworkView.menuPagingActive)
                menu.current = Fx11WheelMode.DASHBOARD
                tick()
                assertTrue(view.artworkView.menuPagingActive)
                input.listener.onShortClick(200087, 0)
                tick()
                assertEquals(NativeDashboardSettings.mode(app), view.artworkView.mode)
                assertTrue("Paging must not hide its own hint", view.artworkView.menuPagingActive)
                menu.current = Fx11WheelMode.MEDIA
                tick()
                assertFalse("Exit must disappear on the next refresh", view.artworkView.menuPagingActive)
                menu.current = Fx11WheelMode.DASHBOARD
                tick()
                assertTrue(view.artworkView.menuPagingActive)
                activity.get().setContentView(android.view.View(activity.get()))
                assertFalse("Detached views must not retain an active hint", view.artworkView.menuPagingActive)
            }
        } finally {
            activity.pause().stop().destroy()
            NativeDashboardRuntime.sourceFactory = oldSource
            NativeDashboardRuntime.exteriorLightFactory = oldLights
        }
    }
}
