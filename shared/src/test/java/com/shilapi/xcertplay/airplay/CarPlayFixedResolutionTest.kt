package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayFixedResolutionTest {
    private val full = PixelSize(1920, 990)
    private val surround = PixelSize(1920, 942)

    @Test
    fun eachTierIsAFixedEvenResolution() {
        assertEquals(PixelSize(1920, 990), CarPlayFixedResolution.negotiated(full, 10))
        assertEquals(PixelSize(1536, 792), CarPlayFixedResolution.negotiated(full, 8))
        assertEquals(PixelSize(1152, 594), CarPlayFixedResolution.negotiated(full, 6))
    }

    @Test
    fun surroundViewShrinkDoesNotLowerTheBase() {
        assertEquals(full, CarPlayFixedResolution.base(surround, full))
        assertEquals(
            CarPlayFixedResolution.negotiated(full, 8),
            CarPlayFixedResolution.negotiated(CarPlayFixedResolution.base(surround, full), 8),
        )
    }

    @Test
    fun baseGrowsToTheLargestSizeSeen() {
        assertEquals(full, CarPlayFixedResolution.base(full, surround))
        assertEquals(surround, CarPlayFixedResolution.base(surround, null))
    }

    @Test
    fun baseIgnoresARecordedMaximumOfTheOtherOrientation() {
        val portrait = PixelSize(990, 1920)
        assertEquals(portrait, CarPlayFixedResolution.base(portrait, full))
    }

    @Test
    fun matchingAspectFillsTheView() {
        val rect = ContentRect.fit(full, 1920, 990)
        assertTrue(rect.isFullView)
        assertEquals(1920f, rect.width)
        assertEquals(990f, rect.height)
        // Even-pixel rounding at 0.8x stays within tolerance and still fills the view.
        assertTrue(ContentRect.fit(PixelSize(1536, 792), 1920, 990).isFullView)
    }

    @Test
    fun surroundViewLetterboxesWithoutDistortion() {
        val rect = ContentRect.fit(full, 1920, 942)
        assertEquals(942f, rect.height)
        assertEquals(1920f * 942f / 990f, rect.width, 0.01f)
        assertEquals((1920f - rect.width) / 2f, rect.left, 0.01f)
        assertEquals(0f, rect.top)
        assertFalse(rect.isFullView)
        assertTrue(rect.contains(960f, 471f))
        assertFalse(rect.contains(10f, 471f))
    }

    @Test
    fun missingCanvasFillsTheView() {
        assertTrue(ContentRect.fit(null, 1920, 942).isFullView)
    }

    @Test
    fun surroundViewKeepsTheSession() {
        assertEquals(
            DisplayChangeAction.KEEP_SESSION,
            decide(previous = full, next = surround),
        )
        assertEquals(
            DisplayChangeAction.KEEP_SESSION,
            decide(previous = surround, next = full),
        )
    }

    @Test
    fun firstSizeStartsAndResetOnlyRecords() {
        assertEquals(DisplayChangeAction.START, decide(previous = null, next = full))
        assertEquals(
            DisplayChangeAction.RECORD_ONLY,
            decide(previous = full, next = surround, resetInProgress = true),
        )
    }

    @Test
    fun rotationLayoutChangeOrNoSessionRenegotiates() {
        assertEquals(
            DisplayChangeAction.RENEGOTIATE,
            decide(previous = full, next = PixelSize(990, 1920)),
        )
        assertEquals(
            DisplayChangeAction.RENEGOTIATE,
            decide(previous = full, next = surround, currentLayout = "top-shown"),
        )
        assertEquals(
            DisplayChangeAction.RENEGOTIATE,
            decide(previous = full, next = surround, sessionBase = null),
        )
    }

    @Test
    fun splitScreenOrFullscreenTransitionRenegotiates() {
        val split = PixelSize(960, 990)
        assertEquals(
            DisplayChangeAction.RENEGOTIATE,
            decide(previous = split, next = full, sessionBase = split),
        )
        assertEquals(
            DisplayChangeAction.RENEGOTIATE,
            decide(previous = full, next = split, sessionBase = full),
        )
    }

    @Test
    fun baseDoesNotDistortMismatchedAspectRatio() {
        val split = PixelSize(960, 990)
        assertEquals(full, CarPlayFixedResolution.base(full, split))
        assertEquals(split, CarPlayFixedResolution.base(split, full))
    }

    private fun decide(
        previous: PixelSize?,
        next: PixelSize,
        resetInProgress: Boolean = false,
        sessionBase: PixelSize? = full,
        currentLayout: String = LAYOUT,
    ) = CarPlayDisplayChangePolicy.decide(
        previous = previous,
        next = next,
        resetInProgress = resetInProgress,
        sessionBase = sessionBase,
        sessionLayout = LAYOUT,
        currentLayout = currentLayout,
    )

    private companion object {
        const val LAYOUT = "top-hidden_bottom-hidden"
    }
}
