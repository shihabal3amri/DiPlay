package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test

class WirelessPerformanceLockTest {
    @Test fun api29AddsLowLatencyAndOlderAndroidKeepsHighPerformance() {
        assertEquals(listOf(3), WirelessPerformanceLock.modes(28))
        assertEquals(listOf(3, 4), WirelessPerformanceLock.modes(29))
    }
    @Test fun rejectionOfOneModeDoesNotPreventTheOtherAndCloseIsIdempotent() {
        val released = mutableListOf<Int>()
        val acquired = mutableListOf<Int>()
        val lease = WirelessPerformanceLock(listOf(3, 4), { mode ->
            object : WirelessPerformanceLock.Handle {
                override fun acquire() { if (mode == 3) throw SecurityException(); acquired.add(mode) }
                override fun release() { released.add(mode) }
            }
        })
        lease.acquire(); lease.acquire()
        assertEquals(listOf(4), acquired)
        lease.close(); lease.close()
        assertEquals(listOf(3, 4), released)
        lease.acquire(); lease.close()
        assertEquals(listOf(4, 4), acquired)
    }
    @Test fun releaseFailureDoesNotLeakAnotherMode() {
        var released = false
        val lease = WirelessPerformanceLock(listOf(3, 4), { mode ->
            object : WirelessPerformanceLock.Handle {
                override fun acquire() {}
                override fun release() { if (mode == 4) throw IllegalStateException(); released = true }
            }
        })
        lease.acquire(); lease.close()
        assertTrue(released)
    }
}
