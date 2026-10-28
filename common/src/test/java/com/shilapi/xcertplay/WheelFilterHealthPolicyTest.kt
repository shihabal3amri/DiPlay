// 本次新增过滤检查及导航调音边界测试。
package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class WheelFilterHealthPolicyTest {
    @Test fun filterChecksAreBoundedAndOnlyRepeatedConfirmedFaultsRequestRecovery() {
        val health = WheelFilterHealthPolicy()
        assertTrue(health.shouldInspect(0))
        assertFalse(health.shouldInspect(4_999))
        assertTrue(health.shouldInspect(5_000))
        assertFalse(health.observed(false))
        assertTrue(health.observed(false))
        assertFalse(health.observed(false))
        assertFalse(health.observed(null))
        assertFalse(health.observed(false))
        assertFalse(health.observed(true))
        assertFalse(health.observed(false))
        assertTrue(health.observed(false))
    }
    @Test fun failedReadDoesNotCaptureButFailedOrDelayedWriteNeverFallsThroughToCar() {
        var writes = 0
        fun adjust(read: () -> Int, write: (Int) -> Unit, readback: () -> Int) =
            NavigationWheelAudioAdjustment.adjust(1, { true }, { 0 }, { 8 }, read, write, readback)
        assertFalse(adjust({ error("read") }, { writes++ }, { 4 }))
        assertEquals(0, writes)
        assertTrue(adjust({ 3 }, { writes++; error("write may already have happened") }, { 4 }))
        assertTrue(adjust({ 3 }, { writes++ }, { error("readback unavailable") }))
        assertTrue(adjust({ 3 }, { writes++ }, { 3 }))
        assertEquals(3, writes)
    }
    @Test fun changingEligibilityBeforeWriteLeavesThePressWithTheVehicle() {
        var checks = 0
        var writes = 0
        assertFalse(NavigationWheelAudioAdjustment.adjust(1, { ++checks == 1 }, { 0 }, { 8 },
            { 3 }, { writes++ }, { 4 }))
        assertEquals(0, writes)
    }
}
