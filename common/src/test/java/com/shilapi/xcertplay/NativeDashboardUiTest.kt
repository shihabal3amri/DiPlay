package com.shilapi.xcertplay

import android.content.res.Configuration
import java.util.Locale
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.ultra.VehicleReading
import com.shilapi.xcertplay.ultra.VehicleSignal
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers="zh-rCN-w1920dp-h532dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeDashboardUiTest {
    @Test fun playingCoverKeepsBlackOuterBackgroundOnEveryClassicPage() {
        val art=Bitmap.createBitmap(24,24,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(235,60,20)) }
        val track=CarPlayNowPlaying(title="星河",artist="测试歌手",playing=true)
        val state=DashboardSnapshot(vehicle=mapOf(
            VehicleSignal.INDICATED_SPEED_KPH to VehicleReading(38,0,0),
            VehicleSignal.ENGINE_RPM to VehicleReading(1600,0,0),
            VehicleSignal.INDICATED_RANGE_KM to VehicleReading(111,0,0),
            VehicleSignal.FRONT_COMPARTMENT_C to VehicleReading(23.5,0,559970554),
            VehicleSignal.GEAR to VehicleReading("D",0,0)), music=ClusterMusicState.Snapshot(track,art))
        assertNull(state.coolantC)
        assertEquals(23.5,state.frontCompartmentC!!,0.01)
        val out=File("build/reports/native-dashboard").apply { mkdirs() }
        for (mode in listOf(DashboardMode.MUSIC,DashboardMode.NAVIGATION,DashboardMode.TIRES)) {
            val image=render(state,mode,DashboardStyle.REPLICA)
            val tint=image.getPixel(12,12)
            assertEquals(Color.BLACK,tint)
            val paused=render(state.copy(music=state.music.copy(info=track.copy(playing=false))),mode,DashboardStyle.REPLICA)
            assertEquals(Color.BLACK,paused.getPixel(12,12))
            val missing=render(state.copy(music=state.music.copy(artwork=null)),mode,DashboardStyle.REPLICA)
            assertEquals(Color.BLACK,missing.getPixel(12,12))
            File(out,"v44-classic-${mode.name.lowercase()}.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
            image.recycle(); paused.recycle(); missing.recycle()
        }
        val realCoolant=state.copy(vehicle=state.vehicle+(VehicleSignal.ENGINE_COOLANT_C to VehicleReading(90,0,0)))
        assertEquals(90.0,realCoolant.coolantC!!,0.01)
        art.recycle()
    }

    @Test fun classicBarsRoundOnlyTheCornersFacingTheMap() {
        val view = DashboardCanvas(RuntimeEnvironment.getApplication()).apply {
            style = DashboardStyle.REPLICA; mode = DashboardMode.NAVIGATION
            mapActive = true; mapWindow = android.graphics.RectF(266f,0f,1654f,532f)
        }
        view.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(532,View.MeasureSpec.EXACTLY))
        view.layout(0,0,1920,532)
        for (appearance in listOf(DashboardAppearance.DAY, DashboardAppearance.NIGHT)) {
            view.appearance = appearance
            val surface = if (appearance == DashboardAppearance.DAY) ReplicaDayPalette.surface else Color.BLACK
            val image = Bitmap.createBitmap(1920,532,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            view.draw(Canvas(image))
            for (x in listOf(512,1407)) {
                assertEquals("Header reaches the topmost pixel: $appearance",surface,image.getPixel(x,0))
                assertNotEquals("Lower header corner reveals the map",surface,image.getPixel(x,58))
            }
            for (x in listOf(645,1274)) {
                assertNotEquals("Upper footer corner reveals the map",surface,image.getPixel(x,480))
                assertEquals("Footer reaches the bottommost pixel: $appearance",surface,image.getPixel(x,531))
            }
            image.recycle()
        }
    }

    @Test fun longMusicLabelsMoveWithoutMovingVehicleGauges() {
        val out = File("build/reports/native-dashboard").apply { mkdirs() }
        for (style in listOf(DashboardStyle.MIDNIGHT, DashboardStyle.PANORAMA, DashboardStyle.REPLICA)) {
            val view = DashboardCanvas(RuntimeEnvironment.getApplication()).apply {
                this.style = style; mode = DashboardMode.MUSIC
                snapshot = DashboardSnapshot(music = ClusterMusicState.Snapshot(CarPlayNowPlaying(
                    title = "这是一首需要完整展示的很长很长的歌曲名称 Long Song Title", artist = "这是一位名字很长很长的歌手 Long Artist Name")))
            }
            view.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(532, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 1920, 532)
            fun frame() = Bitmap.createBitmap(1920, 532, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            val first = frame()
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(3))
            view.snapshot = view.snapshot.copy(music = view.snapshot.music.copy(info = view.snapshot.music.info.copy(elapsedMillis = 3000)))
            val moving = frame()
            var changes = 0
            for (x in 520..1890) for (y in 180..444) if (first.getPixel(x,y) != moving.getPixel(x,y)) changes++
            assertTrue("Long labels must move: $style", changes > 20)
            for (x in 20..420) for (y in 70..410) assertEquals(first.getPixel(x,y), moving.getPixel(x,y))
            for ((name, bitmap) in listOf("start" to first, "scroll" to moving)) {
                File(out, "marquee-${style.name.lowercase()}-$name.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            }
            first.recycle(); moving.recycle()
        }
    }
    @Test fun fx11HasNoFrontFogIconOnAnyFullDashboardPage() {
        for(style in listOf(DashboardStyle.MIDNIGHT,DashboardStyle.PANORAMA,DashboardStyle.REPLICA)) {
            for(mode in listOf(DashboardMode.MUSIC,DashboardMode.NAVIGATION,DashboardMode.TIRES)) {
                val unknown=render(DashboardSnapshot(),mode,style)
                val unsupported=render(DashboardSnapshot(mapOf(VehicleSignal.FRONT_FOG to VehicleReading(true,0,0))),mode,style)
                for(x in 0 until 1920) for(y in 0 until 100)
                    assertEquals("$style/$mode must ignore a nonexistent front fog lamp",unknown.getPixel(x,y),unsupported.getPixel(x,y))
                unknown.recycle(); unsupported.recycle()
            }
        }
    }

    @Test fun replicaRedParkingBadgeFollowsFx11LampFeedbackIndependentlyOfGear() {
        val binding=com.shilapi.xcertplay.ultra.Fx11DashboardSignals.bindings.single { it.signal==VehicleSignal.PARKING_BRAKE_ON }
        for(gear in listOf("P","D")) for(raw in listOf(0,1,2)) {
            val state=DashboardSnapshot(buildMap {
                put(VehicleSignal.GEAR,VehicleReading(gear,0,557874334))
                binding.decode(raw)?.let { put(binding.signal,VehicleReading(it,0,binding.propertyId)) }
            })
            val bitmap=render(state,style=DashboardStyle.REPLICA)
            val red=(1100..1240).sumOf { x -> (486..526).count { y -> bitmap.getPixel(x,y)==Color.rgb(255,105,104) } }
            assertEquals("gear=$gear, raw=$raw",raw==0,red>0)
            bitmap.recycle()
        }
    }
    @Test fun allThreeStylesRenderIndicatedSpeedIndependentlyOfStandardSpeed() {
        for(style in listOf(DashboardStyle.MIDNIGHT,DashboardStyle.PANORAMA,DashboardStyle.REPLICA)) {
            val state=DashboardSnapshot(mapOf(VehicleSignal.INDICATED_SPEED_KPH to VehicleReading(10,0,557874773),
                VehicleSignal.SPEED_KPH to VehicleReading(0,0,0x11600207)))
            val original=render(state,style=style)
            val standardChanged=render(state.copy(vehicle=state.vehicle+(VehicleSignal.SPEED_KPH to VehicleReading(100,0,0x11600207))),style=style)
            val indicatedChanged=render(state.copy(vehicle=state.vehicle+(VehicleSignal.INDICATED_SPEED_KPH to VehicleReading(1,0,557874773))),style=style)
            val unavailable=render(state.copy(vehicle=state.vehicle-VehicleSignal.INDICATED_SPEED_KPH),style=style)
            assertTrue("$style ignores unreliable standard speed",original.sameAs(standardChanged))
            assertFalse("$style shows low-speed changes",original.sameAs(indicatedChanged))
            assertFalse("$style clears unknown speed",original.sameAs(unavailable))
            listOf(original,standardChanged,indicatedChanged,unavailable).forEach(Bitmap::recycle)
        }
    }
    @Test fun replicaPagesKeepIdenticalDialPixelsAndHeaderIncludingArtworkBackground() {
        val cover=Bitmap.createBitmap(24,24,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(220,80,30)) }
        val state=DashboardSnapshot(mapOf(VehicleSignal.INDICATED_SPEED_KPH to VehicleReading(68,0,0),
            VehicleSignal.ENGINE_RPM to VehicleReading(1600,0,0),VehicleSignal.GEAR to VehicleReading("D",0,0),
            VehicleSignal.ENGINE_COOLANT_C to VehicleReading(90,0,0),VehicleSignal.LOW_BEAM to VehicleReading(true,0,0),
            VehicleSignal.AUTO_HEADLIGHTS to VehicleReading(true,0,0),VehicleSignal.ASSIST_MODE to VehicleReading("GPILOT",0,0),
            VehicleSignal.ACC_STATE to VehicleReading(2,0,0),VehicleSignal.LANE_CENTERING_ACTIVE to VehicleReading(true,0,0),
            VehicleSignal.ACC_SET_SPEED_KPH to VehicleReading(80,0,0),VehicleSignal.FOLLOW_GAP to VehicleReading(2,0,0)),
            ClusterMusicState.Snapshot(CarPlayNowPlaying(title="夜曲",artist="周杰伦"),cover))
        val music=render(state,DashboardMode.MUSIC,DashboardStyle.REPLICA)
        for(mode in listOf(DashboardMode.NAVIGATION,DashboardMode.TIRES)) {
            val other=render(state,mode,DashboardStyle.REPLICA)
            assertArrayEquals(replicaStatusForeground(music),replicaStatusForeground(other))
            for(cx in listOf(266,1654)) for(x in cx-250..cx+250) for(y in 16..516) {
                if((x-cx)*(x-cx)+(y-266)*(y-266)<250*250)
                    assertEquals("Page changes must not resize/move/change the dials or RPM text",music.getPixel(x,y),other.getPixel(x,y))
            }
            other.recycle()
        }
        val changed=render(state.copy(vehicle=state.vehicle+(VehicleSignal.FUEL_PERCENT to VehicleReading(7,0,0))),style=DashboardStyle.REPLICA)
        for(x in 12..520) for(y in 12..520) assertEquals("Fuel percentage no longer appears inside speed dial",music.getPixel(x,y),changed.getPixel(x,y))
        changed.recycle(); music.recycle(); cover.recycle()
    }
    @Test fun replicaAutoBadgeRequiresConfirmedSelectorAndDoesNotCreateASecondHeadlamp() {
        val base=DashboardSnapshot(mapOf(VehicleSignal.LOW_BEAM to VehicleReading(true,0,0)))
        val unknown=render(base,style=DashboardStyle.REPLICA)
        val off=render(base.copy(vehicle=base.vehicle+(VehicleSignal.AUTO_HEADLIGHTS to VehicleReading(false,0,0))),style=DashboardStyle.REPLICA)
        val on=render(base.copy(vehicle=base.vehicle+(VehicleSignal.AUTO_HEADLIGHTS to VehicleReading(true,0,0))),style=DashboardStyle.REPLICA)
        assertArrayEquals(replicaStatusForeground(unknown),replicaStatusForeground(off))
        assertTrue((603..617).any { x -> (20..38).any { y -> unknown.getPixel(x,y)!=on.getPixel(x,y) } })
        for(x in 526 until 1394) for(y in 0 until 60) if(x !in 600..620 || y !in 15..40)
            assertEquals("Only the A inside the existing lamp may change",unknown.getPixel(x,y),on.getPixel(x,y))
        unknown.recycle(); off.recycle(); on.recycle()
    }
    @Test fun replicaBrakeBadgesStayGrayUntilConfirmedHoldGreenOrParkingRed() {
        val statuses=listOf(VehicleSignal.AUTO_HOLD_ACTIVE,VehicleSignal.PARKING_BRAKE_ON,VehicleSignal.HILL_DESCENT_ACTIVE)
        val empty=render(DashboardSnapshot(),style=DashboardStyle.REPLICA)
        val off=render(DashboardSnapshot(statuses.associateWith { VehicleReading(false,0,0) }),style=DashboardStyle.REPLICA)
        val on=render(DashboardSnapshot(statuses.associateWith { VehicleReading(true,0,0) }),style=DashboardStyle.REPLICA)
        fun count(bitmap:Bitmap,color:Int,l:Int,t:Int,r:Int,b:Int)=(l..r).sumOf { x -> (t..b).count { y -> bitmap.getPixel(x,y)==color } }
        for(x in 800..880) for(y in 0..59) assertEquals(empty.getPixel(x,y),off.getPixel(x,y))
        for(x in 1100..1240) for(y in 486..526) assertEquals(empty.getPixel(x,y),off.getPixel(x,y))
        assertTrue(count(on,Color.rgb(100,224,166),1118,486,1164,526)>0)
        for ((l,r) in listOf(1118 to 1164,1166 to 1212)) {
            assertTrue(count(off,Color.rgb(141,162,182),l,486,r,526)>0)
            assertEquals(0,count(off,Color.rgb(255,105,104),l,486,r,526))
        }
        assertTrue(count(on,Color.rgb(255,105,104),1166,486,1212,526)>0)
        // The old right-dial brake positions must remain unchanged when flags change.
        for(x in 1520..1790) for(y in 130..205) assertEquals(empty.getPixel(x,y),on.getPixel(x,y))
        assertTrue(count(on,Color.rgb(100,224,166),825,0,875,59)>0)
        val out=File("build/reports/native-dashboard").apply { mkdirs() }
        File(out,"replica-confirmed-brakes-descent-fixture.png").outputStream().use { assertTrue(on.compress(Bitmap.CompressFormat.PNG,100,it)) }
        empty.recycle(); off.recycle(); on.recycle()
    }
    @Test fun replicaCoverBackgroundTracksChangedArtworkAndClearsAfterMusicDisconnects() {
        val app=RuntimeEnvironment.getApplication()
        val view=DashboardCanvas(app).apply { style=DashboardStyle.REPLICA; mode=DashboardMode.MUSIC }
        view.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(532,View.MeasureSpec.EXACTLY))
        view.layout(0,0,1920,532)
        fun image()=Bitmap.createBitmap(1920,532,Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        val art=Bitmap.createBitmap(24,24,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(235,60,20)) }
        view.snapshot=DashboardSnapshot(music=ClusterMusicState.Snapshot(CarPlayNowPlaying(title="测试"),art))
        val first=image()
        val warm=first.getPixel(690,270); assertTrue(Color.red(warm)>Color.blue(warm))
        art.eraseColor(Color.rgb(20,65,235)) // Same bitmap identity, new generation.
        val second=image()
        val cool=second.getPixel(690,270); assertTrue(Color.blue(cool)>Color.red(cool))
        assertArrayEquals(replicaStatusForeground(first),replicaStatusForeground(second))
        view.snapshot=DashboardSnapshot(music=ClusterMusicState.Snapshot(CarPlayNowPlaying(),art))
        val stale=image()
        view.snapshot=DashboardSnapshot()
        val empty=image()
        assertEquals("A stale cover without track metadata cannot survive disconnect",empty.getPixel(690,270),stale.getPixel(690,270))
        art.recycle()
        view.snapshot=DashboardSnapshot(music=ClusterMusicState.Snapshot(CarPlayNowPlaying(title="测试"),art))
        val recycled=image() // No crash or stale backdrop after caller recycles artwork.
        assertEquals(empty.getPixel(690,270),recycled.getPixel(690,270))
        listOf(first,second,stale,empty,recycled).forEach { it.recycle() }
    }
    @Test fun replicaGearIsAfterBothConfirmedBrakeIconsAndNeverInsideTheMusicDial() {
        val base = DashboardSnapshot(mapOf(
            VehicleSignal.AUTO_HOLD_ACTIVE to VehicleReading(true,0,0),
            VehicleSignal.PARKING_BRAKE_ON to VehicleReading(true,0,0),
            VehicleSignal.INDICATED_RANGE_KM to VehicleReading(111,0,0),
        ))
        val park = render(base.copy(vehicle=base.vehicle+(VehicleSignal.GEAR to VehicleReading("P",0,0))),style=DashboardStyle.REPLICA)
        val drive = render(base.copy(vehicle=base.vehicle+(VehicleSignal.GEAR to VehicleReading("D",0,0))),style=DashboardStyle.REPLICA)
        for(x in 1400..1900) for(y in 16..516)
            assertEquals("Gear must no longer occupy the right dial",park.getPixel(x,y),drive.getPixel(x,y))
        val greenXs = mutableListOf<Int>(); val redXs = mutableListOf<Int>(); val gearXs = mutableListOf<Int>()
        for(x in 1080..1300) for(y in 480..531) {
            when(drive.getPixel(x,y)) {
                Color.rgb(100,224,166) -> greenXs.add(x)
                Color.rgb(255,105,104) -> redXs.add(x)
            }
            if(park.getPixel(x,y)!=drive.getPixel(x,y)) gearXs.add(x)
        }
        assertTrue(greenXs.isNotEmpty() && redXs.isNotEmpty() && gearXs.isNotEmpty())
        assertTrue(greenXs.max() < redXs.min())
        assertTrue(redXs.max() < gearXs.min())
        park.recycle(); drive.recycle()
    }

    @Test fun replicaDialCoverUpdatesAndClearsOnNavigationAndStaysInsideInnerWell() {
        val app=RuntimeEnvironment.getApplication()
        val view=DashboardCanvas(app).apply { style=DashboardStyle.REPLICA; mode=DashboardMode.NAVIGATION }
        view.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(532,View.MeasureSpec.EXACTLY))
        view.layout(0,0,1920,532)
        fun draw()=Bitmap.createBitmap(1920,532,Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        val art=Bitmap.createBitmap(80,80,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(220,70,20)) }
        view.snapshot=DashboardSnapshot(music=ClusterMusicState.Snapshot(CarPlayNowPlaying(title="测试歌曲",artist="歌手"),art))
        val warm=draw()
        assertEquals(Color.rgb(220,70,20),warm.getPixel(1654,240)) // Crisp, enlarged cover.
        art.eraseColor(Color.rgb(20,70,220))
        val cool=draw()
        assertEquals(Color.rgb(20,70,220),cool.getPixel(1654,240))
        // The exposed circular background follows the cover, outside the crisp square.
        val blur=warm.getPixel(1522,270)
        assertTrue(Color.red(blur)>Color.blue(blur))
        for(x in 1400..1908) for(y in 12..520)
            if((x-1654)*(x-1654)+(y-266)*(y-266)>152*152)
                assertEquals("Cover may not repaint the scale or RPM",warm.getPixel(x,y),cool.getPixel(x,y))
        view.snapshot=DashboardSnapshot(music=ClusterMusicState.Snapshot(CarPlayNowPlaying(),art))
        val stale=draw(); view.snapshot=DashboardSnapshot(); val empty=draw()
        for(x in 1504..1804) for(y in 116..416) assertEquals(empty.getPixel(x,y),stale.getPixel(x,y))
        listOf(warm,cool,stale,empty,art).forEach(Bitmap::recycle)
    }
    @Test fun renderSharedStatusIconPreview() {
        val app=RuntimeEnvironment.getApplication()
        val bitmap=Bitmap.createBitmap(560,176,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap)
        canvas.drawColor(Color.rgb(6,14,23))
        val label=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE; textSize=22f; textAlign=Paint.Align.CENTER }
        for((index,res) in listOf(com.shilapi.xcertplay.host.R.drawable.ic_dashboard_follow_car,
            com.shilapi.xcertplay.host.R.drawable.ic_dashboard_hill_descent).withIndex()) {
            for((state,color) in listOf(Color.rgb(141,162,182),if(index==0) Color.rgb(243,248,255) else Color.rgb(100,224,166)).withIndex()) {
                val x=56+index*280+state*100
                val icon=requireNotNull(app.getDrawable(res)).mutate()
                icon.setTint(color); icon.setBounds(x,28,x+64,92); icon.draw(canvas)
            }
            canvas.drawText(if(index==0) "跟车距离" else "陡坡缓降",140f+index*280,145f,label)
        }
        val out=File("build/reports/native-dashboard").apply { mkdirs() }
        File(out,"status-icons-v15.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        bitmap.recycle()
    }
    private fun render(snapshot: DashboardSnapshot, mode: DashboardMode = DashboardMode.MUSIC, style: DashboardStyle = DashboardStyle.MIDNIGHT): Bitmap {
        val view=DashboardCanvas(RuntimeEnvironment.getApplication()).apply { this.mode=mode; this.snapshot=snapshot; this.style=style }
        view.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(532,View.MeasureSpec.EXACTLY))
        view.layout(0,0,1920,532)
        return Bitmap.createBitmap(1920,532,Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }
    // Shared chrome matches across pages; rounded corner cutouts reveal each page background.
    private fun replicaStatusForeground(bitmap: Bitmap): IntArray = IntArray(868*60).also {
        bitmap.getPixels(it,0,868,526,0,868,60)
        // Exclude only the two rounded lower corners, not the status icons.
        for (y in 40 until 60) for (x in 0 until 868) {
            val dx = when { x < 20 -> 20f-(x+.5f); x >= 848 -> x+.5f-848f; else -> 0f }
            val dy = y+.5f-40f
            if (dx > 0f && dx*dx+dy*dy >= 19f*19f) it[y*868+x] = 0
        }
    }
    @Test fun centeringHasNoDashAndGpilotGreenRequiresActiveFeedback() {
        fun state(status:Int, centering:Boolean?=null)=DashboardSnapshot(buildMap {
            put(VehicleSignal.ASSIST_MODE,VehicleReading("GPILOT",0,0))
            put(VehicleSignal.ACC_STATE,VehicleReading(status,0,0))
            centering?.let { put(VehicleSignal.LANE_CENTERING_ACTIVE,VehicleReading(it,0,0)) }
        })
        fun countGreen(bitmap:Bitmap,left:Int,top:Int,right:Int,bottom:Int):Int =
            (left until right).sumOf { x -> (top until bottom).count { y -> bitmap.getPixel(x,y)==Color.rgb(100,224,166) } }
        for(status in listOf(1,2,4,5,6,7,8)) {
            val bitmap=render(state(status))
            assertEquals("Longitudinal active alone cannot turn the steering wheel green",0,countGreen(bitmap,518,478,568,527))
            assertEquals(0,countGreen(bitmap,440,5,499,50)) // GPilot cannot imply lane centering.
            for(x in 500..527) for(y in 8..47) assertEquals("No centering dash or badge",Color.rgb(6,14,23),bitmap.getPixel(x,y))
            bitmap.recycle()
        }
        val known=render(state(2,true))
        assertEquals(0,countGreen(known,440,5,499,50))
        assertTrue((440..499).any { x -> (5..50).any { y -> known.getPixel(x,y)==Color.rgb(243,248,255) } })
        assertTrue(countGreen(known,518,478,568,527)>0); known.recycle()
    }
    @Test fun midnightFooterIgnoresOdometerAndSeatbeltAndOrdinaryCruiseHasNoDistanceBars() {
        val base=DashboardSnapshot(mapOf(VehicleSignal.ASSIST_MODE to VehicleReading("OFF",0,0),
            VehicleSignal.CRUISE_STATE to VehicleReading("ACTIVE",0,0),VehicleSignal.FOLLOW_GAP to VehicleReading(3,0,0)))
        val first=render(base)
        val second=render(base.copy(vehicle=base.vehicle+mapOf(VehicleSignal.ODOMETER_KM to VehicleReading(99999.0,0,0),
            VehicleSignal.DRIVER_BELT_FASTENED to VehicleReading(false,0,0))))
        for(x in 0 until 1920) for(y in 472 until 532) assertEquals(first.getPixel(x,y),second.getPixel(x,y))
        for(x in listOf(1708,1768,1828)) assertEquals(Color.rgb(6,14,23),first.getPixel(x,502))
        val out=File("build/reports/native-dashboard").apply { mkdirs() }
        File(out,"midnight-ordinary-cruise-fixture.png").outputStream().use { assertTrue(first.compress(Bitmap.CompressFormat.PNG,100,it)) }
        first.recycle(); second.recycle()
    }
    @Test fun navigationUsesTheWideTextureWindowAndKeepsGuidanceVisibleAboveIt() {
        val app=RuntimeEnvironment.getApplication()
        val bounds=DashboardLayout.of(DashboardStyle.MIDNIGHT,DashboardMode.NAVIGATION).map!!
        MapMirrors.streamAspect=1408.0/400
        val host=NativeDashboardView(app).apply { artworkView.mode=DashboardMode.NAVIGATION }
        host.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(532,View.MeasureSpec.EXACTLY))
        host.layout(0,0,1920,532)
        assertEquals(1408,host.getChildAt(0).width); assertEquals(400,host.getChildAt(0).height)
        assertEquals(488,host.getChildAt(0).left); assertEquals(60,host.getChildAt(0).top)
        val bitmap=Bitmap.createBitmap(1920,532,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap)
        val underlay=Color.rgb(18,43,61)
        canvas.drawColor(underlay)
        // Synthetic video underlay used only to verify the actual renderer's transparent window.
        val road=Paint().apply { color=Color.rgb(42,68,88); strokeWidth=8f }
        for(x in 500..1900 step 90) canvas.drawLine(x.toFloat(),60f,x-120f,460f,road)
        for(y in 80..450 step 65) canvas.drawLine(488f,y.toFloat(),1896f,y+25f,road)
        val view=DashboardCanvas(app).apply {
            mode=DashboardMode.NAVIGATION; mapActive=true; mapWindow=bounds
            snapshot=DashboardSnapshot(mapOf(VehicleSignal.INDICATED_SPEED_KPH to VehicleReading(68.0,0,0),
                VehicleSignal.ENGINE_RPM to VehicleReading(1600.0,0,0),VehicleSignal.GEAR to VehicleReading("D",0,0)),
                navigation=ClusterTurnGuidance(3,0,800,"滨江大道 · 测试导航",remainingSeconds=720,remainingMeters=8400))
        }
        view.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(532,View.MeasureSpec.EXACTLY))
        view.layout(0,0,1920,532); view.draw(canvas)
        assertEquals(underlay,bitmap.getPixel(1800,400)) // Right-hand map survives, no RPM dial.
        assertNotEquals(underlay,bitmap.getPixel(600,100)) // Guidance overlays the stream.
        assertEquals(Color.rgb(6,14,23),bitmap.getPixel(450,400)) // Clear gap after left speed dial.
        val out=File("build/reports/native-dashboard").apply { mkdirs() }
        File(out,"midnight-navigation-map-fixture.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        bitmap.recycle()
    }
    @Test fun distanceBarsFollowFeedbackAcrossAllStylesAndClearOnInvalidValue() {
        val app=RuntimeEnvironment.getApplication()
        val out=File("build/reports/native-dashboard").apply { mkdirs() }
        for (style in listOf(DashboardStyle.MIDNIGHT,DashboardStyle.PANORAMA,DashboardStyle.REPLICA)) {
            val view=DashboardCanvas(app).apply { this.style=style }
            view.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(532,View.MeasureSpec.EXACTLY))
            view.layout(0,0,1920,532)
            fun render(level: Int): Bitmap {
                view.snapshot=DashboardSnapshot(mapOf(VehicleSignal.FOLLOW_GAP to VehicleReading(level,0,557849293)))
                return Bitmap.createBitmap(1920,532,Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            }
            val full=render(3)
            val x=when(style) { DashboardStyle.MIDNIGHT -> 1708; DashboardStyle.REPLICA -> 1302; else -> 1177 }
            val y=when(style) { DashboardStyle.MIDNIGHT -> 502; DashboardStyle.REPLICA -> 20; else -> 489 }
            val step=when(style) { DashboardStyle.MIDNIGHT -> 60; DashboardStyle.REPLICA -> 0; else -> 0 }
            val verticalStep=when(style) { DashboardStyle.PANORAMA -> 13; DashboardStyle.REPLICA -> 11; else -> 0 }
            val lit=full.getPixel(x,y)
            assertEquals(Color.rgb(243,248,255),lit)
            assertEquals(lit,full.getPixel(x+step,y+verticalStep)); assertEquals(lit,full.getPixel(x+2*step,y+2*verticalStep))
            full.recycle()
            for (level in listOf(3,2,1,0)) {
                val bitmap=render(level)
                for (i in 0..2) assertEquals("gap=$level bar=$i",i<level,bitmap.getPixel(x+i*step,y+i*verticalStep)==lit)
                File(out,"${style.name.lowercase()}-gap-$level.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))
                }
                bitmap.recycle()
            }
        }
    }
    @Test fun panoramaMusicDoesNotRenderTimestampsAndCardsShareTheSameSmallBounds() {
        val first=render(DashboardSnapshot(music=ClusterMusicState.Snapshot(
            CarPlayNowPlaying(title="晴天",artist="歌手",durationMillis=100000,elapsedMillis=25000))),style=DashboardStyle.PANORAMA)
        val second=render(DashboardSnapshot(music=ClusterMusicState.Snapshot(
            CarPlayNowPlaying(title="晴天",artist="歌手",durationMillis=200000,elapsedMillis=50000))),style=DashboardStyle.PANORAMA)
        for(x in 1536 until 1896) for(y in 310 until 452) assertEquals(first.getPixel(x,y),second.getPixel(x,y))
        first.recycle(); second.recycle()
    }
    @Test fun panoramaWideVideoSurvivesCardSwitchAndKeepsNavigationAndVehicleOverlay() {
        val app=RuntimeEnvironment.getApplication()
        MapMirrors.streamAspect=1488.0/472
        val host=NativeDashboardView(app).apply { artworkView.style=DashboardStyle.PANORAMA }
        val state=DashboardSnapshot(mapOf(VehicleSignal.INDICATED_SPEED_KPH to VehicleReading(68.0,0,0),
            VehicleSignal.GEAR to VehicleReading("D",0,0),VehicleSignal.ENGINE_RPM to VehicleReading(1600.0,0,0),
            VehicleSignal.TIRE_FL_BAR to VehicleReading(2.4,0,0), VehicleSignal.TIRE_FR_BAR to VehicleReading(2.42,0,0),
            VehicleSignal.TIRE_RL_BAR to VehicleReading(2.38,0,0),VehicleSignal.TIRE_RR_BAR to VehicleReading(2.39,0,0)),
            ClusterMusicState.Snapshot(CarPlayNowPlaying(title="晴天",artist="测试歌手",durationMillis=240000,elapsedMillis=60000)),
            ClusterTurnGuidance(3,0,300,"世纪大道 · 测试导航",remainingSeconds=720,remainingMeters=18000))
        for(mode in DashboardMode.choices(DashboardStyle.PANORAMA)) {
            host.artworkView.mode=mode
            host.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(532,View.MeasureSpec.EXACTLY))
            host.layout(0,0,1920,532)
            assertEquals(1488,host.getChildAt(0).width); assertEquals(472,host.getChildAt(0).height)
            assertEquals(432,host.getChildAt(0).left); assertEquals(0,host.getChildAt(0).top)
            val bitmap=Bitmap.createBitmap(1920,532,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(bitmap); val underlay=Color.rgb(18,43,61); canvas.drawColor(underlay)
            val view=DashboardCanvas(app).apply {
                style=DashboardStyle.PANORAMA; this.mode=mode; snapshot=state
                mapActive=true; mapWindow=DashboardLayout.of(style,mode).map!!
            }
            view.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(532,View.MeasureSpec.EXACTLY))
            view.layout(0,0,1920,532); view.draw(canvas)
            assertEquals(underlay,bitmap.getPixel(1200,400))
            assertEquals(underlay,bitmap.getPixel(1800,250)) // Area reclaimed by the smaller card.
            assertNotEquals(underlay,bitmap.getPixel(700,80)) // Guidance above video.
            assertNotEquals(underlay,bitmap.getPixel(1700,350)) // Music/tire card above video.
            assertNotEquals(underlay,bitmap.getPixel(60,500)) // Vehicle footer remains opaque.
            val out=File("build/reports/native-dashboard").apply { mkdirs() }
            File(out,"panorama-${mode.name.lowercase()}-map-fixture.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
            bitmap.recycle()
        }
        // An older 16:9 stream is fitted, never stretched or cropped, until reconnecting.
        MapMirrors.streamAspect=16.0/9
        host.requestLayout() // This test host is detached, so its aspect listener is not registered.
        host.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(532,View.MeasureSpec.EXACTLY))
        host.layout(0,0,1920,532)
        assertEquals(839,host.getChildAt(0).width); assertEquals(472,host.getChildAt(0).height)
    }
    @Test fun allThreeStylesHideUnknownAutoLightsAndKeepConfirmedLampStates() {
        for (style in listOf(DashboardStyle.MIDNIGHT, DashboardStyle.PANORAMA)) {
            val base=DashboardSnapshot(mapOf(VehicleSignal.LOW_BEAM to VehicleReading(true,0,0)))
            val unknown=render(base,style=style)
            val on=render(base.copy(vehicle=base.vehicle+(VehicleSignal.AUTO_HEADLIGHTS to VehicleReading(true,0,0))),style=style)
            val off=render(base.copy(vehicle=base.vehicle+(VehicleSignal.AUTO_HEADLIGHTS to VehicleReading(false,0,0))),style=style)
            val (cx,cy)=when(style) {
                DashboardStyle.MIDNIGHT -> 629 to 27
                DashboardStyle.PANORAMA -> 97 to 62
                else -> 662 to 32
            }
            val background=when(style) {
                DashboardStyle.MIDNIGHT -> Color.rgb(6,14,23)
                DashboardStyle.PANORAMA -> Color.rgb(6,17,23)
                else -> Color.BLACK
            }
            for(x in cx-22..cx+22) for(y in cy-19..cy+19) {
                assertEquals("$style must not draw an unconfirmed A lamp",
                    if(style==DashboardStyle.REPLICA) unknown.getPixel(cx-24,y) else background,unknown.getPixel(x,y))
            }
            assertTrue("$style confirmed AUTO stays green",(cx-22..cx+22).any { x ->
                (cy-19..cy+19).any { y -> on.getPixel(x,y)==Color.rgb(100,224,166) }
            })
            assertTrue("$style confirmed off keeps its dim icon",(cx-22..cx+22).any { x ->
                (cy-19..cy+19).any { y -> off.getPixel(x,y)==Color.rgb(38,57,74) }
            })
            val nextLamp=cx+when(style) { DashboardStyle.MIDNIGHT -> 80; DashboardStyle.PANORAMA -> 57; else -> 48 }
            for(x in nextLamp-22..nextLamp+22) for(y in cy-19..cy+19) {
                assertEquals("$style low beam must not move or change",unknown.getPixel(x,y),on.getPixel(x,y))
                assertEquals("$style low beam must not move or change",unknown.getPixel(x,y),off.getPixel(x,y))
            }
            unknown.recycle(); on.recycle(); off.recycle()
        }
    }
    @Test fun panoramaLampStatesAndMissingIndividualTireNeverBecomeSimulatedReadings() {
        val signals=listOf(VehicleSignal.LEFT_TURN,VehicleSignal.AUTO_HEADLIGHTS,VehicleSignal.LOW_BEAM,
            VehicleSignal.HIGH_BEAM,VehicleSignal.POSITION_LIGHT,VehicleSignal.REAR_FOG,VehicleSignal.RIGHT_TURN)
        val on=render(DashboardSnapshot(signals.associateWith { VehicleReading(true,0,0) }),style=DashboardStyle.PANORAMA)
        val off=render(DashboardSnapshot(signals.associateWith { VehicleReading(false,0,0) }),style=DashboardStyle.PANORAMA)
        signals.indices.forEach { index ->
            val cx=(40f+(343f/6f)*index).toInt()
            assertTrue((cx-20..cx+20).any { x -> (42..82).any { y -> on.getPixel(x,y)!=off.getPixel(x,y) } })
        }
        on.recycle(); off.recycle()
        val known=DashboardSnapshot(mapOf(VehicleSignal.TIRE_FL_BAR to VehicleReading(2.4,0,0),
            VehicleSignal.TIRE_FR_BAR to VehicleReading(2.42,0,0),VehicleSignal.TIRE_RL_BAR to VehicleReading(2.38,0,0),
            VehicleSignal.TIRE_RR_BAR to VehicleReading(2.39,0,0)))
        val all=render(known,DashboardMode.TIRES,DashboardStyle.PANORAMA)
        val missing=render(known.copy(vehicle=known.vehicle-VehicleSignal.TIRE_RR_BAR),DashboardMode.TIRES,DashboardStyle.PANORAMA)
        assertTrue((1808..1879).any { x -> (400..437).any { y -> all.getPixel(x,y)!=missing.getPixel(x,y) } })
        for(x in 1552..1688) for(y in 340..437) assertEquals(all.getPixel(x,y),missing.getPixel(x,y))
        all.recycle(); missing.recycle()
    }
    @Test fun allThreeStylesHideGuidanceAndArrivalCardsWithoutAnActiveRoute() {
        val app=RuntimeEnvironment.getApplication()
        val underlay=Color.rgb(18,43,61)
        for(style in listOf(DashboardStyle.MIDNIGHT,DashboardStyle.PANORAMA,DashboardStyle.REPLICA)) {
            fun draw(nav: ClusterTurnGuidance?):Bitmap {
                val view=DashboardCanvas(app).apply {
                    this.style=style; mode=DashboardMode.NAVIGATION
                    snapshot=DashboardSnapshot(navigation=nav)
                    mapActive=true; mapWindow=DashboardLayout.of(style,mode).map!!
                }
                view.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(532,View.MeasureSpec.EXACTLY))
                view.layout(0,0,1920,532)
                return Bitmap.createBitmap(1920,532,Bitmap.Config.ARGB_8888).also {
                    val c=Canvas(it); c.drawColor(underlay); view.draw(c)
                }
            }
            val empty=draw(null)
            val active=draw(ClusterTurnGuidance(3,0,300,"世纪大道",remainingSeconds=360,remainingMeters=2600))
            val y=if(style==DashboardStyle.PANORAMA) 90 else 135
            assertEquals("$style must not reserve a blank guidance panel",underlay,empty.getPixel(800,y))
            assertNotEquals("$style route appears over video",underlay,active.getPixel(800,y))
            if(style==DashboardStyle.PANORAMA) {
                assertEquals("No leftover arrival box",underlay,empty.getPixel(1700,130))
                assertNotEquals(underlay,active.getPixel(1700,130))
            }
            val out=File("build/reports/native-dashboard").apply { mkdirs() }
            File(out,"${style.name.lowercase()}-no-navigation-video-fixture.png").outputStream().use { assertTrue(empty.compress(Bitmap.CompressFormat.PNG,100,it)) }
            empty.recycle(); active.recycle()
        }
    }
    @Test fun replicaTextureExtendsUnderDialsWithoutObscuringSpeedGearOrAssistance() {
        val app=RuntimeEnvironment.getApplication()
        MapMirrors.streamAspect=DashboardLayout.REPLICA_MAP_WIDTH.toDouble()/DashboardLayout.REPLICA_MAP_HEIGHT
        val host=NativeDashboardView(app).apply { artworkView.style=DashboardStyle.REPLICA; artworkView.mode=DashboardMode.NAVIGATION }
        host.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(532,View.MeasureSpec.EXACTLY))
        host.layout(0,0,1920,532)
        assertEquals(1388,host.getChildAt(0).width); assertEquals(532,host.getChildAt(0).height)
        assertEquals(266,host.getChildAt(0).left); assertEquals(0,host.getChildAt(0).top)
        val bitmap=Bitmap.createBitmap(1920,532,Bitmap.Config.ARGB_8888)
        val underlay=Color.rgb(18,43,61)
        val c=Canvas(bitmap); c.drawColor(underlay)
        val view=host.artworkView
        view.mapActive=true; view.mapWindow=DashboardLayout.of(DashboardStyle.REPLICA,DashboardMode.NAVIGATION).map!!
        view.draw(c)
        assertEquals(underlay,bitmap.getPixel(950,300))
        assertEquals(Color.BLACK,bitmap.getPixel(340,270)) // Opaque speed-dial face over video.
        assertEquals(Color.BLACK,bitmap.getPixel(1580,270)) // Opaque gear-dial face over video.
        assertEquals(underlay,bitmap.getPixel(600,530)) // Full-height map outside the compact centered footer.
        assertNotEquals(underlay,bitmap.getPixel(950,530)) // Footer protects the live time/temperature/range.
        assertNotEquals(underlay,bitmap.getPixel(566,32)) // Indicators stay readable above the map.
        for (cx in listOf(266,1654)) {
            assertTrue("Thin rim reaches the upper edge", (12..16).any { bitmap.getPixel(cx,it)!=Color.BLACK })
            assertTrue("Thin rim reaches the lower edge", (516..520).any { bitmap.getPixel(cx,it)!=Color.BLACK })
        }
        bitmap.recycle()
    }
    @Test fun replicaSilverSweepFollowsRpmAndUnknownValuesDoNotFillTheGauge() {
        val known=DashboardSnapshot(mapOf(VehicleSignal.ENGINE_RPM to VehicleReading(4000.0,0,0),
            VehicleSignal.INDICATED_SPEED_KPH to VehicleReading(60.0,0,0),VehicleSignal.GEAR to VehicleReading("D",0,0)))
        val first=render(known,style=DashboardStyle.REPLICA)
        val missing=render(known.copy(vehicle=known.vehicle-VehicleSignal.ENGINE_RPM),style=DashboardStyle.REPLICA)
        assertNotEquals(first.getPixel(1454,266),missing.getPixel(1454,266)) // 90° sector from 0 to 4k.
        for(x in 12..520) for(y in 12..520) assertEquals("RPM must not move speed",first.getPixel(x,y),missing.getPixel(x,y))
        val shifted=render(known.copy(vehicle=known.vehicle+(VehicleSignal.GEAR to VehicleReading("P",0,0))),style=DashboardStyle.REPLICA)
        assertTrue((1213..1257).any { x -> (480..531).any { y -> first.getPixel(x,y)!=shifted.getPixel(x,y) } })
        for(x in 1430..1455) for(y in 200..300) assertEquals("Gear does not change RPM sweep",first.getPixel(x,y),shifted.getPixel(x,y))
        first.recycle(); missing.recycle(); shifted.recycle()
    }
    @Test fun replicaMapFillsHeightWithoutStretchingNewOrExistingStreamsAtBothPanelSizes() {
        val app=RuntimeEnvironment.getApplication()
        val previous=MapMirrors.STREAM_ASPECT
        try {
            for (aspect in listOf(1388.0/532,1320.0/428,16.0/9)) for (width in listOf(1920,960)) {
                MapMirrors.streamAspect=aspect
                val height=width*532/1920
                val host=NativeDashboardView(app).apply { artworkView.style=DashboardStyle.REPLICA; artworkView.mode=DashboardMode.NAVIGATION }
                host.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
                host.layout(0,0,width,height)
                val texture=host.getChildAt(0)
                val scale=width/1920f
                assertEquals("Video must retain its aspect",aspect,texture.width.toDouble()/texture.height,0.01)
                assertTrue(texture.height>=height)
                assertTrue(texture.left<=266*scale+1 && texture.right>=1654*scale-1)
                assertTrue(texture.top<=0 && texture.bottom>=height)
                assertEquals(android.graphics.RectF(266f,0f,1654f,532f),host.artworkView.mapWindow)
            }
        } finally { MapMirrors.streamAspect=previous }
    }
    @Test fun replicaAllLightsAreGroupedInCenterAndNeverChangeEitherDial() {
        val lamps=listOf(VehicleSignal.LEFT_TURN,VehicleSignal.RIGHT_TURN,VehicleSignal.AUTO_HEADLIGHTS,
            VehicleSignal.LOW_BEAM,VehicleSignal.HIGH_BEAM,VehicleSignal.POSITION_LIGHT,VehicleSignal.REAR_FOG)
        val base=DashboardSnapshot(lamps.associateWith { VehicleReading(false,0,0) })
        val off=render(base,style=DashboardStyle.REPLICA)
        for ((index,signal) in lamps.withIndex()) {
            val on=render(base.copy(vehicle=base.vehicle+(signal to VehicleReading(true,0,0))),style=DashboardStyle.REPLICA)
            val cx=listOf(546,1374,600,600,667,733,800)[index]
            assertTrue("$signal is visible in the unified strip",(cx-22..cx+22).any { x ->
                (13..51).any { y -> on.getPixel(x,y)!=off.getPixel(x,y) }
            })
            for(x in 0 until 1920) for(y in 0 until 532) {
                if(x in 526..1394 && y in 0..62) continue
                // Time is independent of lamp state; exclude its live clock area.
                if(x in 670..850 && y in 480..532) continue
                assertEquals("$signal must not leak outside center status strip",off.getPixel(x,y),on.getPixel(x,y))
            }
            on.recycle()
        }
        off.recycle()
    }
    @Test fun replicaPagesShareVehicleChromeAndNeverDisplayOdometerOrMusicTimeDigits() {
        val state=DashboardSnapshot(mapOf(VehicleSignal.ASSIST_MODE to VehicleReading("GPILOT",0,0),
            VehicleSignal.ACC_STATE to VehicleReading(2,0,0),VehicleSignal.ACC_SET_SPEED_KPH to VehicleReading(80.0,0,0),
            VehicleSignal.FOLLOW_GAP to VehicleReading(2,0,0),VehicleSignal.LANE_CENTERING_ACTIVE to VehicleReading(true,0,0)),
            ClusterMusicState.Snapshot(CarPlayNowPlaying(title="测试曲目",artist="测试歌手",durationMillis=100000,elapsedMillis=25000)))
        val music=render(state,DashboardMode.MUSIC,DashboardStyle.REPLICA)
        val changed=render(state.copy(vehicle=state.vehicle+(VehicleSignal.ODOMETER_KM to VehicleReading(99999.0,0,0)),
            music=ClusterMusicState.Snapshot(state.music.info.copy(durationMillis=200000,elapsedMillis=50000))),DashboardMode.MUSIC,DashboardStyle.REPLICA)
        for(x in 0 until 1920) for(y in 0 until 532) assertEquals("Odometer/time labels are absent",music.getPixel(x,y),changed.getPixel(x,y))
        for(mode in listOf(DashboardMode.NAVIGATION,DashboardMode.TIRES)) {
            val other=render(state,mode,DashboardStyle.REPLICA)
            assertArrayEquals("All pages preserve lamps and assistance in the shared top row",
                replicaStatusForeground(music),replicaStatusForeground(other))
            other.recycle()
        }
        music.recycle(); changed.recycle()
    }
    @Test fun replicaAssistanceUsesColorWithoutStateWordsAndKeepsTheSpeedUnit() {
        val app=RuntimeEnvironment.getApplication()
        val out=File("build/reports/native-dashboard").apply { mkdirs() }
        for(locale in listOf("zh-CN","zh-TW","en","ar","es","ru","uk","ja")) for(status in listOf(0,1,2,4,5,6,7)) {
            val localized=app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(locale))
            })
            val view=DashboardCanvas(localized).apply {
                style=DashboardStyle.REPLICA; mode=DashboardMode.MUSIC
                snapshot=DashboardSnapshot(mapOf(VehicleSignal.ASSIST_MODE to VehicleReading("GPILOT",0,0),
                    VehicleSignal.ACC_STATE to VehicleReading(status,0,0)))
            }
            view.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(532,View.MeasureSpec.EXACTLY))
            view.layout(0,0,1920,532)
            val bitmap=Bitmap.createBitmap(1920,532,Bitmap.Config.ARGB_8888)
            val labels=mutableListOf<String>()
            val canvas=object: Canvas(bitmap) {
                override fun drawText(text: String,x: Float,y: Float,paint: Paint) {
                    if(x>=996f && y<60) labels+=text
                    super.drawText(text,x,y,paint)
                }
            }
            view.draw(canvas)
            assertEquals("No activation/standby/unknown state words; missing target is not zero",listOf("GPilot","— km/h"),labels)
            if(locale=="zh-CN") File(out,"replica-assistance-$status.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))
            }
            bitmap.recycle()
        }
    }
    @Test fun capturesAllThreeDesignsAllModesAndMissingDataUsingActualRenderer() {
        val app = RuntimeEnvironment.getApplication()
        val album = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        Canvas(album).apply {
            drawColor(Color.rgb(41, 66, 79))
            drawCircle(120f, 96f, 65f, Paint().apply { color=Color.rgb(218, 155, 107) })
            drawRect(0f,155f,240f,240f,Paint().apply { color=Color.rgb(21, 42, 57) })
        }
        // Explicit test fixtures; production never injects demo readings, artwork or routes.
        val state = DashboardSnapshot(
            mapOf(VehicleSignal.INDICATED_SPEED_KPH to VehicleReading(68.0,0,0), VehicleSignal.ENGINE_RPM to VehicleReading(1600.0,0,0),
                VehicleSignal.GEAR to VehicleReading("D",0,0), VehicleSignal.INDICATED_AMBIENT_C to VehicleReading(22.5,0,0),
                VehicleSignal.INDICATED_RANGE_KM to VehicleReading(111.0,0,0),
                VehicleSignal.ENGINE_COOLANT_C to VehicleReading(90.0,0,0),
                VehicleSignal.HILL_DESCENT_ACTIVE to VehicleReading(true,0,0),
                VehicleSignal.ACC_SET_SPEED_KPH to VehicleReading(80.0,0,0),
                VehicleSignal.FOLLOW_GAP to VehicleReading(2,0,557849293),
                VehicleSignal.ODOMETER_KM to VehicleReading(43821.0,0,0), VehicleSignal.FUEL_PERCENT to VehicleReading(61.0,0,0),
                VehicleSignal.TIRE_FL_BAR to VehicleReading(2.40,0,0), VehicleSignal.TIRE_FR_BAR to VehicleReading(2.42,0,0),
                VehicleSignal.TIRE_RL_BAR to VehicleReading(2.38,0,0), VehicleSignal.TIRE_RR_BAR to VehicleReading(2.39,0,0),
                VehicleSignal.AUTO_HEADLIGHTS to VehicleReading(true,0,0), VehicleSignal.LOW_BEAM to VehicleReading(true,0,0), VehicleSignal.HIGH_BEAM to VehicleReading(false,0,0),
                VehicleSignal.LEFT_TURN to VehicleReading(true,0,0), VehicleSignal.RIGHT_TURN to VehicleReading(false,0,0),
                VehicleSignal.POSITION_LIGHT to VehicleReading(true,0,0), VehicleSignal.REAR_FOG to VehicleReading(false,0,0),
                VehicleSignal.DRIVER_BELT_FASTENED to VehicleReading(false,0,0), VehicleSignal.ASSIST_MODE to VehicleReading("GPILOT",0,0),
                VehicleSignal.ACC_STATE to VehicleReading(2,0,0), VehicleSignal.LANE_CENTERING_ACTIVE to VehicleReading(true,0,0), VehicleSignal.LANE_LEFT_COLOR to VehicleReading(2,0,0),
                VehicleSignal.LANE_RIGHT_COLOR to VehicleReading(2,0,0)),
            ClusterMusicState.Snapshot(CarPlayNowPlaying(title="夜曲 · 测试曲目",artist="测试歌手",playing=true,durationMillis=223000,elapsedMillis=97000), album),
            ClusterTurnGuidance(3,0,800,"滨江大道 · 测试导航",remainingSeconds=720,remainingMeters=8400))
        val out = File("build/reports/native-dashboard").apply { mkdirs() }
        val statusPixels = mutableMapOf<Pair<DashboardStyle,Boolean>, IntArray>()
        for (style in listOf(DashboardStyle.MIDNIGHT,DashboardStyle.PANORAMA,DashboardStyle.REPLICA)) {
            for (mode in DashboardMode.entries) {
                for (connected in listOf(false,true)) {
                    val view = DashboardCanvas(app).apply { this.style=style; this.mode=mode; snapshot=if(connected) state else DashboardSnapshot() }
                    view.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(532,View.MeasureSpec.EXACTLY))
                    view.layout(0,0,1920,532)
                    val bitmap=Bitmap.createBitmap(1920,532,Bitmap.Config.ARGB_8888)
                    val canvas=Canvas(bitmap)
                    if(style==DashboardStyle.REPLICA && mode==DashboardMode.NAVIGATION && connected) {
                        // Deterministic stand-in for a video frame, never shipped to the car.
                        canvas.drawColor(Color.rgb(13,27,35))
                        val road=Paint().apply { color=Color.rgb(55,68,75); strokeWidth=3f }
                        for(x in 400..1520 step 120) canvas.drawLine(960f+(x-960)*.25f,0f,x.toFloat(),532f,road)
                        for(y in 180..532 step 50) canvas.drawLine(300f,y.toFloat(),1620f,y.toFloat(),road)
                        road.color=Color.rgb(48,153,231); road.strokeWidth=18f
                        canvas.drawLine(960f,532f,960f,200f,road); canvas.drawLine(960f,200f,1230f,200f,road)
                        view.mapActive=true; view.mapWindow=DashboardLayout.of(style,mode).map!!
                    }
                    view.draw(canvas)
                    File(out,"${style.name.lowercase()}-${mode.name.lowercase()}-${if(connected) "fixture" else "empty"}.png").outputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))
                    }
                    assertEquals(1920,bitmap.width);assertEquals(532,bitmap.height)
                    assertNotEquals(Color.TRANSPARENT,bitmap.getPixel(100,500))
                    val pixels=if(style==DashboardStyle.REPLICA) replicaStatusForeground(bitmap) else
                        IntArray(1920*60).also { bitmap.getPixels(it,0,1920,0,472,1920,60) }
                    statusPixels[style to connected]?.let { assertArrayEquals("Status indicators must survive page switching",it,pixels) }
                    statusPixels[style to connected]=pixels
                    bitmap.recycle()
                }
            }
        }
        for (style in listOf(DashboardStyle.MIDNIGHT, DashboardStyle.PANORAMA, DashboardStyle.REPLICA))
        for (mode in DashboardMode.choices(style))
        for (locale in listOf("zh-CN", "ar")) for (width in listOf(960,1920)) {
            val config=Configuration(app.resources.configuration).apply { fontScale=1.5f; setLocale(Locale.forLanguageTag(locale)) }
            val localized=app.createConfigurationContext(config)
            val height=width*532/1920
            val view=DashboardCanvas(localized).apply { this.style=style; this.mode=mode; snapshot=state }
            view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
            view.layout(0,0,width,height)
            val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(out,"${style.name.lowercase()}-${mode.name.lowercase()}-large-font-$locale-$width.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
            bitmap.recycle()
        }
        album.recycle()
    }
}
