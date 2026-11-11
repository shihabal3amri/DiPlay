package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue

class VideoRequestAdmissionDiagnosticsTest {
    private val mib = 1024L * 1024
    private fun budget() = ControlBodyBudget({ 96 * mib }, { 96 * mib })
    private fun awaitWaiting(gate: VideoRequestAdmission, expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (gate.counts().second != expected && System.nanoTime() < deadline) Thread.yield()
        assertEquals(expected, gate.counts().second)
    }

    @Test fun lengthAndStaleAndInterruptedRejectionsAreDistinct() {
        val gate = VideoRequestAdmission(budget())
        val events = mutableListOf<VideoRequestAdmission.Event>()
        assertNull(gate.acquire(16385, true, observer = { events += it }))
        assertEquals("url_length", events.last().reason)
        val old = gate.epochToken(); gate.cancelAll()
        assertNull(gate.acquire(10, true, old) { events += it })
        assertEquals("stale_epoch", events.last().reason)
        try {
            Thread.currentThread().interrupt()
            assertNull(gate.acquire(10, true, observer = { events += it }))
            assertEquals("interrupted", events.last().reason)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
        assertEquals(0 to 0, gate.counts())
    }

    @Test fun queueFullDiagnosticsReportActualCountsWithoutChangingTheCaps() {
        val gate = VideoRequestAdmission(budget())
        val pool = Executors.newFixedThreadPool(8)
        val active = List(4) { gate.acquire(10, true)!! }
        try {
            val waiting = (1..8).map { count ->
                val future = pool.submit<VideoRequestAdmission.Ticket?> { gate.acquire(10, true) }
                awaitWaiting(gate, count); future
            }
            val events = mutableListOf<VideoRequestAdmission.Event>()
            assertNull(gate.acquire(10, true, observer = { events += it }))
            val event = events.single()
            assertEquals("queue_full", event.reason)
            assertEquals(4, event.active); assertEquals(8, event.waiting)
            assertEquals(8 * 65536L, event.reservedBytes)
            assertEquals(48 * mib, event.limitBytes)
            gate.cancelAll()
            waiting.forEach { assertNull(it.get(1, TimeUnit.SECONDS)) }
        } finally { gate.cancelAll(); active.forEach { it.close() }; pool.shutdownNow() }
    }

    @Test fun memoryRefusalShowsSharedReservationAndHeapHeadroom() {
        val budget = budget()
        val gate = VideoRequestAdmission(budget)
        val body = budget.acquire(12 * 1024 * 1024)!!
        val active = List(4) { gate.acquire(10, true)!! }
        try {
            val events = mutableListOf<VideoRequestAdmission.Event>()
            assertNull(gate.acquire(10, true, observer = { events += it }))
            val event = events.single()
            assertEquals("queue_memory_budget", event.reason)
            assertEquals(48 * mib, event.reservedBytes)
            assertEquals(12 * mib, event.headroomBytes)
            assertEquals(96 * mib, event.availableHeapBytes)
        } finally { body.close(); active.forEach { it.close() } }
    }

    @Test fun timeoutAndCancellationAreDistinctAndReleaseQueuedReservations() {
        val budget = budget()
        val gate = VideoRequestAdmission(budget, TimeUnit.MILLISECONDS.toNanos(30))
        val active = List(4) { gate.acquire(10, true)!! }
        val events = ConcurrentLinkedQueue<VideoRequestAdmission.Event>()
        val pool = Executors.newSingleThreadExecutor()
        try {
            assertNull(gate.acquire(10, true, observer = { events += it }))
            assertEquals("wait_timeout", events.last().reason)
            val waiting = pool.submit<VideoRequestAdmission.Ticket?> {
                gate.acquire(10, true, observer = { events += it })
            }
            awaitWaiting(gate, 1); gate.cancelAll()
            assertNull(waiting.get(1, TimeUnit.SECONDS))
            assertEquals("stale_epoch", events.last().reason)
            assertEquals(0L, budget.reservedBytes())
        } finally { gate.cancelAll(); active.forEach { it.close() }; pool.shutdownNow() }
    }

    @Test fun aThrowingObserverCannotBreakAdmissionOrCleanup() {
        val gate = VideoRequestAdmission(budget())
        val ticket = gate.acquire(10, true, observer = { throw IllegalStateException("diagnostic failure") })!!
        assertTrue(ticket.isCurrent()); assertEquals(1 to 0, gate.counts())
        ticket.close(); assertEquals(0 to 0, gate.counts())
    }
}
