package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioManager
import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject

/**
 * The music renderer on the #1229 head unit: its track stays PLAYING with the head at 0, fills, and then returns 0
 * from every write. The renderer says so once and builds one new track, without asking for audio focus again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 28, 33], manifest = Config.NONE, shadows = [MediaTrackRebuildTest.CarTrack::class])
class MediaTrackRebuildTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(AudioManager::class.java)
    private val reports = CopyOnWriteArrayList<String>()
    private val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
    private lateinit var sink: AndroidMediaSink
    private lateinit var renderer: Any
    @Volatile private var nowNs = 1_000_000_000L

    @Before fun setUp() {
        CarTrack.reset()
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        sink = AndroidMediaSink(context = context, audioFocusEnabled = true, onAudioDiagnostic = reports::add)
        renderer = renderer("media")
    }

    @After fun tearDown() {
        call("release")
        sink.close()
        CarTrack.reset()
    }

    private fun renderer(audioType: String): Any = AndroidMediaSink::class.java
        .getDeclaredMethod("audioRenderer", AudioStreamId::class.java, AudioFormat::class.java)
        .apply { isAccessible = true }
        .invoke(sink, AudioStreamId(100, audioType), AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 100, audioType))!!
        .also { created ->
            val clock: () -> Long = { nowNs }
            field("nanoTime").set(created, clock)
        }

    private fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
    private fun get(name: String): Any? = field(name).get(renderer)
    private fun set(name: String, value: Any?) = field(name).set(renderer, value)
    private fun call(name: String) { type.getDeclaredMethod(name).apply { isAccessible = true }.invoke(renderer) }

    private fun write(bytes: Int = PACKET_BYTES) {
        type.getDeclaredMethod("writePcm", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(renderer, ByteArray(bytes), 0, bytes)
    }

    /** What the worker does before its loop. */
    private fun open() {
        call("createTrack")
        call("requestAudioFocus")
    }

    /** Music packets 20 ms apart for [millis], through the worker's steps after each one. */
    private fun play(millis: Long) {
        repeat((millis / 20).toInt()) {
            write()
            call("recoverStalledTrack")
            nowNs += 20_000_000L
        }
    }

    private fun reportMediaStart(force: Boolean = false) {
        type.getDeclaredMethod("reportMediaStart", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(renderer, force)
    }

    private fun stallLines() = reports.filter { it.startsWith("Audio: car is not consuming") }
    private fun track(index: Int) = CarTrack.tracks[index]

    @Test fun aTrackThatNeverStartsIsRebuiltOnceAndTheNewOnePlays() {
        CarTrack.consumes = { it > 0 }
        open()
        play(6_000)

        assertEquals(listOf("Audio: car is not consuming CarPlay audio; media track never started " +
            "(head=0 for 3 s, zeroWrites=151, queue=0); rebuilding the track once"), stallLines())
        assertEquals(1, reports.count { it == "Audio: media track rebuilt recreated=true" })
        assertEquals(1, reports.count { it.startsWith("Audio: car consumes CarPlay audio after the rebuild head=") })
        assertEquals(2, CarTrack.tracks.size)
        assertTrue(track(0).released)
        assertTrue(track(1).playing)
        assertTrue(track(1).head > 0)
        // The frame counters belong to the new track: nothing the old one held counts as queued.
        assertEquals(track(1).written, get("totalWrittenFrames"))
    }

    @Test fun aRebuildThatDoesNotHelpIsReportedAndNotRepeated() {
        CarTrack.consumes = { false }
        open()
        play(60_000)

        assertEquals(listOf(true, false), stallLines().map { it.endsWith("rebuilding the track once") })
        assertTrue(stallLines()[1].endsWith("not rebuilding it again"))
        assertEquals(2, CarTrack.tracks.size)
        assertTrue(reports.none { it.startsWith("Audio: car consumes") })
    }

    @Test fun aTrackThatStopsMidSongIsRebuiltToo() {
        open()
        play(5_000)
        assertTrue(stallLines().isEmpty())
        val head = track(0).head
        CarTrack.consumes = { it > 0 }
        play(6_000)

        assertTrue(stallLines().single(), stallLines().single().contains("media track stopped (head=$head for 3 s"))
        assertEquals(2, CarTrack.tracks.size)
        assertTrue(track(1).playing)
    }

    @Test fun musicTheCarPlaysIsNeverRebuilt() {
        open()
        play(60_000)
        assertTrue(stallLines().isEmpty())
        assertEquals(1, CarTrack.tracks.size)
    }

    @Test fun theRebuiltTrackKeepsTheFocusRequestAndTheDuckedVolume() {
        CarTrack.consumes = { it > 0 }
        open()
        val request = shadowOf(manager).lastAudioFocusRequest
        sink.onMediaAudioFocusChanged(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        play(6_000)

        assertEquals(2, CarTrack.tracks.size)
        assertSame(request, shadowOf(manager).lastAudioFocusRequest)
        assertEquals(1, reports.count { it.startsWith("Audio: focus requested") })
        assertEquals(listOf(0.2f), track(1).volumes)
        // The old track is out of the coordinator; the new one follows the next change.
        sink.onMediaAudioFocusChanged(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(listOf(0.2f), track(0).volumes)
        assertEquals(listOf(0.2f, 1f), track(1).volumes)
    }

    @Test fun aHeadUnitThatRefusesANewTrackKeepsTheOldOne() {
        CarTrack.consumes = { false }
        open()
        CarTrack.minBufferSize = AudioTrack.ERROR_BAD_VALUE
        play(4_000)
        assertTrue(reports.toString(), reports.contains("Audio: media track rebuilt recreated=false"))
        assertEquals(1, CarTrack.tracks.size)
        assertFalse(track(0).released)

        CarTrack.consumes = { true }
        play(2_000)
        assertTrue(track(0).playing)
        assertTrue(track(0).head > 0)
        assertEquals(1, reports.count { it.startsWith("Audio: car consumes CarPlay audio after the rebuild") })
    }

    @Test fun aNewTrackThatCannotBeBuiltKeepsTheOldOne() {
        CarTrack.consumes = { false }
        open()
        CarTrack.refuseBuild = true
        play(4_000)
        assertTrue(reports.toString(), reports.any { it.startsWith("Audio: renderer failed") && it.contains("stage=track-build") })
        assertTrue(reports.toString(), reports.contains("Audio: media track rebuilt recreated=false"))
        assertEquals(1, CarTrack.tracks.size)
        assertFalse(track(0).released)

        CarTrack.consumes = { true }
        play(2_000)
        assertTrue(track(0).playing)
        assertTrue(track(0).head > 0)
        assertEquals(1, reports.count { it.startsWith("Audio: car consumes CarPlay audio after the rebuild") })
    }

    @Test fun theLampsFollowTheRebuiltTrack() {
        CarTrack.consumes = { it > 0 }
        open()
        val before = get("ambientEnvelope")
        assertLampsFollow(0)
        play(6_000)
        assertEquals(2, CarTrack.tracks.size)
        assertNotSame(before, get("ambientEnvelope"))
        assertLampsFollow(1)
    }

    @Test fun theLampsFollowTheKeptTrackWhenTheHeadUnitHasNoBufferSize() =
        theLampsFollowTheKeptTrack { CarTrack.minBufferSize = AudioTrack.ERROR_BAD_VALUE }

    @Test fun theLampsFollowTheKeptTrackWhenTheNewOneCannotBeBuilt() =
        theLampsFollowTheKeptTrack { CarTrack.refuseBuild = true }

    private fun theLampsFollowTheKeptTrack(refuse: () -> Unit) {
        CarTrack.consumes = { false }
        open()
        val before = get("ambientEnvelope")
        refuse()
        play(4_000)
        CarTrack.consumes = { true }
        play(2_000)
        assertTrue(reports.contains("Audio: media track rebuilt recreated=false"))
        assertEquals(1, CarTrack.tracks.size)
        assertTrue(track(0).head > 0)
        // The kept track counts from zero again, so what it was given before the rebuild is not in the way.
        assertNotSame(before, get("ambientEnvelope"))
        assertLampsFollow(0)
    }

    /** The lamps read the envelope this renderer writes to, and the position of the track at [index]. */
    private fun assertLampsFollow(index: Int) {
        settleLamps()
        assertTrue(activeLampRenderer() != 0L)
        assertEquals(get("ambientRendererToken"), activeLampRenderer())
        assertSame(get("ambientEnvelope"), lamps("envelope"))
        @Suppress("UNCHECKED_CAST")
        val playback = lamps("playback") as () -> Pair<Int, Boolean>
        assertEquals(track(index).head to track(index).playing, playback())
    }

    @Test fun aStreamClosedWhileItsTrackIsRebuiltLeavesTheLampsToTheNextSong() {
        CarTrack.consumes = { false }
        open()
        writeUntilRebuildIsPending()
        // The skip arrives in the middle of the rebuild: after its own checks, before the new track is there.
        var next: Any? = null
        CarTrack.onMinBufferSize = {
            CarTrack.onMinBufferSize = {}
            sink.onAudioStopped(AudioStreamId(100, "media"))
            next = renderer("media")
            type.getDeclaredMethod("createTrack").apply { isAccessible = true }.invoke(next)
        }
        call("recoverStalledTrack")
        assertNotSame(renderer, next)
        assertEquals(false, get("running"))
        val token = field("ambientRendererToken").get(next)
        assertNotEquals(0L, token)
        assertEquals(token, activeLampRenderer())
        settleLamps()
        assertSame(field("ambientEnvelope").get(next), lamps("envelope"))

        call("release")
        settleLamps()
        assertEquals(token, activeLampRenderer())
        assertSame(field("ambientEnvelope").get(next), lamps("envelope"))
        renderer = next!!
    }

    @Test fun aStreamClosedWhileItsTrackIsRebuiltTakesNoLamps() {
        CarTrack.consumes = { false }
        open()
        writeUntilRebuildIsPending()
        CarTrack.onMinBufferSize = { (renderer as java.io.Closeable).close() }
        call("recoverStalledTrack")
        settleLamps()
        assertEquals(0L, activeLampRenderer())
        assertNull(lamps("playback"))
    }

    @Test fun onlyTheStreamThatOwnsTheLampsPassesThemOn() {
        val lampSink = AndroidMediaSink::class.java.getDeclaredField("ambientSinkToken")
            .apply { isAccessible = true }.getLong(sink)
        val closed = AmbientMusicController.openRenderer(lampSink, AmbientMusicEnvelope()) { 1 to true }
        AmbientMusicController.closeRenderer(closed)
        assertEquals(0L, AmbientMusicController.reopenRenderer(lampSink, closed, AmbientMusicEnvelope()) { 2 to true })
        assertEquals(0L, AmbientMusicController.reopenRenderer(lampSink, 0L, AmbientMusicEnvelope()) { 2 to true })
        assertEquals(0L, activeLampRenderer())

        val nextEnvelope = AmbientMusicEnvelope()
        val next = AmbientMusicController.openRenderer(lampSink, nextEnvelope) { 3 to true }
        assertEquals(0L, AmbientMusicController.reopenRenderer(lampSink, closed, AmbientMusicEnvelope()) { 2 to true })
        AmbientMusicController.closeRenderer(closed)
        settleLamps()
        assertEquals(next, activeLampRenderer())
        assertSame(nextEnvelope, lamps("envelope"))

        val rebuilt = AmbientMusicController.reopenRenderer(lampSink, next, AmbientMusicEnvelope()) { 4 to true }
        assertTrue(rebuilt != 0L && rebuilt != next)
        assertEquals(rebuilt, activeLampRenderer())
        AmbientMusicController.closeRenderer(rebuilt)
    }

    private fun lamps(name: String): Any? = AmbientMusicController::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(AmbientMusicController)
    private fun activeLampRenderer() = (lamps("activeRenderer") as AtomicLong).get()
    private fun settleLamps() { (lamps("executor") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS) }

    /** A stalled track whose worker has not reached its rebuild yet. */
    private fun writeUntilRebuildIsPending() {
        var packets = 0
        while (get("rebuildPending") != true) {
            assertTrue("No stall after $packets packets", packets++ < 500)
            write()
            nowNs += 20_000_000L
        }
        assertEquals(1, stallLines().size)
    }

    @Test fun guidanceAndCallsAreNeverRebuilt() {
        CarTrack.consumes = { false }
        for (audioType in listOf("default", "telephony", "speechRecognition")) {
            call("release")
            renderer = renderer(audioType)
            CarTrack.tracks.clear()
            open()
            play(10_000)
            assertEquals(audioType, 1, CarTrack.tracks.size)
        }
        assertTrue(stallLines().isEmpty())
    }

    @Test fun aClosedRendererIsNotRebuilt() {
        CarTrack.consumes = { false }
        open()
        writeUntilRebuildIsPending()
        (renderer as java.io.Closeable).close()
        call("recoverStalledTrack")
        assertEquals(false, get("rebuildPending"))
        play(10_000)
        assertEquals(1, stallLines().size)
        assertTrue(reports.none { it.startsWith("Audio: media track rebuilt") })
        assertEquals(1, CarTrack.tracks.size)
    }

    @Test fun aRendererClosedDuringItsLastRefusedWriteReportsNoStall() {
        CarTrack.consumes = { false }
        open()
        // A head unit on which close()'s pause fails, so the track still says it plays.
        CarTrack.pauseFails = true
        val stallNs = MediaTrackStall.STALL_NS
        var firstRefusedNs = 0L
        CarTrack.onRefused = {
            if (firstRefusedNs == 0L) firstRefusedNs = nowNs
            if (nowNs - firstRefusedNs >= stallNs) (renderer as java.io.Closeable).close()
        }
        play(6_000)
        assertEquals(false, get("running"))
        assertTrue(track(0).playing)
        assertTrue(stallLines().toString(), stallLines().isEmpty())
        assertEquals(false, get("rebuildPending"))
        assertEquals(1, CarTrack.tracks.size)
    }

    @Test fun aWriteThatNeverReturnsIsInterruptedAndTheTrackRebuilt() {
        CarTrack.consumes = { it > 0 }
        CarTrack.blockWhenFull = true
        open()
        set("started", true)
        val blocked = CountDownLatch(1)
        CarTrack.onBlocked = blocked::countDown
        val worker = Thread { repeat(100) { write() } }.apply { start() }
        try {
            assertTrue(blocked.await(10, TimeUnit.SECONDS))
            // The network thread keeps delivering packets meanwhile.
            val submit = type.getDeclaredMethod("submit", ByteArray::class.java, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
            repeat(150) {
                submit.invoke(renderer, ByteArray(12 + PACKET_BYTES), it * PACKET_FRAMES)
                nowNs += 20_000_000L
            }
            assertTrue(stallLines().isEmpty())
            submit.invoke(renderer, ByteArray(12 + PACKET_BYTES), 150 * PACKET_FRAMES)
            worker.join(10_000)
            assertFalse("The write is still blocked", worker.isAlive)
        } finally {
            CarTrack.tracks.forEach { it.pause() }
            worker.join(10_000)
        }
        assertTrue(stallLines().single(), stallLines().single().matches(Regex(
            "Audio: car is not consuming CarPlay audio; media track write blocked \\(for 3 s, queue=\\d+\\); rebuilding the track once")))
        CarTrack.blockWhenFull = false
        play(2_000)
        assertEquals(2, CarTrack.tracks.size)
        assertTrue(track(0).released)
        assertTrue(track(1).playing)
    }

    @Test fun eachMediaStartReportsTheVolumeAndTheRouteOnce() {
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        open()
        @Suppress("UNCHECKED_CAST")
        set("musicVolume", (get("readMusicVolume") as () -> MusicVolume?)())
        reportMediaStart()
        assertTrue("Nothing plays yet", reports.none { it.startsWith("Audio: media start") })
        repeat(200) {
            play(20)
            reportMediaStart()
        }
        val line = reports.single { it.startsWith("Audio: media start") }
        assertTrue(line, line.matches(Regex(
            "Audio: media start musicVolume=3/$max muted=false fixed=(true|false) routeType=-?\\d+ streamOverride=0")))
    }

    @Test fun theAudioWorkerReportsTheStartAndRecoversTheStream() {
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
        CarTrack.consumes = { it > 0 }
        val id = AudioStreamId(100, "media")
        val format = AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 100, "media")
        sink.onAudioStarted(id, format, 0)
        val deadlineNs = System.nanoTime() + 20_000_000_000L
        var packet = 0
        val queue = get("queue") as java.util.Queue<*>
        fun recovered() = reports.any { it.startsWith("Audio: car consumes CarPlay audio after the rebuild") }
        while (!recovered() && System.nanoTime() < deadlineNs) {
            sink.onAudioRtp(id, format, ByteArray(12 + PACKET_BYTES), packet++ * PACKET_FRAMES)
            // The clock moves only once the worker has taken the packet, however slowly this machine runs it.
            while (queue.isNotEmpty() && System.nanoTime() < deadlineNs) Thread.sleep(1)
            nowNs += 20_000_000L
        }
        assertTrue(reports.toString(), recovered())
        assertTrue(stallLines().single(), stallLines().single().contains("media track never started (head=0 for 3 s"))
        assertEquals(2, CarTrack.tracks.size)
        assertEquals(1, reports.count { it.startsWith("Audio: media start musicVolume=3/") })
    }

    @Test fun aStreamThatEndsEarlyStillReportsItsStart() {
        open()
        play(400)
        reportMediaStart(force = true)
        assertEquals(1, reports.count { it.startsWith("Audio: media start musicVolume=unavailable") })
    }

    @Test fun eachStreamHasItsOwnRebuild() {
        CarTrack.consumes = { it > 0 }
        open()
        play(6_000)
        val first = renderer
        call("release")
        (first as java.io.Closeable).close()
        sink.onAudioStopped(AudioStreamId(100, "media"))
        renderer = renderer("media")
        assertNotSame(first, renderer)
        val before = CarTrack.tracks.size
        CarTrack.consumes = { it > before }
        open()
        play(6_000)
        assertEquals(2, stallLines().size)
        assertTrue(stallLines().all { it.endsWith("rebuilding the track once") })
    }

    /** A track that holds half a second and, when the car takes audio, plays 20 ms for each packet written. */
    @Implements(AudioTrack::class)
    class CarTrack {
        @RealObject private lateinit var track: AudioTrack
        @Volatile var head = 0
        @Volatile var written = 0L
        @Volatile var released = false
        @Volatile var playing = false
        val volumes = mutableListOf<Float>()
        private val index: Int by lazy { synchronized(tracks) { if (this !in tracks) tracks.add(this); tracks.indexOf(this) } }

        @Implementation fun getState(): Int {
            if (refuseBuild && this !in tracks) return AudioTrack.STATE_UNINITIALIZED
            index
            return AudioTrack.STATE_INITIALIZED
        }
        @Implementation fun getBufferSizeInFrames() = CAPACITY_FRAMES
        @Implementation fun getSampleRate() = 48_000
        @Implementation fun getUnderrunCount() = 0
        @Implementation fun getPlaybackHeadPosition() = head
        @Implementation fun getPlayState() = if (playing) AudioTrack.PLAYSTATE_PLAYING else AudioTrack.PLAYSTATE_PAUSED
        @Implementation fun setPlaybackRate(rate: Int) = AudioTrack.SUCCESS
        @Implementation fun setStereoVolume(left: Float, right: Float): Int { volumes.add(left); return AudioTrack.SUCCESS }
        @Implementation fun play() { playing = true }
        @Implementation fun pause() {
            check(!pauseFails)
            synchronized(this) { playing = false; (this as Object).notifyAll() }
        }
        @Implementation fun flush() { head = 0; written = 0L }
        @Implementation fun release() { released = true; playing = false }
        @Implementation fun write(bytes: ByteArray, offset: Int, size: Int, mode: Int): Int = synchronized(this) {
            val position = index
            if (playing && consumes(position)) head = minOf(written, head + PACKET_FRAMES.toLong()).toInt()
            var room = CAPACITY_FRAMES - (written - head).toInt()
            if (room <= 0 && blockWhenFull && playing) {
                onBlocked()
                while (playing) (this as Object).wait(10_000)
                room = 0
            }
            val frames = minOf(room, size / 4)
            if (frames == 0) onRefused()
            written += frames
            frames * 4
        }

        companion object {
            const val CAPACITY_FRAMES = 24_000
            val tracks = CopyOnWriteArrayList<CarTrack>()
            /** Whether the car plays the track that was built at this position. */
            @Volatile var consumes: (Int) -> Boolean = { true }
            @Volatile var blockWhenFull = false
            @Volatile var onBlocked: () -> Unit = {}
            var minBufferSize = 4096
            var onMinBufferSize: () -> Unit = {}
            var onRefused: () -> Unit = {}
            /** Whether a track that is not built yet fails to initialize, so AudioTrack.Builder.build() throws. */
            var refuseBuild = false
            var pauseFails = false

            fun reset() {
                tracks.clear()
                consumes = { true }
                blockWhenFull = false
                onBlocked = {}
                minBufferSize = 4096
                onMinBufferSize = {}
                onRefused = {}
                refuseBuild = false
                pauseFails = false
            }

            @JvmStatic @Implementation fun getMinBufferSize(rate: Int, channels: Int, format: Int): Int {
                onMinBufferSize()
                return minBufferSize
            }
        }
    }

    private companion object {
        const val PACKET_FRAMES = 960
        const val PACKET_BYTES = PACKET_FRAMES * 4
    }
}
