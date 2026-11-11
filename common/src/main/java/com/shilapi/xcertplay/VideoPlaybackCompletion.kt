package com.shilapi.xcertplay

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist

/** Completion needs explicit end-of-media evidence, never the end of the buffered range. */
@OptIn(UnstableApi::class)
internal class VideoPlaybackCompletion(private val completed: () -> Unit) : Player.Listener {
    private var wasReady = false
    private var wasLive = false
    private var notified = false
    private val window = Timeline.Window()

    fun reset() {
        wasReady = false
        wasLive = false
        notified = false
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY) wasReady = true
    }

    override fun onEvents(player: Player, events: Player.Events) {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return
        timeline.getWindow(player.currentMediaItemIndex, window)
        if (window.isPlaceholder) return
        val hls = (player.currentManifest as? HlsManifest)?.mediaPlaylist
        if (window.isLive || window.isDynamic || hls?.hasEndTag == false ||
            hls?.playlistType == HlsMediaPlaylist.PLAYLIST_TYPE_EVENT) wasLive = true

        // A live playlist becoming static after ENDLIST must not turn it into an on-demand item.
        // ENDLIST alone can also describe a snapshot; unclassified HLS is intentionally left open.
        if (notified || wasLive || !wasReady || player.playbackState != Player.STATE_ENDED ||
            player.playerError != null || player.duration == C.TIME_UNSET || player.duration <= 0 ||
            (hls != null && (!hls.hasEndTag || hls.playlistType != HlsMediaPlaylist.PLAYLIST_TYPE_VOD))) return
        notified = true
        completed()
    }
}
