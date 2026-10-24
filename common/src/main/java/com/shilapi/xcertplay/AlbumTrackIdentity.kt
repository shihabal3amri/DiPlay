package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayNowPlaying

/** Metadata arrives incrementally, often AFTER the artwork for the new transfer reference. */
internal fun albumTrackChanged(previous: CarPlayNowPlaying, next: CarPlayNowPlaying): Boolean {
    if (previous.artworkTransferId != next.artworkTransferId) return true
    fun hasTrack(info: CarPlayNowPlaying) = !info.title.isNullOrBlank() ||
        !info.artist.isNullOrBlank() || !info.album.isNullOrBlank()
    if (hasTrack(previous) && !hasTrack(next)) return true
    if (!previous.sourceApp.isNullOrBlank() && !next.sourceApp.isNullOrBlank() &&
        previous.sourceApp != next.sourceApp) return true
    // Artist, album and duration can still contain the PREVIOUS song's values when its successor's
    // reference and image arrive. Corrections (including temporary blanks/zero duration) do not
    // revoke that image. A transfer ID is reusable, so a title change plus a substantial playback
    // restart still invalidates an old cover; lyrics or seeking alone must not do so.
    return !previous.title.isNullOrBlank() && !next.title.isNullOrBlank() &&
        previous.title != next.title && previous.elapsedMillis?.let { it > 15_000 } == true &&
        next.elapsedMillis?.let { it < 3_000 } == true
}
