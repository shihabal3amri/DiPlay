package com.shilapi.xcertplay

import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Fx11DimMenuMonitorTest {
    private class Bridge : Fx11DimMenuBridge {
        lateinit var listener: Fx11DimMenuBridge.Listener
        val subscribed = CountDownLatch(1)
        val closed = CountDownLatch(1)
        override fun connect(listener: Fx11DimMenuBridge.Listener) {
            this.listener = listener
            listener.onConnection(true)
        }
        override fun subscribe(): Boolean { subscribed.countDown(); return true }
        override fun close() { closed.countDown() }
    }

    @Test fun followsActualMenuFeedbackAndNeverInfersModeFromVolumePopup() {
        val bridge = Bridge()
        val monitor = Fx11DimMenuMonitor(RuntimeEnvironment.getApplication(), { bridge },
            Executor { Thread(it, "test-menu-worker").start() })
        monitor.start()
        try {
            assertTrue(bridge.subscribed.await(3, TimeUnit.SECONDS))
            assertEquals(Fx11WheelMode.UNKNOWN, monitor.mode())
            bridge.listener.onVolumeBar(false)
            assertEquals(Fx11WheelMode.UNKNOWN, monitor.mode())
            for (tab in listOf(0, 2, 3, 4)) {
                bridge.listener.onTab(tab)
                assertEquals(Fx11WheelMode.DASHBOARD, monitor.mode())
            }
            bridge.listener.onTab(1)
            assertEquals(Fx11WheelMode.PHONE, monitor.mode())
            bridge.listener.onTab(5)
            assertEquals(Fx11WheelMode.MEDIA, monitor.mode())
            bridge.listener.onTab(99)
            assertEquals(Fx11WheelMode.UNKNOWN, monitor.mode())
            bridge.listener.onTab(3)
            bridge.listener.onConnection(false)
            bridge.listener.onTab(3)
            assertEquals(Fx11WheelMode.UNKNOWN, monitor.mode())
            bridge.listener.onConnection(true)
            assertEquals(Fx11WheelMode.UNKNOWN, monitor.mode())
            bridge.listener.onTab(2)
            bridge.listener.onControlCenter(2)
            bridge.listener.onTab(2)
            assertEquals(Fx11WheelMode.UNKNOWN, monitor.mode())
            bridge.listener.onControlCenter(0)
            assertEquals(Fx11WheelMode.UNKNOWN, monitor.mode())
            bridge.listener.onTab(2)
            assertEquals(Fx11WheelMode.DASHBOARD, monitor.mode())
            bridge.listener.onEngineOff(true)
            assertEquals(Fx11WheelMode.UNKNOWN, monitor.mode())
            repeat(70) { bridge.listener.onVolumeBar(false) }
            assertTrue(monitor.diagnosticReport().contains("retained=32/32"))
        } finally { monitor.close() }
        assertTrue(bridge.closed.await(3, TimeUnit.SECONDS))
        bridge.listener.onConnection(true)
        bridge.listener.onTab(3)
        assertEquals(Fx11WheelMode.UNKNOWN, monitor.mode())
    }

    @Test fun timedOutQueuedWorkerDoesNotOpenOemConnectionLater() {
        var pending: Runnable? = null
        var created = false
        val monitor = Fx11DimMenuMonitor(RuntimeEnvironment.getApplication(),
            { created = true; Bridge() }, Executor { pending = it })
        monitor.start()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        pending!!.run()
        assertFalse(created)
        assertEquals(Fx11WheelMode.UNKNOWN, monitor.mode())
        assertTrue(monitor.diagnosticReport().contains("timed out"))
    }

    @Test fun unsupportedPlatformLeavesModeUnknownAndOrdinaryCarPlayAvailable() {
        val monitor = Fx11DimMenuMonitor(RuntimeEnvironment.getApplication(),
            { throw ClassNotFoundException("No vehicle SDK") }, Executor { it.run() })
        monitor.start()
        assertEquals(Fx11WheelMode.UNKNOWN, monitor.mode())
        assertTrue(monitor.diagnosticReport().contains("unavailable: ClassNotFoundException"))
        monitor.close()
    }
}
