package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.MediaAudioBuffer

/**
 * "Weak head-unit mode": one switch that trades picture size and music latency for
 * headroom on slow head units (weak SoC, small RAM, lossy 2.4 GHz Wi-Fi). Turning it
 * on applies [targets]; turning it off restores the [Snapshot] saved beforehand, or
 * [defaults] when nothing was saved. The values themselves are stored by
 * AirPlayPersistence; this object only defines what the mode means, so it stays
 * unit-testable without Android. Only settings that help a weak unit are in the
 * bundle: Resolution, direct video output and the wireless music buffer. CarPlay
 * size is already Medium by default, car Bluetooth pause is not a performance
 * setting, and Spreadtrum decoder tuning stays out of this mode.
 */
object WeakDeviceProfile {
    /** Display scale while the mode is on; the settings screen accepts 30..160%. */
    const val TARGET_DISPLAY_SCALE_PERCENT = 75
    const val TARGET_DIRECT_VIDEO_OUTPUT = true
    /** Wireless music buffer while the mode is on: the most stable preset, outlasting Wi-Fi gaps. */
    const val TARGET_WIRELESS_MUSIC_BUFFER_MILLIS = MediaAudioBuffer.MOST_STABLE_MILLIS

    /** Everything the mode changes, so turning it off can put it all back. */
    data class Snapshot(
        val displayScalePercent: Int,
        val directVideoOutput: Boolean,
        val wirelessMusicBufferMillis: Int,
    )

    /** The values the mode applies. */
    fun targets(): Snapshot = Snapshot(
        displayScalePercent = TARGET_DISPLAY_SCALE_PERCENT,
        directVideoOutput = TARGET_DIRECT_VIDEO_OUTPUT,
        wirelessMusicBufferMillis = TARGET_WIRELESS_MUSIC_BUFFER_MILLIS,
    )

    /** Factory defaults, used when the mode is turned off but nothing was saved before it. */
    fun defaults(): Snapshot = Snapshot(
        displayScalePercent = 100,
        directVideoOutput = false,
        wirelessMusicBufferMillis = MediaAudioBuffer.DEFAULT_MILLIS,
    )
}
