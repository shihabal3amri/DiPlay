package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.VideoInCar
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoInCarGateTest {
    @Test
    fun forcedVideoSkipsGearReadsAndRemainsAllowedForEveryGear() {
        val changes = mutableListOf<Boolean>()
        val gate = VideoInCarGate(
            readParked = { error("Forced video must not read BYD gear") },
            onChanged = { changes += it },
            onObserved = { error("Forced video must not report an invented gear") },
            forceVideoInCar = true,
        )
        try {
            gate.start()
            assertTrue(VideoInCar.allowed)
            gate.update(false)
            gate.update(null)
            gate.update(true)
            assertEquals(listOf(true), changes)
            assertTrue(VideoInCar.allowed)
        } finally {
            gate.close()
        }
        gate.update(true)
        assertFalse(VideoInCar.allowed)
    }

    @Test
    fun normalGateRestoresGearChecksAfterAForcedConnection() {
        val forced = VideoInCarGate({ null }, {}, forceVideoInCar = true)
        forced.start()
        forced.close()
        val normal = VideoInCarGate({ null }, {})
        normal.update(false)
        assertFalse(VideoInCar.allowed)
        normal.update(true)
        assertTrue(VideoInCar.allowed)
        normal.close()
    }

    @After
    fun reset() {
        VideoInCar.allowed = false
    }

    @Test
    fun videoIsAllowedOnlyWhileTheGearReadsPark() {
        val changes = mutableListOf<Boolean>()
        val gate = VideoInCarGate({ null }, onChanged = { changes += it })

        gate.update(null) // no ADB: stays off
        gate.update(false)
        gate.update(true)
        gate.update(true)
        gate.update(null) // unreadable while parked: off again
        gate.update(false)

        assertEquals(listOf(true, false), changes)
        assertFalse(VideoInCar.allowed)
    }

    @Test
    fun closingTheGateTurnsVideoOff() {
        val gate = VideoInCarGate({ true }, onChanged = {})
        gate.update(true)
        gate.close()
        gate.update(true)

        assertFalse(VideoInCar.allowed)
    }

    @Test
    fun observationsDistinguishUnknownParkAndNotPark() {
        val observations = mutableListOf<Boolean?>()
        val gate = VideoInCarGate({ null }, {}, observations::add)

        gate.update(null)
        gate.update(null)
        gate.update(false)
        gate.update(true)

        assertEquals(listOf(null, false, true), observations)
    }
}
