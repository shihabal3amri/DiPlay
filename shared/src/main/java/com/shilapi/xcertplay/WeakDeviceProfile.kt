package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.CarPlaySize
import com.shilapi.xcertplay.media.MediaAudioBuffer

/**
 * "Weak head-unit mode": one switch that trades picture size and music latency for
 * headroom on slow head units (weak SoC, small RAM, lossy 2.4 GHz Wi-Fi). Turning it
 * on applies [targets]; turning it off restores the [Snapshot] saved beforehand, or
 * [defaults] when nothing was saved. The values themselves are stored by
 * AirPlayPersistence; this object only defines what the mode means, so it stays
 * unit-testable without Android.
 */
object WeakDeviceProfile {
    /** Display scale while the mode is on; the settings screen accepts 30..160%. */
    const val TARGET_DISPLAY_SCALE_PERCENT = 75
    /** CarPlay size while the mode is on: Medium reports a wider screen, so iOS draws smaller controls. */
    val TARGET_WIDTH_PHYSICAL_MM = CarPlaySize.MEDIUM.widthMillimeters
    const val TARGET_DIRECT_VIDEO_OUTPUT = true
    /** Wireless music buffer while the mode is on: the most stable preset, outlasting Wi-Fi gaps. */
    const val TARGET_WIRELESS_MUSIC_BUFFER_MILLIS = MediaAudioBuffer.MOST_STABLE_MILLIS
    const val TARGET_SPRD_DECODER_TUNING = true
    const val TARGET_BT_SUSPEND_DURING_CARPLAY = true

    /** Everything the mode changes, so turning it off can put it all back. */
    data class Snapshot(
        val displayScalePercent: Int,
        val widthPhysicalMm: Int,
        val directVideoOutput: Boolean,
        val wirelessMusicBufferMillis: Int,
        val sprdDecoderTuning: Boolean,
        val btSuspendDuringCarplay: Boolean,
    )

    /** The values the mode applies. */
    fun targets(): Snapshot = Snapshot(
        displayScalePercent = TARGET_DISPLAY_SCALE_PERCENT,
        widthPhysicalMm = TARGET_WIDTH_PHYSICAL_MM,
        directVideoOutput = TARGET_DIRECT_VIDEO_OUTPUT,
        wirelessMusicBufferMillis = TARGET_WIRELESS_MUSIC_BUFFER_MILLIS,
        sprdDecoderTuning = TARGET_SPRD_DECODER_TUNING,
        btSuspendDuringCarplay = TARGET_BT_SUSPEND_DURING_CARPLAY,
    )

    /** Factory defaults, used when the mode is turned off but nothing was saved before it. */
    fun defaults(): Snapshot = Snapshot(
        displayScalePercent = 100,
        widthPhysicalMm = CarPlaySize.DEFAULT.widthMillimeters,
        directVideoOutput = false,
        wirelessMusicBufferMillis = MediaAudioBuffer.DEFAULT_MILLIS,
        sprdDecoderTuning = false,
        btSuspendDuringCarplay = false,
    )

    /** True when every target value is one the settings screens accept. */
    fun targetsAreValid(): Boolean {
        val targets = targets()
        return targets.displayScalePercent in CarPlayDisplayScale.MIN_PERCENT..CarPlayDisplayScale.MAX_PERCENT &&
            CarPlaySize.exact(targets.widthPhysicalMm) != null &&
            targets.wirelessMusicBufferMillis in MediaAudioBuffer.wirelessPresets
    }
}
