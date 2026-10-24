package com.shilapi.xcertplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Presentation
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import com.shilapi.xcertplay.host.R
import java.io.FileDescriptor
import java.io.PrintWriter

/** Owns the native display independently of CarPlayHostActivity's session and lifetime. */
class NativeDashboardService : Service(), DisplayManager.DisplayListener {
    private val handler = Handler(Looper.getMainLooper())
    private var panel: Presentation? = null
    private var overlayPanel: Fx11OemOverlayWindow? = null
    private var comparisonDisplayId: Int? = null
    // The default route owns only a bounded Android window, while DIM stays in OEM state 0.
    // Never persist visibility across a process restart or infer it from DIM feedback.
    private var factoryOverlayEnabled = false
    private var displays: DisplayManager? = null
    private var alive = false
    private var panelDrawn = false
    private var stopping = false
    private var panelStyle: DashboardStyle? = null
    private var dteButton: Fx11DteMonitor? = null
    private lateinit var projection: Fx11ThreeScreenController
    private val retry = object : Runnable {
        override fun run() { updatePanel(); if (alive) handler.postDelayed(this, 3000) }
    }
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLocale.wrap(newBase)) }
    override fun onBind(intent: Intent?): IBinder? = null
    /** Framework dumpsys reads cached diagnostics only; it never reads or changes OEM settings. */
    public override fun dump(fd: FileDescriptor?, writer: PrintWriter, args: Array<out String>?) {
        val report = buildString {
            appendLine("--- FX11 native dashboard diagnostics ---")
            appendLine("style=${NativeDashboardSettings.style(this@NativeDashboardService)} " +
                "page=${NativeDashboardSettings.mode(this@NativeDashboardService)} " +
                "pageKey=${NativeDashboardSettings.pageKey(this@NativeDashboardService)}")
            appendLine("overlayRestoreRequired=${NativeDashboardSettings.overlayRestoreRequired(this@NativeDashboardService)}")
            appendLine("overlayPath=${NativeDashboardSettings.overlayPath(this@NativeDashboardService)} " +
                "androidOutput=${comparisonDisplayId ?: NativeDashboardSettings.overlayDisplay(this@NativeDashboardService)} " +
                "factoryWindowEnabled=$factoryOverlayEnabled bounds=${Fx11OemOverlayWindow.bounds()}")
            appendLine("phoneContent mapStream=${MapMirrors.streamActive} " +
                "navigation=${NativeDashboardRuntime.state.navigation != null} " +
                "musicTitle=${!NativeDashboardRuntime.state.music.info.title.isNullOrBlank()}; selected page controls visibility")
            appendLine(status)
            overlayPanel?.view?.mapDiagnostic?.let { appendLine(it) }
            appendLine(NativeDashboardRuntime.status)
            appendLine(GeelyWheelKeys.diagnosticReport())
        }
        report.lineSequence().forEach { line -> DiagnosticRedactor.redact(line)?.let(writer::println) }
    }
    override fun onCreate() {
        super.onCreate()
        BoyueLSettings.migrateDashboardControls(this)
        alive = true
        projection = newProjection(NativeDashboardSettings.style(this))
        displays = getSystemService(DisplayManager::class.java)
        displays?.registerDisplayListener(this, handler)
        val notifications = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.settings_dashboard_title), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 19,
            Intent(this, DiPlayActivity::class.java).putExtra("page", "settings"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 20, Intent(this, NativeDashboardService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        startForeground(1941, builder.setSmallIcon(R.drawable.ic_dp_dashboard)
            .setContentTitle(getString(R.string.settings_dashboard_title))
            .setContentText(getString(R.string.dashboard_notification)).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.settings_dashboard_off), stop).build()).build())
    }
    private fun newProjection(style: DashboardStyle): Fx11ThreeScreenController =
        (if (style == DashboardStyle.OEM_OVERLAY) overlayControllerFactory(this) else controllerFactory()).also { it.start(this) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Parked-device diagnostic only. Never persist a speculative display mapping or
        // change outputs while this service owns an active/pending OEM projection.
        if (intent?.action == "com.shilapi.xcertplay.TEST_OEM_OVERLAY_DISPLAY") {
            val id = intent.getIntExtra("display", -1)
            if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
                !stopping && NativeDashboardSettings.style(this) == DashboardStyle.OEM_OVERLAY &&
                !factoryOverlayEnabled && !NativeDashboardSettings.overlayRestoreRequired(this) && !projection.hasPendingRequest &&
                comparisonDisplay(id) != null) {
                comparisonDisplayId = id
                updatePanel()
            }
            return START_STICKY
        }
        // Non-exported service: controlled comparison for this debug test build only.
        // It changes window opacity, never the vehicle mode, display target or settings.
        if (intent?.action == "com.shilapi.xcertplay.TEST_OEM_OVERLAY_ALPHA") {
            if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 && !stopping) {
                val alpha = intent.getFloatExtra("alpha", 1f)
                if (alpha == 1f || alpha == 0.8f) overlayPanel?.let {
                    it.setAlpha(alpha)
                    panelStatus = "Display ${it.display.displayId}: OEM overlay type=2038 alpha=$alpha; physical composition unverified"
                }
            }
            return START_STICKY
        }
        if (intent?.action == "com.shilapi.xcertplay.SELECT_NATIVE_DASHBOARD_PAGE") {
            DashboardMode.entries.getOrNull(intent.getIntExtra("page", -1))?.let {
                if (NativeDashboardSettings.enabled(this)) NativeDashboardSettings.setMode(this, it)
            }
            return START_STICKY
        }
        if (intent?.action == SELECT_STYLE) {
            val selected = DashboardStyle.entries.getOrNull(intent.getIntExtra("style", -1))
            if (selected != null && BoyueLSettings.selected(this)) changeStyle(selected)
            else if (!NativeDashboardSettings.enabled(this)) stopSelf()
            return if (NativeDashboardSettings.enabled(this) || stopping) START_STICKY else START_NOT_STICKY
        }
        if (intent?.action == SELECT_OVERLAY_OUTPUT) {
            val path = OemOverlayPath.entries.getOrNull(intent.getIntExtra("path", -1))
            val display = intent.getIntExtra("display", -1)
            if (path != null && display in listOf(3, 4) && BoyueLSettings.selected(this) &&
                NativeDashboardSettings.style(this) == DashboardStyle.OEM_OVERLAY) changeOverlayOutput(path, display)
            return START_STICKY
        }
        if (intent?.action == STOP) {
            stopAfterRestoringOem()
            return if (NativeDashboardSettings.enabled(this)) START_STICKY else START_NOT_STICKY
        }
        if (!NativeDashboardSettings.enabled(this)) { stopSelf(); return START_NOT_STICKY }
        GeelyWheelKeys.startDashboard(this)
        if (dteButton == null) {
            dteButton = dteMonitorFactory {
                // An explicitly learned OneOS key takes precedence, preventing a double flip.
                if (alive && !stopping && NativeDashboardSettings.enabled(this) && BoyueLSettings.wheel(this) &&
                    NativeDashboardSettings.pageKey(this) == 0 && !BoyueLSettings.hasConfiguredPageAction(this) &&
                    !GeelyWheelKeys.isDashboardPageKeyLearning()) {
                    val next = NativeDashboardSettings.cycle(this)
                    android.util.Log.i(Fx11DteButtonMonitor.TAG, "candidate pulse applied page=$next; physical RESET unconfirmed")
                } else android.util.Log.i(Fx11DteButtonMonitor.TAG, "candidate pulse ignored by dashboard/keys/learning policy")
            }.also { activeDteMonitor = it; it.start() }
        }
        updatePanel()
        if (intent?.action == TOGGLE && !stopping) {
            if (factoryOverlay()) toggleFactoryOverlay() else projection.toggle(panelReady()) { result ->
                recordResult(result)
                android.util.Log.i("DiPlay-Dashboard", projectionStatus)
                android.widget.Toast.makeText(this, getString(when {
                    !result.success -> R.string.dashboard_projection_failed
                    result.after == 0 -> R.string.dashboard_projection_oem
                    NativeDashboardSettings.style(this) == DashboardStyle.OEM_OVERLAY -> R.string.dashboard_oem_overlay_active
                    else -> R.string.dashboard_projection_local
                }), android.widget.Toast.LENGTH_LONG).show()
            }
        }
        handler.removeCallbacks(retry); handler.post(retry)
        return START_STICKY
    }
    private fun recordResult(result: Fx11ThreeScreenController.Result) {
        projectionStatus = result.message
        if (result.success && result.after != null) BoyueLSettings.syncDimForDashboard(this,
            result.after == 0 || NativeDashboardSettings.style(this) == DashboardStyle.OEM_OVERLAY)
        if (NativeDashboardSettings.style(this) == DashboardStyle.OEM_OVERLAY && result.success && result.after == 0)
            NativeDashboardSettings.setOverlayRestoreRequired(this, false)
    }

    private fun factoryOverlay() = NativeDashboardSettings.style(this) == DashboardStyle.OEM_OVERLAY &&
        NativeDashboardSettings.overlayPath(this) == OemOverlayPath.FACTORY

    private fun toggleFactoryOverlay() {
        val enable = !factoryOverlayEnabled
        val requestedDisplay = targetDisplay()?.displayId
        if (enable && (requestedDisplay == null || !android.provider.Settings.canDrawOverlays(this))) {
            projectionStatus = "Central overlay window unavailable: output/overlay permission missing; no vehicle write"
            android.widget.Toast.makeText(this, R.string.dashboard_projection_failed, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        // In particular, recover an outstanding v25 mode-3 request before adding our window.
        // This route never sends 3. A confirmed 0 says nothing about final QNX visibility.
        stopping = true
        projection.restoreOem { result ->
            stopping = false
            recordResult(result)
            if (result.success) {
                factoryOverlayEnabled = enable && targetDisplay()?.displayId == requestedDisplay &&
                    android.provider.Settings.canDrawOverlays(this)
                updatePanel()
                if (factoryOverlayEnabled && overlayPanel?.isShowing != true) factoryOverlayEnabled = false
                projectionStatus += "; centralWindow=$factoryOverlayEnabled; physical composition unverified"
            }
            android.util.Log.i("DiPlay-Dashboard", projectionStatus)
            android.widget.Toast.makeText(this, getString(when {
                !result.success || (enable && !factoryOverlayEnabled) -> R.string.dashboard_projection_failed
                factoryOverlayEnabled -> R.string.dashboard_oem_overlay_active
                else -> R.string.dashboard_projection_oem
            }), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun changeOverlayOutput(path: OemOverlayPath, display: Int) {
        if (stopping || projection.hasPendingRequest) return
        if (path == NativeDashboardSettings.overlayPath(this) && display == NativeDashboardSettings.overlayDisplay(this)) return
        fun applyOutput() {
            factoryOverlayEnabled = false
            projection.updateDashboardReady(false)
            dismissPanel(); panelDrawn = false; comparisonDisplayId = null
            NativeDashboardSettings.setOverlayOutput(this, path, display)
            onStartCommand(null, 0, 0)
        }
        if (!NativeDashboardSettings.overlayRestoreRequired(this) && !factoryOverlayEnabled) { applyOutput(); return }
        stopping = true
        projection.restoreOem { result ->
            stopping = false
            recordResult(result)
            if (result.success) applyOutput()
            else android.widget.Toast.makeText(this, R.string.dashboard_projection_failed, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun changeStyle(selected: DashboardStyle) {
        if (stopping) return
        val previous = NativeDashboardSettings.style(this)
        if (selected == previous) { onStartCommand(null, 0, 0); return }
        if (selected == DashboardStyle.OFF) { stopAfterRestoringOem(); return }
        fun applySelection() {
            factoryOverlayEnabled = false
            projection.updateDashboardReady(false)
            dismissPanel(); panelDrawn = false
            projection.close()
            NativeDashboardSettings.setStyle(this, selected)
            projection = newProjection(selected)
            onStartCommand(null, 0, 0)
        }
        val crossingOverlay = (previous == DashboardStyle.OEM_OVERLAY) != (selected == DashboardStyle.OEM_OVERLAY)
        val needsRestore = previous != DashboardStyle.OFF && crossingOverlay &&
            (previous != DashboardStyle.OEM_OVERLAY || NativeDashboardSettings.overlayRestoreRequired(this) || projection.hasPendingRequest)
        if (!needsRestore) { applySelection(); return }
        stopping = true
        projection.restoreOem { result ->
            stopping = false
            recordResult(result)
            if (result.success) applySelection()
            else android.widget.Toast.makeText(this, R.string.dashboard_projection_failed, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun panelReady() = panelDrawn && panelShowing() &&
        targetDisplay()?.displayId == panelDisplayId()
    private fun comparisonDisplay(id: Int): android.view.Display? {
        if (id != 3 && id != 4) return null
        return displays?.getDisplay(id)?.takeIf {
            val size = ClusterMapPresentation.sizeOf(it)
            size.x == 1920 && size.y == 532 && it.isValid && it.state == android.view.Display.STATE_ON &&
                it.flags and android.view.Display.FLAG_PRESENTATION != 0 &&
                it.flags and android.view.Display.FLAG_PRIVATE == 0
        }
    }
    private fun targetDisplay(): android.view.Display? {
        if (NativeDashboardSettings.style(this) == DashboardStyle.OEM_OVERLAY) {
            return comparisonDisplay(comparisonDisplayId ?: NativeDashboardSettings.overlayDisplay(this))
        } else comparisonDisplayId = null
        return BoyueLSettings.display(this)
    }
    private fun panelShowing() = panel?.isShowing == true || overlayPanel?.isShowing == true
    private fun panelDisplayId() = overlayPanel?.display?.displayId ?: panel?.display?.displayId
    private fun dismissPanel() {
        overlayPanel?.dismiss(); overlayPanel = null
        panel?.dismiss(); panel = null
    }

    private fun stopAfterRestoringOem() {
        if (stopping) return
        if (NativeDashboardSettings.style(this) == DashboardStyle.OEM_OVERLAY && !NativeDashboardSettings.overlayRestoreRequired(this) && !projection.hasPendingRequest) {
            NativeDashboardSettings.setStyle(this, DashboardStyle.OFF)
            stopSelf()
            return
        }
        stopping = true
        projection.restoreOem { result ->
            stopping = false
            recordResult(result)
            android.util.Log.i("DiPlay-Dashboard", projectionStatus)
            if (result.success) {
                NativeDashboardSettings.setStyle(this, DashboardStyle.OFF)
                stopSelf()
            } else {
                // Keep a valid window alive if OEM state is unknown; dismissing alone can leave it black.
                android.widget.Toast.makeText(this, R.string.dashboard_projection_failed, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    private fun updatePanel() {
        if (!NativeDashboardSettings.enabled(this)) { stopSelf(); return }
        if (factoryOverlay() && !factoryOverlayEnabled) {
            panelDrawn = false
            projection.updateDashboardReady(false)
            dismissPanel()
            panelStatus = "Central overlay off; Android output=${NativeDashboardSettings.overlayDisplay(this)}; " +
                "awaiting explicit MODE/toggle to confirm OEM state 0; no fullscreen window"
            return
        }
        val display = targetDisplay()
        if (display == null) {
            factoryOverlayEnabled = false
            panelDrawn = false
            projection.updateDashboardReady(false)
            dismissPanel()
            panelStatus = getString(R.string.dashboard_waiting_display)
            return
        }
        if (panelShowing() && panelDisplayId() == display.displayId && panelStyle == NativeDashboardSettings.style(this)) {
            projection.updateDashboardReady(panelReady())
            return
        }
        panelDrawn = false
        projection.updateDashboardReady(false)
        dismissPanel()
        val style = NativeDashboardSettings.style(this)
        if (style == DashboardStyle.OEM_OVERLAY) {
            showOverlay(display)
            return
        }
        val next = Presentation(this, display)
        next.setOnDismissListener { if (panel === next) {
            panel = null; panelDrawn = false; projection.updateDashboardReady(false)
        } }
        try {
            val view = NativeDashboardView(next.context)
            next.setContentView(view)
            val firstDraw = object : android.view.ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    view.post {
                        if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(this)
                        if (alive && panel === next && next.isShowing) {
                            panelDrawn = true
                            panelStatus = "Display ${display.displayId} (${display.name}): $style 1920×532 first draw complete"
                            projection.updateDashboardReady(panelReady())
                        }
                    }
                }
            }
            view.viewTreeObserver.addOnDrawListener(firstDraw)
            next.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            next.window?.decorView?.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            next.show()
            panel = next
            panelStyle = style
            panelStatus = "Display ${display.displayId} (${display.name}): $style 1920×532 presentation shown; waiting for first draw"
        } catch (error: RuntimeException) {
            next.dismiss()
            panelStatus = "Native display unavailable: ${error.javaClass.simpleName}: ${error.message}"
            android.util.Log.w("DiPlay-Dashboard", panelStatus)
        }
    }
    private fun showOverlay(display: android.view.Display) {
        val next = try { Fx11OemOverlayWindow(this, display) } catch (error: RuntimeException) {
            panelStatus = "OEM overlay context unavailable: ${error.javaClass.simpleName}"
            return
        }
        val listener = object : android.view.ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                next.view.post {
                    if (next.view.viewTreeObserver.isAlive) next.view.viewTreeObserver.removeOnDrawListener(this)
                    if (alive && overlayPanel === next && next.isShowing) {
                        panelDrawn = true
                        panelStatus = "Display ${display.displayId}: central overlay type=2038 " +
                            "bounds=${Fx11OemOverlayWindow.bounds()} path=${NativeDashboardSettings.overlayPath(this@NativeDashboardService)} " +
                            "alpha=${next.params.alpha} first draw complete; physical composition unverified"
                        projection.updateDashboardReady(panelReady())
                    }
                }
            }
        }
        next.view.viewTreeObserver.addOnDrawListener(listener)
        next.view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: android.view.View) = Unit
            override fun onViewDetachedFromWindow(view: android.view.View) {
                if (overlayPanel === next) { panelDrawn = false; projection.updateDashboardReady(false) }
            }
        })
        try {
            next.show()
            overlayPanel = next
            panelStyle = DashboardStyle.OEM_OVERLAY
            panelStatus = "Display ${display.displayId}: central overlay type=2038 " +
                "bounds=${Fx11OemOverlayWindow.bounds()} path=${NativeDashboardSettings.overlayPath(this)} shown; waiting for first draw"
        } catch (error: RuntimeException) {
            next.dismiss()
            panelStatus = "OEM overlay window unavailable: ${error.javaClass.simpleName}: ${error.message}"
            android.util.Log.w("DiPlay-Dashboard", panelStatus)
        }
    }
    override fun onDisplayAdded(displayId: Int) { updatePanel() }
    override fun onDisplayChanged(displayId: Int) { updatePanel() }
    override fun onDisplayRemoved(displayId: Int) { updatePanel() }
    override fun onDestroy() {
        alive = false
        dteButton?.close()
        if (activeDteMonitor === dteButton) activeDteMonitor = null
        dteButton = null
        panelDrawn = false
        projection.updateDashboardReady(false)
        projection.close()
        handler.removeCallbacksAndMessages(null)
        displays?.unregisterDisplayListener(this)
        dismissPanel()
        GeelyWheelKeys.stopDashboard()
        panelStatus = "Native dashboard stopped"
        super.onDestroy()
    }
    companion object {
        internal var dteMonitorFactory: (() -> Unit) -> Fx11DteMonitor = { Fx11DteButtonMonitor(it) }
        private var activeDteMonitor: Fx11DteMonitor? = null
        internal var overlayControllerFactory: (Context) -> Fx11ThreeScreenController = { context ->
            Fx11ThreeScreenController(bridgeFactory = { Fx11OemOverlayBridge() }, mode = Fx11ProjectionMode.OEM_OVERLAY,
                onWriteAttempt = { value -> if (value == 3) NativeDashboardSettings.setOverlayRestoreRequired(context, true) })
        }
        private const val SELECT_STYLE = "com.shilapi.xcertplay.SELECT_NATIVE_DASHBOARD_STYLE"
        private const val SELECT_OVERLAY_OUTPUT = "com.shilapi.xcertplay.SELECT_OEM_OVERLAY_OUTPUT"
        internal fun selectOverlayOutput(context: Context, path: OemOverlayPath, display: Int) {
            require(display == 3 || display == 4)
            val intent = Intent(context, NativeDashboardService::class.java).setAction(SELECT_OVERLAY_OUTPUT)
                .putExtra("path", path.ordinal).putExtra("display", display)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            }.onFailure { panelStatus = "Overlay output change failed: ${it.message}" }
        }
        internal fun selectStyle(context: Context, style: DashboardStyle) {
            launch(context, SELECT_STYLE, style)
        }
        private const val CHANNEL = "native-dashboard"
        private const val STOP = "com.shilapi.xcertplay.STOP_NATIVE_DASHBOARD"
        private const val TOGGLE = "com.shilapi.xcertplay.TOGGLE_NATIVE_DASHBOARD_PROJECTION"
        internal var controllerFactory: () -> Fx11ThreeScreenController = { Fx11ThreeScreenController() }
        private var panelStatus = "Native dashboard stopped"
        private var projectionStatus = "Three-screen state not read"
        val status get() = "$panelStatus\n$projectionStatus\n" +
            (activeDteMonitor?.diagnosticReport() ?: "DTE candidate observer idle; physical RESET unconfirmed")
        fun toggleProjection(context: Context) {
            if (NativeDashboardSettings.enabled(context)) launch(context, TOGGLE)
        }
        fun disable(context: Context) { launch(context, STOP) }
        private fun launch(context: Context, action: String? = null, style: DashboardStyle? = null) {
            val intent = Intent(context, NativeDashboardService::class.java).setAction(action).apply { style?.let { putExtra("style", it.ordinal) } }
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            }.onFailure { panelStatus = "Native dashboard start failed: ${it.message}" }
        }
        fun refresh(context: Context) {
            val intent = Intent(context, NativeDashboardService::class.java)
            if (!NativeDashboardSettings.enabled(context)) { context.stopService(intent); return }
            launch(context)
        }
    }
}
