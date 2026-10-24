package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal interface Fx11MenuMonitor : AutoCloseable {
    fun start()
    fun mode(): Fx11WheelMode
    fun diagnosticReport(): String
}

internal interface Fx11DimMenuBridge : AutoCloseable {
    interface Listener {
        fun onConnection(connected: Boolean)
        fun onTab(tab: Int)
        fun onControlCenter(state: Int)
        fun onVolumeBar(visible: Boolean)
        fun onEngineOff(off: Boolean)
    }
    fun connect(listener: Listener)
    /** Called off main after a connected callback; returns false if the OEM is not ready yet. */
    fun subscribe(): Boolean
}

/** Read-only menu subscription. The SDK has no getter for the current tab or volume-key ownership. */
internal class Fx11DimMenuMonitor(
    context: Context,
    private val bridgeFactory: () -> Fx11DimMenuBridge = { Fx11DimMenuReflectionBridge(context.applicationContext) },
    private val worker: Executor = READERS,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : Fx11MenuMonitor {
    private val lock = Object()
    private var started = false
    private var ended = false
    private var connected = false
    private var epoch = 0L
    private var current = Fx11WheelMode.UNKNOWN
    private var tab: Int? = null
    private var controlCenter: Int? = null
    private var volumeBar: Boolean? = null
    private var status = "idle"
    private val events = ArrayDeque<String>()
    private var count = 0L
    private val timeout = Runnable { synchronized(lock) { if (!ended) end("connection/subscription timed out") } }

    override fun start() = synchronized(lock) {
        if (started || ended) return@synchronized
        started = true
        status = "connecting; initial menu mode unknown"
        handler.postDelayed(timeout, 8_000)
        try { worker.execute(::observe) }
        catch (e: RuntimeException) { end("worker unavailable: ${e.javaClass.simpleName}") }
    }

    private fun record(event: String) {
        val line = "uptimeMs=${SystemClock.uptimeMillis()} $event"
        if (events.size == 32) events.removeFirst()
        events.addLast(line)
        if (++count <= 64) Log.i(TAG, line)
    }

    private fun resetState() { current = Fx11WheelMode.UNKNOWN; tab = null; controlCenter = null; volumeBar = null }

    private fun observe() {
        var bridge: Fx11DimMenuBridge? = null
        try {
            if (synchronized(lock) { ended }) return
            bridge = bridgeFactory()
            bridge.connect(object : Fx11DimMenuBridge.Listener {
                override fun onConnection(connected: Boolean) = synchronized(lock) {
                    if (ended) return@synchronized
                    this@Fx11DimMenuMonitor.connected = connected
                    epoch++
                    resetState()
                    status = if (connected) "connected; awaiting menu subscription/feedback" else "disconnected"
                    record("connection=$connected epoch=$epoch; mode=UNKNOWN")
                    handler.removeCallbacks(timeout)
                    handler.postDelayed(timeout, 8_000)
                    lock.notifyAll()
                }
                override fun onTab(tab: Int) = synchronized(lock) {
                    if (ended || !connected) return@synchronized
                    this@Fx11DimMenuMonitor.tab = tab
                    current = if (controlCenter == 1 || controlCenter == 2) Fx11WheelMode.UNKNOWN
                        else Fx11MenuWheelPolicy.modeForTab(tab)
                    record("onTabChanged=$tab mode=$current")
                }
                override fun onControlCenter(state: Int) = synchronized(lock) {
                    if (ended || !connected) return@synchronized
                    controlCenter = state
                    // A call/active media control center must not be treated as a page browser.
                    // Its exit does not invent a tab; a new onTabChanged is required.
                    current = Fx11WheelMode.UNKNOWN
                    tab = null
                    record("onControlCenterStateChanged=$state; awaiting tab feedback")
                }
                override fun onVolumeBar(visible: Boolean) = synchronized(lock) {
                    if (ended || !connected) return@synchronized
                    volumeBar = visible
                    // A hidden volume popup does NOT mean that volume adjustment is disabled.
                    record("onVolumeBarStatusChanged=$visible; diagnostic only")
                }
                override fun onEngineOff(off: Boolean) = synchronized(lock) {
                    if (ended || !connected) return@synchronized
                    if (off) resetState()
                    record("onEngineStatusChanged=$off mode=$current")
                }
            })
            var subscribedEpoch = -1L
            while (true) {
                val next = synchronized(lock) {
                    if (ended) return
                    if (connected && epoch != subscribedEpoch) epoch else null
                }
                if (next != null && bridge.subscribe()) synchronized(lock) {
                    if (!ended && connected && epoch == next) {
                        subscribedEpoch = next
                        handler.removeCallbacks(timeout)
                        status = "subscribed; current state comes only from OEM onTabChanged"
                        record("subscribed epoch=$next; no tab getter; no key interception")
                    }
                }
                synchronized(lock) { if (!ended) lock.wait(1_000) }
            }
        } catch (e: Exception) {
            synchronized(lock) { if (!ended) end("unavailable: ${e.javaClass.simpleName}") }
            if (e is InterruptedException) Thread.currentThread().interrupt()
        } catch (e: LinkageError) {
            synchronized(lock) { if (!ended) end("OEM API unavailable: ${e.javaClass.simpleName}") }
        } finally {
            try { bridge?.close() }
            catch (e: Exception) { Log.w(TAG, "menu cleanup: ${e.javaClass.simpleName}") }
            catch (e: LinkageError) { Log.w(TAG, "menu cleanup API unavailable") }
        }
    }

    private fun end(reason: String) {
        ended = true; connected = false; epoch++; resetState(); status = reason
        handler.removeCallbacks(timeout)
        record("$reason; mode=UNKNOWN")
        lock.notifyAll()
    }

    override fun close() = synchronized(lock) { if (!ended) end("stopped") }
    override fun mode(): Fx11WheelMode = synchronized(lock) { current }
    override fun diagnosticReport(): String = synchronized(lock) {
        "DIM menu $status; mode=$current tab=$tab controlCenter=$controlCenter volumeBar=$volumeBar\n" +
            "initialSnapshotGetter=unavailable; menu-to-volume-ownership association requires vehicle validation; " +
            "events=$count retained=${events.size}/32\n" + events.joinToString("\n")
    }

    companion object {
        const val TAG = "DiPlay-DimMenu"
        private val READERS = ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(2),
            { task -> Thread(task, "fx11-menu-observer").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    }
}
