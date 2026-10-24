package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.*
import android.os.Looper
import android.view.View
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.ultra.UltraVehicleSource
import com.shilapi.xcertplay.ultra.VehicleReading
import com.shilapi.xcertplay.ultra.VehicleSignal
import java.io.File
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29], qualifiers="zh-rCN-w1920dp-h532dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReplicaDayAppearanceTest {
    private fun layout(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(532,View.MeasureSpec.EXACTLY))
        view.layout(0,0,1920,532)
    }
    private fun frame(view: DashboardCanvas) = Bitmap.createBitmap(1920,532,Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }

    @Test fun daytimeKeepsLightDialsOnBlackSurroundingsForAllPages() {
        val view = DashboardCanvas(RuntimeEnvironment.getApplication()).apply {
            style = DashboardStyle.REPLICA; appearance = DashboardAppearance.DAY
        }
        layout(view)
        view.snapshot = DashboardSnapshot(mapOf(
            VehicleSignal.INDICATED_SPEED_KPH to VehicleReading(60,0,0),
            VehicleSignal.ENGINE_RPM to VehicleReading(1600,0,0),
            VehicleSignal.AUTO_HOLD_ACTIVE to VehicleReading(true,0,32),
            VehicleSignal.TIRE_FL_BAR to VehicleReading(2.3,0,0),
            VehicleSignal.TIRE_FR_BAR to VehicleReading(2.3,0,0),
            VehicleSignal.TIRE_RL_BAR to VehicleReading(2.3,0,0),
            VehicleSignal.TIRE_RR_BAR to VehicleReading(2.3,0,0)))
        val out = File("build/reports/native-dashboard").apply { mkdirs() }
        for (mode in listOf(DashboardMode.NAVIGATION,DashboardMode.MUSIC,DashboardMode.TIRES)) {
            view.mode = mode
            val image = frame(view)
            assertEquals(Color.BLACK,image.getPixel(12,12))
            assertEquals(Color.BLACK,image.getPixel(600,100))
            assertTrue(Color.luminance(image.getPixel(266,300)) > .8f)
            File(out,"v46-day-black-${mode.name.lowercase()}.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
            image.recycle()
        }
    }

    @Test fun automaticPaletteUsesLocalSixAndEighteenBoundariesAndPreservesManualChoices() {
        val app=RuntimeEnvironment.getApplication()
        val clock=java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
        NativeDashboardSettings.setAppearance(app,DashboardAppearance.AUTO)
        for ((hour,minute,expected) in listOf(
            Triple(0,0,DashboardAppearance.NIGHT),Triple(5,59,DashboardAppearance.NIGHT),
            Triple(6,0,DashboardAppearance.DAY),Triple(17,59,DashboardAppearance.DAY),
            Triple(18,0,DashboardAppearance.NIGHT),Triple(23,59,DashboardAppearance.NIGHT))) {
            clock.set(2026,9,24,hour,minute,0)
            assertEquals("$hour:$minute",expected,NativeDashboardSettings.effectiveAppearance(app,clock))
            for (manual in listOf(DashboardAppearance.DAY,DashboardAppearance.NIGHT))
                assertEquals(manual,manual.resolve(hour))
        }
        clock.set(2026,9,24,6,0,0)
        val utc=java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis=clock.timeInMillis }
        assertEquals(DashboardAppearance.DAY,NativeDashboardSettings.effectiveAppearance(app,clock))
        assertEquals(DashboardAppearance.NIGHT,NativeDashboardSettings.effectiveAppearance(app,utc))
        assertEquals(DashboardAppearance.AUTO,NativeDashboardSettings.appearance(app))
    }

    @Test fun sweepUsesFixedScaleGetsDarkerAndKeepsBottomBridgePale() {
        val view=DashboardCanvas(RuntimeEnvironment.getApplication()).apply {
            style=DashboardStyle.REPLICA; appearance=DashboardAppearance.DAY; mode=DashboardMode.MUSIC
        }
        layout(view)
        fun render(speed:Int,rpm:Int):Bitmap {
            view.snapshot=DashboardSnapshot(mapOf(VehicleSignal.INDICATED_SPEED_KPH to VehicleReading(speed,0,0),
                VehicleSignal.ENGINE_RPM to VehicleReading(rpm,0,0)))
            return frame(view)
        }
        fun sectorPixel(bitmap:Bitmap,center:Float,fraction:Float):Int {
            val angle=Math.toRadians(135.0+270.0*fraction)
            val r=175f*DashboardLayout.REPLICA_DIAL_RADIUS/208f
            return bitmap.getPixel((center+kotlin.math.cos(angle)*r).toInt(),
                (DashboardLayout.REPLICA_DIAL_Y+kotlin.math.sin(angle)*r).toInt())
        }
        val low=render(60,2000); val full=render(240,8000)
        for (center in listOf(266f,1654f)) {
            // A value changing must not stretch/restart the gradient along the illuminated arc.
            assertEquals(sectorPixel(low,center,.15f),sectorPixel(full,center,.15f))
            var previous=1f
            for (fraction in listOf(.05f,.25f,.5f,.75f,.95f)) {
                val luminance=Color.luminance(sectorPixel(full,center,fraction))
                assertTrue("Scale must darken monotonically: $center/$fraction",luminance<previous)
                previous=luminance
            }
            val x=center.toInt()
            assertEquals(low.getPixel(x,480),full.getPixel(x,480))
            assertTrue("Unnumbered bridge stays pale",Color.luminance(full.getPixel(x,480))>.75f)
        }
        low.recycle();full.recycle()
    }

    @Test fun palettePersistsWithoutChangingStylePageOrMapSizeAndInvalidValueFallsBack() {
        val app=RuntimeEnvironment.getApplication()
        assertEquals(DashboardAppearance.NIGHT,NativeDashboardSettings.appearance(app))
        NativeDashboardSettings.setStyle(app,DashboardStyle.REPLICA)
        NativeDashboardSettings.setMode(app,DashboardMode.TIRES)
        NativeDashboardSettings.setMapSize(app,DashboardMapSize.EXTRA_LARGE)
        NativeDashboardSettings.setAppearance(app,DashboardAppearance.DAY)
        assertEquals(DashboardAppearance.DAY,DashboardCanvas(app).appearance)
        assertEquals(DashboardStyle.REPLICA,NativeDashboardSettings.style(app))
        assertEquals(DashboardMode.TIRES,NativeDashboardSettings.mode(app))
        assertEquals(DashboardMapSize.EXTRA_LARGE,NativeDashboardSettings.mapSize(app))
        app.getSharedPreferences("native_dashboard",0).edit().putInt("appearance",99).commit()
        assertEquals(DashboardAppearance.NIGHT,NativeDashboardSettings.appearance(app))
    }

    @Test fun attachedDashboardChangesPaletteWithoutReplacingVideoViewOrChangingGeometry() {
        val app=RuntimeEnvironment.getApplication()
        NativeDashboardSettings.setStyle(app,DashboardStyle.REPLICA)
        val oldFactory=NativeDashboardRuntime.sourceFactory
        NativeDashboardRuntime.sourceFactory={ object : UltraVehicleSource {
            override fun start()=Unit
            override fun snapshot(): Map<VehicleSignal,VehicleReading> = emptyMap()
            override fun close()=Unit
        } }
        val activity=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val view=NativeDashboardView(activity.get(),preview=true)
            activity.get().setContentView(view)
            layout(view)
            val texture=view.getChildAt(0)
            val geometry=view.artworkView.geometry
            NativeDashboardSettings.setAppearance(app,DashboardAppearance.DAY)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            assertEquals(DashboardAppearance.DAY,view.artworkView.appearance)
            assertSame(texture,view.getChildAt(0))
            assertEquals(geometry,view.artworkView.geometry)
            NativeDashboardSettings.setAppearance(app,DashboardAppearance.NIGHT)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            assertEquals(DashboardAppearance.NIGHT,view.artworkView.appearance)
        } finally { activity.pause().stop().destroy(); NativeDashboardRuntime.sourceFactory=oldFactory }
    }

    @Test fun dayPaletteDoesNotRecolorOtherStylesAndKeepsReadableStatusColors() {
        for(style in listOf(DashboardStyle.MIDNIGHT,DashboardStyle.PANORAMA,DashboardStyle.OEM_OVERLAY)) {
            val view=DashboardCanvas(RuntimeEnvironment.getApplication()).apply { this.style=style }
            layout(view)
            val night=frame(view)
            view.appearance=DashboardAppearance.DAY
            val day=frame(view)
            assertTrue("Only classic dashboard uses this palette: $style",night.sameAs(day))
            night.recycle(); day.recycle()
        }
        val surface=Color.luminance(ReplicaDayPalette.surface)
        for(color in listOf(ReplicaDayPalette.text,ReplicaDayPalette.secondary,ReplicaDayPalette.green,
            ReplicaDayPalette.red,ReplicaDayPalette.amber,ReplicaDayPalette.blue)) {
            assertTrue("Day text and active indicators need contrast",(surface+.05)/(Color.luminance(color)+.05)>=4.5)
        }
    }

    @Test fun actualDayRendererCapturesMusicNavigationTiresAndMissingData() {
        val app=RuntimeEnvironment.getApplication()
        val cover=Bitmap.createBitmap(240,240,Bitmap.Config.ARGB_8888)
        Canvas(cover).apply {
            drawColor(Color.rgb(41,66,79))
            drawCircle(120f,96f,65f,Paint().apply { color=Color.rgb(218,155,107) })
            drawRect(0f,155f,240f,240f,Paint().apply { color=Color.rgb(21,42,57) })
        }
        // Test-only samples; production always renders the real vehicle/music/navigation sources.
        val state=DashboardSnapshot(mapOf(
            VehicleSignal.INDICATED_SPEED_KPH to VehicleReading(68,0,0),VehicleSignal.ENGINE_RPM to VehicleReading(1600,0,0),
            VehicleSignal.GEAR to VehicleReading("D",0,0),VehicleSignal.ENGINE_COOLANT_C to VehicleReading(90,0,0),
            VehicleSignal.INDICATED_RANGE_KM to VehicleReading(111,0,0),VehicleSignal.INDICATED_AMBIENT_C to VehicleReading(23,0,0),
            VehicleSignal.TIRE_FL_BAR to VehicleReading(2.3,0,0),VehicleSignal.TIRE_FR_BAR to VehicleReading(2.3,0,0),
            VehicleSignal.TIRE_RL_BAR to VehicleReading(2.3,0,0),VehicleSignal.TIRE_RR_BAR to VehicleReading(2.3,0,0),
            VehicleSignal.ASSIST_MODE to VehicleReading("GPILOT",0,0),VehicleSignal.ACC_STATE to VehicleReading(2,0,0),
            VehicleSignal.ACC_SET_SPEED_KPH to VehicleReading(80,0,0),VehicleSignal.FOLLOW_GAP to VehicleReading(2,0,0),
            VehicleSignal.LANE_CENTERING_ACTIVE to VehicleReading(true,0,0),VehicleSignal.LOW_BEAM to VehicleReading(true,0,0),
            VehicleSignal.AUTO_HEADLIGHTS to VehicleReading(true,0,0),VehicleSignal.POSITION_LIGHT to VehicleReading(true,0,0),
            VehicleSignal.HIGH_BEAM to VehicleReading(false,0,0),VehicleSignal.REAR_FOG to VehicleReading(false,0,0),
            VehicleSignal.LEFT_TURN to VehicleReading(true,0,0),VehicleSignal.RIGHT_TURN to VehicleReading(false,0,0)),
            ClusterMusicState.Snapshot(CarPlayNowPlaying(title="夜曲 · 测试曲目",artist="测试歌手",playing=true,durationMillis=223000,elapsedMillis=97000),cover),
            ClusterTurnGuidance(3,0,800,"滨江大道 · 测试导航",remainingSeconds=720,remainingMeters=8400))
        val out=File("build/reports/native-dashboard").apply { mkdirs() }
        for(mode in listOf(DashboardMode.NAVIGATION,DashboardMode.MUSIC,DashboardMode.TIRES)) {
            val view=DashboardCanvas(app).apply { style=DashboardStyle.REPLICA; appearance=DashboardAppearance.DAY; this.mode=mode; snapshot=state }
            layout(view)
            val bitmap=Bitmap.createBitmap(1920,532,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(bitmap)
            if(mode==DashboardMode.NAVIGATION) {
                val mapColor=Color.rgb(229,236,228)
                canvas.drawColor(mapColor)
                val road=Paint().apply { color=Color.rgb(189,203,188); strokeWidth=4f }
                for(x in 400..1520 step 120) canvas.drawLine(960f+(x-960)*.25f,0f,x.toFloat(),532f,road)
                for(y in 180..532 step 50) canvas.drawLine(300f,y.toFloat(),1620f,y.toFloat(),road)
                road.color=Color.rgb(26,128,208); road.strokeWidth=18f
                canvas.drawLine(960f,532f,960f,200f,road); canvas.drawLine(960f,200f,1230f,200f,road)
                view.mapActive=true; view.mapWindow=view.geometry.map!!
            }
            view.draw(canvas)
            assertEquals(ReplicaDayPalette.background,bitmap.getPixel(12,12))
            assertEquals(ReplicaDayPalette.surface,bitmap.getPixel(340,270))
            assertEquals(Color.rgb(218,155,107),bitmap.getPixel(1654,240)) // Actual cover stays unfiltered.
            File(out,"replica-day-${mode.name.lowercase()}-fixture.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            if(mode==DashboardMode.NAVIGATION) {
                // A palette change must never tint pixels belonging to the live map video.
                assertEquals(Color.rgb(229,236,228),bitmap.getPixel(900,300))
                for ((name,changes) in listOf(
                    "speed-240" to mapOf(VehicleSignal.INDICATED_SPEED_KPH to VehicleReading(240,0,0)),
                    "autohold" to mapOf(VehicleSignal.INDICATED_SPEED_KPH to VehicleReading(0,0,0),
                        VehicleSignal.ENGINE_RPM to VehicleReading(800,0,0),
                        VehicleSignal.AUTO_HOLD_ACTIVE to VehicleReading(true,0,0),
                        VehicleSignal.PARKING_BRAKE_ON to VehicleReading(false,0,0)))) {
                    view.snapshot=state.copy(vehicle=state.vehicle+changes)
                    view.draw(canvas)
                    File(out,"replica-day-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                }
                // A confirmed hold fixture lights only AutoHold; no hold feedback must remain gray.
                var activePixels=0
                for (x in 1120..1162) for (y in 488..522)
                    if(bitmap.getPixel(x,y)==ReplicaDayPalette.green) activePixels++
                assertTrue("Confirmed AutoHold must be green",activePixels>10)
                view.snapshot=state
                view.draw(canvas)
                for (x in 1120..1162) for (y in 488..522)
                    assertNotEquals("Unknown hold must never be green",ReplicaDayPalette.green,bitmap.getPixel(x,y))
            }
            bitmap.recycle()
        }
        val emptyView=DashboardCanvas(app).apply { style=DashboardStyle.REPLICA; appearance=DashboardAppearance.DAY; mode=DashboardMode.NAVIGATION }
        layout(emptyView)
        assertNull(emptyView.snapshot.speedKph); assertNull(emptyView.snapshot.coolantC)
        assertNull(emptyView.snapshot.navigation); assertEquals("—",emptyView.snapshot.gear)
        val empty=frame(emptyView)
        File(out,"replica-day-empty.png").outputStream().use { empty.compress(Bitmap.CompressFormat.PNG,100,it) }
        empty.recycle();cover.recycle()
    }
}
