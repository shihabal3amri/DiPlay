package com.shilapi.xcertplay

import android.graphics.*
import android.view.View
import com.shilapi.xcertplay.ultra.VehicleReading
import com.shilapi.xcertplay.ultra.VehicleSignal
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="zh-rCN-w1920dp-h532dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardRenderPerformanceTest {
    @Test fun cachedDialsReuseFacesButChangeOnValueThemeAndMissingData() {
        val view=DashboardCanvas(RuntimeEnvironment.getApplication()).apply {
            style=DashboardStyle.REPLICA;mode=DashboardMode.MUSIC
            snapshot=DashboardSnapshot(mapOf(VehicleSignal.INDICATED_SPEED_KPH to VehicleReading(60,0,0),
                VehicleSignal.ENGINE_RPM to VehicleReading(1600,0,0)))
        }
        view.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(532,View.MeasureSpec.EXACTLY))
        view.layout(0,0,1920,532)
        fun frame()=Bitmap.createBitmap(1920,532,Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        val first=frame(); assertEquals(2,view.dialFaceBuildCount)
        val second=frame(); assertTrue(first.sameAs(second))
        assertEquals(2,view.dialFaceBuildCount)
        view.snapshot=view.snapshot.copy(music=ClusterMusicState.Snapshot(CarPlayNowPlaying(title="another track")))
        frame().recycle();assertEquals(2,view.dialFaceBuildCount)
        view.snapshot=view.snapshot.copy(vehicle=view.snapshot.vehicle+(VehicleSignal.ENGINE_RPM to VehicleReading(2000,1,0)))
        frame().recycle();assertEquals(3,view.dialFaceBuildCount)
        view.appearance=DashboardAppearance.DAY
        frame().recycle();assertEquals(5,view.dialFaceBuildCount)
        view.snapshot=DashboardSnapshot()
        frame().recycle();assertEquals(7,view.dialFaceBuildCount)
        first.recycle();second.recycle()
    }

    @Test fun timestampsAloneDoNotRedrawButActualValuesExpiryAndMusicDo() {
        val view=DashboardCanvas(RuntimeEnvironment.getApplication())
        view.snapshot=DashboardSnapshot(mapOf(VehicleSignal.GEAR to VehicleReading("D",1,42)))
        assertTrue(view.invalidateIfContentChanged())
        view.snapshot=DashboardSnapshot(mapOf(VehicleSignal.GEAR to VehicleReading("D",999,42)))
        assertFalse(view.invalidateIfContentChanged())
        view.snapshot=DashboardSnapshot(mapOf(VehicleSignal.GEAR to VehicleReading("P",999,42)))
        assertTrue(view.invalidateIfContentChanged())
        view.snapshot=DashboardSnapshot()
        assertTrue(view.invalidateIfContentChanged())
        view.snapshot=view.snapshot.copy(music=ClusterMusicState.Snapshot(CarPlayNowPlaying(title="music")))
        assertTrue(view.invalidateIfContentChanged())
        view.mapActive=true
        assertTrue(view.invalidateIfContentChanged())
        assertFalse(view.invalidateIfContentChanged())
    }

    @Test fun inPlaceCoverUpdatesAndMapBoundsStillInvalidate() {
        val view=DashboardCanvas(RuntimeEnvironment.getApplication())
        val art=Bitmap.createBitmap(12,12,Bitmap.Config.ARGB_8888)
        view.snapshot=DashboardSnapshot(music=ClusterMusicState.Snapshot(CarPlayNowPlaying(title="music"),art))
        assertTrue(view.invalidateIfContentChanged())
        assertFalse(view.invalidateIfContentChanged())
        art.eraseColor(Color.GREEN)
        assertTrue(view.invalidateIfContentChanged())
        assertFalse(view.invalidateIfContentChanged())
        view.mapWindow=RectF(10f,10f,100f,100f)
        assertTrue(view.invalidateIfContentChanged())
        art.recycle()
        assertTrue(view.invalidateIfContentChanged())
    }

    @Test fun performanceHistoryIsBoundedAndRedacted() {
        repeat(140) { DashboardPerformanceLog.record("Dashboard: testCounter=$it") }
        DashboardPerformanceLog.record("token=secret")
        val report=DashboardPerformanceLog.report()
        assertEquals(120,report.lines().size)
        assertTrue(report.contains("testCounter=139"))
        assertFalse(report.contains("testCounter=19\n"))
        assertFalse(report.contains("secret"))
    }
}
