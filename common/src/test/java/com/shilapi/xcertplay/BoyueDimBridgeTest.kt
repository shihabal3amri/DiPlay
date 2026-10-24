package com.shilapi.xcertplay

import android.media.MediaMetadata
import android.media.session.PlaybackState
import com.shilapi.xcertplay.vehicle.GeelyEcarxDimInteractionBridge
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BoyueDimBridgeTest {
    class ProgressReceiver {
        var progress = -1L
        var calls = 0
        fun updateCurrentProgress(value: Long) { progress = value; calls++ }
    }

    @Test fun positionUpdatesUseTheProgressSetterAndStopPreventsLaterWrites() {
        val bridge = GeelyEcarxDimInteractionBridge.get(RuntimeEnvironment.getApplication())
        bridge.stop()
        val receiver = ProgressReceiver()
        ReflectionHelpers.setField(bridge, "mediaManager", receiver)
        ReflectionHelpers.setField(bridge, "mediaApiClass", ProgressReceiver::class.java)
        ReflectionHelpers.setField(bridge, "lastMediaMetadata", MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "Song").build())
        fun playback(position: Long) = PlaybackState.Builder()
            .setState(PlaybackState.STATE_PLAYING, position, 1f).build()
        ReflectionHelpers.setField(bridge, "lastPlaybackState", playback(0))
        bridge.updateMusicPlayback(playback(1200))
        assertEquals(1200, receiver.progress)
        assertEquals(1, receiver.calls)
        // This receiver deliberately has no playlist or artwork setter.
        bridge.stop()
        bridge.updateMusicPlayback(playback(2400))
        assertEquals(1, receiver.calls)
    }

    @Test fun missingOemFrameworkReportsUnavailableInsteadOfCrashing() {
        val bridge = GeelyEcarxDimInteractionBridge.get(RuntimeEnvironment.getApplication())
        bridge.stop()
        assertFalse(bridge.publishMusic(MediaMetadata.Builder().build(),
            PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0, 0f).build()))
        assertFalse(bridge.isMediaAvailable)
        assertFalse(bridge.isNavigationAvailable)
        bridge.stop()
    }
}
