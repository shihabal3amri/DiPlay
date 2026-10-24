package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class MediaBufferPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Before fun clearPreferences() {
        prefs.edit().clear().apply()
    }

    @Test fun freshInstallUsesTheDefaultForBothTransports() {
        assertEquals(300, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true))
        assertEquals(300, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false))
    }

    @Test fun wiredAndWirelessAreSavedSeparately() {
        AirPlayPersistence.saveMediaBufferMillis(context, wireless = false, millis = 50)
        AirPlayPersistence.saveMediaBufferMillis(context, wireless = true, millis = 1000)
        assertEquals(50, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false))
        assertEquals(1000, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true))
    }

    @Test fun sharedDelayFromEarlierVersionsCarriesOverToWired() {
        prefs.edit().putInt("media_buffer_ms", 50).apply()
        assertEquals(50, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false))
        // 50 ms is not a wireless preset, so wireless falls back to the default.
        assertEquals(300, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true))
    }

    @Test fun changingWirelessDoesNotMoveAnInheritedWiredDelay() {
        prefs.edit().putInt("media_buffer_ms", 500).apply()
        AirPlayPersistence.saveMediaBufferMillis(context, wireless = true, millis = 1000)
        assertEquals(500, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false))
        assertEquals(1000, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true))
    }

    @Test fun wirelessRejectsWiredOnlyPresets() {
        AirPlayPersistence.saveMediaBufferMillis(context, wireless = true, millis = 100)
        assertEquals(300, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true))
    }
    @Test fun fx11TuningRunsOncePreservesWiredAndAllowsLaterManualChanges() {
        BoyueLSettings.select(context,true)
        prefs.edit().putInt("media_buffer_ms",500).apply()
        AirPlayPersistence.migrateFx11MusicBuffer(context)
        assertEquals(1000,AirPlayPersistence.loadMediaBufferMillis(context,true))
        assertEquals(500,AirPlayPersistence.loadMediaBufferMillis(context,false))
        AirPlayPersistence.saveMediaBufferMillis(context,true,300)
        AirPlayPersistence.migrateFx11MusicBuffer(context)
        assertEquals(300,AirPlayPersistence.loadMediaBufferMillis(context,true))
        assertEquals(500,AirPlayPersistence.loadMediaBufferMillis(context,false))
    }

    @Test fun bufferTuningDoesNotTouchOtherVehiclesOrVoiceSettings() {
        BoyueLSettings.select(context,false)
        val before = prefs.all.toMap()
        AirPlayPersistence.migrateFx11MusicBuffer(context)
        assertEquals(before,prefs.all)
        BoyueLSettings.select(context,true)
        AirPlayPersistence.migrateFx11MusicBuffer(context)
        assertEquals(300,AirPlayPersistence.loadMediaBufferMillis(context,false))
        assertEquals(false,AirPlayPersistence.loadMainBufferedAudio(context))
        val allowed=setOf("media_buffer_ms","wired_media_buffer_ms","fx11_music_buffer_v44")
        assertEquals(before.filterKeys { it !in allowed },prefs.all.filterKeys { it !in allowed })
    }

}
