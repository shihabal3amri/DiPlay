package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.geely.lib.oneosapi.input.IInputListener
import com.geely.lib.oneosapi.input.IInputManager
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.network.GeelyHeadUnit

/**
 * Observe the OEM OneOS input service: it does not produce Android KeyEvents for these wheel keys.
 * No key interception is requested. The OEM's own action can still occur for a subscribed key.
 * Bluetooth AVRCP owns media actions when car-Bluetooth audio is enabled, avoiding double playback.
 */
internal object GeelyWheelKeys {
    private const val TAG = "DiPlay-GeelyWheel"
    private const val INPUT_PACKAGE = "com.geely.inputservice"
    private const val INPUT_CLASS = "com.geely.inputservice.InputService"
    private const val RETRY_MILLIS = 3_000L
    internal const val LEARN_TIMEOUT_MILLIS = 45_000L
    internal const val DIAGNOSTIC_EVENT_LIMIT = 64

    private val handler = Handler(Looper.getMainLooper())
    private val learningHandler = Handler(Looper.getMainLooper())
    @Volatile private var appContext: Context? = null
    private var bound = false
    private var manager: IInputManager? = null
    private var listener: IInputListener? = null
    private var connection: ServiceConnection? = null
    private var bindingEpoch = 0L
    @Volatile private var listenerEpoch = 0L
    private var mediaOwner = false
    private var dashboardOwner = false
    private var registeredCodes = emptyList<Int>()
    private val diagnosticsLock = Any()
    private val diagnosticEvents = ArrayDeque<String>()
    private var diagnosticEventCount = 0L
    private var loggedEventsThisRegistration = 0
    private val dashboardDebounce = DashboardWheelDebounce()
    private val actionDebounce = WheelActionDebounce()
    internal var menuMonitorFactory: (Context) -> Fx11MenuMonitor = { Fx11DimMenuMonitor(it) }
    @Volatile private var menuMonitor: Fx11MenuMonitor? = null
    private var menuRoute = "No track-key routing decision"
    private class Learning(val callback: (Int) -> Unit) {
        val debounce = DashboardWheelDebounce()
        var candidate: Int? = null
        var timeout: Runnable? = null
    }
    @Volatile private var learning: Learning? = null
    var learningStatus = "Page-key learning idle"
        private set

    /** Bounded input evidence; raw OEM event integers are deliberately not interpreted as gestures. */
    @Synchronized fun diagnosticReport(): String = buildString {
        append("Geely wheel bound=$bound connected=${manager != null} listenerEpoch=$listenerEpoch ")
        append("mediaOwner=$mediaOwner dashboardOwner=$dashboardOwner learning=${learning != null}\n")
        append("modeKey=${appContext?.let(NativeDashboardSettings::modeKey) ?: NativeDashboardSettings.MODE_KEY} " +
            "pageKey=${appContext?.let(NativeDashboardSettings::pageKey) ?: 0}\n")
        append("registeredCodes=${registeredCodes.joinToString()}\n")
        val passive = DashboardWheelKeyPolicy.passivelyObservesCandidates(dashboardOwner,
            appContext?.let(NativeDashboardSettings::pageKey) ?: 0, learning != null)
        append("passiveCandidates=$passive; observation never saves a page key or starts learning\n")
        append("$learningStatus\n")
        appendLine(menuMonitor?.diagnosticReport() ?: "DIM menu observer idle; mode=UNKNOWN")
        appendLine("menuPagingActive=${appContext?.let(::isMenuPagingActive) ?: false}")
        appendLine(menuRoute)
        synchronized(diagnosticsLock) {
            append("callbacks=$diagnosticEventCount retained=${diagnosticEvents.size}/$DIAGNOSTIC_EVENT_LIMIT; raw events observe only\n")
            diagnosticEvents.forEach { append(it).append('\n') }
        }
    }

