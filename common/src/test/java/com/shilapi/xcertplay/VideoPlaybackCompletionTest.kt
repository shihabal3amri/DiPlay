package com.shilapi.xcertplay

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class VideoPlaybackCompletionTest {
    private class Playback {
        val player = mock(Player::class.java)
        val events = mock(Player.Events::class.java)
        val timeline = mock(Timeline::class.java)
        var dynamic = false
        var live = false
        var placeholder = false
        var calls = 0
        val listener = VideoPlaybackCompletion { calls++ }
        init {
            `when`(player.currentTimeline).thenReturn(timeline)
            `when`(timeline.windowCount).thenReturn(1)
            `when`(timeline.getWindow(anyInt(), any(Timeline.Window::class.java))).thenAnswer {
                (it.getArgument(1) as Timeline.Window).apply {
                    isDynamic = dynamic
                    liveConfiguration = if (live) MediaItem.LiveConfiguration.Builder().build() else null
                    isPlaceholder = placeholder
                }
            }
            `when`(player.duration).thenReturn(10_000L)
        }
        fun state(state: Int) {
            `when`(player.playbackState).thenReturn(state)
            listener.onPlaybackStateChanged(state)
            listener.onEvents(player, events)
        }
        fun hls(type: String? = null, end: Boolean = false) {
            val text = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n" +
                (type?.let { "#EXT-X-PLAYLIST-TYPE:$it\n" } ?: "") +
                "#EXTINF:2,\nsegment.ts\n" + if (end) "#EXT-X-ENDLIST\n" else ""
            val media = HlsPlaylistParser().parse(Uri.parse("https://example.invalid/video.m3u8"),
                text.byteInputStream()) as HlsMediaPlaylist
            val constructor = HlsManifest::class.java.getDeclaredConstructor(
                HlsMultivariantPlaylist::class.java, HlsMediaPlaylist::class.java).apply { isAccessible = true }
            `when`(player.currentManifest).thenReturn(constructor.newInstance(
                HlsMultivariantPlaylist.createSingleVariantMultivariantPlaylist("https://example.invalid/video.m3u8"), media))
        }
    }

    @Test fun aFiniteFileEndsOnceAndANewItemCanCompleteAgain() {
        val p = Playback()
        p.state(Player.STATE_READY)
        p.state(Player.STATE_ENDED)
        p.state(Player.STATE_ENDED)
        assertEquals(1, p.calls)
        p.listener.reset()
        p.state(Player.STATE_ENDED)
        assertEquals(1, p.calls)
        p.state(Player.STATE_READY)
        p.state(Player.STATE_ENDED)
        assertEquals(2, p.calls)
    }

    @Test fun reachingTheBufferedEndWhileWaitingDoesNotCompletePlayback() {
        val p = Playback()
        `when`(p.player.currentPosition).thenReturn(10_000)
        `when`(p.player.bufferedPosition).thenReturn(10_000)
        p.state(Player.STATE_READY)
        repeat(5) { p.state(Player.STATE_BUFFERING) }
        assertEquals(0, p.calls)
    }

    @Test fun aLiveIptvPlaylistRemainsLiveEvenIfItsLastUpdateAddsEndlist() {
        val p = Playback()
        p.hls()
        p.state(Player.STATE_READY)
        p.state(Player.STATE_BUFFERING)
        p.hls(end = true)
        p.state(Player.STATE_ENDED)
        assertEquals(0, p.calls)
    }

    @Test fun explicitlyFinishedVodHlsCanComplete() {
        val p = Playback()
        p.hls("VOD", end = true)
        p.state(Player.STATE_READY)
        p.state(Player.STATE_ENDED)
        assertEquals(1, p.calls)
    }

    @Test fun unfinishedAndEventHlsPlaylistsDoNotAutoClose() {
        for ((type, end) in listOf(null to false, null to true, "VOD" to false, "EVENT" to true)) {
            val p = Playback()
            p.hls(type, end)
            p.state(Player.STATE_READY)
            p.state(Player.STATE_ENDED)
            assertEquals(0, p.calls)
        }
    }

    @Test fun liveAndDynamicTimelinesCannotBecomeOnDemandWithoutANewItem() {
        for (live in listOf(true, false)) {
            val p = Playback()
            p.live = live
            p.dynamic = !live
            p.state(Player.STATE_READY)
            p.live = false
            p.dynamic = false
            p.state(Player.STATE_ENDED)
            assertEquals(0, p.calls)
        }
    }

    @Test fun unknownDurationAndPlaceholderTimelinesAreNotCompletionEvidence() {
        for (duration in listOf(C.TIME_UNSET, 0L)) {
            val p = Playback()
            `when`(p.player.duration).thenReturn(duration)
            p.state(Player.STATE_READY)
            p.state(Player.STATE_ENDED)
            assertEquals(0, p.calls)
        }
        val p = Playback()
        p.placeholder = true
        p.state(Player.STATE_READY)
        p.state(Player.STATE_ENDED)
        assertEquals(0, p.calls)
    }
}
