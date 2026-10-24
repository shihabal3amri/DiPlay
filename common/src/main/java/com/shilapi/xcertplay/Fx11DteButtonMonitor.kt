package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal interface Fx11DteMonitor : AutoCloseable {
    fun start()
    fun diagnosticReport(): String
}

internal interface Fx11DteButtonBridge : AutoCloseable {
    interface Listener {
        fun onSample(sample: Fx11DteButtonSample)
        fun onDisconnected(reason: String)
    }
    /** Connect, take an optional getter baseline and subscribe to this one property. */
    fun connect(listener: Listener): String
}

/** Service-owned, read-only event subscription. No dependency on a connected iPhone. */
internal class Fx11DteButtonMonitor(
    private val onPage: () -> Unit,
    private val bridgeFactory: () -> Fx11DteButtonBridge = {
        // Do not resolve OEM framework types on ordinary phones/head units at app startup.
        Class.forName("com.shilapi.xcertplay.Fx11DteHidlBridge").getDeclaredConstructor().newInstance() as Fx11DteButtonBridge
    },
    private val worker: Executor = READERS,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val clock: () -> Long = SystemClock::elapsedRealtimeNanos,
) : Fx11DteMonitor {
    private val lock = Any()
    private val done = CountDownLatch(1)
    private var started = false
    private var ended = false
    private var connected = false
    private var policy: Fx11DteButtonPolicy? = null
    private var status = "DTE candidate observer idle"
    private val events = ArrayDeque<String>()
    private var eventCount = 0L
    private var pulses = 0L
    private val timeout = Runnable { synchronized(lock) {
        if (!ended && !connected) end("connect timed out; no automatic retry")
    } }

    override fun start() = synchronized(lock) {
        if (started || ended) return@synchronized
        started = true
        policy = Fx11DteButtonPolicy(clock())
        status = "DTE candidate observer connecting; read-only"
        handler.postDelayed(timeout, 3000)
        try { worker.execute(::observe) }
        catch (error: RuntimeException) { end("worker unavailable: ${error.javaClass.simpleName}") }
    }

    private fun observe() {
        var bridge: Fx11DteButtonBridge? = null
        try {
            if (synchronized(lock) { ended }) return
            bridge = bridgeFactory()
            val info = bridge.connect(object : Fx11DteButtonBridge.Listener {
                override fun onSample(sample: Fx11DteButtonSample) = receive(sample)
                override fun onDisconnected(reason: String) = synchronized(lock) { end("disconnected: $reason") }
            })
            synchronized(lock) {
                if (!ended) { connected = true; status = info; handler.removeCallbacks(timeout); Log.i(TAG, info) }
            }
            done.await()
        } catch (error: Exception) {
            synchronized(lock) { if (!ended) end("unavailable: ${error.javaClass.simpleName}: ${error.message?.take(200)}") }
            if (error is InterruptedException) Thread.currentThread().interrupt()
        } catch (error: LinkageError) {
            synchronized(lock) { if (!ended) end("OEM HIDL unavailable: ${error.javaClass.simpleName}") }
        } finally {
            // A late connect after timeout/close must still unsubscribe; never block the UI thread.
            try { bridge?.close() }
            catch (error: Exception) { Log.w(TAG, "unsubscribe failed: ${error.javaClass.simpleName}") }
            catch (error: LinkageError) { Log.w(TAG, "unsubscribe API unavailable") }
        }
    }

    private fun receive(sample: Fx11DteButtonSample) = synchronized(lock) {
        if (ended) return@synchronized
        val decision = policy?.accept(sample, clock()) ?: return@synchronized
        eventCount++
        val line = "seq=$eventCount prop=${sample.property} area=${sample.area} status=${sample.status} " +
            "timestampNs=${sample.timestampNs} initial=${sample.initial} ${Fx11DteButtonPolicy.rawDescription(sample)} " +
            "decision=${decision.reason} pageCandidate=${decision.page}"
        if (events.size == 64) events.removeFirst()
        events.addLast(line)
        if (eventCount <= 256 || decision.page) Log.i(TAG, line)
        if (decision.page) {
            pulses++
            handler.post {
                synchronized(lock) {
                    if (!ended && connected) onPage()
                }
            }
        }
    }

    private fun end(reason: String) {
        ended = true; connected = false; policy?.reset()
        status = "DTE candidate observer $reason"
        handler.removeCallbacks(timeout)
        done.countDown()
    }

    override fun close() = synchronized(lock) { if (!ended) end("stopped") }
    override fun diagnosticReport(): String = synchronized(lock) {
        "$status\nproperty=${Fx11DteButtonPolicy.PROPERTY} name=padtetypebutton demoAlias=10059; " +
            "physical RESET unconfirmed; candidate=0->1->0,40..1200ms; events=$eventCount pulses=$pulses; retained=${events.size}/64\n" +
            events.joinToString("\n")
    }
    companion object {
        const val TAG = "DiPlay-DteButton"
        // Bound blocked native calls across service restarts. Never start replacement threads endlessly.
        private val READERS = ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
            ArrayBlockingQueue(2), { task -> Thread(task, "fx11-dte-observer").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy())
    }
}
