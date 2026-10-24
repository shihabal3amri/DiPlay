package com.shilapi.xcertplay

import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Fx11DteButtonMonitorTest {
    private val clock = AtomicLong(10_000_000_000)
    private var monitor: Fx11DteButtonMonitor? = null
    private val threads = mutableListOf<Thread>()
    private val worker = Executor { task ->
        Thread(task,"test-dte-worker").also { threads.add(it); it.start() }
    }
    private class Bridge : Fx11DteButtonBridge {
        lateinit var listener: Fx11DteButtonBridge.Listener
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        var blockConnect = false
        var connects = 0
        override fun connect(listener: Fx11DteButtonBridge.Listener): String {
            this.listener = listener; connects++; entered.countDown()
            if (blockConnect) check(release.await(3,TimeUnit.SECONDS))
            return "DTE candidate subscribed"
        }
        override fun close() { closed.countDown() }
    }
    private fun start(bridge: Bridge, onPage: () -> Unit): Fx11DteButtonMonitor {
        val result = Fx11DteButtonMonitor(onPage, { bridge }, worker, clock = clock::get)
        monitor = result; result.start()
        assertTrue(bridge.entered.await(2,TimeUnit.SECONDS))
        if (!bridge.blockConnect) {
            val deadline=System.nanoTime()+2_000_000_000
            while (!result.diagnosticReport().startsWith("DTE candidate subscribed") && System.nanoTime()<deadline) Thread.sleep(1)
            assertTrue(result.diagnosticReport().startsWith("DTE candidate subscribed"))
        }
        return result
    }
    private fun emit(bridge: Bridge, value: Int, millis: Long, initial: Boolean = false) {
        clock.set(10_000_000_000 + millis*1_000_000)
        bridge.listener.onSample(Fx11DteButtonSample(Fx11DteButtonPolicy.PROPERTY,0,0,clock.get(),
            ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(value).putInt(0).putInt(0).array(),initial))
    }
    @After fun cleanup() {
        monitor?.close()
        threads.forEach { it.join(3500); assertFalse("Observer must clean up",it.isAlive) }
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun realCallbackPathPagesOnceAndKeepsOnlyBoundedRawDiagnostics() {
        val b=Bridge(); var pages=0
        val m=start(b) { pages++ }; m.start()
        assertEquals(1,b.connects)
        emit(b,0,0,true); emit(b,1,100); emit(b,0,200); emit(b,0,200)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1,pages)
        for(i in 1..100) emit(b,0,1000+i.toLong())
        assertTrue(m.diagnosticReport().contains("retained=64/64"))
        assertTrue(m.diagnosticReport().contains("physical RESET unconfirmed"))
        assertTrue(m.diagnosticReport().contains("PA=[availability=1,data=0"))
    }
    @Test fun closeInvalidatesQueuedPagesAndLateBinderCallbacks() {
        val b=Bridge(); var pages=0; val m=start(b) { pages++ }
        emit(b,0,0,true); emit(b,1,100); emit(b,0,200)
        m.close()
        assertTrue(b.closed.await(2,TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        emit(b,1,1000); emit(b,0,1100); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0,pages)
    }
    @Test fun deathInvalidatesPendingPageAndUnsubscribes() {
        val b=Bridge(); var pages=0; start(b) { pages++ }
        emit(b,0,0,true); emit(b,1,100); emit(b,0,200)
        b.listener.onDisconnected("test death")
        assertTrue(b.closed.await(2,TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0,pages)
    }
    @Test fun timedOutConnectionCannotDeliverPagesAndCleansUpWhenItReturns() {
        val b=Bridge().also { it.blockConnect=true }; var pages=0
        val m=start(b) { pages++ }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3001))
        assertTrue(m.diagnosticReport().contains("timed out"))
        b.release.countDown()
        assertTrue(b.closed.await(2,TimeUnit.SECONDS))
        emit(b,0,0,true); emit(b,1,100); emit(b,0,200)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0,pages)
    }
}
