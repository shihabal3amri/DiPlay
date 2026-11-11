package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VideoRequestAdmissionTest {
    private val mib = 1024L * 1024
    private fun budget() = ControlBodyBudget({ 96 * mib }, { 96 * mib })
    private fun awaitCount(gate: VideoRequestAdmission, count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (gate.counts().second != count && System.nanoTime() < deadline) Thread.yield()
        assertEquals(count, gate.counts().second)
    }

    @Test fun fourActiveAndEightWaitingAreBoundedAndFifo() {
        val budget = budget()
        val gate = VideoRequestAdmission(budget)
        val pool = Executors.newFixedThreadPool(8)
        val active = List(4) { gate.acquire(100, true)!! }
        val admitted = mutableListOf<VideoRequestAdmission.Ticket>()
        try {
            val waiting = (1..8).map { count ->
                val result = pool.submit<VideoRequestAdmission.Ticket?> { gate.acquire(100, true) }
                awaitCount(gate, count)
                result
            }
            assertEquals(8 * 64 * 1024L, budget.reservedBytes())
            assertNull(gate.acquire(100, true))
            assertEquals(4 to 8, gate.counts())
            active.first().close()
            waiting.forEach { result ->
                val ticket = result.get(2, TimeUnit.SECONDS)!!
                admitted += ticket
                assertTrue(ticket.isCurrent())
                assertTrue(gate.counts().first <= 4)
                ticket.close()
            }
            assertEquals(0, gate.counts().second)
            assertEquals(0L, budget.reservedBytes())
        } finally {
            gate.cancelAll(); active.forEach { it.close() }; admitted.forEach { it.close() }
            pool.shutdownNow()
        }
        assertEquals(0 to 0, gate.counts())
    }

    @Test fun cancelReleasesQueuedMetadataAndInvalidatesOldTickets() {
        val budget = budget()
        val gate = VideoRequestAdmission(budget)
        val pool = Executors.newSingleThreadExecutor()
        val active = List(4) { gate.acquire(100, true)!! }
        try {
            val waiting = pool.submit<VideoRequestAdmission.Ticket?> { gate.acquire(100, true) }
            awaitCount(gate, 1)
            val oldEpoch = gate.epochToken()
            gate.cancelAll()
            assertNull(gate.acquire(100, true, oldEpoch))
            assertNull(waiting.get(1, TimeUnit.SECONDS))
            assertEquals(0L, budget.reservedBytes())
            assertFalse(active.first().isCurrent())
            active.forEach { it.close() }
            val fresh = gate.acquire(100, true)!!
            assertTrue(fresh.isCurrent())
            fresh.close()
        } finally { gate.cancelAll(); active.forEach { it.close() }; pool.shutdownNow() }
    }

    @Test fun interruptionRemovesWaiterAndDoesNotLeakItsReservation() {
        val budget = budget()
        val gate = VideoRequestAdmission(budget)
        val active = List(4) { gate.acquire(100, true)!! }
        val thread = Thread {
            try { gate.acquire(100, true); fail("Expected interruption") }
            catch (_: InterruptedException) { }
        }
        try {
            thread.start(); awaitCount(gate, 1); thread.interrupt(); thread.join(1000)
            assertFalse(thread.isAlive)
            assertEquals(0L, budget.reservedBytes())
            assertEquals(4 to 0, gate.counts())
        } finally { gate.cancelAll(); active.forEach { it.close() }; thread.interrupt(); thread.join(1000) }
    }

    @Test fun waitingExpiresAndRemainingDeadlineIncludesWaitingTime() {
        val gate = VideoRequestAdmission(budget(), TimeUnit.MILLISECONDS.toNanos(80), TimeUnit.MILLISECONDS.toNanos(500))
        val active = List(4) { gate.acquire(100, true)!! }
        val pool = Executors.newSingleThreadExecutor()
        try {
            assertNull(gate.acquire(100, true))
            val waiting = pool.submit<VideoRequestAdmission.Ticket?> { gate.acquire(100, true) }
            awaitCount(gate, 1)
            Thread.sleep(25)
            active.first().close()
            val ticket = waiting.get(1, TimeUnit.SECONDS)!!
            assertTrue(ticket.remainingNanos() < TimeUnit.MILLISECONDS.toNanos(490))
            ticket.close()
        } finally { gate.cancelAll(); active.forEach { it.close() }; pool.shutdownNow() }
    }

    @Test fun disabledForwardingAndOversizedUrlsNeverWait() {
        val gate = VideoRequestAdmission(budget())
        assertNull(gate.acquire(VideoRequestAdmission.MAX_URL_CHARACTERS + 1, true))
        val active = List(4) { gate.acquire(100, false)!! }
        try {
            assertNull(gate.acquire(100, false))
            assertEquals(4 to 0, gate.counts())
        } finally { active.forEach { it.close() } }
    }

    @Test fun waitingAndBodyReservationsShareOneHeapAdaptivePool() {
        var available = 96 * mib
        val budget = ControlBodyBudget({ 96 * mib }, { available })
        val gate = VideoRequestAdmission(budget)
        val body = budget.acquire(12 * 1024 * 1024)!! // 48 MiB fills half of this heap.
        val active = List(4) { gate.acquire(100, true)!! }
        try {
            assertNull(gate.acquire(100, true))
            assertEquals(48 * mib, budget.reservedBytes())
            body.close()
            available = 12 * mib
            assertNull(gate.acquire(100, true)) // Preserve headroom even without another reservation.
            assertEquals(0L, budget.reservedBytes())
        } finally { body.close(); active.forEach { it.close() } }
    }
}
