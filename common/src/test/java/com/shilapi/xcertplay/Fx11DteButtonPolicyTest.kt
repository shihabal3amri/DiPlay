package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Fx11DteButtonPolicyTest {
    private val start = 10_000_000_000L
    private fun sample(value: Int, millis: Long, initial: Boolean = false, availability: Int = 1) =
        Fx11DteButtonSample(Fx11DteButtonPolicy.PROPERTY, 0, 0, start + millis * 1_000_000,
            ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(availability).putInt(value)
                .putInt(0).putInt(0).array(), initial)
    private fun Fx11DteButtonPolicy.event(value: Int, millis: Long) =
        accept(sample(value, millis), start + millis * 1_000_000)
    private fun policy() = Fx11DteButtonPolicy(start).also {
        assertFalse(it.accept(sample(0, -100, initial = true), start).page)
    }

    @Test fun profileAliasIsNotTheSubscribedProperty() { assertEquals(561024641, Fx11DteButtonPolicy.PROPERTY) }
    @Test fun completeShortPulsePagesOnlyOnReleaseAndDuplicatesDoNotRepeat() {
        val p = policy()
        assertFalse(p.event(1, 100).page)
        assertFalse(p.event(1, 100).page)
        assertFalse(p.event(1, 150).page)
        assertTrue(p.event(0, 250).page)
        assertFalse(p.event(0, 250).page)
        assertFalse(p.event(0, 400).page)
        assertFalse(p.event(1, 1000).page)
        assertTrue(p.event(0, 1200).page)
    }
    @Test fun initialPressedStateAndCachedEventsNeverSynthesizeAClick() {
        for (initial in listOf(true, false)) {
            val p = Fx11DteButtonPolicy(start)
            assertFalse(p.accept(sample(1, if (initial) -100 else 100, initial), start + 100_000_000).page)
            assertFalse(p.event(0, 200).page)
        }
        val p = policy()
        assertFalse(p.event(1, -50).page)
        assertFalse(p.event(0, 50).page)
    }
    @Test fun longHeldLatchedAndBouncingValuesDoNotFlipPages() {
        val p = policy()
        p.event(1, 100)
        assertFalse(p.event(0, 120).page)
        p.event(1, 500)
        assertFalse(p.event(0, 2000).page)
        p.event(1, 3000)
        assertFalse(p.event(1, 10000).page)
        assertFalse(p.event(0, 10050).page)
        p.event(1, 12000)
        assertTrue(p.event(0, 12100).page)
    }
    @Test fun shortPulseDebounceDoesNotBlockALaterPress() {
        val p = policy()
        p.event(1,100); assertTrue(p.event(0,200).page)
        p.event(1,250); assertFalse(p.event(0,350).page)
        p.event(1,800); assertTrue(p.event(0,900).page)
    }
    @Test fun unknownEnumsUnavailableMalformedAndFutureEventsCancelPendingPress() {
        val invalid = listOf(
            sample(2,150), sample(1,150,availability=0), sample(1,150).copy(status=1),
            sample(1,150).copy(bytes=ByteArray(8)), sample(1,150).copy(timestampNs=0), sample(1,300),
        )
        for (event in invalid) {
            val p = policy(); p.event(1,100)
            assertFalse(p.accept(event,start+200_000_000).page)
            assertFalse(p.event(0,400).page)
        }
    }
    @Test fun unrelatedPropertiesAndAreasNeverGenerateAnAction() {
        val p = policy()
        for (event in listOf(sample(1,100).copy(property=10059), sample(1,100).copy(area=1))) {
            assertFalse(p.accept(event,start+100_000_000).page)
        }
        assertFalse(p.event(0,200).page)
    }
    @Test fun outOfOrderEventsAreIgnoredAndStaleEventsCannotCompleteAPress() {
        val p = policy()
        p.event(1,100)
        assertFalse(p.event(0,90).page)
        assertTrue(p.event(0,250).page)
        p.event(1,1000)
        assertFalse(p.accept(sample(0,1100),start+4_000_000_000).page)
    }
    @Test fun resetCannotRetainAPendingPressAcrossConnections() {
        val p = policy(); p.event(1,100); p.reset()
        assertFalse(p.event(0,200).page)
    }
}
