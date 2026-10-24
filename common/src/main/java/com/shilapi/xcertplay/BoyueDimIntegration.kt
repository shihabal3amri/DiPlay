package com.shilapi.xcertplay

import android.content.Context
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.hud.GeelyDimNavigation
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.vehicle.BoyueDimNavigationAdapter
import com.shilapi.xcertplay.vehicle.GeelyEcarxDimInteractionBridge

/** DIM output shares the media controller's lifetime, not a settings Activity's lifetime. */
internal object BoyueDimIntegration {
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var stopMusicObserver: (() -> Unit)? = null
    private var bridge: GeelyEcarxDimInteractionBridge? = null
    private var lastTurn: ClusterTurnGuidance? = null
    private val artworkCache = DimArtworkCache()
    private var lastMusicInfo: com.shilapi.xcertplay.media.CarPlayNowPlaying? = null
    private var lastMusicArtwork: DimArtworkCache.Output? = null
    @Volatile var status = "DIM inactive"
        private set

    fun attach(context: Context) { main.post { app = context.applicationContext; refreshOnMain() } }
    fun detach() { main.post { app = null; stopOutput() } }
    fun refresh() { main.post { refreshOnMain() } }

    fun playback(info: com.shilapi.xcertplay.media.CarPlayNowPlaying) {
        main.post {
            bridge?.updateMusicPlayback(PlaybackState.Builder().setState(
                if (info.title.isNullOrEmpty()) PlaybackState.STATE_STOPPED
                else if (info.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                info.elapsedMillis?.coerceAtLeast(0) ?: 0, if (info.playing) 1f else 0f)
                .setActiveQueueItemId(0).build())
        }
    }

    private fun refreshOnMain() {
        stopOutput()
        val context = app ?: return
        if (!BoyueLSettings.dim(context)) return
        bridge = GeelyEcarxDimInteractionBridge.get(context)
        stopMusicObserver = ClusterMusicState.observe { snapshot ->
            val output = bridge ?: return@observe
            val info = snapshot.info
            val art = artworkCache.get(snapshot.artwork, android.os.Build.VERSION.SDK_INT >= 30 &&
                android.os.Environment.isExternalStorageManager())
            val metadataInfo = info.copy(elapsedMillis = null, playing = false)
            // Position/state already use playback(). Only metadata/image changes require a playlist.
            if (metadataInfo == lastMusicInfo && art == lastMusicArtwork) return@observe
            val identity = listOf(info.title, info.artist, info.album, info.artworkTransferId?.toString())
                .joinToString("") { "${it.orEmpty().length}:${it.orEmpty()}" }
            val id = java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val metadata = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, id)
                .putString(MediaMetadata.METADATA_KEY_TITLE, info.title.orEmpty())
                .putString(MediaMetadata.METADATA_KEY_ARTIST, info.artist.orEmpty())
                .putString(MediaMetadata.METADATA_KEY_ALBUM, info.album.orEmpty())
                .putLong(MediaMetadata.METADATA_KEY_DURATION, info.durationMillis?.coerceAtLeast(0) ?: 0)
                .apply {
                    art.bitmap?.let { putBitmap(MediaMetadata.METADATA_KEY_ART, it) }
                    art.uri?.let { putString(MediaMetadata.METADATA_KEY_ART_URI, it.toString()) }
                }.build()
            val published = output.publishMusic(metadata, PlaybackState.Builder().setState(
                if (info.title.isNullOrEmpty()) PlaybackState.STATE_STOPPED
                else if (info.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                info.elapsedMillis?.coerceAtLeast(0) ?: 0, if (info.playing) 1f else 0f)
                .setActiveQueueItemId(0).build())
            if (published) { lastMusicInfo = metadataInfo; lastMusicArtwork = art }
            status = output.status()
        }
        main.post(pollNavigation)
    }

    private val pollNavigation = object : Runnable {
        override fun run() {
            val output = bridge ?: return
            val current = GeelyDimNavigation.snapshot()
            if (current != lastTurn) {
                if (current == null) output.clearNavigation()
                else output.publishNavigation(BoyueDimNavigationAdapter.convert(current))
                lastTurn = current
            }
            status = output.status()
            main.postDelayed(this, 500)
        }
    }

    private fun stopOutput() {
        main.removeCallbacks(pollNavigation)
        stopMusicObserver?.invoke(); stopMusicObserver = null
        bridge?.let {
            it.publishMusic(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "").build(),
                PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0, 0f).build())
            it.stop()
        }
        bridge = null; lastTurn = null; status = "DIM inactive"
        artworkCache.clear(); lastMusicInfo = null; lastMusicArtwork = null
    }
}
