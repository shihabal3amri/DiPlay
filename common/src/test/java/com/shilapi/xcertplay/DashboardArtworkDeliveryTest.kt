package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.os.Looper
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DashboardArtworkDeliveryTest {
    @Test fun delayedMetadataLyricsAndFailedDuplicateCannotEraseCoverAfterSongSwitch() {
        val controller = mock(CarPlayController::class.java)
        var info = CarPlayNowPlaying(title = "Song", artist = "Singer", artworkTransferId = 7,
            durationMillis = 180000, elapsedMillis = 1000, playing = true)
        `when`(controller.nowPlayingSnapshot()).thenAnswer { info }
        val art = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val secondArt = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        var latest = ClusterMusicState.Snapshot()
        val stop = ClusterMusicState.observe { latest = it }
        val decodedClass = Class.forName("com.shilapi.xcertplay.CarPlayMediaKeys\$AmbientArtwork")
        val decode = CarPlayMediaKeys::class.java.declaredMethods.single { it.name == "onArtworkDecoded" }.apply { isAccessible = true }
        fun deliver(bitmap: Bitmap?, owner: Any = ReflectionHelpers.getField(CarPlayMediaKeys, "artworkOwner")) {
            val result = bitmap?.let { decodedClass.declaredConstructors.single().apply { isAccessible = true }.newInstance(it, null) }
            decode.invoke(CarPlayMediaKeys, owner, info.artworkTransferId!!, result)
            shadowOf(Looper.getMainLooper()).idle()
        }
        fun update(next: CarPlayNowPlaying) {
            info = next
            ReflectionHelpers.callInstanceMethod<Unit>(CarPlayMediaKeys, "onNowPlayingChanged",
                ReflectionHelpers.ClassParameter.from(CarPlayController::class.java, controller))
            shadowOf(Looper.getMainLooper()).idle()
        }
        try {
            CarPlayMediaKeys.attach(RuntimeEnvironment.getApplication(), controller)
            shadowOf(Looper.getMainLooper()).idle()
            deliver(art); assertSame(art, latest.artwork)
            update(info.copy(durationMillis = 180700, album = "Album", sourceApp = "Music"))
            assertSame(art, latest.artwork)
            repeat(200) {
                update(info.copy(title = "Lyric $it", elapsedMillis = 2000L + it * 500))
                assertSame(art, latest.artwork)
            }
            deliver(null)
            update(info.copy(elapsedMillis = 110000)); assertSame(art, latest.artwork)
            assertSame(art, ReflectionHelpers.getField<Bitmap>(CarPlayMediaKeys, "artwork"))
            // Metadata arriving after artwork must not invalidate the decode owner.
            // Keep the previous cover until a replacement arrives.
            val oldOwner: Any = ReflectionHelpers.getField(CarPlayMediaKeys, "artworkOwner")
            update(info.copy(title = "Next song", elapsedMillis = 0))
            assertSame(art, latest.artwork)
            assertSame(oldOwner, ReflectionHelpers.getField<Any>(CarPlayMediaKeys, "artworkOwner"))
            deliver(secondArt); assertSame(secondArt, latest.artwork)
            update(info.copy(elapsedMillis = 1000)); assertSame(secondArt, latest.artwork)
            update(info.copy(artworkTransferId = 8)); assertSame(secondArt, latest.artwork)
            deliver(art); assertSame(art, latest.artwork)
            // The new reference/cover can arrive before the new artist, album and duration.
            // Retained fields still describe the preceding song until these later packets arrive.
            update(info.copy(artist = "Next singer", album = "Next album", durationMillis = 240000))
            assertSame("Late metadata must not erase the newly delivered cover", art, latest.artwork)
            update(info.copy(artist = null, album = null, durationMillis = 0))
            assertSame("Partial empty metadata is not an artwork removal", art, latest.artwork)
            update(info.copy(artist = "Next singer", album = "Next album", durationMillis = 240000))
            assertSame(art, latest.artwork)
            CarPlayMediaKeys.detach(controller); shadowOf(Looper.getMainLooper()).idle()
            assertNull(latest.artwork)
            val stale = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            deliver(stale, oldOwner)
            assertTrue(stale.isRecycled)
            assertNull(latest.artwork)
        } finally {
            CarPlayMediaKeys.detach(controller); shadowOf(Looper.getMainLooper()).idle(); stop()
            art.recycle(); secondArt.recycle()
        }
    }
}
