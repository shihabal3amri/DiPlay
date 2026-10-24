package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.media.CarPlayNowPlaying

/** Main-thread snapshots reuse the bounded media artwork cache; they never own/recycle its bitmap. */
internal object ClusterMusicState {
    data class Snapshot(val info: CarPlayNowPlaying = CarPlayNowPlaying(), val artwork: Bitmap? = null,
        val changedAt: Long = 0L, val revision: Long = 0L)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(Snapshot) -> Unit>()
    @Volatile private var state = Snapshot()
    @Volatile private var retainedCoverUpdates = 0L
    @Volatile private var lastCoverChange = "none"

    fun diagnostic(): String {
        val current = state
        return "Cluster music: titlePresent=${!current.info.title.isNullOrBlank()} " +
            "artistPresent=${!current.info.artist.isNullOrBlank()} transfer=${current.info.artworkTransferId} " +
            "coverValid=${current.artwork?.let { !it.isRecycled } == true} " +
            "retainedCoverUpdates=$retainedCoverUpdates lastCoverChange=$lastCoverChange"
    }

    fun publish(info: CarPlayNowPlaying, artwork: Bitmap?) {
        main.post {
            val changed = trackChanged(state.info, info)
            // Keep the previous decoded cover while a new transfer is pending, including
            // metadata-only updates. An empty disconnect snapshot clears it.
            val hasTrack = !info.title.isNullOrBlank() || !info.artist.isNullOrBlank() || !info.album.isNullOrBlank()
            // Keep album identity in diagnostics without clearing a valid pending cover.
            val sameCover = !albumTrackChanged(state.info, info)
            val cover = if (!hasTrack) null else artwork?.takeUnless { it.isRecycled }
                ?: state.artwork?.takeIf { !it.isRecycled }
            if (artwork == null && cover != null) retainedCoverUpdates++
            if (state.artwork !== cover) {
                lastCoverChange = when {
                    cover != null -> "decoded"
                    !hasTrack -> "metadata_cleared"
                    !sameCover -> "track_or_transfer_changed"
                    else -> "unavailable_or_recycled"
                }
                android.util.Log.i("ClusterMusic", "cover=$lastCoverChange transfer=${info.artworkTransferId}")
            }
            state = Snapshot(info, cover, if (changed) SystemClock.elapsedRealtime() else state.changedAt, state.revision + if (changed) 1 else 0)
            listeners.toList().forEach { it(state) }
        }
    }

    fun observe(listener: (Snapshot) -> Unit): () -> Unit {
        check(Looper.myLooper() == Looper.getMainLooper())
        listeners.add(listener)
        listener(state)
        return { listeners.remove(listener) }
    }

    fun trackChanged(old: CarPlayNowPlaying, next: CarPlayNowPlaying): Boolean =
        old.title != next.title || old.artist != next.artist || old.album != next.album ||
            old.sourceApp != next.sourceApp ||
            (old.durationMillis != null && next.durationMillis != null && old.durationMillis != next.durationMillis)
}
