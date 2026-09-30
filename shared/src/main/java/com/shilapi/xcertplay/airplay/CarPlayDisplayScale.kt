package com.shilapi.xcertplay.airplay

/**
 * Display scaling uses tenths so the UI can expose only the supported 0.3x..1.5x steps.
 * Steps above 1.0x ask the iPhone to render a supersampled canvas.
 */
object CarPlayDisplayScale {
    const val MIN_TENTHS = 3
    const val NATIVE_TENTHS = 10
    const val MAX_TENTHS = 15
    const val DEFAULT_TENTHS = NATIVE_TENTHS
    private const val MAX_LONG_EDGE = 3840
    private const val MAX_SHORT_EDGE = 2160

    fun sanitize(tenths: Int): Int = tenths.coerceIn(MIN_TENTHS, MAX_TENTHS)

    fun label(tenths: Int): String {
        val value = sanitize(tenths)
        return "${value / 10}.${value % 10}x"
    }

    fun apply(display: AirPlayDisplayConfig, tenths: Int): AirPlayDisplayConfig {
        val value = sanitize(tenths)
        val width = scalePixels(display.widthPixels, value)
        val height = scalePixels(display.heightPixels, value)
        // Never request an upscaled canvas beyond 4K; fall back to the native step instead.
        if (value > NATIVE_TENTHS && exceedsCanvasLimit(width, height)) {
            return apply(display, NATIVE_TENTHS)
        }
        return display.copy(widthPixels = width, heightPixels = height)
    }

    /** Same 4K bound as [CarPlayUiScale]'s enlarged canvas. */
    fun exceedsCanvasLimit(width: Int, height: Int): Boolean =
        maxOf(width, height) > MAX_LONG_EDGE || minOf(width, height) > MAX_SHORT_EDGE

    private fun scalePixels(pixels: Int, tenths: Int): Int {
        require(pixels > 0) { "pixels must be positive" }
        val scaled = ((pixels.toLong() * tenths + 5L) / 10L).toInt().coerceAtLeast(1)
        return if (scaled % 2 == 0) scaled else scaled + 1
    }
}
