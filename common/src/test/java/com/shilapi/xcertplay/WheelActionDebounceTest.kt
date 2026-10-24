package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class WheelActionDebounceTest {
    @Test fun heldKeyFiresOnceAndReleaseCannotTurnLongPressIntoShortPress() {
        val key = 200400
        val gestures = WheelActionDebounce()
        gestures.holding(key, true, 0)
        assertTrue(gestures.accept(key, true, 800))
        assertFalse(gestures.accept(key, true, 2500))
        assertFalse(gestures.accept(key, false, 2501))
        gestures.holding(key, false, 3000)
        assertFalse(gestures.accept(key, false, 3001))
        assertTrue(gestures.accept(key, false, 3500))
        assertFalse(gestures.accept(key, false, 3501))
        assertTrue(gestures.accept(key + 1, false, 3501))
        gestures.holding(key, true, 4000)
        assertTrue(gestures.accept(key, true, 4800))
    }
    @Test fun unmappedLongGestureDoesNotConsumeNextShortAndResetDropsOldGestureState() {
        val gestures = WheelActionDebounce()
        gestures.holding(1, true, 0)
        gestures.holding(1, false, 2000)
        assertTrue(gestures.accept(1, false, 2001))
        assertTrue(gestures.accept(2, true, 2100))
        gestures.reset()
        assertTrue(gestures.accept(2, false, 2101))
    }
}
