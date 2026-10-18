package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class VehicleMediaMutePolicyTest {
    private val owner = Any()
    private val policy = VehicleMediaMutePolicy()
    private fun sample(mute: Boolean?, playing: Boolean? = false,
        token: Any? = owner, enabled: Boolean = true, connected: Boolean = true,
        validMedia: Boolean = true, voice: Boolean = false) =
        policy.observe(token, enabled, connected, validMedia, voice, mute, playing)

    @Test fun manualControlDisconnectAndFailureInvalidateQueuedDecisions() {
        for (cancel in listOf<() -> Unit>({ policy.manualControl() }, { policy.commandFailed() }, { policy.clear() })) {
            val before = policy.revision
            cancel()
            assertNotEquals(before, policy.revision)
        }
    }
    @Test fun playingMutePausesAndUnmutePlaysExactlyOnce() {
        assertNull(sample(false, true))
        assertEquals(VehicleMediaMutePolicy.Command.PAUSE, sample(true, true))
        assertNull(sample(true, false))
        assertEquals(VehicleMediaMutePolicy.Command.PLAY, sample(false, false))
        assertNull(sample(false, false))
    }
    @Test fun unmutePlaysOriginallyPausedAndUnknownWithoutOwnedPause() {
        for (playing in listOf(false, null)) {
            policy.clear()
            assertNull(sample(false, playing))
            assertNull(sample(true, playing))
            assertEquals(VehicleMediaMutePolicy.Command.PLAY, sample(false, playing))
        }
    }
    @Test fun initialUnmutedAttachmentDoesNotPlayButInitialMutedFallingEdgeDoes() {
        assertNull(sample(false))
        assertNull(sample(false))
        policy.clear()
        assertNull(sample(true))
        assertEquals(VehicleMediaMutePolicy.Command.PLAY, sample(false))
    }
    @Test fun voiceSuppressesCommandsThenDeferredUnmutePlaysOnce() {
        sample(false, true)
        assertNull(sample(true, true, voice = true))
        assertNull(sample(false, voice = true))
        assertNull(sample(false, voice = true))
        assertEquals(VehicleMediaMutePolicy.Command.PLAY, sample(false))
        assertNull(sample(false))
    }
    @Test fun pendingPlayClearsForManualControlFailureAndNewMute() {
        for (mode in 0..2) {
            policy.clear(); sample(true)
            sample(false, voice = true)
            when (mode) {
                0 -> policy.manualControl()
                1 -> policy.commandFailed()
                2 -> { sample(true, voice = true); assertNull(sample(true)); continue }
            }
            assertNull(sample(false))
        }
    }
    @Test fun disconnectDisableNewSessionUnknownMuteAndInvalidMediaClearPending() {
        for (mode in 0..5) {
            policy.clear(); sample(true); sample(false, voice = true)
            when (mode) {
                0 -> sample(false, connected = false)
                1 -> sample(false, enabled = false)
                2 -> sample(false, token = Any())
                3 -> sample(null)
                4 -> sample(false, validMedia = false)
                5 -> sample(false, token = null)
            }
            assertNull(sample(false))
        }
    }
    @Test fun playbackChangesWhileMutedNeverRetryPause() {
        sample(false, true)
        assertEquals(VehicleMediaMutePolicy.Command.PAUSE, sample(true, true))
        assertNull(sample(true, false))
        assertNull(sample(true, true))
        assertEquals(VehicleMediaMutePolicy.Command.PLAY, sample(false, true))
    }
    @Test fun initialMutedPlayingPausesOnceAndNeverLoops() {
        assertEquals(VehicleMediaMutePolicy.Command.PAUSE, sample(true, true))
        assertNull(sample(true, true))
        assertNull(sample(true, false))
        assertNull(sample(true, true))
    }
    @Test fun initialAndRisingMutedVoiceDeferPauseUntilPriorityClearsOnce() {
        for (initial in listOf(true, false)) {
            policy.clear()
            if (!initial) sample(false, true)
            assertNull(sample(true, true, voice = true))
            assertNull(sample(true, true, voice = true))
            assertEquals(VehicleMediaMutePolicy.Command.PAUSE, sample(true, true))
            assertNull(sample(true, true))
        }
    }
    @Test fun deferredPauseClearsWhenPhoneNoLongerPlays() {
        sample(true, true, voice = true)
        assertNull(sample(true, false))
        assertNull(sample(true, true))
    }
    @Test fun deferredPauseClearsOnManualControlDisconnectDisableAndUnknownMute() {
        for (mode in 0..4) {
            policy.clear(); sample(true, true, voice = true)
            when (mode) {
                0 -> policy.manualControl()
                1 -> sample(true, true, connected = false)
                2 -> sample(true, true, enabled = false)
                3 -> sample(null, true)
                4 -> sample(false, true, voice = true)
            }
            // Reattachment while muted starts a fresh initial observation, so a currently
            // paused phone confirms that no old deferred pause crosses the gap.
            assertNull(sample(true, false))
            assertNull(sample(true, true))
        }
    }

}
