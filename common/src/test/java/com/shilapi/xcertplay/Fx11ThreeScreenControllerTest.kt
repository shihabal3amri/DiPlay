package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class Fx11ThreeScreenControllerTest {
    private class QueuedWorker : AbstractExecutorService() {
        val queue = ArrayDeque<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) { check(!stopped); queue.add(command) }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return queue.toMutableList().also { queue.clear() }
        }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && queue.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
        fun runNext() { queue.removeFirst().run() }
    }

    private open class Bridge : Fx11ThreeScreenBridge {
        var state = 0
        var supported = "active"
        var supportedZones: IntArray? = intArrayOf(Int.MIN_VALUE)
        var supportedValues: IntArray? = intArrayOf(0, 1)
        var accepted = true
        var applyWrite = true
        var readback: Int? = null
        var readError: RuntimeException? = null
        var connectError: RuntimeException? = null
        var connects = 0
        var closes = 0
        var reads = 0
        val writes = mutableListOf<Int>()
        override fun connect(context: Context) { connects++; connectError?.let { throw it } }
        override fun supportedStatus() = supported
        override fun zones() = supportedZones
        override fun values() = supportedValues
        override fun read(): Int {
            reads++
            readError?.let { throw it }
            return if (writes.isNotEmpty()) readback ?: state else state
        }
        override fun write(value: Int): Boolean {
            writes.add(value)
            if (accepted && applyWrite) state = value
            return accepted
        }
        override fun close() { closes++ }
    }

    private class Fixture(val bridge: Bridge = Bridge(), mode: Fx11ProjectionMode = Fx11ProjectionMode.THREE_SCREEN) : AutoCloseable {
        val context = Mockito.mock(Context::class.java).also {
            Mockito.`when`(it.applicationContext).thenReturn(it)
        }
        val worker = QueuedWorker()
        val delays = mutableListOf<Long>()
        val results = mutableListOf<Fx11ThreeScreenController.Result>()
        val controller = Fx11ThreeScreenController({ bridge }, worker, Executor { it.run() },
            pause = { delays.add(it) }, mode = mode)
        init { controller.start(context) }
        fun request(ready: Boolean = true) { controller.toggle(ready) { results.add(it) } }
        fun toggle(ready: Boolean = true): Fx11ThreeScreenController.Result {
            request(ready); worker.runNext(); return results.last()
        }
        override fun close() { controller.close() }
    }

    @Test fun startupAndCloseDoNotConnectReadOrWriteTheVehicle() {
        Fixture().use {
            assertTrue(it.worker.queue.isEmpty())
            assertEquals(0, it.bridge.connects)
            assertEquals(0, it.bridge.reads)
            assertTrue(it.bridge.writes.isEmpty())
        }
    }

    @Test fun explicitRestoreDoesNotEnableAnAlreadyRestoredOem() {
        Fixture().use {
            it.controller.restoreOem { result -> it.results.add(result) }
            it.worker.runNext()
            val result = it.results.single()
            assertTrue(result.success)
            assertEquals(0, result.before)
            assertEquals(0, result.target)
            assertEquals(0, result.after)
            assertFalse(result.writeAttempted)
            assertTrue(it.bridge.writes.isEmpty())
            assertEquals(1, it.bridge.reads)
        }
    }

    @Test fun explicitRestoreWritesZeroOnceButRejectsAnUnknownOemState() {
        Fixture(Bridge().apply { state = 1 }).use {
            it.controller.restoreOem { result -> it.results.add(result) }
            it.worker.runNext()
            assertTrue(it.results.single().success)
            assertEquals(0, it.results.single().after)
            assertEquals(listOf(0), it.bridge.writes)
        }
        Fixture(Bridge().apply { state = 255 }).use {
            it.controller.restoreOem { result -> it.results.add(result) }
            it.worker.runNext()
            assertFalse(it.results.single().success)
            assertFalse(it.results.single().writeAttempted)
            assertTrue(it.bridge.writes.isEmpty())
        }
    }

    @Test fun explicitPressEntersAndExitsUsingFreshStateAndOneWritePerPress() {
        Fixture().use {
            val on = it.toggle()
            assertTrue(on.success)
            assertEquals(0, on.before)
            assertEquals(1, on.after)
            assertTrue(on.writeAttempted)
            // Returning to OEM must work even after the dashboard display becomes unavailable.
            val off = it.toggle(ready = false)
            assertTrue(off.success)
            assertEquals(1, off.before)
            assertEquals(0, off.target)
            assertEquals(listOf(1, 0), it.bridge.writes)
            assertEquals(2, it.bridge.connects)
            assertEquals(2, it.bridge.closes)
        }
    }

    @Test fun unavailablePresentationCannotHideTheOemInstrument() {
        Fixture().use {
            val result = it.toggle(ready = false)
            assertFalse(result.success)
            assertFalse(result.writeAttempted)
            assertEquals(0, result.before)
            assertTrue(result.message.contains("not ready"))
            assertTrue(it.bridge.writes.isEmpty())
        }
    }

    @Test fun displayLossWhileConnectingCancelsEnableButStillPermitsDisable() {
        Fixture().use {
            it.request()
            it.controller.updateDashboardReady(false)
            it.worker.runNext()
            assertFalse(it.results.last().success)
            assertTrue(it.bridge.writes.isEmpty())
            it.bridge.state = 1
            assertTrue(it.toggle(false).success)
            assertEquals(listOf(0), it.bridge.writes)
        }
    }

    @Test fun unknownStatesAndMissingCapabilitiesNeverWrite() {
        val cases: List<(Bridge) -> Unit> = listOf(
            { it.supported = "notavailable" }, { it.supported = "unknown" },
            { it.supportedZones = null }, { it.supportedZones = intArrayOf(0, 1) },
            { it.supportedValues = null }, { it.supportedValues = intArrayOf(1) },
            { it.supportedValues = intArrayOf(0) }, { it.state = 255 }, { it.state = -1 },
            { it.readError = IllegalStateException("Disconnected") },
            { it.connectError = SecurityException("Rejected by OEM") },
        )
        cases.forEach { configure ->
            Fixture(Bridge().also(configure)).use {
                assertFalse(it.toggle().success)
                assertFalse(it.results.last().writeAttempted)
                assertTrue(it.bridge.writes.isEmpty())
                assertEquals(1, it.bridge.closes)
            }
        }
    }

    @Test fun rejectedWriteIsNotRetriedAndDoesNotClaimOemRestored() {
        Fixture(Bridge().apply { state = 1; accepted = false }).use {
            val result = it.toggle(false)
            assertFalse(result.success)
            assertTrue(result.writeAttempted)
            assertNull(result.after)
            assertEquals(listOf(0), it.bridge.writes)
            assertEquals(1, it.bridge.reads)
        }
    }

    @Test fun acceptedWriteHasBoundedReadbacksAndNeverRepeatsTheWrite() {
        Fixture(Bridge().apply { applyWrite = false }).use {
            val result = it.toggle()
            assertFalse(result.success)
            assertEquals(0, result.after)
            assertEquals(1 + Fx11ThreeScreenPolicy.READBACK_ATTEMPTS, it.bridge.reads)
            assertEquals(7, it.delays.size)
            assertTrue(it.delays.all { delay -> delay == 250L })
            assertEquals(listOf(1), it.bridge.writes)
        }
    }

    @Test fun invalidReadbackFailsWithoutResendingOrReportingSuccess() {
        Fixture(Bridge().apply { readback = 255 }).use {
            val result = it.toggle()
            assertFalse(result.success)
            assertTrue(result.writeAttempted)
            assertEquals(2, it.bridge.reads)
            assertEquals(listOf(1), it.bridge.writes)
        }
    }

    @Test fun rapidPressesAreRejectedWhilePendingInsteadOfQueued() {
        Fixture().use {
            it.request(); it.request()
            assertEquals(1, it.worker.queue.size)
            assertEquals(1, it.results.size)
            assertFalse(it.results.single().success)
            assertTrue(it.results.single().message.contains("still in progress"))
            it.worker.runNext()
            assertTrue(it.results.last().success)
            assertEquals(listOf(1), it.bridge.writes)
        }
    }

    @Test fun closeCancelsQueuedPressAndNeverRestoresOrReplaysIt() {
        Fixture().use {
            it.request()
            it.controller.close()
            assertTrue(it.worker.queue.isEmpty())
            assertTrue(it.bridge.writes.isEmpty())
            assertEquals(0, it.bridge.connects)
            assertTrue(it.results.isEmpty())
        }
    }

    @Test fun timeoutDuringReadPreventsALateWriteAndDoesNotSpawnReplacementWorkers() {
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val timedOut = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val bridge = object : Bridge() {
            override fun read(): Int {
                readStarted.countDown()
                check(releaseRead.await(5, TimeUnit.SECONDS))
                return super.read()
            }
            override fun close() { super.close(); finished.countDown() }
        }
        val context = Mockito.mock(Context::class.java).also { Mockito.`when`(it.applicationContext).thenReturn(it) }
        val results = java.util.concurrent.CopyOnWriteArrayList<Fx11ThreeScreenController.Result>()
        val controller = Fx11ThreeScreenController({ bridge }, callbacks = Executor { it.run() }, timeoutMillis = 300)
        try {
            controller.start(context)
            controller.toggle(true) { results.add(it); timedOut.countDown() }
            assertTrue(readStarted.await(2, TimeUnit.SECONDS))
            assertTrue(timedOut.await(2, TimeUnit.SECONDS))
            assertFalse(results.single().writeAttempted)
            controller.toggle(true) { results.add(it) }
            assertTrue(results.last().message.contains("still in progress"))
            releaseRead.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals(2, results.size)
            assertTrue(bridge.writes.isEmpty())
            assertEquals(1, bridge.connects)
        } finally { releaseRead.countDown(); controller.close() }
    }

    @Test fun closeAfterAWriteDoesNotIssueAnyAutomaticRestore() {
        Fixture().use {
            assertTrue(it.toggle().success)
            it.controller.close()
            assertEquals(listOf(1), it.bridge.writes)
            assertTrue(it.controller.status.contains("no automatic write"))
        }
    }

    @Test fun timeoutInsideACommittedWriteReportsUncertaintyWithoutRetryOrLateSuccess() {
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val timedOut = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val bridge = object : Bridge() {
            override fun write(value: Int): Boolean {
                super.write(value)
                writeStarted.countDown()
                check(releaseWrite.await(5, TimeUnit.SECONDS))
                return true
            }
            override fun close() { super.close(); finished.countDown() }
        }
        val context = Mockito.mock(Context::class.java).also { Mockito.`when`(it.applicationContext).thenReturn(it) }
        val results = java.util.concurrent.CopyOnWriteArrayList<Fx11ThreeScreenController.Result>()
        val controller = Fx11ThreeScreenController({ bridge }, callbacks = Executor { it.run() }, timeoutMillis = 300)
        try {
            controller.start(context)
            controller.toggle(true) { results.add(it); timedOut.countDown() }
            assertTrue(writeStarted.await(2, TimeUnit.SECONDS))
            assertTrue(timedOut.await(2, TimeUnit.SECONDS))
            assertFalse(results.single().success)
            assertTrue(results.single().writeAttempted)
            assertTrue(results.single().message.contains("unconfirmed"))
            releaseWrite.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals(1, results.size)
            assertEquals(listOf(1), bridge.writes)
            assertEquals(1, bridge.reads)
        } finally { releaseWrite.countDown(); controller.close() }
    }
    private fun overlayBridge() = object : Bridge() {
        override fun validate(mode: Fx11ProjectionMode) { check(mode == Fx11ProjectionMode.OEM_OVERLAY) }
    }

    @Test fun overlayToggleUsesThreeAndZeroWithPhysicalDisplayStillUnverified() {
        Fixture(overlayBridge(), Fx11ProjectionMode.OEM_OVERLAY).use {
            assertEquals(3, it.toggle().after)
            assertEquals(0, it.toggle().after)
            assertEquals(listOf(3, 0), it.bridge.writes)
            assertTrue(it.results.first().message.contains("physical display not verified"))
        }
    }

    @Test fun overlayNeverWritesBeforeFirstDrawOrWhenWrongModeIsActive() {
        Fixture(overlayBridge(), Fx11ProjectionMode.OEM_OVERLAY).use {
            assertFalse(it.toggle(false).success)
            assertTrue(it.bridge.writes.isEmpty())
            it.bridge.state = 1
            assertFalse(it.toggle().success)
            assertTrue(it.bridge.writes.isEmpty())
        }
    }

    @Test fun overlayRefusesTheEcarxBridgeAndDoesNotRetryARejectedWrite() {
        Fixture(Bridge(), Fx11ProjectionMode.OEM_OVERLAY).use {
            assertFalse(it.toggle().success)
            assertTrue(it.bridge.writes.isEmpty())
        }
        Fixture(overlayBridge().apply { accepted = false }, Fx11ProjectionMode.OEM_OVERLAY).use {
            assertFalse(it.toggle().success)
            assertEquals(listOf(3), it.bridge.writes)
        }
    }

    @Test fun overlayRestoresZeroAndRequiresFeedbackNotJustSetSuccess() {
        Fixture(overlayBridge().apply { state = 3 }, Fx11ProjectionMode.OEM_OVERLAY).use {
            it.controller.restoreOem { result -> it.results.add(result) }
            it.worker.runNext()
            assertEquals(0, it.results.last().after)
            assertEquals(listOf(0), it.bridge.writes)
        }
        Fixture(overlayBridge().apply { applyWrite = false }, Fx11ProjectionMode.OEM_OVERLAY).use {
            assertFalse(it.toggle().success)
            assertEquals(listOf(3), it.bridge.writes)
            assertEquals(7, it.delays.size)
        }
    }

}
