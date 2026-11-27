package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioManager
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.media.MediaTrackRebuildTest.CarTrack
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The music renderer on the #1229 head unit when its track was started while another app held media focus: the car
 * never activates it, Java says PLAYING, the head stays at 0 and writes return 0. Once DiPlay has focus back the
 * renderer plays the same track again, once per gain.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 28, 33], manifest = Config.NONE, shadows = [CarTrack::class])
class MediaTrackRestartTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(AudioManager::class.java)
    private val reports = CopyOnWriteArrayList<String>()
    private val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
    private lateinit var sink: AndroidMediaSink
    private lateinit var renderer: Any
    @Volatile private var nowNs = 1_000_000_000L
    /** The car's Bluetooth music or its telephony holds media focus, so the car refuses to start a track. */
    @Volatile private var focusElsewhere = false

    @Before fun setUp() {
        CarTrack.reset()
        CarTrack.startAccepted = { !focusElsewhere }
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        openSink(audioFocusEnabled = false)
    }

    @After fun tearDown() {
        call("release")
        sink.close()
        CarTrack.reset()
    }

    /** The default leaves focus to the media-key session, which passes each change on to the sink. */
    private fun openSink(audioFocusEnabled: Boolean) {
        sink = AndroidMediaSink(context = context, audioFocusEnabled = audioFocusEnabled, onAudioDiagnostic = reports::add)
        renderer = renderer("media")
    }

    private fun renderer(audioType: String): Any = AndroidMediaSink::class.java
        .getDeclaredMethod("audioRenderer", AudioStreamId::class.java, AudioFormat::class.java)
        .apply { isAccessible = true }
        .invoke(sink, AudioStreamId(100, audioType), AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 100, audioType))!!
        .also { created ->
            val clock: () -> Long = { nowNs }
            type.getDeclaredField("nanoTime").apply { isAccessible = true }.set(created, clock)
        }

    private fun get(name: String): Any? = type.getDeclaredField(name).apply { isAccessible = true }.get(renderer)
    private fun call(name: String) { type.getDeclaredMethod(name).apply { isAccessible = true }.invoke(renderer) }

    /** What the worker does before its loop. */
    private fun open() {
        call("createTrack")
        call("requestAudioFocus")
    }

    /** One music packet, through the worker's steps after it, 20 ms later than the last. */
    private fun packet() {
        type.getDeclaredMethod("writePcm", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(renderer, ByteArray(PACKET_BYTES), 0, PACKET_BYTES)
        call("recoverStalledTrack")
        call("restartRefusedStart")
        nowNs += 20_000_000L
    }

    private fun play(millis: Long) = repeat((millis / 20).toInt()) { packet() }

    /** DiPlay has media focus back: the media-key session's request gained it, or a regain was granted. */
    private fun focusBack() {
        focusElsewhere = false
        sink.onMediaAudioFocusChanged(AudioManager.AUDIOFOCUS_GAIN)
    }

    private fun restartLines() = reports.filter { it.startsWith("Audio: media track start not accepted") }
    private fun stallLines() = reports.filter { it.startsWith("Audio: car is not consuming") }
    private fun track(index: Int) = CarTrack.tracks[index]

    @Test fun aTrackStartedWhileFocusWasElsewhereIsStartedAgainOnFocusGain() {
        focusElsewhere = true
        open()
        play(1_000)
        assertEquals(1, track(0).plays)
        assertTrue(track(0).playing)
        assertEquals(0, track(0).head)

        focusBack()
        play(1_000)
        assertEquals(2, track(0).plays)
        assertTrue(track(0).head > 0)
        assertEquals(1, CarTrack.tracks.size)
        assertTrue(restartLines().single(), restartLines().single().matches(Regex(
            "Audio: media track start not accepted; focus held elsewhere; starting it again on focus gain " +
                "\\(head=0, zeroWrites=\\d+, restart=1/3\\)")))
        assertTrue(stallLines().isEmpty())
    }

    @Test fun focusThatComesBackBeforeTheBufferIsFullWaitsForTheFirstRefusedWrite() {
        // Report 9, 17:51:47: DiPlay had focus back 0.3 s after play(); the buffer still had room for 0.2 s.
        focusElsewhere = true
        open()
        while (track(0).plays == 0) packet()
        focusBack()
        packet()
        assertEquals(1, track(0).plays)
        play(500)
        assertEquals(2, track(0).plays)
        assertTrue(track(0).head > 0)
        assertEquals(1, restartLines().size)
    }

    @Test fun musicAfterSiriPlaysOnceTheCallLetsGoEvenAfterTheRebuild() {
        // Report 11: telephony held focus from 18:02:35, when music started, to 18:02:53.
        focusElsewhere = true
        open()
        play(18_000)
        assertEquals(listOf(true, false), stallLines().map { it.endsWith("rebuilding the track once") })
        assertEquals(2, CarTrack.tracks.size)
        assertTrue(restartLines().isEmpty())

        focusBack()
        play(2_000)
        assertEquals(1, track(0).plays)
        assertEquals(2, track(1).plays)
        assertTrue(track(1).head > 0)
        assertEquals(1, restartLines().size)
        assertEquals(2, CarTrack.tracks.size)
        assertEquals(2, stallLines().size)
    }

    @Test fun aStartThatIsRefusedAgainLeavesTheRebuildBudgetAlone() {
        focusElsewhere = true
        open()
        play(1_000)
        // The gain arrives, but the car still refuses the start.
        sink.onMediaAudioFocusChanged(AudioManager.AUDIOFOCUS_GAIN)
        play(9_000)
        assertEquals(2, track(0).plays)
        assertEquals(1, restartLines().size)
        // #1376 still rebuilds once, 3 s after the first refused write, and only once.
        assertEquals(listOf(true, false), stallLines().map { it.endsWith("rebuilding the track once") })
        assertTrue(stallLines()[0], stallLines()[0].contains("media track never started (head=0 for 3 s"))
        assertEquals(2, CarTrack.tracks.size)
        assertEquals(1, track(1).plays)
    }

    @Test fun eachGainStartsTheTrackOnceAndAStreamAtMostThreeTimes() {
        focusElsewhere = true
        open()
        play(1_000)
        repeat(4) {
            sink.onMediaAudioFocusChanged(AudioManager.AUDIOFOCUS_GAIN)
            play(400)
        }
        assertEquals(1 + MediaFocusRestart.MAX_RESTARTS, track(0).plays)
        assertEquals(listOf("1/3", "2/3", "3/3"), restartLines().map { it.substringAfter("restart=").removeSuffix(")") })
        assertTrue(stallLines().isEmpty())
    }

    @Test fun aFocusLossAfterTheGainCancelsIt() {
        focusElsewhere = true
        open()
        while (track(0).plays == 0) packet()
        sink.onMediaAudioFocusChanged(AudioManager.AUDIOFOCUS_GAIN)
        // The worker sees the gain while the buffer still takes writes, then the car's Bluetooth takes focus again.
        packet()
        sink.onMediaAudioFocusChanged(AudioManager.AUDIOFOCUS_LOSS)
        play(1_000)
        assertEquals(1, track(0).plays)
        assertTrue(restartLines().isEmpty())
    }

    @Test fun musicTheCarPlaysIsNotStartedAgain() {
        open()
        play(2_000)
        focusBack()
        play(2_000)
        assertEquals(1, track(0).plays)
        assertTrue(restartLines().isEmpty())
    }

    @Test fun aTrackThatStartedAndThenStoppedIsLeftToTheRebuild() {
        open()
        play(2_000)
        val head = track(0).head
        assertTrue(head > 0)
        CarTrack.consumes = { false }
        play(1_000)
        focusBack()
        play(1_000)
        assertEquals(head, track(0).head)
        assertEquals(1, track(0).plays)
        assertTrue(restartLines().isEmpty())
    }

    @Test fun callsSiriAndGuidanceAreNotStartedAgain() {
        for (audioType in listOf("telephony", "speechRecognition", "default")) {
            call("release")
            renderer = renderer(audioType)
            CarTrack.tracks.clear()
            focusElsewhere = true
            open()
            play(1_000)
            assertEquals(audioType, 0, track(0).head)
            focusBack()
            play(1_000)
            assertEquals(audioType, 1, track(0).plays)
        }
        assertTrue(restartLines().isEmpty())
    }

    @Test fun theSinksOwnFocusRequestStartsTheTrackAgainAndAMediaKeyGainOnlyOnce() {
        call("release")
        sink.close()
        openSink(audioFocusEnabled = true)
        focusElsewhere = true
        open()
        play(1_000)
        val coordinator = AndroidMediaSink::class.java.getDeclaredField("audioFocusCoordinator")
            .apply { isAccessible = true }.get(sink) as AudioFocusCoordinator
        coordinator.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        play(400)
        assertEquals(2, track(0).plays)
        // The media-key session's change reaches the renderer once, not again through the coordinator.
        sink.onMediaAudioFocusChanged(AudioManager.AUDIOFOCUS_GAIN)
        play(400)
        assertEquals(3, track(0).plays)
        assertEquals(2, restartLines().size)
    }

    @Test fun theAudioWorkerStartsTheTrackAgainOnFocusGain() {
        focusElsewhere = true
        val id = AudioStreamId(100, "media")
        val format = AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 100, "media")
        sink.onAudioStarted(id, format, 0)
        val queue = get("queue") as java.util.Queue<*>
        val deadlineNs = System.nanoTime() + 20_000_000_000L
        var packet = 0
        fun send() {
            sink.onAudioRtp(id, format, ByteArray(12 + PACKET_BYTES), packet++ * PACKET_FRAMES)
            // The clock moves only once the worker has taken the packet, however slowly this machine runs it.
            while (queue.isNotEmpty() && System.nanoTime() < deadlineNs) Thread.sleep(1)
            nowNs += 20_000_000L
        }
        repeat(50) { send() }
        assertEquals(1, track(0).plays)
        assertEquals(0, track(0).head)
        focusBack()
        while (track(0).head == 0 && System.nanoTime() < deadlineNs) send()
        assertTrue(track(0).head > 0)
        assertEquals(2, track(0).plays)
        assertEquals(1, restartLines().size)
        assertEquals(1, CarTrack.tracks.size)
    }

    private companion object {
        const val PACKET_FRAMES = 960
        const val PACKET_BYTES = PACKET_FRAMES * 4
    }
}
