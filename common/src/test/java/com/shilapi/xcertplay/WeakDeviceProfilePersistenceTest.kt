package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.media.MediaAudioBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * applyWeakDeviceProfile / restoreWeakDeviceProfile round-trip through
 * SharedPreferences: the mode's own values change, the previous values come
 * back on restore, and the wired music buffer is never touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class WeakDeviceProfilePersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Before fun clearPreferences() = prefs.edit().clear().apply()

    @Test fun applyThenRestoreReturnsThePreviousValues() {
        AirPlayPersistence.saveDisplayScalePercent(context, 90)
        AirPlayPersistence.saveDirectVideoOutput(context, false)
        AirPlayPersistence.saveMediaBufferMillis(context, wireless = true, 500)
        AirPlayPersistence.saveMediaBufferMillis(context, wireless = false, 700)

        AirPlayPersistence.applyWeakDeviceProfile(context)
        assertTrue(AirPlayPersistence.loadWeakDeviceMode(context))
        assertEquals(75, AirPlayPersistence.loadDisplayScalePercent(context))
        assertTrue(AirPlayPersistence.loadDirectVideoOutput(context))
        assertEquals(MediaAudioBuffer.MOST_STABLE_MILLIS, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true))
        assertEquals(700, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false))

        AirPlayPersistence.restoreWeakDeviceProfile(context)
        assertFalse(AirPlayPersistence.loadWeakDeviceMode(context))
        assertEquals(90, AirPlayPersistence.loadDisplayScalePercent(context))
        assertFalse(AirPlayPersistence.loadDirectVideoOutput(context))
        assertEquals(500, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true))
        assertEquals(700, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false))
    }

    @Test fun restoreWithNoSnapshotFallsBackToTheDefaults() {
        AirPlayPersistence.restoreWeakDeviceProfile(context)
        assertEquals(100, AirPlayPersistence.loadDisplayScalePercent(context))
        assertFalse(AirPlayPersistence.loadDirectVideoOutput(context))
        assertEquals(MediaAudioBuffer.DEFAULT_MILLIS, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true))
    }

    @Test fun applyAndRestoreLeaveTheWiredBufferAlone() {
        AirPlayPersistence.saveMediaBufferMillis(context, wireless = false, 700)
        AirPlayPersistence.applyWeakDeviceProfile(context)
        assertEquals(700, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false))
        AirPlayPersistence.restoreWeakDeviceProfile(context)
        assertEquals(700, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false))
    }
}
