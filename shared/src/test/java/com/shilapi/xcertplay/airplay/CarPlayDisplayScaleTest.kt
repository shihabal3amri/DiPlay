package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayDisplayScaleTest {
    private val headUnit = AirPlayDisplayConfig(widthPixels = 1920, heightPixels = 990)

    @Test
    fun scalesHandshakeDisplayAtSupportedSteps() {
        val native = AirPlayDisplayConfig(widthPixels = 1080, heightPixels = 2160)

        val half = CarPlayDisplayScale.apply(native, 5)

        assertEquals(540, half.widthPixels)
        assertEquals(1080, half.heightPixels)
        assertEquals("0.5x", CarPlayDisplayScale.label(5))
    }

    @Test
    fun clampsScaleToTheUiRange() {
        assertEquals(3, CarPlayDisplayScale.sanitize(0))
        assertEquals(15, CarPlayDisplayScale.sanitize(20))
        assertEquals(15, CarPlayDisplayScale.MAX_TENTHS)
    }

    @Test
    fun defaultStaysNativeWhenTheRangeGrows() {
        assertEquals(10, CarPlayDisplayScale.DEFAULT_TENTHS)
        assertEquals("1.0x", CarPlayDisplayScale.label(CarPlayDisplayScale.DEFAULT_TENTHS))
    }

    @Test
    fun alignsScaledDisplayDimensionsToEvenPixels() {
        val native = AirPlayDisplayConfig(widthPixels = 1920, heightPixels = 978)

        val scaled = CarPlayDisplayScale.apply(native, 7)

        assertEquals(1344, scaled.widthPixels)
        assertEquals(686, scaled.heightPixels)
    }

    @Test
    fun upscaledStepsSupersampleTheHeadUnitCanvas() {
        assertSize(2112, 1090, CarPlayDisplayScale.apply(headUnit, 11))
        assertSize(2496, 1288, CarPlayDisplayScale.apply(headUnit, 13))
        assertSize(2880, 1486, CarPlayDisplayScale.apply(headUnit, 15))
        assertEquals("1.1x", CarPlayDisplayScale.label(11))
        assertEquals("1.5x", CarPlayDisplayScale.label(15))
    }

    @Test
    fun upscaledOddResultsRoundUpToEvenPixels() {
        // 990 x 1.1 = 1089 and 990 x 1.5 = 1485 are odd; both round up.
        assertEquals(1090, CarPlayDisplayScale.apply(headUnit, 11).heightPixels)
        assertEquals(1486, CarPlayDisplayScale.apply(headUnit, 15).heightPixels)
        // 1081 x 1.2 = 1297.2 -> 1297 -> 1298.
        val odd = AirPlayDisplayConfig(widthPixels = 1921, heightPixels = 1081)
        assertSize(2306, 1298, CarPlayDisplayScale.apply(odd, 12))
    }

    @Test
    fun upscaledStepsNeverExceed4k() {
        val qhd = AirPlayDisplayConfig(widthPixels = 2560, heightPixels = 1440)
        // Exactly 4K is allowed.
        assertSize(3840, 2160, CarPlayDisplayScale.apply(qhd, 15))
        // Beyond 4K falls back to the native step.
        val wide = AirPlayDisplayConfig(widthPixels = 2880, heightPixels = 1200)
        assertSize(2880, 1200, CarPlayDisplayScale.apply(wide, 14))
        assertSize(2880, 1200, CarPlayDisplayScale.apply(wide, 15))
        assertSize(3744, 1560, CarPlayDisplayScale.apply(wide, 13))
    }

    @Test
    fun canvasLimitMatchesTheUiScaleBound() {
        assertFalse(CarPlayDisplayScale.exceedsCanvasLimit(3840, 2160))
        assertFalse(CarPlayDisplayScale.exceedsCanvasLimit(2160, 3840))
        assertTrue(CarPlayDisplayScale.exceedsCanvasLimit(3842, 1000))
        assertTrue(CarPlayDisplayScale.exceedsCanvasLimit(2400, 2162))
    }

    @Test
    fun reductionStepsAreNotCappedByThe4kLimit() {
        val large = AirPlayDisplayConfig(widthPixels = 5120, heightPixels = 2880)
        assertSize(4096, 2304, CarPlayDisplayScale.apply(large, 8))
    }

    private fun assertSize(width: Int, height: Int, display: AirPlayDisplayConfig) {
        assertEquals(width, display.widthPixels)
        assertEquals(height, display.heightPixels)
    }
}
