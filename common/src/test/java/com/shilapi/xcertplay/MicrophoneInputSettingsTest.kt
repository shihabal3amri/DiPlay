package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.media.MicrophoneInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MicrophoneInputSettingsTest {
    @Test fun defaultsPreserveAutomaticAndSelectionsSurviveReload() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(-1, AirPlayPersistence.loadMicrophoneInputSource(context))
        assertEquals("", AirPlayPersistence.loadMicrophoneInputDevice(context))
        for (source in MicrophoneInput.sources) {
            AirPlayPersistence.saveMicrophoneInputSource(context, source)
            assertEquals(source, AirPlayPersistence.loadMicrophoneInputSource(context))
            assertEquals(if (source == -1) 7 else source, MicrophoneInput.resolve(source, 7))
        }
        AirPlayPersistence.saveMicrophoneInputSource(context, 999)
        assertEquals(-1, AirPlayPersistence.loadMicrophoneInputSource(context))
        AirPlayPersistence.saveMicrophoneInputDevice(context, "15|test|microphone")
        assertEquals("15|test|microphone", AirPlayPersistence.loadMicrophoneInputDevice(context))
        AirPlayPersistence.saveMicrophoneInputDevice(context, "")
        assertEquals("", AirPlayPersistence.loadMicrophoneInputDevice(context))
    }
}
