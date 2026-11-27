package com.shilapi.xcertplay.media

import android.media.AudioManager
import com.shilapi.xcertplay.media.MediaFocusRestart.Restart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** When a music track whose start the car refused is started again after a focus gain (#1229). */
class MediaFocusRestartTest {
    private val restart = MediaFocusRestart()
    private var playing = true
    private var head = 0
    private var reads = 0

    private fun due(): Restart? = restart.due { reads++; playing to head }
    private fun refuse(times: Int = 1) = repeat(times) { restart.refused() }
    private fun gain() = restart.focusChanged(AudioManager.AUDIOFOCUS_GAIN)

    @Test fun aRefusedStartIsStartedAgainOnceFocusComesBack() {
        // Report 11, 18:02:35: music started while the car's telephony held focus; it let go at 18:02:53.
        restart.played()
        refuse(900)
        assertNull(due())
        gain()
        assertEquals(Restart(zeroWrites = 900, number = 1), due())
        assertNull(due())
    }

    @Test fun oneGainStartsTheTrackOnce() {
        restart.played()
        refuse()
        gain()
        assertNotNull(due())
        restart.played()
        refuse(500)
        assertNull(due())
        gain()
        assertEquals(Restart(zeroWrites = 500, number = 2), due())
    }

    @Test fun aGainWaitsForTheFirstRefusedWrite() {
        // Report 9, 17:51:47: focus came back 0.3 s after play(), while the buffer still took writes.
        restart.played()
        gain()
        repeat(10) { assertNull(due()) }
        refuse()
        assertEquals(Restart(zeroWrites = 1, number = 1), due())
    }

    @Test fun writesRefusedBeforeTheLastStartDoNotCount() {
        refuse(10)
        restart.played()
        gain()
        assertNull(due())
    }

    @Test fun aTrackTheCarPlaysUsesTheGainUp() {
        gain()
        head = 960
        assertNull(due())
        head = 0
        refuse()
        assertNull(due())
    }

    @Test fun aStartedTrackThatStopsIsLeftToTheRebuild() {
        head = 48_000
        restart.played()
        refuse(200)
        gain()
        assertNull(due())
    }

    @Test fun aLaterFocusChangeReplacesTheGain() {
        for (loss in listOf(AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)) {
            restart.played()
            gain()
            // The worker has seen the gain, which waits for a refused write.
            assertNull(due())
            restart.focusChanged(loss)
            refuse()
            assertNull("change=$loss", due())
        }
        gain()
        assertEquals(Restart(zeroWrites = 1, number = 1), due())
    }

    @Test fun aPausedTrackWaitsUntilItPlays() {
        restart.played()
        refuse()
        gain()
        playing = false
        assertNull(due())
        playing = true
        assertNotNull(due())
    }

    @Test fun aStreamIsStartedAgainAtMostThreeTimes() {
        repeat(MediaFocusRestart.MAX_RESTARTS) {
            gain()
            refuse()
            assertEquals(it + 1, due()?.number)
            restart.played()
        }
        gain()
        refuse()
        assertNull(due())
    }

    @Test fun theTrackIsReadOnlyWhileAGainWaits() {
        refuse()
        repeat(100) { due() }
        assertEquals(0, reads)
        gain()
        due()
        repeat(100) { due() }
        assertEquals(1, reads)
    }

    @Test fun theLineSaysWhatHappenedInNumbersAndFixedWords() {
        assertEquals("Audio: media track start not accepted; focus held elsewhere; starting it again on focus gain " +
            "(head=0, zeroWrites=151, restart=1/3)", MediaAudioLines.startNotAccepted(151, 1))
    }
}