    private fun recordCallback(type: String, keyCode: Int, event: Int?,
                               softKeyFunction: Int, token: Long) = synchronized(diagnosticsLock) {
        if (token != listenerEpoch || appContext == null) return@synchronized
        val line = "type=$type key=$keyCode event=${event ?: "n/a"} function=$softKeyFunction " +
            "uptimeMs=${SystemClock.uptimeMillis()} epoch=$token learning=${learning != null}"
        diagnosticEventCount++
        if (diagnosticEvents.size == DIAGNOSTIC_EVENT_LIMIT) diagnosticEvents.removeFirst()
        diagnosticEvents.addLast(line)
        // A noisy OEM callback cannot produce an unbounded stream of logcat lines.
        if (loggedEventsThisRegistration < DIAGNOSTIC_EVENT_LIMIT) {
            Log.i(TAG, "callback $line")
            loggedEventsThisRegistration++
            if (loggedEventsThisRegistration == DIAGNOSTIC_EVENT_LIMIT) {
                Log.i(TAG, "callback log limit reached; latest $DIAGNOSTIC_EVENT_LIMIT remain in diagnostic report")
            }
        }
    }

    @Synchronized fun startDashboard(context: Context) {
        if (dashboardOwner) return
        dashboardOwner = true
        appContext = context.applicationContext
        refresh()
    }

    @Synchronized fun stopDashboard() {
        dashboardOwner = false
        stopMenuMonitor()
        if (mediaOwner || learning != null) refresh() else unbind()
    }

    private fun wheelEnabled(context: Context) = !BoyueLSettings.selected(context) || BoyueLSettings.wheel(context)

    /** Cached state only: drawing a hint must never query an OEM Binder on the UI thread. */
    @Synchronized fun isMenuPagingActive(context: Context): Boolean =
        menuPagingReady(context, menuMonitor?.mode() ?: Fx11WheelMode.UNKNOWN)

    // Both the indicator and key dispatch use this gate, including registration failures/disconnects.
    private fun menuPagingReady(context: Context, mode: Fx11WheelMode): Boolean =
        dashboardOwner && NativeDashboardSettings.enabled(context) && wheelEnabled(context) &&
            learning == null && mode == Fx11WheelMode.DASHBOARD && manager != null && listener != null &&
            registeredCodes.containsAll(Fx11MenuWheelPolicy.trackKeys)

    private fun stopMenuMonitor() {
        menuMonitor?.close()
        menuMonitor = null
        menuRoute = "Menu observer stopped; no retained mode"
    }

    private fun updateMenuMonitor(context: Context) {
        if (dashboardOwner && NativeDashboardSettings.enabled(context) && wheelEnabled(context)) {
            if (menuMonitor == null) menuMonitor = menuMonitorFactory(context).also { it.start() }
        } else stopMenuMonitor()
    }

    @Synchronized fun start(context: Context) {
        if (!GeelyHeadUnit.detected && !BoyueLSettings.selected(context)) return
        val changed = !mediaOwner
        mediaOwner = true
        appContext = context.applicationContext
        if (changed) refresh() else if (!bound) bindLocked()
    }

    /** Releases media ownership only; an offline native dashboard still owns both dashboard keys. */
    @Synchronized fun stop() {
        mediaOwner = false
        if (dashboardOwner || learning != null) refresh() else unbind()
    }

    /**
     * Observe explicit display/menu/media candidates for at most 45s. Callback is on main, once per learning
     * session; it never saves a key. The UI must confirm before setPageKey + refresh, then cancel.
     * While learning, no DiPlay media, page or projection action is dispatched.
     */
    @Synchronized fun learnDashboardPageKey(context: Context, onLearned: (Int) -> Unit): () -> Unit {
        learning?.timeout?.let(learningHandler::removeCallbacks)
        val session = Learning(onLearned)
        learning = session
        learningStatus = "Listening for a display/menu/media short press; explicit candidates only; raw callbacks recorded; no key interception"
        appContext = context.applicationContext
        refresh()
        session.timeout = Runnable { finishLearning(session, "Page-key learning timed out; no key saved") }
            .also { learningHandler.postDelayed(it, LEARN_TIMEOUT_MILLIS) }
        return { finishLearning(session, "Page-key learning ended; key is saved only by explicit confirmation") }
    }

