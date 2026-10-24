package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.os.Looper
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DashboardEnhancementsTest {
    @Test fun positionAndPendingTrackUpdatesKeepCoverButDisconnectAndRecycledArtClearIt() {
        var latest = ClusterMusicState.Snapshot()
        val stop = ClusterMusicState.observe { latest = it }
        fun publish(info: CarPlayNowPlaying, art: Bitmap? = null) {
            ClusterMusicState.publish(info, art); shadowOf(Looper.getMainLooper()).idle()
        }
        val art = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val first = CarPlayNowPlaying(title = "One", artist = "Artist", artworkTransferId = 4, playing = true)
        try {
            publish(first, art)
            repeat(200) { publish(first.copy(elapsedMillis = it * 500L)); assertSame(art, latest.artwork) }
            publish(first.copy(playing = false)); assertSame(art, latest.artwork)
            publish(first.copy(durationMillis = 123000)); assertSame(art, latest.artwork)
            publish(first.copy(durationMillis = 123500)); assertSame(art, latest.artwork)
            publish(first.copy(elapsedMillis = 60000))
            publish(first.copy(title = "Two", elapsedMillis = 0)); assertSame(art, latest.artwork)
            publish(first, art)
            publish(first.copy(artworkTransferId = 5)); assertSame(art, latest.artwork)
            publish(first, art)
            publish(CarPlayNowPlaying()); assertNull(latest.artwork)
            publish(first); assertNull(latest.artwork)
            art.recycle()
            publish(first, art); assertNull(latest.artwork)
        } finally { publish(CarPlayNowPlaying()); stop(); if (!art.isRecycled) art.recycle() }
    }

    @Test fun instrumentMapScaleChangesOnlySecondaryPhysicalSizeForEveryStyle() {
        val app = RuntimeEnvironment.getApplication()
        val oldAspect = MapMirrors.streamAspect
        BoyueLSettings.select(app, true)
        AirPlayPersistence.saveWidthPhysicalMm(app, 350)
        assertEquals(DashboardMapSize.LARGE, NativeDashboardSettings.mapSize(app))
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        fun config() = ReflectionHelpers.callInstanceMethod<AirPlayDisplayConfig>(host, "clusterDisplayConfig")
        try {
            for (style in listOf(DashboardStyle.MIDNIGHT, DashboardStyle.PANORAMA, DashboardStyle.REPLICA, DashboardStyle.OEM_OVERLAY)) {
                NativeDashboardSettings.setStyle(app, style)
                NativeDashboardSettings.setMapSize(app, DashboardMapSize.LARGE)
                val before = config()
                val geometry = DashboardLayout.of(style, DashboardMode.NAVIGATION)
                for (size in DashboardMapSize.entries) {
                    NativeDashboardSettings.setMapSize(app, size)
                    assertEquals(size, NativeDashboardSettings.mapSize(app))
                    val after = config()
                    assertEquals(size.widthMm, after.widthPhysicalMm)
                    assertEquals(before, after.copy(widthPhysicalMm = before.widthPhysicalMm))
                    assertEquals(geometry, DashboardLayout.of(style, DashboardMode.NAVIGATION))
                    assertEquals(350, AirPlayPersistence.loadWidthPhysicalMm(app))
                }
            }
            app.getSharedPreferences("native_dashboard", 0).edit().putInt("map_size", 999).commit()
            assertEquals(DashboardMapSize.LARGE, NativeDashboardSettings.mapSize(app))
        } finally { NativeDashboardSettings.setMapSize(app, DashboardMapSize.LARGE); MapMirrors.streamAspect = oldAspect }
    }
}
