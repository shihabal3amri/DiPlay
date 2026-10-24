package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.*
import org.junit.Test

class AlbumTrackIdentityTest {
    private val track = CarPlayNowPlaying(title = "song", artist = "artist", album = "album",
        durationMillis = 180_000, artworkTransferId = 1, elapsedMillis = 60_000, playing = true)

    @Test fun lyricAndPositionUpdatesKeepDecodedCover() {
        repeat(200) { assertFalse(albumTrackChanged(track, track.copy(title = "lyric $it", elapsedMillis = 60_000L + it * 500))) }
        assertFalse(albumTrackChanged(track, track.copy(elapsedMillis = 0)))
        assertFalse(albumTrackChanged(track, track.copy(durationMillis = 180700)))
        assertFalse(albumTrackChanged(track, track.copy(durationMillis = 181999)))
    }
    @Test fun realTrackChangesInvalidateOldCover() {
        assertTrue(albumTrackChanged(track, track.copy(artworkTransferId = null)))
        assertTrue(albumTrackChanged(track, track.copy(artworkTransferId = 2)))
        assertFalse(albumTrackChanged(track, track.copy(artist = "other")))
        assertFalse(albumTrackChanged(track, track.copy(durationMillis = 240_000)))
        assertTrue(albumTrackChanged(track, track.copy(title = "next", elapsedMillis = 0)))
    }
    @Test fun latePreviousSongFieldsAndPartialBlanksDoNotRevokeArtwork() {
        assertFalse(albumTrackChanged(track, track.copy(artist = null, album = null, durationMillis = 0)))
        assertFalse(albumTrackChanged(track, track.copy(album = "new album", durationMillis = 240000)))
        assertFalse(albumTrackChanged(track, track.copy(title = null)))
        assertTrue(albumTrackChanged(track, track.copy(title = null, artist = null, album = null)))
        assertTrue(albumTrackChanged(track.copy(sourceApp = "One"), track.copy(sourceApp = "Two")))
    }
    @Test fun delayedFirstReferenceAndSourceAreAccepted() {
        assertFalse(albumTrackChanged(CarPlayNowPlaying(), track.copy(artworkTransferId = null)))
        assertTrue(albumTrackChanged(track.copy(artworkTransferId = null), track))
        assertFalse(albumTrackChanged(track.copy(sourceApp = null), track.copy(sourceApp = "Music")))
    }
}
