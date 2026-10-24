package com.shilapi.xcertplay

import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.Display
import android.view.ViewGroup
import com.shilapi.xcertplay.ultra.UltraVehicleSource
import com.shilapi.xcertplay.ultra.VehicleReading
import com.shilapi.xcertplay.ultra.VehicleSignal
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.io.PrintWriter
import java.io.StringWriter
import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/** Drives the actual Service, Presentation first-draw listener, MODE route and OEM controller. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class NativeDashboardServiceTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var previousOverlayFactory: (Context) -> Fx11ThreeScreenController
    private lateinit var overlayWorker: Worker
    private lateinit var overlayBridge: Bridge
    private lateinit var overlayController: Fx11ThreeScreenController
    private lateinit var previousFactory: () -> Fx11ThreeScreenController
    private lateinit var previousSource: () -> UltraVehicleSource
    private lateinit var previousDteFactory: (() -> Unit) -> Fx11DteMonitor
    private lateinit var dte: Dte
    private lateinit var projection: Fx11ThreeScreenController
    private lateinit var worker: Worker
    private lateinit var bridge: Bridge
    private var serviceController: ServiceController<NativeDashboardService>? = null
    private val createdDisplays = mutableListOf<Int>()
    private val service get() = checkNotNull(serviceController).get()
    private val panel get() = ReflectionHelpers.getField<Presentation?>(service, "panel")
    private val overlayPanel get() = ReflectionHelpers.getField<Fx11OemOverlayWindow?>(service, "overlayPanel")

    private class Dte(val page: () -> Unit) : Fx11DteMonitor {
        var starts = 0
        var closed = false
        override fun start() { starts++ }
        override fun close() { closed = true }
        override fun diagnosticReport() = "DTE candidate observer test; physical RESET unconfirmed"
    }

    private class Worker : AbstractExecutorService() {
        val pending = ArrayDeque<Runnable>()
        private var closed = false
        override fun execute(command: Runnable) { check(!closed); pending.add(command) }
        override fun shutdown() { closed = true }
        override fun shutdownNow(): MutableList<Runnable> {
            closed = true
            return pending.toMutableList().also { pending.clear() }
        }
        override fun isShutdown() = closed
        override fun isTerminated() = closed && pending.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
        fun runNext() { assertFalse("Expected an explicit user OEM request", pending.isEmpty()); pending.removeFirst().run() }
    }

    private open class Bridge : Fx11ThreeScreenBridge {
        var state = 0
        var acceptWrite = true
        var connects = 0
        var reads = 0
        val writes = mutableListOf<Int>()
        override fun connect(context: Context) { connects++ }
        override fun supportedStatus() = "active"
        override fun zones() = intArrayOf(Int.MIN_VALUE)
        override fun values() = intArrayOf(0, 1)
        override fun read(): Int { reads++; return state }
        override fun write(value: Int): Boolean {
            writes.add(value)
            if (acceptWrite) state = value
            return acceptWrite
        }
        override fun close() = Unit
    }

    @Before fun setup() {
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        GeelyWheelKeys.stop()
        GeelyWheelKeys.stopDashboard()
        BoyueLSettings.select(app, true)
        BoyueLSettings.migrateDashboardControls(app)
        BoyueLSettings.setWheel(app, true)
        NativeDashboardSettings.setStyle(app, DashboardStyle.REPLICA)
        NativeDashboardSettings.setMode(app, DashboardMode.MUSIC)
        NativeDashboardSettings.setPageKey(app, 200082)
        previousOverlayFactory = NativeDashboardService.overlayControllerFactory
        overlayWorker = Worker()
        overlayBridge = object : Bridge() {
            override fun validate(mode: Fx11ProjectionMode) { check(mode == Fx11ProjectionMode.OEM_OVERLAY) }
        }
        overlayController = Fx11ThreeScreenController({ overlayBridge }, overlayWorker, pause = {},
            mode = Fx11ProjectionMode.OEM_OVERLAY,
            onWriteAttempt = { if (it == 3) NativeDashboardSettings.setOverlayRestoreRequired(app, true) })
        NativeDashboardService.overlayControllerFactory = { overlayController }
        NativeDashboardSettings.setOverlayRestoreRequired(app, false)
        // These existing tests exercise the explicit legacy DIM route, not the new default.
        NativeDashboardSettings.setOverlayOutput(app, OemOverlayPath.DIM, 4)
        previousFactory = NativeDashboardService.controllerFactory
        previousSource = NativeDashboardRuntime.sourceFactory
        previousDteFactory = NativeDashboardService.dteMonitorFactory
        NativeDashboardService.dteMonitorFactory = { page -> Dte(page).also { dte = it } }
        worker = Worker()
        bridge = Bridge()
        projection = Fx11ThreeScreenController({ bridge }, worker, pause = {})
        NativeDashboardService.controllerFactory = { projection }
        NativeDashboardRuntime.sourceFactory = { object : UltraVehicleSource {
            override fun start() = Unit
            override fun snapshot(): Map<VehicleSignal, VehicleReading> = emptyMap()
            override fun close() = Unit
        } }
        shadowOf(app).clearStartedServices()
    }

    @After fun cleanup() {
        serviceController?.destroy()
        projection.close()
        overlayController.close()
        NativeDashboardService.overlayControllerFactory = previousOverlayFactory
        NativeDashboardService.controllerFactory = previousFactory
        NativeDashboardRuntime.sourceFactory = previousSource
        NativeDashboardService.dteMonitorFactory = previousDteFactory
        GeelyWheelKeys.stop()
        GeelyWheelKeys.stopDashboard()
        createdDisplays.forEach(ShadowDisplayManager::removeDisplay)
        shadowOf(app).clearStartedServices()
    }

    private fun display(): Display {
        val displays = app.getSystemService(DisplayManager::class.java)
        while (displays.getDisplay(4) == null) {
            val id = ShadowDisplayManager.addDisplay("w1920dp-h532dp-mdpi", 5)
            createdDisplays.add(id)
            check(id <= 4)
        }
        return displays.getDisplay(4).also {
            shadowOf(it).setFlags(Display.FLAG_PRESENTATION)
            shadowOf(it).setState(Display.STATE_ON)
        }
    }

    private fun start() {
        serviceController = Robolectric.buildService(NativeDashboardService::class.java).create()
        service.onStartCommand(Intent(app, NativeDashboardService::class.java), 0, 1)
        // ServiceController construction records the initial service start. Subsequent assertions
        // must deliver the user route's Intent, rather than replaying that action-less start.
        shadowOf(app).clearStartedServices()
    }

    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }

    private fun drawFirstFrame() {
        val view = overlayPanel?.view ?: checkNotNull(panel).window!!.decorView
            .findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        view.viewTreeObserver.dispatchOnDraw()
        idle()
        assertTrue(ReflectionHelpers.getField<Boolean>(service, "panelDrawn"))
    }

    /** Deliver the actual Intent that the public route sent through Android's service boundary. */
    private fun deliverStartedIntent(action: String) {
        val intent = shadowOf(app).nextStartedService
        assertNotNull(intent)
        assertEquals(NativeDashboardService::class.java.name, intent.component?.className)
        assertEquals("com.shilapi.xcertplay.$action", intent.action)
        service.onStartCommand(intent, 0, 2)
    }

    private fun pressMode() {
        shadowOf(app).clearStartedServices()
        ReflectionHelpers.callInstanceMethod<Unit>(GeelyWheelKeys, "forward",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, NativeDashboardSettings.MODE_KEY),
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
        idle()
        deliverStartedIntent("TOGGLE_NATIVE_DASHBOARD_PROJECTION")
        worker.runNext()
        idle()
    }

    @Test fun startingAndDrawingTheServiceNeverChangesOemMode() {
        display()
        start()
        assertNotNull(panel)
        drawFirstFrame()
        assertTrue(worker.pending.isEmpty())
        assertEquals(0, bridge.connects)
        assertEquals(0, bridge.reads)
        assertTrue(bridge.writes.isEmpty())
        assertTrue(ReflectionHelpers.getField(GeelyWheelKeys, "dashboardOwner"))
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test fun dteCandidateCyclesMusicNavigationTiresWithoutCarPlayOrProjectionWrites() {
        NativeDashboardSettings.setPageKey(app,0)
        start()
        assertEquals(1,dte.starts)
        assertEquals(DashboardMode.MUSIC,NativeDashboardSettings.mode(app))
        dte.page(); assertEquals(DashboardMode.NAVIGATION,NativeDashboardSettings.mode(app))
        dte.page(); assertEquals(DashboardMode.TIRES,NativeDashboardSettings.mode(app))
        dte.page(); assertEquals(DashboardMode.MUSIC,NativeDashboardSettings.mode(app))
        NativeDashboardSettings.setStyle(app,DashboardStyle.MIDNIGHT)
        NativeDashboardSettings.setMode(app,DashboardMode.MUSIC)
        dte.page(); assertEquals(DashboardMode.NAVIGATION,NativeDashboardSettings.mode(app))
        NativeDashboardSettings.setStyle(app,DashboardStyle.PANORAMA)
        NativeDashboardSettings.setMode(app,DashboardMode.MUSIC)
        dte.page(); assertEquals(DashboardMode.TIRES,NativeDashboardSettings.mode(app))
        dte.page(); assertEquals(DashboardMode.MUSIC,NativeDashboardSettings.mode(app))
        assertTrue(worker.pending.isEmpty())
        assertTrue(bridge.writes.isEmpty())
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun dteCandidateRespectsExplicitPageKeyWheelDisableLearningAndServiceShutdown() {
        start()
        dte.page() // Explicit OneOS key is already set in setup; it takes precedence.
        assertEquals(DashboardMode.MUSIC,NativeDashboardSettings.mode(app))
        NativeDashboardSettings.setPageKey(app,0)
        BoyueLSettings.setWheel(app,false); dte.page()
        assertEquals(DashboardMode.MUSIC,NativeDashboardSettings.mode(app))
        BoyueLSettings.setWheel(app,true)
        val cancel=GeelyWheelKeys.learnDashboardPageKey(app) {}
        try { dte.page(); assertEquals(DashboardMode.MUSIC,NativeDashboardSettings.mode(app)) }
        finally { cancel() }
        NativeDashboardSettings.setStyle(app,DashboardStyle.OFF); dte.page()
        NativeDashboardSettings.setStyle(app,DashboardStyle.REPLICA)
        assertEquals(DashboardMode.MUSIC,NativeDashboardSettings.mode(app))
        val stopped=dte
        serviceController!!.destroy(); serviceController=null
        assertTrue(stopped.closed)
        stopped.page()
        assertEquals(DashboardMode.MUSIC,NativeDashboardSettings.mode(app))
        assertTrue(bridge.writes.isEmpty())
    }

    @Test fun dumpsysReportsCachedVehicleAndKeyStateWithoutChangingAnything() {
        start()
        val previousState = NativeDashboardRuntime.state
        val snapshot = DashboardSnapshot(
            vehicle = mapOf(VehicleSignal.LEFT_TURN to VehicleReading(true, 100, 557873489)),
            music = ClusterMusicState.Snapshot(CarPlayNowPlaying(title = "Private song title")),
            navigation = ClusterTurnGuidance(1, 0, 100, "Private destination street"),
        )
        ReflectionHelpers.setField(NativeDashboardRuntime, "state", snapshot)
        try {
            val output = StringWriter()
            service.dump(null, PrintWriter(output), arrayOf("toggle", "payload=private-argument"))
            val report = output.toString()
            assertTrue(report.contains("--- FX11 native dashboard diagnostics ---"))
            assertTrue(report.contains("style=REPLICA page=MUSIC pageKey=200082"))
            assertTrue(report.contains("FX11 reader idle"))
            assertTrue(report.contains("LEFT_TURN=true"))
            assertTrue(report.contains("Geely wheel bound="))
            assertTrue(report.contains("registeredCodes="))
            assertFalse(report.contains("Private song title"))
            assertFalse(report.contains("Private destination street"))
            assertFalse(report.contains("private-argument"))
            assertTrue(worker.pending.isEmpty())
            assertEquals(0, bridge.connects)
            assertEquals(0, bridge.reads)
            assertTrue(bridge.writes.isEmpty())
            assertEquals(DashboardStyle.REPLICA, NativeDashboardSettings.style(app))
            assertEquals(DashboardMode.MUSIC, NativeDashboardSettings.mode(app))
            assertEquals(200082, NativeDashboardSettings.pageKey(app))
            assertSame(snapshot, NativeDashboardRuntime.state)
            assertNull(shadowOf(app).nextStartedService)
        } finally {
            ReflectionHelpers.setField(NativeDashboardRuntime, "state", previousState)
        }
    }

    @Test fun dumpsysRedactsStatusLinesBeforePrinting() {
        start()
        val previous = ReflectionHelpers.getStaticField<String>(NativeDashboardService::class.java, "panelStatus")
        ReflectionHelpers.setStaticField(NativeDashboardService::class.java, "panelStatus",
            "panel ip=192.168.49.1\npassword=private-credential")
        try {
            val output = StringWriter()
            service.dump(null, PrintWriter(output), null)
            val report = output.toString()
            assertTrue(report.contains("panel ip=[ip]"))
            assertFalse(report.contains("192.168.49.1"))
            assertFalse(report.contains("private-credential"))
            assertTrue(bridge.writes.isEmpty())
        } finally {
            ReflectionHelpers.setStaticField(NativeDashboardService::class.java, "panelStatus", previous)
        }
    }

    @Test fun offlineModeSwitchRetainsStyleWindowAndKeyOwnerSoItCanOpenAgain() {
        display()
        start()
        drawFirstFrame()
        val initial = panel
        for (expected in listOf(1, 0, 1)) {
            pressMode()
            assertEquals(expected, bridge.state)
            assertEquals("DIM follows confirmed factory/custom mode", expected == 0, BoyueLSettings.dim(app))
            assertEquals(DashboardStyle.REPLICA, NativeDashboardSettings.style(app))
            assertEquals(DashboardMode.MUSIC, NativeDashboardSettings.mode(app))
            assertTrue(ReflectionHelpers.getField(GeelyWheelKeys, "dashboardOwner"))
            assertFalse(ReflectionHelpers.getField(GeelyWheelKeys, "mediaOwner"))
            assertSame(initial, panel)
            assertTrue(panel!!.isShowing)
            assertFalse(shadowOf(service).isStoppedBySelf)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        }
        assertEquals(listOf(1, 0, 1), bridge.writes)
    }

    @Test fun disableKeepsPanelAndStyleUntilOemRestoreIsConfirmed() {
        display()
        start()
        val initial = panel
        bridge.state = 1
        NativeDashboardService.disable(app)
        deliverStartedIntent("STOP_NATIVE_DASHBOARD")
        assertEquals(DashboardStyle.REPLICA, NativeDashboardSettings.style(app))
        assertSame(initial, panel)
        assertFalse(shadowOf(service).isStoppedBySelf)
        worker.runNext()
        // Even after readback, the Service processes confirmation on its main callback.
        assertEquals(DashboardStyle.REPLICA, NativeDashboardSettings.style(app))
        idle()
        assertEquals(listOf(0), bridge.writes)
        assertEquals(DashboardStyle.OFF, NativeDashboardSettings.style(app))
        assertTrue(BoyueLSettings.dim(app))
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun failedDisableLeavesDashboardAliveAndASecondExplicitAttemptCanRestore() {
        display()
        start()
        val initial = panel
        bridge.state = 1
        bridge.acceptWrite = false
        NativeDashboardService.disable(app)
        deliverStartedIntent("STOP_NATIVE_DASHBOARD")
        worker.runNext()
        idle()
        assertEquals(DashboardStyle.REPLICA, NativeDashboardSettings.style(app))
        assertSame(initial, panel)
        assertTrue(panel!!.isShowing)
        assertTrue(ReflectionHelpers.getField(GeelyWheelKeys, "dashboardOwner"))
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertEquals(listOf(0), bridge.writes)
        bridge.acceptWrite = true
        NativeDashboardService.disable(app)
        deliverStartedIntent("STOP_NATIVE_DASHBOARD")
        worker.runNext()
        idle()
        assertEquals(listOf(0, 0), bridge.writes)
        assertEquals(DashboardStyle.OFF, NativeDashboardSettings.style(app))
        assertTrue(BoyueLSettings.dim(app))
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun withoutFirstDrawTheServiceCannotEnableProjectionButCanReturnToOem() {
        display()
        start()
        assertNotNull(panel)
        assertFalse(ReflectionHelpers.getField<Boolean>(service, "panelDrawn"))
        NativeDashboardService.toggleProjection(app)
        deliverStartedIntent("TOGGLE_NATIVE_DASHBOARD_PROJECTION")
        worker.runNext()
        assertTrue(bridge.writes.isEmpty())
        idle()
        bridge.state = 1
        // User restore is independent of first-frame readiness.
        NativeDashboardService.disable(app)
        deliverStartedIntent("STOP_NATIVE_DASHBOARD")
        worker.runNext()
        idle()
        assertEquals(listOf(0), bridge.writes)
        assertEquals(DashboardStyle.OFF, NativeDashboardSettings.style(app))
    }

    @Test fun displayLossCancelsAPendingEnableButTheNextModePressCanRestoreOem() {
        val display = display()
        start()
        drawFirstFrame()
        NativeDashboardService.toggleProjection(app)
        deliverStartedIntent("TOGGLE_NATIVE_DASHBOARD_PROJECTION") // Request queued while its drawn window is valid.
        shadowOf(display).setState(Display.STATE_OFF)
        service.onDisplayChanged(4)
        assertNull(panel)
        worker.runNext()
        idle()
        assertTrue(bridge.writes.isEmpty())
        bridge.state = 1
        NativeDashboardService.toggleProjection(app)
        deliverStartedIntent("TOGGLE_NATIVE_DASHBOARD_PROJECTION")
        worker.runNext()
        idle()
        assertEquals(listOf(0), bridge.writes)
        assertEquals(DashboardStyle.REPLICA, NativeDashboardSettings.style(app))
        assertTrue(ReflectionHelpers.getField(GeelyWheelKeys, "dashboardOwner"))
        assertFalse(shadowOf(service).isStoppedBySelf)
    }
    @Test fun overlayWindowIsTransparentAndModeUsesThreeWithPersistedRestoreMarker() {
        NativeDashboardSettings.setStyle(app, DashboardStyle.OEM_OVERLAY)
        display(); start(); drawFirstFrame()
        assertTrue(overlayBridge.writes.isEmpty())
        assertNull(panel)
        val overlay = checkNotNull(overlayPanel)
        assertEquals(2038, overlay.params.type)
        assertEquals(720, overlay.params.width)
        assertEquals(478, overlay.params.height)
        assertEquals(600, overlay.params.x)
        assertEquals(48, overlay.params.y)
        assertEquals(android.graphics.PixelFormat.TRANSLUCENT, overlay.params.format)
        assertEquals(0, overlay.params.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        assertTrue(overlay.params.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertTrue(overlay.params.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertEquals(android.graphics.Color.TRANSPARENT,
            (overlay.view.background as android.graphics.drawable.ColorDrawable).color)
        NativeDashboardService.toggleProjection(app)
        deliverStartedIntent("TOGGLE_NATIVE_DASHBOARD_PROJECTION")
        overlayWorker.runNext(); idle()
        assertEquals(listOf(3), overlayBridge.writes)
        assertTrue(NativeDashboardSettings.overlayRestoreRequired(app))
        assertTrue(bridge.writes.isEmpty())
        NativeDashboardService.disable(app)
        deliverStartedIntent("STOP_NATIVE_DASHBOARD")
        overlayWorker.runNext(); idle()
        assertEquals(listOf(3, 0), overlayBridge.writes)
        assertFalse(NativeDashboardSettings.overlayRestoreRequired(app))
        assertEquals(DashboardStyle.OFF, NativeDashboardSettings.style(app))
    }

    @Test fun switchingFromFullDashboardRestoresItBeforeSelectingOverlay() {
        display(); start(); drawFirstFrame(); bridge.state = 1
        NativeDashboardService.selectStyle(app, DashboardStyle.OEM_OVERLAY)
        deliverStartedIntent("SELECT_NATIVE_DASHBOARD_STYLE")
        assertEquals(DashboardStyle.REPLICA, NativeDashboardSettings.style(app))
        worker.runNext(); idle()
        assertEquals(listOf(0), bridge.writes)
        assertEquals(DashboardStyle.OEM_OVERLAY, NativeDashboardSettings.style(app))
        assertTrue(overlayBridge.writes.isEmpty())
        assertNull(panel)
        assertNotNull(overlayPanel)
    }

    @Test fun failedOverlayRestorationKeepsCurrentSelectionAndWindow() {
        NativeDashboardSettings.setStyle(app, DashboardStyle.OEM_OVERLAY)
        NativeDashboardSettings.setOverlayRestoreRequired(app, true)
        overlayBridge.state = 3; overlayBridge.acceptWrite = false
        display(); start(); drawFirstFrame()
        val before = overlayPanel
        NativeDashboardService.selectStyle(app, DashboardStyle.MIDNIGHT)
        deliverStartedIntent("SELECT_NATIVE_DASHBOARD_STYLE")
        overlayWorker.runNext(); idle()
        assertEquals(DashboardStyle.OEM_OVERLAY, NativeDashboardSettings.style(app))
        assertSame(before, overlayPanel)
        assertTrue(NativeDashboardSettings.overlayRestoreRequired(app))
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test fun unusedOverlayCanBeClosedEvenWhenCarDoesNotProvideItsVhalChannel() {
        NativeDashboardSettings.setStyle(app, DashboardStyle.OEM_OVERLAY)
        start()
        NativeDashboardService.disable(app)
        deliverStartedIntent("STOP_NATIVE_DASHBOARD")
        assertEquals(DashboardStyle.OFF, NativeDashboardSettings.style(app))
        assertEquals(0, overlayBridge.connects)
        assertTrue(overlayBridge.writes.isEmpty())
    }

    @Test fun overlayWithoutWindowPermissionCannotEnableOrInterfereWithFullDashboard() {
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false)
        NativeDashboardSettings.setStyle(app, DashboardStyle.OEM_OVERLAY)
        display(); start()
        assertNull(panel); assertNull(overlayPanel)
        NativeDashboardService.toggleProjection(app)
        deliverStartedIntent("TOGGLE_NATIVE_DASHBOARD_PROJECTION")
        overlayWorker.runNext(); idle()
        assertTrue(overlayBridge.writes.isEmpty())
        assertFalse(NativeDashboardSettings.overlayRestoreRequired(app))
        assertTrue(NativeDashboardService.status.contains("window"))
    }

    @Test fun overlayOpacityComparisonAndPageSelectionNeverWriteVehicleMode() {
        app.applicationInfo.flags = app.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
        NativeDashboardSettings.setStyle(app, DashboardStyle.OEM_OVERLAY)
        display(); start(); drawFirstFrame()
        val original = overlayPanel
        for (alpha in listOf(0.8f, 1f, 0f, 0.5f)) {
            service.onStartCommand(Intent(app, NativeDashboardService::class.java)
                .setAction("com.shilapi.xcertplay.TEST_OEM_OVERLAY_ALPHA").putExtra("alpha", alpha), 0, 2)
            assertSame(original, overlayPanel)
            assertEquals(if (alpha == 0.8f) 0.8f else 1f, overlayPanel!!.params.alpha, 0f)
            assertTrue(overlayBridge.writes.isEmpty()); assertTrue(bridge.writes.isEmpty())
        }
        assertEquals(1f, overlayPanel!!.params.alpha, 0f)
        service.onStartCommand(Intent(app, NativeDashboardService::class.java)
            .setAction("com.shilapi.xcertplay.SELECT_NATIVE_DASHBOARD_PAGE").putExtra("page", DashboardMode.ALL.ordinal), 0, 3)
        assertEquals(DashboardMode.ALL, NativeDashboardSettings.mode(app))
        assertTrue(overlayBridge.writes.isEmpty())
    }

    @Test fun overlayDisplayLossRemovesWindowAndRejectsQueuedEnable() {
        NativeDashboardSettings.setStyle(app, DashboardStyle.OEM_OVERLAY)
        val screen = display(); start(); drawFirstFrame()
        val original = overlayPanel!!
        NativeDashboardService.toggleProjection(app)
        deliverStartedIntent("TOGGLE_NATIVE_DASHBOARD_PROJECTION")
        shadowOf(screen).setState(Display.STATE_OFF)
        service.onDisplayChanged(4)
        assertNull(overlayPanel); assertFalse(original.isShowing)
        overlayWorker.runNext(); idle()
        assertTrue(overlayBridge.writes.isEmpty())
    }

    private fun compareDisplay(id: Int) {
        service.onStartCommand(Intent(app, NativeDashboardService::class.java)
            .setAction("com.shilapi.xcertplay.TEST_OEM_OVERLAY_DISPLAY").putExtra("display", id), 0, 4)
    }

    @Test fun comparisonOutputRequiresDebugAndExactPublicGeometryAndNeverWritesMode() {
        NativeDashboardSettings.setStyle(app, DashboardStyle.OEM_OVERLAY)
        display()
        val alternative = app.getSystemService(DisplayManager::class.java).getDisplay(3)
        shadowOf(alternative).setFlags(Display.FLAG_PRESENTATION)
        shadowOf(alternative).setState(Display.STATE_ON)
        start(); drawFirstFrame()
        app.applicationInfo.flags = app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE.inv()
        compareDisplay(3)
        assertEquals(4, overlayPanel!!.display.displayId)
        app.applicationInfo.flags = app.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
        compareDisplay(0); compareDisplay(2)
        assertEquals(4, overlayPanel!!.display.displayId)
        shadowOf(alternative).setFlags(Display.FLAG_PRESENTATION or Display.FLAG_PRIVATE)
        compareDisplay(3)
        assertEquals(4, overlayPanel!!.display.displayId)
        shadowOf(alternative).setFlags(Display.FLAG_PRESENTATION)
        compareDisplay(3); drawFirstFrame()
        assertEquals(3, overlayPanel!!.display.displayId)
        assertTrue(overlayBridge.writes.isEmpty())
        assertEquals(4, BoyueLSettings.display(app)!!.displayId)
        NativeDashboardService.toggleProjection(app)
        deliverStartedIntent("TOGGLE_NATIVE_DASHBOARD_PROJECTION")
        compareDisplay(4) // queued request must keep its prepared output
        assertEquals(3, overlayPanel!!.display.displayId)
        overlayWorker.runNext(); idle()
        assertEquals(listOf(3), overlayBridge.writes)
        compareDisplay(4) // active projection also must not move
        assertEquals(3, overlayPanel!!.display.displayId)
    }

    @Test fun comparisonOutputLossDoesNotFallBackToAnotherScreen() {
        app.applicationInfo.flags = app.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
        NativeDashboardSettings.setStyle(app, DashboardStyle.OEM_OVERLAY)
        display()
        val alternative = app.getSystemService(DisplayManager::class.java).getDisplay(3)
        shadowOf(alternative).setFlags(Display.FLAG_PRESENTATION)
        shadowOf(alternative).setState(Display.STATE_ON)
        start(); compareDisplay(3); drawFirstFrame()
        NativeDashboardService.toggleProjection(app)
        deliverStartedIntent("TOGGLE_NATIVE_DASHBOARD_PROJECTION")
        shadowOf(alternative).setState(Display.STATE_OFF)
        service.onDisplayChanged(3)
        overlayWorker.runNext(); idle()
        assertNull(overlayPanel)
        assertTrue(overlayBridge.writes.isEmpty())
    }

    private fun factoryMode() {
        NativeDashboardSettings.setStyle(app, DashboardStyle.OEM_OVERLAY)
        NativeDashboardSettings.setOverlayOutput(app, OemOverlayPath.FACTORY, 4)
    }
    private fun toggleOverlay() {
        NativeDashboardService.toggleProjection(app)
        deliverStartedIntent("TOGGLE_NATIVE_DASHBOARD_PROJECTION")
    }
    private fun selectOutput(path: OemOverlayPath, display: Int) {
        NativeDashboardService.selectOverlayOutput(app, path, display)
        deliverStartedIntent("SELECT_OEM_OVERLAY_OUTPUT")
    }

    @Test fun factoryModeStartsWithNoWindowAndModeOnlyTogglesTheBoundedWindow() {
        factoryMode(); display(); start()
        assertNull(overlayPanel)
        assertEquals(0, overlayBridge.reads)
        repeat(2) {
            toggleOverlay()
            assertNull(overlayPanel) // Wait for OEM confirmation before rendering anything.
            overlayWorker.runNext(); idle(); drawFirstFrame()
            val shown = checkNotNull(overlayPanel)
            assertEquals(720, shown.params.width)
            assertEquals(600, shown.params.x)
            assertEquals(0, overlayBridge.state)
            toggleOverlay(); overlayWorker.runNext(); idle()
            assertFalse(shown.isShowing)
            assertNull(overlayPanel)
        }
        assertTrue(overlayBridge.writes.isEmpty())
        assertTrue(bridge.writes.isEmpty())
        assertEquals(DashboardStyle.OEM_OVERLAY, NativeDashboardSettings.style(app))
    }

    @Test fun factoryModeRecoversOldModeThreeBeforeShowingAndNeverWritesThree() {
        factoryMode(); overlayBridge.state = 3
        NativeDashboardSettings.setOverlayRestoreRequired(app, true)
        display(); start(); toggleOverlay()
        assertNull(overlayPanel)
        overlayWorker.runNext(); idle()
        assertEquals(listOf(0), overlayBridge.writes)
        assertFalse(NativeDashboardSettings.overlayRestoreRequired(app))
        assertNotNull(overlayPanel)
        NativeDashboardService.disable(app); deliverStartedIntent("STOP_NATIVE_DASHBOARD")
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun failedRestoreOrUnknownFeedbackCannotOpenFactoryWindow() {
        factoryMode(); overlayBridge.state = 3; overlayBridge.acceptWrite = false
        NativeDashboardSettings.setOverlayRestoreRequired(app, true)
        display(); start(); toggleOverlay(); overlayWorker.runNext(); idle()
        assertNull(overlayPanel)
        assertTrue(NativeDashboardSettings.overlayRestoreRequired(app))
        overlayBridge.state = 7; overlayBridge.acceptWrite = true
        toggleOverlay(); overlayWorker.runNext(); idle()
        assertNull(overlayPanel)
        assertEquals(listOf(0), overlayBridge.writes)
    }

    @Test fun factoryWindowNeedsTheSelectedDisplayAndPermissionAndDoesNotAutoReopen() {
        factoryMode(); val screen = display(); start()
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false)
        toggleOverlay(); assertTrue(overlayWorker.pending.isEmpty()); assertNull(overlayPanel)
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        toggleOverlay()
        shadowOf(screen).setState(Display.STATE_OFF); service.onDisplayChanged(4)
        overlayWorker.runNext(); idle(); assertNull(overlayPanel)
        shadowOf(screen).setState(Display.STATE_ON); service.onDisplayChanged(4)
        assertNull(overlayPanel)
        toggleOverlay(); overlayWorker.runNext(); idle(); assertNotNull(overlayPanel)
        shadowOf(screen).setState(Display.STATE_OFF); service.onDisplayChanged(4)
        assertNull(overlayPanel)
        shadowOf(screen).setState(Display.STATE_ON); service.onDisplayChanged(4)
        assertNull(overlayPanel)
        assertTrue(overlayBridge.writes.isEmpty())
    }

    @Test fun outputChangeIsAtomicRestoresFirstAndNeverChangesTheFullDashboardTarget() {
        NativeDashboardSettings.setStyle(app, DashboardStyle.OEM_OVERLAY)
        display(); start(); drawFirstFrame()
        toggleOverlay(); overlayWorker.runNext(); idle()
        val old = overlayPanel
        selectOutput(OemOverlayPath.FACTORY, 3)
        assertEquals(4, NativeDashboardSettings.overlayDisplay(app))
        assertSame(old, overlayPanel)
        overlayWorker.runNext(); idle()
        assertEquals(listOf(3, 0), overlayBridge.writes)
        assertEquals(3, NativeDashboardSettings.overlayDisplay(app))
        assertEquals(OemOverlayPath.FACTORY, NativeDashboardSettings.overlayPath(app))
        assertNull(overlayPanel)
        assertEquals(4, BoyueLSettings.display(app)!!.displayId)
        assertEquals(DashboardStyle.OEM_OVERLAY, NativeDashboardSettings.style(app))
    }

    @Test fun failedOutputRestoreRetainsOldConfigurationAndWindow() {
        NativeDashboardSettings.setStyle(app, DashboardStyle.OEM_OVERLAY)
        display(); start(); drawFirstFrame(); toggleOverlay(); overlayWorker.runNext(); idle()
        val old = overlayPanel
        overlayBridge.acceptWrite = false
        selectOutput(OemOverlayPath.FACTORY, 3); overlayWorker.runNext(); idle()
        assertEquals(OemOverlayPath.DIM, NativeDashboardSettings.overlayPath(app))
        assertEquals(4, NativeDashboardSettings.overlayDisplay(app))
        assertSame(old, overlayPanel)
        assertTrue(NativeDashboardSettings.overlayRestoreRequired(app))
    }

    @Test fun repeatedFactoryToggleAndOutputChangeDuringReadbackDoNotQueueNewRequests() {
        factoryMode(); display(); start(); toggleOverlay()
        toggleOverlay(); selectOutput(OemOverlayPath.DIM, 3)
        assertEquals(1, overlayWorker.pending.size)
        overlayWorker.runNext(); idle()
        assertNotNull(overlayPanel)
        assertEquals(OemOverlayPath.FACTORY, NativeDashboardSettings.overlayPath(app))
        assertEquals(4, NativeDashboardSettings.overlayDisplay(app))
        assertTrue(overlayBridge.writes.isEmpty())
    }

}