    @Synchronized private fun finishLearning(session: Learning, status: String) {
        if (learning !== session) return
        session.timeout?.let(learningHandler::removeCallbacks)
        learning = null
        learningStatus = status
        if (mediaOwner || dashboardOwner) refresh() else unbind()
    }

    /** Invalidate Binder callbacks and queued UI dispatch before unregistering the old binder. */
    private fun unbind() {
        actionDebounce.reset()
        bindingEpoch++
        listenerEpoch++
        listener?.let { registered ->
            appContext?.packageName?.let { name -> runCatching { manager?.unregisterListener(registered, name) } }
        }
        listener = null
        manager = null
        registeredCodes = emptyList()
        handler.removeCallbacksAndMessages(null)
        val context = appContext
        val oldConnection = connection
        if (bound && context != null && oldConnection != null) runCatching { context.unbindService(oldConnection) }
        connection = null
        bound = false
        appContext = null
        if (!mediaOwner && !dashboardOwner && learning == null) dashboardDebounce.reset()
    }

    private fun bindLocked() {
        val context = appContext ?: return
        if (!wheelEnabled(context) && learning == null) return
        val token = ++bindingEpoch
        val next = newConnection(context, token)
        connection = next
        val intent = Intent().setComponent(ComponentName(INPUT_PACKAGE, INPUT_CLASS))
        bound = runCatching { context.bindService(intent, next, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        Log.i(TAG, "bind geely input service requested=$bound epoch=$token")
        if (!bound) handler.postDelayed({
            synchronized(this) { if (bindingEpoch == token && appContext != null && !bound) bindLocked() }
        }, RETRY_MILLIS)
    }

    private fun newConnection(context: Context, token: Long): ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            synchronized(this@GeelyWheelKeys) {
                if (bindingEpoch != token || !bound || appContext !== context ||
                    (!wheelEnabled(context) && learning == null)) return
                val input = IInputManager.Stub.asInterface(service) ?: return
                listener?.let { previous -> runCatching { manager?.unregisterListener(previous, context.packageName) } }
                val eventEpoch = ++listenerEpoch
                synchronized(diagnosticsLock) { loggedEventsThisRegistration = 0 }
                val registered = object : IInputListener.Stub() {
                    override fun onKeyCodeEvent(keyCode: Int, event: Int, softKeyFunction: Int) {
                        recordCallback("raw", keyCode, event, softKeyFunction, eventEpoch)
                    }
                    override fun onShortClick(keyCode: Int, softKeyFunction: Int) {
                        recordCallback("short", keyCode, null, softKeyFunction, eventEpoch)
                        enqueue(keyCode, false, softKeyFunction, eventEpoch)
                    }
                    override fun onHoldingPressStarted(keyCode: Int, softKeyFunction: Int) {
                        recordCallback("holdingStarted", keyCode, null, softKeyFunction, eventEpoch)
                        holding(keyCode, true, eventEpoch)
                    }
                    override fun onHoldingPressStopped(keyCode: Int, softKeyFunction: Int) {
                        recordCallback("holdingStopped", keyCode, null, softKeyFunction, eventEpoch)
                        holding(keyCode, false, eventEpoch)
                    }
                    override fun onLongPressTriggered(keyCode: Int, softKeyFunction: Int) {
                        recordCallback("long", keyCode, null, softKeyFunction, eventEpoch)
                        enqueue(keyCode, true, softKeyFunction, eventEpoch)
                    }
                    override fun onDoubleClick(keyCode: Int, softKeyFunction: Int) {
                        recordCallback("double", keyCode, null, softKeyFunction, eventEpoch)
                    }
                }
                manager = input
                listener = registered
                registeredCodes = emptyList()
                val codes = DashboardWheelKeyPolicy.registeredCodes(mediaOwner, dashboardOwner,
                    AirPlayPersistence.loadCarBluetoothAudio(context), NativeDashboardSettings.pageKey(context), learning != null,
                    NativeDashboardSettings.modeKey(context), menuPaging = menuMonitor != null).toMutableSet()
                if (learning == null && BoyueLSettings.selected(context)) {
                    for (key in BoyueLSettings.configurableKeys(context)) for (longPress in listOf(false, true)) {
                        val action = BoyueLSettings.action(context, key, longPress)
                        if (action != BoyueWheelAction.NONE &&
                            ((action.dashboard && dashboardOwner) || (!action.dashboard && mediaOwner &&
                                (!AirPlayPersistence.loadCarBluetoothAudio(context) || key == GeelyWheelKeyCodes.KEY_VOICE_ASSIST)))) codes.add(key)
                    }
                }
                // Never register an empty array: the OEM meaning of that value is not established.
                if (codes.isEmpty()) return
                runCatching { input.registerListener(registered, context.packageName, codes.toIntArray()) }
                    .onSuccess {
                        registeredCodes = codes.toList()
                        Log.i(TAG, "registered epoch=$eventEpoch codes=${codes.joinToString()} learning=${learning != null}")
                    }
                    .onFailure { Log.w(TAG, "registerListener failed", it) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(this@GeelyWheelKeys) {
                if (bindingEpoch != token) return
                listenerEpoch++
                manager = null
                listener = null
                registeredCodes = emptyList()
                if (appContext == null) return
                handler.postDelayed({
                    synchronized(this@GeelyWheelKeys) {
                        if (bindingEpoch == token && appContext != null && manager == null) refresh()
                    }
                }, RETRY_MILLIS)
            }
        }
        override fun onBindingDied(name: ComponentName?) = onServiceDisconnected(name)
        override fun onNullBinding(name: ComponentName?) = onServiceDisconnected(name)
    }

    @Synchronized fun refresh() {
        val context = appContext ?: return
        updateMenuMonitor(context)
        unbind()
        if (mediaOwner || dashboardOwner || learning != null) {
            appContext = context
            bindLocked()
        }
    }

    // Retained as a narrow local dispatch entry for existing device-free integration tests.
    internal fun isDashboardPageKeyLearning(): Boolean = synchronized(this) { learning != null }

    private fun forward(keyCode: Int, longPress: Boolean) = enqueue(keyCode, longPress, 0, listenerEpoch)

    private fun holding(key: Int, started: Boolean, token: Long) {
        handler.post { synchronized(this) { if (token == listenerEpoch) actionDebounce.holding(key, started, SystemClock.elapsedRealtime()) } }
    }

    private fun enqueue(keyCode: Int, longPress: Boolean, softKeyFunction: Int, token: Long) {
        val menuAtReceipt = menuMonitor
        val modeAtReceipt = menuAtReceipt?.mode()
        handler.post {
            synchronized(this) {
                if (token != listenerEpoch) return@post
                val context = appContext ?: return@post
                val modeKey = NativeDashboardSettings.modeKey(context)
                val session = learning
                if (session != null) {
                    if (session.candidate == null && DashboardWheelKeyPolicy.isPageKeyAllowed(keyCode, modeKey) &&
                        session.debounce.accept(keyCode, longPress, SystemClock.elapsedRealtime())) {
                        session.candidate = keyCode
                        learningStatus = "Detected page-key candidate=$keyCode function=$softKeyFunction; waiting for user confirmation"
                        Log.i(TAG, learningStatus)
                        session.callback(keyCode)
                    }
                    return@post
                }
                if (!wheelEnabled(context)) return@post
                if (BoyueLSettings.selected(context) && keyCode in BoyueLSettings.originalOnlyKeys) return@post
                if (!longPress && actionDebounce.suppressesShort(keyCode, SystemClock.elapsedRealtime())) return@post
                if (dashboardOwner && NativeDashboardSettings.enabled(context)) {
                    val isMode = keyCode == modeKey
                    val direction = Fx11MenuWheelPolicy.direction(keyCode)
                    val menuTrack = !isMode && !longPress && direction != 0 && menuMonitor != null &&
                        !BoyueLSettings.hasAction(context, keyCode)
                    if (menuTrack) {
                        val mode = menuMonitor?.mode() ?: Fx11WheelMode.UNKNOWN
                        menuRoute = "track key=$keyCode menuMode=$mode longPress=$longPress uptimeMs=${SystemClock.uptimeMillis()}"
                        if (menuAtReceipt !== menuMonitor || modeAtReceipt != mode) {
                            menuRoute += " action=ignore-mode-changed-before-dispatch"
                            return@post
                        }
                        if (mode == Fx11WheelMode.DASHBOARD) {
                            if (!menuPagingReady(context, mode)) {
                                menuRoute += " action=ignore-paging-unavailable"
                            } else if (dashboardDebounce.accept(keyCode, longPress, SystemClock.elapsedRealtime())) {
                                val page = NativeDashboardSettings.step(context, direction)
                                menuRoute += " action=page page=$page"
                                Log.i(TAG, menuRoute)
                            } else menuRoute += " action=ignore-duplicate-or-long"
                            return@post
                        }
                        menuRoute += " action=ordinary-media-policy"
                    }
                }
                if (BoyueLSettings.selected(context)) {
                    if (keyCode !in BoyueLSettings.configurableKeys(context)) return@post
                    val action = BoyueLSettings.action(context, keyCode, longPress)
                    if (action == BoyueWheelAction.NONE) return@post
                    if (action.dashboard) {
                        if (!dashboardOwner || !NativeDashboardSettings.enabled(context)) return@post
                    } else if (!mediaOwner || (AirPlayPersistence.loadCarBluetoothAudio(context) &&
                            keyCode != GeelyWheelKeyCodes.KEY_VOICE_ASSIST)) return@post
                    if (!actionDebounce.accept(keyCode, longPress, SystemClock.elapsedRealtime())) return@post
                    Log.i(TAG, "configured key=$keyCode longPress=$longPress action=$action")
                    when (action) {
                        BoyueWheelAction.TOGGLE_DASHBOARD -> NativeDashboardService.toggleProjection(context)
                        BoyueWheelAction.CYCLE_DASHBOARD -> NativeDashboardSettings.cycle(context)
                        BoyueWheelAction.SIRI -> CarPlayMediaKeys.onExternalSiriKey()
                        BoyueWheelAction.PLAY_PAUSE -> CarPlayMediaKeys.onExternalMediaKey(CarPlayMediaButton.PLAY_PAUSE)
                        BoyueWheelAction.NEXT -> CarPlayMediaKeys.onExternalMediaKey(CarPlayMediaButton.NEXT)
                        BoyueWheelAction.PREVIOUS -> CarPlayMediaKeys.onExternalMediaKey(CarPlayMediaButton.PREVIOUS)
                        BoyueWheelAction.NONE -> Unit
                    }
                    return@post
                }
                if (!mediaOwner || !BoyueWheelPolicy.accepts(keyCode, longPress,
                        AirPlayPersistence.loadCarBluetoothAudio(context))) return@post
                val action = if (BoyueLSettings.selected(context)) BoyueLSettings.action(context, keyCode)
                    else BoyueWheelPolicy.defaultAction(keyCode)
                when (action) {
                    BoyueWheelAction.NONE, BoyueWheelAction.TOGGLE_DASHBOARD, BoyueWheelAction.CYCLE_DASHBOARD -> Unit
                    BoyueWheelAction.SIRI -> CarPlayMediaKeys.onExternalSiriKey()
                    BoyueWheelAction.PLAY_PAUSE -> CarPlayMediaKeys.onExternalMediaKey(CarPlayMediaButton.PLAY_PAUSE)
                    BoyueWheelAction.NEXT -> CarPlayMediaKeys.onExternalMediaKey(CarPlayMediaButton.NEXT)
                    BoyueWheelAction.PREVIOUS -> CarPlayMediaKeys.onExternalMediaKey(CarPlayMediaButton.PREVIOUS)
                }
            }
        }
    }
}
