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

class Fx11ExteriorLightSourceTest {
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

    private open class Bridge : Fx11ExteriorLightBridge {
        var state = Fx11ExteriorLightPolicy.AUTO
        var supported = "active"
        var supportedZones: IntArray? = intArrayOf(Int.MIN_VALUE)
        var supportedValues: IntArray? = intArrayOf(0, 537136641, 537136642, 537136643, 537136644)
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
        override fun zones() = supportedZones
        override fun values() = supportedValues
        override fun read(): Int { reads++; readError?.let { throw it }; return state }
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
        val source = Fx11ExteriorLightSource(context = Mockito.mock(Context::class.java).also {
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
        fun value() = source.snapshot()[VehicleSignal.AUTO_HEADLIGHTS]?.value
        override fun close() = source.close()
    }

    @Test fun usesEcarxSelectorEnumsAndDoesNotTreatStandard256AsAuto() {
        val values = mapOf(0 to false, 537136641 to false, 537136642 to false, 537136643 to true,
            537136644 to null, 256 to null, 1 to null, -1 to null, Int.MAX_VALUE to null)
        values.forEach { (raw, expected) ->
            Fixture(Bridge().apply {
                state = raw
                supportedValues = supportedValues!! + raw
            }).use { fixture ->
                fixture.run({
                    assertEquals("raw=$raw", expected, fixture.value())
                    fixture.source.snapshot()[VehicleSignal.AUTO_HEADLIGHTS]?.let {
                        assertEquals(537136640, it.propertyId)
                    }
                })
            }
        }
    }

    @Test fun requiresActiveGlobalFunctionAndSupportedReturnValue() {
        val cases: List<(Bridge) -> Unit> = listOf(
            { it.supported = "notavailable" }, { it.supported = "unknown" },
            { it.supportedZones = null }, { it.supportedZones = intArrayOf(0) },
            { it.supportedValues = null }, { it.supportedValues = intArrayOf() },
            { it.supportedValues = intArrayOf(0, 537136642) },
            { it.connectError = SecurityException("unavailable") },
        )
        cases.forEach { configure ->
            Fixture(Bridge().also(configure)).use { fixture ->
                fixture.run({ assertNull(fixture.value()) })
            }
        }
    }

    @Test fun failedGetterImmediatelyClearsPreviouslyAcceptedAuto() {
        Fixture().use { fixture ->
            fixture.run({
                assertEquals(true, fixture.value())
                fixture.bridge.readError = IllegalStateException("getter unavailable")
            }, {
                assertNull(fixture.value())
                assertTrue(fixture.source.diagnosticReport().contains("getter unavailable"))
            })
        }
    }

    @Test fun connectionFailuresBackOffToThirtySecondsWithoutRapidRecreation() {
        Fixture(Bridge().apply { connectError = IllegalStateException("connect failed") }).use { fixture ->
            val expected = listOf(2_000L, 5_000L, 15_000L, 30_000L, 30_000L)
            fixture.run(*expected.mapIndexed { index, delay ->
                {
                    assertNull(fixture.value())
                    assertEquals(index + 1, fixture.bridge.connects)
                    assertEquals(index + 1, fixture.bridge.closes)
                    assertEquals(delay, fixture.delays.last())
                    assertTrue(fixture.source.diagnosticReport().contains("reconnect in ${delay}ms"))
                }
            }.toTypedArray())
            assertEquals(expected, fixture.delays)
        }
    }

    @Test fun successfulReadResetsReconnectBackoffAndKeepsNormalPolling() {
        Fixture().use { fixture ->
            fixture.run({
                assertEquals(true, fixture.value())
                fixture.bridge.readError = IllegalStateException("read failed")
            }, {
                assertNull(fixture.value())
                assertEquals(2_000L, fixture.delays.last())
            }, {
                assertNull(fixture.value())
                assertEquals(5_000L, fixture.delays.last())
                fixture.bridge.readError = null
            }, {
                assertEquals(true, fixture.value())
                assertEquals(500L, fixture.delays.last())
                fixture.bridge.readError = IllegalStateException("read failed again")
            }, {
                assertNull(fixture.value())
                assertEquals(2_000L, fixture.delays.last())
            })
            assertEquals(listOf(500L, 2_000L, 5_000L, 500L, 2_000L), fixture.delays)
        }
    }

    @Test fun unavailableCapabilitiesRetainConnectionAndRecheckWithoutGetterOrReconnect() {
        val cases: List<(Bridge) -> Unit> = listOf(
            { it.supported = "notavailable" },
            { it.supportedZones = intArrayOf(0) },
            { it.supportedValues = null },
            { it.supportedValues = intArrayOf() },
        )
        cases.forEach { configure ->
            Fixture(Bridge().also(configure)).use { fixture ->
                fixture.run({
                    assertNull(fixture.value())
                    assertEquals(30_000L, fixture.delays.last())
                    assertEquals(1, fixture.bridge.connects)
                    assertEquals(0, fixture.bridge.closes)
                    assertEquals(0, fixture.bridge.reads)
                }, {
                    assertNull(fixture.value())
                    assertEquals(30_000L, fixture.delays.last())
                    assertEquals(1, fixture.bridge.connects)
                    assertEquals(0, fixture.bridge.closes)
                    assertEquals(0, fixture.bridge.reads)
                    assertTrue(fixture.source.diagnosticReport().contains("connection retained"))
                })
                assertEquals(1, fixture.bridge.closes)
            }
        }
    }

    @Test fun capabilityCanBecomeAvailableOnSameConnectionAndPreviouslyAcceptedValueIsCleared() {
        Fixture().use { fixture ->
            fixture.run({
                assertEquals(true, fixture.value())
                fixture.bridge.supported = "notavailable"
            }, {
                assertNull(fixture.value())
                assertEquals(30_000L, fixture.delays.last())
                assertEquals(1, fixture.bridge.connects)
                assertEquals(0, fixture.bridge.closes)
                fixture.bridge.supported = "active"
            }, {
                assertEquals(true, fixture.value())
                assertEquals(500L, fixture.delays.last())
                assertEquals(1, fixture.bridge.connects)
                assertEquals(0, fixture.bridge.closes)
            })
        }
    }

    @Test fun closeDuringReconnectBackoffDoesNotCreateAnotherConnection() {
        Fixture(Bridge().apply { connectError = IllegalStateException("unavailable") }).use { fixture ->
            fixture.run({
                assertEquals(2_000L, fixture.delays.last())
                fixture.source.close()
            })
            assertEquals(1, fixture.bridge.connects)
            assertEquals(1, fixture.bridge.closes)
            assertNull(fixture.value())
            assertTrue(fixture.source.status.contains("closed"))
        }
    }

    @Test fun unknownOrNoLongerSupportedEnumClearsAutoWithoutInventingOff() {
        listOf(Fx11ExteriorLightPolicy.AHBC, 256).forEach { raw ->
            Fixture().use { fixture ->
                fixture.run({
                    assertEquals(true, fixture.value())
                    fixture.bridge.state = raw
                }, {
                    assertNull(fixture.value())
                    assertTrue(fixture.source.diagnosticReport().contains("value=unknown"))
                })
            }
        }
    }

    @Test fun disconnectClearsImmediatelyAndStaleSnapshotsExpireAfter2500Millis() {
        Fixture().use { fixture ->
            fixture.run({
                assertEquals(true, fixture.value())
                fixture.bridge.disconnect()
                assertNull(fixture.value())
            })
        }
        Fixture().use { fixture ->
            fixture.run({
                assertEquals(true, fixture.value())
                fixture.now += 2501
                assertNull(fixture.value())
                assertTrue(fixture.source.diagnosticReport().contains("value=unknown"))
            })
        }
    }

    @Test fun disconnectDuringGetterDiscardsLateValue() {
        val bridge = object : Bridge() {
            override fun read(): Int {
                val result = super.read()
                disconnect()
                return result
            }
        }
        Fixture(bridge).use { fixture -> fixture.run({ assertNull(fixture.value()) }) }
    }

    @Test fun repeatedStartQueuesOneReaderAndClosedSourceCannotRestart() {
        Fixture().use { fixture ->
            repeat(10) { fixture.source.start() }
            assertEquals(1, fixture.worker.queue.size)
            fixture.source.close()
            fixture.worker.runNext()
            fixture.source.start()
            assertEquals(0, fixture.bridge.connects)
            assertEquals(0, fixture.bridge.reads)
            assertTrue(fixture.worker.queue.isEmpty())
            assertTrue(fixture.source.snapshot().isEmpty())
        }
    }

    @Test fun normalCloseClearsValueAndCleansUpOnReader() {
        Fixture().use { fixture ->
            fixture.run({
                assertEquals(true, fixture.value())
                fixture.source.close()
                assertNull(fixture.value())
            })
            assertEquals(1, fixture.bridge.closes)
            assertNull(fixture.value())
            assertTrue(fixture.source.status.contains("closed"))
        }
    }

    @Test fun stuckReadDoesNotBlockClosePublishLateOrSpawnAReplacement() {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val bridge = object : Bridge() {
            override fun read(): Int {
                reading.countDown()
                while (true) {
                    try { if (release.await(5, TimeUnit.SECONDS)) break }
                    catch (_: InterruptedException) { /* Binder can ignore interruption. */ }
                }
                return super.read()
            }
            override fun close() { super.close(); cleaned.countDown() }
        }
        val worker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1))
        val source = Fx11ExteriorLightSource(context(), { bridge }, { 1000L }, worker)
        try {
            source.start()
            assertTrue(reading.await(2, TimeUnit.SECONDS))
            source.close()
            repeat(10) { source.start() }
            assertEquals(1, worker.largestPoolSize)
            assertTrue(worker.queue.isEmpty())
            assertTrue(source.snapshot().isEmpty())
            release.countDown()
            assertTrue(cleaned.await(2, TimeUnit.SECONDS))
            assertTrue(source.snapshot().isEmpty())
            assertEquals(1, bridge.connects)
        } finally {
            release.countDown()
            source.close()
            worker.shutdownNow()
        }
    }
}
