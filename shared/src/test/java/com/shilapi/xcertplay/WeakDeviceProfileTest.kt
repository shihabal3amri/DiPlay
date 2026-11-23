package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.media.MediaAudioBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeakDeviceProfileTest {
    @Test
    fun `targets lower the display scale inside the accepted range`() {
        assertTrue(WeakDeviceProfile.targetsAreValid())
        assertEquals(75, WeakDeviceProfile.targets().displayScalePercent)
        assertTrue(WeakDeviceProfile.TARGET_DISPLAY_SCALE_PERCENT in
            CarPlayDisplayScale.MIN_PERCENT..CarPlayDisplayScale.MAX_PERCENT)
    }

    @Test
    fun `targets use the most stable wireless music buffer preset`() {
        assertEquals(MediaAudioBuffer.MOST_STABLE_MILLIS, WeakDeviceProfile.targets().wirelessMusicBufferMillis)
        assertTrue(WeakDeviceProfile.targets().wirelessMusicBufferMillis in MediaAudioBuffer.wirelessPresets)
    }

    @Test
    fun `targets enable direct video output`() {
        assertTrue(WeakDeviceProfile.targets().directVideoOutput)
    }

    @Test
    fun `targets differ from the factory defaults they restore to`() {
        assertNotEquals(WeakDeviceProfile.defaults(), WeakDeviceProfile.targets())
        assertEquals(100, WeakDeviceProfile.defaults().displayScalePercent)
        assertEquals(MediaAudioBuffer.DEFAULT_MILLIS, WeakDeviceProfile.defaults().wirelessMusicBufferMillis)
    }
}
