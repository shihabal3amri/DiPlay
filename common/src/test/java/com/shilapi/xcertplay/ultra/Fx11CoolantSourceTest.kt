package com.shilapi.xcertplay.ultra

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CancellationException
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class Fx11CoolantSourceTest {
    private class QueuedWorker : AbstractExecutorService() {
        val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue.add(command) }
        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = queue.toMutableList().also { queue.clear() }
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = false
        fun runNext() {
            try {
                val work = queue.removeFirst()
                work.run()
                try { (work as? Future<*>)?.get() }
                catch (_: CancellationException) { /* Closing the source cancels its owning task. */ }
            }
            finally { Thread.interrupted() } // Fake execution uses the test thread, not a dedicated reader.
        }
    }

    private open class Bridge : Fx11CoolantBridge {
        var state = 90f
        var supported = "active"
        var connectError: RuntimeException? = null
        var readError: RuntimeException? = null
        var connects = 0
        var reads = 0
        var closes = 0
        var disconnect: () -> Unit = {}
        override fun connect(context: Context, onDisconnected: () -> Unit) {
            connects++
            disconnect = onDisconnected
            connectError?.let { throw it }
        }
        override fun supportedStatus() = supported
        override fun read(): Float { reads++; readError?.let { throw it }; return state }
        override fun close() { closes++ }
    }

    private fun context() = Mockito.mock(Context::class.java).also {
        Mockito.`when`(it.applicationContext).thenReturn(it)
    }

    private class Fixture(val bridge: Bridge = Bridge()) : AutoCloseable {
        val worker = QueuedWorker()
        var now = 1000L
        val delays = mutableListOf<Long>()
        private val afterPoll = ArrayDeque<() -> Unit>()
        val source = Fx11CoolantSource(context = Mockito.mock(Context::class.java).also {
            Mockito.`when`(it.applicationContext).thenReturn(it)
        }, bridgeFactory = { bridge }, clock = { now }, worker = worker, pause = { delay ->
            delays.add(delay)
            afterPoll.removeFirst().invoke()
            now += delay
            if (afterPoll.isEmpty()) sourceClose()
        })
        private fun sourceClose() { source.close() }
        fun run(vararg steps: () -> Unit) {
            afterPoll.addAll(steps.toList())
            source.start()
            worker.runNext()
        }
        fun value() = source.snapshot()[VehicleSignal.ENGINE_COOLANT_C]?.value
        override fun close() = source.close()
    }

    @Test fun activeSensorReportsCelsiusAndDropsInvalidOrStaleReadings() {
        Fixture().use { f ->
            f.run({
                assertEquals(90.0, f.value())
                assertEquals(1052416, f.source.snapshot()[VehicleSignal.ENGINE_COOLANT_C]!!.propertyId)
                f.now += 3000
                assertNull(f.value())
                f.bridge.state = Float.NaN
            }, { assertNull(f.value()); f.bridge.state = -20f }, {
                assertEquals(-20.0, f.value())
            })
            assertNull(f.value())
        }
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, 65535f, -41f, 216f, Float.MIN_VALUE))
            assertNull(Fx11CoolantPolicy.decode(bad))
    }

    @Test fun unsupportedSensorNeverPublishesDefaultZeroAndKeepsConnectionForRecheck() {
        Fixture(Bridge().apply { supported = "not_support"; state = 0f }).use { f ->
            f.run({ assertNull(f.value()); assertEquals(0, f.bridge.reads); f.bridge.supported = "active" }, {
                assertEquals(0.0, f.value()); assertEquals(1, f.bridge.connects)
            })
            assertEquals(30000L, f.delays.first())
        }
    }

    @Test fun disconnectDuringGetterDiscardsLateReadingAndFailureClearsOldValue() {
        val bridge = object : Bridge() {
            override fun read(): Float { disconnect(); return 90f }
        }
        Fixture(bridge).use { f -> f.run({ assertNull(f.value()) }) }
        Fixture().use { f ->
            f.run({ assertEquals(90.0, f.value()); f.bridge.readError = IllegalStateException("offline") }, {
                assertNull(f.value())
                assertTrue(f.source.diagnosticReport().contains("unavailable"))
            })
        }
    }
}
