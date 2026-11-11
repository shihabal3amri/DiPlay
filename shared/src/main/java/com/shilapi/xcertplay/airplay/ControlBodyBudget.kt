package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** One process-wide admission budget, including wire data, decode headroom and bounded metadata. */
internal class ControlBodyBudget(
    private val maxHeap: () -> Long = { Runtime.getRuntime().maxMemory() },
    private val availableHeap: () -> Long = {
        Runtime.getRuntime().let { it.maxMemory() - (it.totalMemory() - it.freeMemory()) }
    },
) {
    private var reserved = 0L
    @Synchronized fun acquire(bodyBytes: Int): Closeable? {
        // Large video plists now share one wire array; nested data is spooled by slice.
        // Reserve an extra wire-sized margin plus bounded metadata, without raising the pool cap.
        val charge = bodyBytes.toLong() * 2 + 24 * MIB
        return reserve(charge)
    }

    /** Small waiting descriptors share the same pool as control bodies. */
    @Synchronized internal fun acquireWaitingMetadata(): Closeable? = reserve(64 * 1024L)

    private fun reserve(charge: Long): Closeable? {
        val limit = minOf(160 * MIB, maxHeap() / 2)
        val headroom = minOf(32 * MIB, maxHeap() / 8)
        // Other receivers may have reserved decode space without allocating it yet.
        if (charge > limit - reserved || charge + reserved + headroom > availableHeap()) return null
        reserved += charge
        val released = AtomicBoolean()
        return Closeable {
            if (released.compareAndSet(false, true)) synchronized(this) { reserved -= charge }
        }
    }

    internal data class Snapshot(val reserved: Long, val limit: Long, val available: Long, val headroom: Long)

    @Synchronized internal fun snapshot(): Snapshot = Snapshot(
        reserved, minOf(160 * MIB, maxHeap() / 2), availableHeap(), minOf(32 * MIB, maxHeap() / 8))

    @Synchronized internal fun reservedBytes(): Long = reserved

    companion object {
        private const val MIB = 1024L * 1024
        val shared = ControlBodyBudget()
    }
}
