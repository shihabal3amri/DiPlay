package com.shilapi.xcertplay.airplay

import java.net.ProtocolException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** Also run in a 96 MiB JVM: large/late replies must not cause cumulative heap growth. */
class ControlMessageMemoryTest {
    private fun header(size: Int, stream: Int = 3, path: String = "/command") =
        "POST $path RTSP/1.0\r\nX-Apple-StreamID: $stream\r\nContent-Length: $size\r\n\r\n".toByteArray()

    @Test fun ordinaryAndUnknownStreamsRetainTheOriginalOneMiBLimit() {
        for (reader in listOf(ControlMessageReader(), ControlMessageReader({ setOf(3L) }))) {
            reader.use {
                assertThrows(ProtocolException::class.java) { it.feed(header(2 * 1024 * 1024, 4)) }
            }
        }
        ControlMessageReader({ setOf(3L) }).use { reader ->
            assertThrows(ProtocolException::class.java) { reader.feed(header(2 * 1024 * 1024, 3, "/feedback")) }
        }
    }

    @Test fun aDeniedVideoBodyIsDrainedWithoutAllocationAndTheNextCommandStillParses() {
        val budget = ControlBodyBudget({ 64L * 1024 * 1024 }, { 64L * 1024 * 1024 })
        if (java.lang.Boolean.getBoolean("diplay.lowHeapProbe")) {
            assertTrue(Runtime.getRuntime().maxMemory() <= 96L * 1024 * 1024)
        }
        val logs = mutableListOf<String>()
        ControlMessageReader({ setOf(3L, 4L) }, budget, logs::add).use { reader ->
            val chunk = ByteArray(16 * 1024)
            repeat(12) {
                var remaining = 21_558_983 // largest body from the supplied device report
                reader.feed(header(remaining, if (it % 2 == 0) 3 else 4))
                var rejected: RtspMessage.Request? = null
                while (remaining > 0) {
                    val take = minOf(remaining, chunk.size)
                    val messages = reader.feed(if (take == chunk.size) chunk else chunk.copyOf(take))
                    if (messages.isNotEmpty()) rejected = messages.single()
                    remaining -= take
                }
                rejected!!.use {
                    assertEquals("memory_budget", it.rejectionReason)
                    assertEquals(0, it.body.size)
                    assertEquals(21_558_983, it.rejectedBodyBytes)
                }
                val next = reader.feed("GET /info RTSP/1.0\r\n\r\n".toByteArray()).single()
                next.use { assertEquals("/info", it.path) }
                assertEquals(0L, budget.reservedBytes())
            }
            assertEquals(12, logs.size)
            println("CONTROL_MEMORY_PROBE maxHeapBytes=${Runtime.getRuntime().maxMemory()} drainedBytes=${21_558_983L * 12} reservedAfter=${budget.reservedBytes()}")
        }
    }

    @Test fun concurrentPartialRepliesShareOneBudgetAndReleaseItOnDisconnect() {
        val budget = ControlBodyBudget({ 512L * 1024 * 1024 }, { 512L * 1024 * 1024 })
        val readers = List(12) { ControlMessageReader({ setOf(3L) }, budget) }
        try {
            readers.forEach { it.feed(header(21_558_983)) }
            val charge = 21_558_983L * 2 + 24L * 1024 * 1024
            assertEquals(2 * charge, budget.reservedBytes()) // views let two bodies fit the same unchanged pool
        } finally { readers.forEach { it.close() } }
        assertEquals(0L, budget.reservedBytes())
    }

    @Test fun heapHeadroomIncludesReservationsWhoseDecodeAllocationsHaveNotHappenedYet() {
        val budget = ControlBodyBudget({ 512L * 1024 * 1024 }, { 100L * 1024 * 1024 })
        val first = budget.acquire(10 * 1024 * 1024)!! // reserves 44 MiB before any allocation
        try {
            assertNull(budget.acquire(10 * 1024 * 1024)) // 88 MiB plus headroom cannot fit
        } finally { first.close() }
        budget.acquire(10 * 1024 * 1024)!!.close()
        assertEquals(0L, budget.reservedBytes())
    }

    @Test fun oversizedVideoIsDiscardedWithoutAllocatingItsBody() {
        ControlMessageReader({ setOf(3L) }).use { reader ->
            val size = 64 * 1024 * 1024 + 1
            reader.feed(header(size))
            val chunk = ByteArray(16 * 1024)
            var left = size
            var result: RtspMessage.Request? = null
            while (left > 0) {
                val take = minOf(left, chunk.size)
                reader.feed(if (take == chunk.size) chunk else chunk.copyOf(take)).firstOrNull()?.let { result = it }
                left -= take
            }
            result!!.use {
                assertEquals("resource_size_limit", it.rejectionReason)
                assertEquals(size, it.rejectedBodyBytes)
                assertEquals(0, it.body.size)
            }
            reader.feed("GET /info RTSP/1.0\r\n\r\n".toByteArray()).single().use { assertEquals("GET", it.method) }
        }
    }

    @Test fun closedFileHandlesDoNotRetainResourceArraysAndCacheQuotaIsReclaimed() {
        val root = Files.createTempDirectory("diplay-resource-memory-").toFile()
        val store = VideoResourceStore(root)
        val closed = mutableListOf<VideoResourceFile>()
        try {
            repeat(12) {
                val payload = store.save(ByteArray(8 * 1024 * 1024))
                payload.close()
                closed += payload // retaining closed handles must not retain the original arrays
                assertFalse(payload.file.exists())
                assertEquals(0L, store.retainedBytes())
            }
            assertEquals(12, closed.size)
        } finally { root.deleteRecursively() }
    }
}
