package com.shilapi.xcertplay.airplay

import kotlin.math.abs
import kotlin.math.min

/** Integer pixel dimensions of a host view or a negotiated CarPlay canvas. */
data class PixelSize(val width: Int, val height: Int) {
    val isLandscape: Boolean get() = width >= height

    override fun toString(): String = "${width}x$height"
}

/**
 * The CarPlay resolution is negotiated with the iPhone once per session and stays fixed while
 * the head unit changes the visible area, for example when a surround-view camera page shrinks
 * the window. Only a resolution-tier change, a bar-layout change or a rotation renegotiates.
 */
object CarPlayFixedResolution {
    /**
     * Stable base for the tier calculation: the largest size seen for the same bar layout and
     * orientation. A transient shrink (surround view) therefore never lowers the base.
     */
    fun base(current: PixelSize, recordedMaximum: PixelSize?): PixelSize {
        if (recordedMaximum == null ||
            recordedMaximum.width <= 0 ||
            recordedMaximum.height <= 0 ||
            recordedMaximum.isLandscape != current.isLandscape
        ) {
            return current
        }
        val currentAspect = current.width.toDouble() / current.height
        val maxAspect = recordedMaximum.width.toDouble() / recordedMaximum.height
        if (abs(maxAspect / currentAspect - 1.0) > 0.06) {
            return current
        }
        return PixelSize(
            maxOf(current.width, recordedMaximum.width),
            maxOf(current.height, recordedMaximum.height),
        )
    }

    /** The fixed width x height negotiated for [tenths] of [base]; even pixels, deterministic. */
    fun negotiated(base: PixelSize, tenths: Int): PixelSize {
        val scaled = CarPlayDisplayScale.apply(
            AirPlayDisplayConfig(widthPixels = base.width, heightPixels = base.height),
            tenths,
        )
        return PixelSize(scaled.widthPixels, scaled.heightPixels)
    }
}

/** Where the fixed-aspect video is drawn inside the host view, in view pixels. */
data class ContentRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height

    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom

    val isFullView: Boolean get() = left == 0f && top == 0f

    companion object {
        /**
         * Fits [content] into the view without distortion, centred with black bars. Aspect
         * differences within [ASPECT_TOLERANCE] (even-pixel rounding) fill the whole view.
         */
        fun fit(content: PixelSize?, viewWidth: Int, viewHeight: Int): ContentRect {
            val full = ContentRect(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
            if (content == null || content.width <= 0 || content.height <= 0 ||
                viewWidth <= 0 || viewHeight <= 0
            ) {
                return full
            }
            val contentAspect = content.width.toDouble() / content.height
            val viewAspect = viewWidth.toDouble() / viewHeight
            if (abs(contentAspect / viewAspect - 1.0) <= ASPECT_TOLERANCE) return full
            val scale = min(viewWidth.toDouble() / content.width, viewHeight.toDouble() / content.height)
            val width = (content.width * scale).toFloat()
            val height = (content.height * scale).toFloat()
            return ContentRect((viewWidth - width) / 2f, (viewHeight - height) / 2f, width, height)
        }

        const val ASPECT_TOLERANCE = 0.04
    }
}

/** What the host does with a debounced display-size change. */
enum class DisplayChangeAction {
    /** First size: start CarPlay. */
    START,

    /** A handshake reset is already rebuilding the stack; only record the size. */
    RECORD_ONLY,

    /** Keep the fixed-resolution session and re-fit the video; no renegotiation. */
    KEEP_SESSION,

    /** Tear down and renegotiate. */
    RENEGOTIATE,
}

object CarPlayDisplayChangePolicy {
    /**
     * @param sessionBase the base the running session was negotiated from, or null when no
     *   session is running.
     */
    fun decide(
        previous: PixelSize?,
        next: PixelSize,
        resetInProgress: Boolean,
        sessionBase: PixelSize?,
        sessionLayout: String?,
        currentLayout: String,
    ): DisplayChangeAction = when {
        previous == null -> DisplayChangeAction.START
        resetInProgress -> DisplayChangeAction.RECORD_ONLY
        sessionBase == null -> DisplayChangeAction.RENEGOTIATE
        sessionLayout != currentLayout -> DisplayChangeAction.RENEGOTIATE
        sessionBase.isLandscape != next.isLandscape -> DisplayChangeAction.RENEGOTIATE
        else -> {
            val aspectDiff = abs((next.width.toDouble() / next.height) / (sessionBase.width.toDouble() / sessionBase.height) - 1.0)
            val widthRatio = abs(next.width.toDouble() / sessionBase.width - 1.0)
            val heightRatio = abs(next.height.toDouble() / sessionBase.height - 1.0)
            if (aspectDiff > 0.08 || widthRatio > 0.15 || heightRatio > 0.15) {
                DisplayChangeAction.RENEGOTIATE
            } else {
                DisplayChangeAction.KEEP_SESSION
            }
        }
    }
}
