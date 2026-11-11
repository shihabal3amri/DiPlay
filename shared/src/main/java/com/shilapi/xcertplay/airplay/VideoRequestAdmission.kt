package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One bounded FIFO for the receiver's iPhone resource requests; never holds media bytes. */
class VideoRequestAdmission internal constructor(
    private val budget: ControlBodyBudget,
    private val waitNanos: Long = TimeUnit.SECONDS.toNanos(5),
    private val requestNanos: Long = TimeUnit.SECONDS.toNanos(30),
) {
    constructor() : this(ControlBodyBudget.shared)

    private val lock = Object()
    private val waiting = ArrayDeque<Waiter>()
    private var active = 0
    private var epoch = 0L
    private class Waiter(val epoch: Long, val permit: Closeable)

    inner class Ticket internal constructor(private val deadline: Long, private val owner: Long) : Closeable {
        private val released = AtomicBoolean()
        fun remainingNanos(): Long = (deadline - System.nanoTime()).coerceAtLeast(0)
        fun isCurrent(): Boolean = synchronized(lock) { owner == epoch && !released.get() }
        override fun close() {
            if (released.compareAndSet(false, true)) synchronized(lock) {
                active--
                lock.notifyAll()
            }
        }
    }

    fun epochToken(): Long = synchronized(lock) { epoch }

    data class Event(val stage: String, val reason: String, val active: Int, val waiting: Int,
        val waitedMs: Long, val reservedBytes: Long, val limitBytes: Long,
        val availableHeapBytes: Long, val headroomBytes: Long)

    /** Waits on the existing loader thread when queueing is allowed. */
    @Throws(InterruptedException::class)
    fun acquire(urlCharacters: Int, allowWaiting: Boolean, expectedEpoch: Long = epochToken(),
        observer: (Event) -> Unit = {},
    ): Ticket? {
        val started = System.nanoTime()
        fun report(stage: String, reason: String) {
            // Observation must not change admission, deadlines, or cancellation if logging fails.
            try {
                synchronized(lock) {
                    val memory = budget.snapshot()
                    observer(Event(stage, reason, active, waiting.size,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                        memory.reserved, memory.limit, memory.available, memory.headroom))
                }
            } catch (_: Exception) { }
        }
        if (allowWaiting && urlCharacters !in 1..MAX_URL_CHARACTERS) {
            report("rejected", "url_length")
            return null
        }
        if (Thread.currentThread().isInterrupted) {
            report("cancelled", "interrupted")
            return null
        }
        synchronized(lock) {
            if (expectedEpoch != epoch) { report("cancelled", "stale_epoch"); return null }
            val owner = expectedEpoch
            if (active < MAX_ACTIVE && waiting.isEmpty()) {
                active++
                report("admitted", "immediate")
                return Ticket(started + requestNanos, owner)
            }
            if (!allowWaiting) { report("rejected", "waiting_disabled"); return null }
            if (waiting.size >= MAX_WAITING) { report("rejected", "queue_full"); return null }
            val permit = budget.acquireWaitingMetadata()
            if (permit == null) { report("rejected", "queue_memory_budget"); return null }
            val waiter = Waiter(owner, permit)
            waiting.addLast(waiter)
            report("queued", "request_capacity")
            try {
                while (owner == epoch) {
                    val remaining = minOf(started + waitNanos, started + requestNanos) - System.nanoTime()
                    if (remaining <= 0) { report("rejected", "wait_timeout"); return null }
                    if (waiting.firstOrNull() === waiter && active < MAX_ACTIVE) {
                        waiting.removeFirst()
                        active++
                        report("admitted", "after_wait")
                        lock.notifyAll()
                        return Ticket(started + requestNanos, owner)
                    }
                    TimeUnit.NANOSECONDS.timedWait(lock, remaining)
                }
                report("cancelled", "stale_epoch")
                return null
            } catch (error: InterruptedException) {
                report("cancelled", "interrupted")
                throw error
            } finally {
                waiting.remove(waiter)
                waiter.permit.close()
                lock.notifyAll()
            }
        }
    }

    /** Invalidates both admitted and waiting work; callers cancel their response futures too. */
    fun cancelAll() = synchronized(lock) {
        epoch++
        waiting.forEach { it.permit.close() }
        waiting.clear()
        lock.notifyAll()
    }

    internal fun counts(): Pair<Int, Int> = synchronized(lock) { active to waiting.size }

    companion object {
        const val MAX_ACTIVE = 4
        const val MAX_WAITING = 8
        const val MAX_URL_CHARACTERS = 16 * 1024
    }
}
