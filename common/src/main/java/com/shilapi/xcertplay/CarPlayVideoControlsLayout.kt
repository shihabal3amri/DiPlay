package com.shilapi.xcertplay

/** One sizing policy for player controls; the timeline keeps its existing dimensions. */
internal object CarPlayVideoControlsLayout {
    // The Back container and the timeline's time labels share this content edge.
    const val CONTENT_HORIZONTAL_INSET_DP = 32
    const val FULL_SKIP_SIZE_DP = 80
    const val FULL_PLAY_SIZE_DP = 96
    const val FULL_BUTTON_GAP_DP = 48
    const val FULL_ICON_PADDING_DP = 18
    const val COMPACT_SKIP_SIZE_DP = 64
    const val COMPACT_PLAY_SIZE_DP = 80
    const val COMPACT_BUTTON_GAP_DP = 24
    const val COMPACT_ICON_PADDING_DP = 14
    private const val ICON_PADDING_SIZE_DIVISOR = 4

    data class Sizes(val skipDp: Int, val playDp: Int, val gapDp: Int, val iconPaddingDp: Int)

    fun resolve(widthDp: Int, heightDp: Int): Sizes {
        val compact = SettingsLayoutPolicy.isCompact(widthDp, heightDp)
        val skip = if (compact) COMPACT_SKIP_SIZE_DP else FULL_SKIP_SIZE_DP
        val play = if (compact) COMPACT_PLAY_SIZE_DP else FULL_PLAY_SIZE_DP
        val preferredGap = if (compact) COMPACT_BUTTON_GAP_DP else FULL_BUTTON_GAP_DP
        val padding = if (compact) COMPACT_ICON_PADDING_DP else FULL_ICON_PADDING_DP
        val available = (widthDp - CONTENT_HORIZONTAL_INSET_DP * 2).coerceAtLeast(0)
        // Consume spare width with the gaps first. Only exceptionally narrow windows shrink circles.
        val fittedSkip = minOf(skip, available * skip / (skip * 2 + play))
        val fittedPlay = minOf(play, (available - fittedSkip * 2).coerceAtLeast(0))
        val gap = minOf(preferredGap, (available - fittedSkip * 2 - fittedPlay) / 2)
        return Sizes(fittedSkip, fittedPlay, gap, minOf(padding, fittedSkip / ICON_PADDING_SIZE_DIVISOR))
    }
}
