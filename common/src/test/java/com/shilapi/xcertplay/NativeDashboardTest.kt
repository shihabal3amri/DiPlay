package com.shilapi.xcertplay

import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.ultra.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class NativeDashboardTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Test fun nativeSpeedUsesIndicatedFeedbackWithoutFallingBackToUnreliableStandardZero() {
        val standard=mapOf(VehicleSignal.SPEED_KPH to VehicleReading(0.0,0,0x11600207))
        for(speed in listOf(0.0,1.0,3.0,10.0,68.0)) {
            val state=DashboardSnapshot(standard+(VehicleSignal.INDICATED_SPEED_KPH to VehicleReading(speed,0,557874773)))
            assertEquals(speed,state.speedKph!!,0.0)
            assertEquals(0.0,state.number(VehicleSignal.SPEED_KPH)!!,0.0)
        }
        assertNull(DashboardSnapshot(standard).speedKph)
        assertNull(DashboardSnapshot(standard+(VehicleSignal.SPEED_INDICATED_RAW to VehicleReading(10,0,557874773))).speedKph)
        assertNull(DashboardSnapshot(mapOf(VehicleSignal.SPEED_KPH to VehicleReading(30.0,0,0x11600207))).speedKph)
    }
    @Test fun selectionEnablesOrdinaryMapAndSynchronizesFactoryDimOutput() {
        BoyueLSettings.select(app, true)
        BoyueLSettings.setDim(app, true)
        BoyueLSettings.setUltra(app, true)
        NativeDashboardSettings.setStyle(app, DashboardStyle.PANORAMA)
        assertTrue(AirPlayPersistence.loadClusterMapEnabled(app))
        assertNull(BoyueUltraIntegration.config(app, BoyueLSettings.streamConfig()))
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val config = ReflectionHelpers.callInstanceMethod<com.shilapi.xcertplay.airplay.AirPlayDisplayConfig>(host, "clusterDisplayConfig")
        assertEquals(1488, config.widthPixels)
        assertEquals(472, config.heightPixels)
        assertEquals(24 + 1088, config.safeArea!!.left + config.widthPixels - config.safeArea!!.right)
        assertTrue(config.safeArea!!.right > 400)
        assertEquals(true, config.safeAreaDrawOutside)
        assertNotNull(config.initialUrl)
        assertTrue(BoyueLSettings.ultra(app))
        assertFalse(BoyueLSettings.dim(app))
        NativeDashboardSettings.setStyle(app, DashboardStyle.OFF)
        assertTrue(BoyueLSettings.dim(app))
        BoyueLSettings.setUltra(app, false)
        assertFalse(AirPlayPersistence.loadClusterMapEnabled(app))
        BoyueLSettings.select(app, false)
        NativeDashboardSettings.setStyle(app, DashboardStyle.MIDNIGHT)
        assertFalse(NativeDashboardSettings.enabled(app))
    }
    @Test fun realHostClearsPhoneDataWhileMenuDefersReconnectAndIgnoresUnrelatedSessionEnd() {
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val active = org.mockito.Mockito.mock(com.shilapi.xcertplay.airplay.AirPlaySession::class.java)
        ReflectionHelpers.setField(host, "activeAirPlaySession", active)
        ReflectionHelpers.setField(host, "menuOpen", true)
        val listener = ReflectionHelpers.callInstanceMethod<com.shilapi.xcertplay.airplay.AirPlaySessionListener>(host,
            "createSessionListener", ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 0))
        var music = ClusterMusicState.Snapshot()
        val remove = ClusterMusicState.observe { music = it }
        try {
            MapMirrors.setStreamActive(true)
            ClusterMusicState.publish(CarPlayNowPlaying(title="Retained track"), null)
            shadowOf(Looper.getMainLooper()).idle()
            listener.onSessionEnded(org.mockito.Mockito.mock(com.shilapi.xcertplay.airplay.AirPlaySession::class.java))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(MapMirrors.streamActive)
            assertEquals("Retained track", music.info.title)
            listener.onSessionEnded(active)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(MapMirrors.streamActive)
            assertNull(music.info.title)
            MapMirrors.setStreamActive(true)
            ClusterMusicState.publish(CarPlayNowPlaying(title="Next session"), null)
            shadowOf(Looper.getMainLooper()).idle()
            listener.onTransportError("Disconnected")
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(MapMirrors.streamActive)
            assertNull(music.info.title)
        } finally {
            remove()
            for (name in listOf("teardownExecutor", "airPlayCommandExecutor"))
                ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(host, name).shutdownNow()
        }
    }
    @Test fun mapStaysHiddenOnDisconnectAndUntilFirstFrameOfNextStream() {
        val frames = DashboardMapFrames()
        frames.frame(); assertFalse(frames.visible)
        frames.stream(true); assertFalse(frames.visible)
        frames.frame(); assertTrue(frames.visible)
        frames.stream(false); assertFalse(frames.visible)
        frames.stream(true); assertFalse(frames.visible)
        frames.frame(); assertTrue(frames.visible)
        frames.resetSurface(); assertFalse(frames.visible)
    }
    @Test fun modeCyclesAndIgnoresLongPressDuplicateAndUnrelatedKeys() {
        NativeDashboardSettings.setStyle(app, DashboardStyle.PANORAMA)
        val key = DashboardModeKey()
        assertFalse(key.accept(200085, false, 1000))
        assertFalse(key.accept(200110, true, 1000))
        assertTrue(key.accept(200110, false, 1000))
        assertFalse(key.accept(200110, false, 1100))
        assertTrue(key.accept(200110, false, 1500))
        assertEquals(DashboardMode.MUSIC, NativeDashboardSettings.mode(app))
        assertEquals(DashboardMode.TIRES, NativeDashboardSettings.cycle(app))
        assertEquals(DashboardMode.MUSIC, NativeDashboardSettings.cycle(app))
        assertEquals(DashboardMode.TIRES, NativeDashboardSettings.cycle(app))
    }
    @Test fun mediaDisconnectDoesNotReleaseOfflinePageSubscription() {
        BoyueLSettings.select(app, true)
        NativeDashboardSettings.setStyle(app, DashboardStyle.MIDNIGHT)
        NativeDashboardSettings.setPageKey(app, 200082)
        GeelyWheelKeys.startDashboard(app)
        GeelyWheelKeys.start(app)
        GeelyWheelKeys.stop()
        try {
            assertTrue(ReflectionHelpers.getField(GeelyWheelKeys, "dashboardOwner"))
            assertFalse(ReflectionHelpers.getField(GeelyWheelKeys, "mediaOwner"))
            ReflectionHelpers.callInstanceMethod<Unit>(GeelyWheelKeys, "forward",
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 200082),
                ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(DashboardMode.NAVIGATION, NativeDashboardSettings.mode(app))
        } finally { GeelyWheelKeys.stopDashboard() }
        assertFalse(ReflectionHelpers.getField(GeelyWheelKeys, "bound"))
    }
    @Test fun sharedSourceKeepsOfflineVehicleDataClearsDisconnectedMusicAndExpiresReadings() {
        val telemetry = VehicleTelemetry(SystemClock::elapsedRealtime)
        var starts = 0; var closes = 0
        val previous = NativeDashboardRuntime.sourceFactory
        NativeDashboardRuntime.sourceFactory = { object : UltraVehicleSource {
            override fun start() { starts++ }
            override fun snapshot() = telemetry.snapshot()
            override fun close() { closes++ }
        } }
        val snapshots = mutableListOf<DashboardSnapshot>()
        var remove1: (() -> Unit)? = null; var remove2: (() -> Unit)? = null
        try {
            telemetry.accept(VehicleSignal.SPEED_KPH, 68.0, 0x11600207, SystemClock.elapsedRealtime())
            telemetry.accept(VehicleSignal.FOLLOW_GAP, 3, 557849293)
            remove1 = NativeDashboardRuntime.observe { snapshots.add(it) }
            remove2 = NativeDashboardRuntime.observe { }
            ClusterMusicState.publish(CarPlayNowPlaying(title="Track", playing=true, elapsedMillis=1000), null)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            assertEquals(1, starts)
            assertEquals(68.0, snapshots.last().number(VehicleSignal.SPEED_KPH)!!, 0.001)
            assertEquals("Track", snapshots.last().music.info.title)
            assertEquals(3, snapshots.last().followGap)
            telemetry.accept(VehicleSignal.FOLLOW_GAP, 1, 557849293)
            ClusterMusicState.publish(CarPlayNowPlaying(), null)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            assertNull(snapshots.last().music.info.title)
            assertEquals(1, snapshots.last().followGap)
            assertNotNull(snapshots.last().number(VehicleSignal.SPEED_KPH))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            assertNull(snapshots.last().number(VehicleSignal.SPEED_KPH))
            assertNull(snapshots.last().followGap)
            assertEquals("—", snapshots.last().gear)
            remove1.invoke(); remove1 = null
            assertEquals(0, closes)
            remove2.invoke(); remove2 = null
            assertEquals(1, closes)
            assertTrue(NativeDashboardRuntime.state.vehicle.isEmpty())
        } finally {
            remove1?.invoke(); remove2?.invoke()
            NativeDashboardRuntime.sourceFactory = previous
        }
    }
    @Test fun autoHoldFeedbackSharesLifecycleAndClearsUnknownWithoutTouchingOtherReadings() {
        val oldVehicle=NativeDashboardRuntime.sourceFactory
        val oldHold=NativeDashboardRuntime.autoHoldFactory
        var starts=0; var closes=0; var holding:Boolean?=null
        NativeDashboardRuntime.sourceFactory={ object:UltraVehicleSource {
            override fun start() {}
            override fun snapshot()=mapOf(VehicleSignal.INDICATED_RANGE_KM to VehicleReading(111,0,561024488))
            override fun close() {}
        } }
        NativeDashboardRuntime.autoHoldFactory={ object:UltraVehicleSource {
            override fun start() { starts++ }
            override fun snapshot()=holding?.let { mapOf(VehicleSignal.AUTO_HOLD_ACTIVE to VehicleReading(it,0,32)) }.orEmpty()
            override fun close() { closes++ }
        } }
        var first:(()->Unit)?=null;var second:(()->Unit)?=null
        try {
            first=NativeDashboardRuntime.observe(app) {}; second=NativeDashboardRuntime.observe(app) {}
            for (value in listOf(null,true,false,null)) {
                holding=value;shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
                assertEquals(value,NativeDashboardRuntime.state.vehicle[VehicleSignal.AUTO_HOLD_ACTIVE]?.value)
                assertEquals(111,NativeDashboardRuntime.state.vehicle[VehicleSignal.INDICATED_RANGE_KM]?.value)
            }
            assertEquals(1,starts); first.invoke();first=null;assertEquals(0,closes)
            second.invoke();second=null;assertEquals(1,closes)
            assertTrue(NativeDashboardRuntime.state.vehicle.isEmpty())
        } finally {
            first?.invoke();second?.invoke()
            NativeDashboardRuntime.sourceFactory=oldVehicle;NativeDashboardRuntime.autoHoldFactory=oldHold
        }
    }

    @Test fun exteriorLightFeedbackMergesWithVhalAndSharesPanelLifecycle() {
        val oldVehicle = NativeDashboardRuntime.sourceFactory
        val oldLight = NativeDashboardRuntime.exteriorLightFactory
        var lightStarts = 0; var lightCloses = 0
        val lights = VehicleTelemetry(SystemClock::elapsedRealtime)
        NativeDashboardRuntime.sourceFactory = { object : UltraVehicleSource {
            override fun start() {}
            override fun snapshot() = mapOf(VehicleSignal.LEFT_TURN to VehicleReading(true, 0, 557873489))
            override fun close() {}
        } }
        NativeDashboardRuntime.exteriorLightFactory = { object : UltraVehicleSource {
            override fun start() { lightStarts++ }
            override fun snapshot() = lights.snapshot()
            override fun close() { lightCloses++ }
        } }
        var first: (() -> Unit)? = null; var second: (() -> Unit)? = null
        try {
            lights.accept(VehicleSignal.AUTO_HEADLIGHTS, false, 537136640)
            first = NativeDashboardRuntime.observe(app) { }
            second = NativeDashboardRuntime.observe(app) { }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            assertEquals(1, lightStarts)
            assertEquals(false, NativeDashboardRuntime.state.vehicle[VehicleSignal.AUTO_HEADLIGHTS]?.value)
            assertEquals(true, NativeDashboardRuntime.state.vehicle[VehicleSignal.LEFT_TURN]?.value)
            lights.accept(VehicleSignal.AUTO_HEADLIGHTS, null, 537136640)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            assertNull(NativeDashboardRuntime.state.vehicle[VehicleSignal.AUTO_HEADLIGHTS])
            first.invoke(); first = null
            assertEquals(0, lightCloses)
            second.invoke(); second = null
            assertEquals(1, lightCloses)
        } finally {
            first?.invoke(); second?.invoke()
            NativeDashboardRuntime.sourceFactory = oldVehicle
            NativeDashboardRuntime.exteriorLightFactory = oldLight
        }
    }
    @Test fun followGapSnapshotUsesActualFeedbackAndNeverCountsOrClampsInvalidLevels() {
        fun state(raw: Number) = DashboardSnapshot(mapOf(VehicleSignal.FOLLOW_GAP to VehicleReading(raw,0,557849293)))
        for (raw in listOf(3,2,1,2,3)) assertEquals(raw,state(raw).followGap)
        for (raw in listOf(0,4,255,-1,1.5,Double.NaN)) assertNull(state(raw).followGap)
        assertNull(DashboardSnapshot().followGap)
    }
    @Test fun midnightDefaultsToMusicAndCyclesMusicNavigationTiresWithoutChangingSavedOrdinals() {
        val prefs = app.getSharedPreferences("native_dashboard", android.content.Context.MODE_PRIVATE)
        prefs.edit().putInt("mode", DashboardMode.ALL.ordinal).apply()
        NativeDashboardSettings.setStyle(app, DashboardStyle.MIDNIGHT)
        assertEquals(listOf(DashboardMode.MUSIC, DashboardMode.NAVIGATION, DashboardMode.TIRES), NativeDashboardSettings.modes(app))
        assertEquals(DashboardMode.MUSIC, NativeDashboardSettings.mode(app))
        // Legacy ALL is shown as map + music without changing stored ordinals.
        NativeDashboardSettings.setStyle(app, DashboardStyle.PANORAMA)
        assertEquals(DashboardMode.MUSIC, NativeDashboardSettings.mode(app))
        NativeDashboardSettings.setStyle(app, DashboardStyle.MIDNIGHT)
        repeat(3) {
            assertEquals(DashboardMode.NAVIGATION, NativeDashboardSettings.cycle(app))
            assertEquals(DashboardMode.TIRES, NativeDashboardSettings.cycle(app))
            assertEquals(DashboardMode.MUSIC, NativeDashboardSettings.cycle(app))
        }
        NativeDashboardSettings.setMode(app, DashboardMode.ALL)
        assertEquals(DashboardMode.MUSIC, NativeDashboardSettings.mode(app))
        assertEquals(DashboardLayout.of(DashboardStyle.MIDNIGHT, DashboardMode.MUSIC),
            DashboardLayout.of(DashboardStyle.MIDNIGHT, DashboardMode.ALL))
    }
    @Test fun eachStyleRemembersItsOwnPageAndPanoramaKeepsTireSelection() {
        NativeDashboardSettings.setStyle(app, DashboardStyle.MIDNIGHT)
        NativeDashboardSettings.setMode(app, DashboardMode.TIRES)
        assertEquals(DashboardMode.TIRES, NativeDashboardSettings.mode(app))
        assertEquals(3, app.getSharedPreferences("native_dashboard", android.content.Context.MODE_PRIVATE).getInt("mode", -1))
        NativeDashboardSettings.setStyle(app, DashboardStyle.PANORAMA)
        assertEquals(DashboardMode.TIRES, NativeDashboardSettings.mode(app))
        assertEquals(listOf(DashboardMode.MUSIC,DashboardMode.TIRES),NativeDashboardSettings.modes(app))
        NativeDashboardSettings.setMode(app, DashboardMode.MUSIC)
        NativeDashboardSettings.setStyle(app, DashboardStyle.MIDNIGHT)
        assertEquals(DashboardMode.TIRES, NativeDashboardSettings.mode(app))
        assertEquals(DashboardMode.MUSIC,NativeDashboardSettings.cycle(app))
    }
    @Test fun panoramaMigratesLegacyPagesAndOnlySwitchesTheCardWithoutDroppingTheMap() {
        val prefs=app.getSharedPreferences("native_dashboard", android.content.Context.MODE_PRIVATE)
        NativeDashboardSettings.setStyle(app,DashboardStyle.PANORAMA)
        for(legacy in DashboardMode.entries) {
            prefs.edit().remove("panorama_mode").putInt("mode",legacy.ordinal).apply()
            assertEquals(if(legacy==DashboardMode.TIRES) DashboardMode.TIRES else DashboardMode.MUSIC,
                NativeDashboardSettings.mode(app))
        }
        NativeDashboardSettings.setMode(app,DashboardMode.MUSIC)
        val before=DashboardLayout.of(DashboardStyle.PANORAMA,NativeDashboardSettings.mode(app))
        repeat(4) {
            val next=DashboardLayout.of(DashboardStyle.PANORAMA,NativeDashboardSettings.cycle(app))
            assertEquals(before.map,next.map); assertEquals(before.navigation,next.navigation)
            assertTrue(next.map!!.contains(next.music ?: next.tires!!))
            assertEquals(before.music,next.music ?: next.tires)
        }
        NativeDashboardSettings.setStyle(app,DashboardStyle.MIDNIGHT)
        NativeDashboardSettings.setMode(app,DashboardMode.NAVIGATION)
        NativeDashboardSettings.setStyle(app,DashboardStyle.PANORAMA)
        assertEquals(DashboardMode.MUSIC,NativeDashboardSettings.mode(app))
        NativeDashboardSettings.cycle(app)
        NativeDashboardSettings.setStyle(app,DashboardStyle.MIDNIGHT)
        assertEquals(DashboardMode.NAVIGATION,NativeDashboardSettings.mode(app))
    }
    @Test fun panoramaConfirmedPageKeySwitchesCardsWhileOfflineAndKeepsMapSessionActive() {
        BoyueLSettings.select(app,true)
        NativeDashboardSettings.setStyle(app,DashboardStyle.PANORAMA)
        NativeDashboardSettings.setMode(app,DashboardMode.MUSIC)
        NativeDashboardSettings.setPageKey(app,200600)
        GeelyWheelKeys.startDashboard(app)
        try {
            for(expected in listOf(DashboardMode.TIRES,DashboardMode.MUSIC)) {
                MapMirrors.setStreamActive(true)
                ReflectionHelpers.callInstanceMethod<Unit>(GeelyWheelKeys,"forward",
                    ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType,200600),
                    ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType,false))
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
                assertEquals(expected,NativeDashboardSettings.mode(app))
                assertTrue(MapMirrors.streamActive)
            }
        } finally { GeelyWheelKeys.stopDashboard(); MapMirrors.setStreamActive(false) }
    }
    @Test fun rangeUsesOemDteAndAcceptsZeroWithoutFallingBackToMismatchedStandardValue() {
        fun reading(value: Number) = VehicleReading(value,0,0)
        assertNull(DashboardSnapshot(mapOf(VehicleSignal.FUEL_PERCENT to reading(61))).rangeKm)
        val oem=mapOf(VehicleSignal.INDICATED_RANGE_KM to reading(326.0))
        assertEquals(326.0,DashboardSnapshot(oem).rangeKm!!,0.0)
        assertEquals(326.0,DashboardSnapshot(oem + (VehicleSignal.RANGE_KM to reading(0))).rangeKm!!,0.0)
        assertEquals(326.0,DashboardSnapshot(oem + (VehicleSignal.RANGE_KM to reading(519))).rangeKm!!,0.0)
        assertEquals(0.0,DashboardSnapshot(mapOf(VehicleSignal.INDICATED_RANGE_KM to reading(0),
            VehicleSignal.RANGE_KM to reading(519))).rangeKm!!,0.0)
        for (bad in listOf(-1,65535,Double.NaN))
            assertEquals(326.0,DashboardSnapshot(oem + (VehicleSignal.RANGE_KM to reading(bad))).rangeKm!!,0.0)
        for (bad in listOf(-1,65535,3001,Double.NaN,Double.POSITIVE_INFINITY)) {
            assertNull(DashboardSnapshot(mapOf(VehicleSignal.INDICATED_RANGE_KM to reading(bad),
                VehicleSignal.RANGE_KM to reading(519))).rangeKm)
            assertNull(DashboardSnapshot(mapOf(VehicleSignal.INDICATED_RANGE_KM to reading(bad),
                VehicleSignal.RANGE_KM to reading(0))).rangeKm)
            assertNull(DashboardSnapshot(mapOf(VehicleSignal.INDICATED_RANGE_KM to reading(bad),
                VehicleSignal.RANGE_KM to reading(bad))).rangeKm)
        }
        assertNull(DashboardSnapshot(mapOf(VehicleSignal.RANGE_KM to reading(519))).rangeKm)
        assertNull(DashboardSnapshot().rangeKm)
    }
    @Test fun liveFx11TemperatureAndRangePreferOemFeedbackAndExpireWithoutInventingValues() {
        var now=100L
        val telemetry=VehicleTelemetry({now})
        fun accept(signal: VehicleSignal, value: Double?, property: Int) = telemetry.accept(signal,value,property)
        accept(VehicleSignal.AMBIENT_C,25.0,0x11600703)
        accept(VehicleSignal.INDICATED_AMBIENT_C,23.3,559970552)
        accept(VehicleSignal.RANGE_KM,50.0,0x11600308)
        accept(VehicleSignal.INDICATED_RANGE_KM,111.0,561024488)
        var snapshot=DashboardSnapshot(telemetry.snapshot())
        assertEquals(23.3,snapshot.ambientC!!,0.0001)
        assertEquals(111.0,snapshot.rangeKm!!,0.0)
        for(value in listOf(-12.5,0.0)) {
            accept(VehicleSignal.INDICATED_AMBIENT_C,value,559970552)
            assertEquals(value,DashboardSnapshot(telemetry.snapshot()).ambientC!!,0.0)
        }
        accept(VehicleSignal.INDICATED_AMBIENT_C,null,559970552)
        accept(VehicleSignal.INDICATED_RANGE_KM,null,561024488)
        snapshot=DashboardSnapshot(telemetry.snapshot())
        assertNull(snapshot.ambientC)
        assertNull(snapshot.rangeKm)
        for(value in listOf(-61.0,86.0,Double.NaN)) {
            snapshot=DashboardSnapshot(mapOf(VehicleSignal.INDICATED_AMBIENT_C to VehicleReading(value,now,559970552),
                VehicleSignal.AMBIENT_C to VehicleReading(25.0,now,0x11600703)))
            assertNull(snapshot.ambientC)
        }
        now+=2501
        snapshot=DashboardSnapshot(telemetry.snapshot())
        assertNull(snapshot.ambientC)
        assertNull(snapshot.rangeKm)
        assertNull(snapshot.accSetSpeedKph)
        assertFalse(snapshot.assistanceIconActive)
    }
    @Test fun replicaDefaultsToNavigationAndKeepsIndependentPageSelections() {
        val prefs = app.getSharedPreferences("native_dashboard", android.content.Context.MODE_PRIVATE)
        prefs.edit().remove("replica_mode").apply()
        NativeDashboardSettings.setStyle(app,DashboardStyle.MIDNIGHT)
        NativeDashboardSettings.setMode(app,DashboardMode.TIRES)
        NativeDashboardSettings.setStyle(app,DashboardStyle.PANORAMA)
        NativeDashboardSettings.setMode(app,DashboardMode.MUSIC)
        NativeDashboardSettings.setStyle(app,DashboardStyle.REPLICA)
        assertEquals(DashboardMode.NAVIGATION,NativeDashboardSettings.mode(app))
        assertEquals(DashboardMode.TIRES,NativeDashboardSettings.cycle(app))
        NativeDashboardSettings.setStyle(app,DashboardStyle.MIDNIGHT)
        assertEquals(DashboardMode.TIRES,NativeDashboardSettings.mode(app))
        NativeDashboardSettings.setStyle(app,DashboardStyle.PANORAMA)
        assertEquals(DashboardMode.MUSIC,NativeDashboardSettings.mode(app))
        NativeDashboardSettings.setStyle(app,DashboardStyle.REPLICA)
        assertEquals(DashboardMode.TIRES,NativeDashboardSettings.mode(app))
        assertEquals(DashboardMode.MUSIC,NativeDashboardSettings.cycle(app))
    }
    @Test fun replicaRequestsOrdinaryMapWithSafeAreaBehindItsDialsRegardlessOfSelectedPage() {
        BoyueLSettings.select(app,true)
        BoyueLSettings.setUltra(app,true)
        NativeDashboardSettings.setStyle(app,DashboardStyle.REPLICA)
        val host=Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        try {
            assertTrue(AirPlayPersistence.loadClusterMapEnabled(app))
            assertNull(BoyueUltraIntegration.config(app,BoyueLSettings.streamConfig()))
            for (page in NativeDashboardSettings.modes(app)) {
                NativeDashboardSettings.setMode(app,page)
                val config=ReflectionHelpers.callInstanceMethod<com.shilapi.xcertplay.airplay.AirPlayDisplayConfig>(host,"clusterDisplayConfig")
                assertEquals(1388,config.widthPixels)
                assertEquals(532,config.heightPixels)
                assertEquals(160,config.safeArea?.top)
                assertEquals(68,config.safeArea?.bottom)
                assertEquals(555,config.safeArea?.left)
                assertEquals(555,config.safeArea?.right)
                assertEquals(true,config.safeAreaDrawOutside)
                assertNotNull(config.initialUrl)
                assertEquals(1388.0/532,MapMirrors.STREAM_ASPECT,0.00001)
            }
            val map=DashboardLayout.of(DashboardStyle.REPLICA,DashboardMode.NAVIGATION).map!!
            assertEquals(1388f,map.width(),0f)
            assertEquals(532f,map.height(),0f)
            assertEquals(0f,map.top,0f)
            assertEquals(532f,map.bottom,0f)
            assertTrue(map.left<432f && map.right>1488f) // Map extends behind both inner dial edges.
            assertNull(DashboardLayout.of(DashboardStyle.REPLICA,DashboardMode.MUSIC).map)
            assertNull(DashboardLayout.of(DashboardStyle.REPLICA,DashboardMode.TIRES).map)
        } finally {
            for(name in listOf("teardownExecutor","airPlayCommandExecutor"))
                ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(host,name).shutdownNow()
        }
    }
    @Test fun accSetSpeedIsSeparateFromRoadSpeedAndUnknownVendorUnitsStayUnknown() {
        val base=mapOf(VehicleSignal.SPEED_KPH to VehicleReading(68.0,0,0),
            VehicleSignal.ACC_SET_SPEED_RAW to VehicleReading(160,0,557849502),
            VehicleSignal.ACC_STATE to VehicleReading(2,0,557871508))
        assertNull(DashboardSnapshot(base).accSetSpeedKph)
        assertEquals(80.0,DashboardSnapshot(base + (VehicleSignal.ACC_SET_SPEED_KPH to VehicleReading(80.0,0,0x11601013))).accSetSpeedKph!!,0.0)
        assertEquals(68.0,DashboardSnapshot(base).number(VehicleSignal.SPEED_KPH)!!,0.0)
    }
    @Test fun allLayoutsKeepVehicleAreaAndMidnightNeverCombinesMusicAndNavigation() {
        for (style in listOf(DashboardStyle.MIDNIGHT, DashboardStyle.PANORAMA)) {
            for (mode in DashboardMode.entries) {
                val layout = DashboardLayout.of(style, mode)
                val selected = mode.forStyle(style)
                val showMap = style == DashboardStyle.PANORAMA || selected == DashboardMode.NAVIGATION
                assertEquals(showMap, layout.map != null)
                assertEquals(showMap, layout.navigation != null)
                assertEquals(selected == DashboardMode.MUSIC, layout.music != null)
                assertEquals(selected == DashboardMode.TIRES, layout.tires != null)
                if (style == DashboardStyle.MIDNIGHT) assertFalse(layout.music != null && layout.navigation != null)
                listOfNotNull(layout.map, layout.navigation, layout.music, layout.tires).forEach {
                    assertTrue(it.left >= 432 && it.right <= 1920 && it.top >= 0 && it.bottom <= if (style == DashboardStyle.MIDNIGHT) 460 else 472)
                }
            }
        }
    }
    @Test fun midnightRequestsWideMapEvenWhenMusicIsSelectedAndKeepsFullStreamVisible() {
        BoyueLSettings.select(app,true)
        NativeDashboardSettings.setStyle(app,DashboardStyle.MIDNIGHT)
        NativeDashboardSettings.setMode(app,DashboardMode.MUSIC)
        assertEquals(DashboardMode.MUSIC,NativeDashboardSettings.mode(app))
        val host=Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        try {
            val config=ReflectionHelpers.callInstanceMethod<com.shilapi.xcertplay.airplay.AirPlayDisplayConfig>(host,"clusterDisplayConfig")
            assertEquals(1408,config.widthPixels); assertEquals(400,config.heightPixels)
            val bounds=DashboardLayout.of(DashboardStyle.MIDNIGHT,DashboardMode.NAVIGATION).map!!
            assertEquals(bounds.width()/bounds.height(),MapMirrors.STREAM_ASPECT.toFloat(),0.001f)
            assertTrue(bounds.left>404f+60f) // Distinct gap after the left dial.
            assertTrue(bounds.right>1800f) // No right-hand RPM dial in this page.
            assertNotNull(config.initialUrl)
        } finally {
            for(name in listOf("teardownExecutor","airPlayCommandExecutor"))
                ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(host,name).shutdownNow()
        }
    }
    @Test fun rpmLabelsAndArcUseTheSameScaleAndMinorTicksAreTwoHundredRpm() {
        val dial=DashboardDial.RPM
        assertEquals((0..8000 step 1000).toList(),(0..dial.divisions step dial.majorEvery).map { dial.valueAtTick(it).toInt() })
        assertEquals(200.0,dial.valueAtTick(1),0.0)
        assertEquals(54f,dial.sweep(1600.0),0.0001f)
        assertEquals(189f,dial.angle(1600.0),0.0001f)
        assertEquals(371.25f,dial.angle(7000.0),0.0001f)
        assertEquals(405f,dial.angle(8000.0),0.0001f)
        assertEquals(270f,dial.sweep(9000.0),0.0f)
        assertEquals(0f,dial.sweep(-1.0),0.0f)
    }
    @Test fun ordinaryCruiseAndAdaptiveModesHaveIndependentStatesAndUnknownIsNeverActive() {
        fun state(mode:String?, acc:Number?, cruise:String?=null)=DashboardSnapshot(buildMap {
            mode?.let { put(VehicleSignal.ASSIST_MODE,VehicleReading(it,0,557849285)) }
            acc?.let { put(VehicleSignal.ACC_STATE,VehicleReading(it,0,557871508)) }
            cruise?.let { put(VehicleSignal.CRUISE_STATE,VehicleReading(it,0,557849289)) }
        }).assistance
        assertEquals(DashboardAssistMode.CRUISE,state("OFF",2,"ACTIVE").mode)
        assertTrue(state("OFF",null,"ACTIVE").active)
        assertFalse(state("OFF",null,"ACTIVE").showGap)
        assertEquals(DashboardAssistState.STANDBY,state("OFF",2,"STANDBY").state)
        assertFalse(state("OFF",2,"OFF").active)
        assertEquals(DashboardAssistMode.UNKNOWN,state("OFF",null,null).mode)
        assertFalse(state(null,2,"ACTIVE").active)
        assertEquals(DashboardAssistMode.ACC,state("ACC",1,"ACTIVE").mode)
        assertFalse(state("ACC",1,"ACTIVE").active)
        assertTrue(state("GPILOT",2).active)
        for(value in listOf(null,1,4,5,6,7,8,255,2.5)) assertFalse(state("GPILOT",value).active)
        assertEquals(DashboardAssistState.FAULT,state("GPILOT",7).state)
        assertEquals(DashboardAssistState.UNKNOWN,state("GPILOT",null).state)
        assertTrue(state("ACC",2).showGap)
    }
    @Test fun serviceRecreatesTheInstrumentWindowAfterDisplayPowerCycle() {
        val displayIds = mutableListOf<Int>()
        val manager = app.getSystemService(android.hardware.display.DisplayManager::class.java)
        while (manager.getDisplay(4) == null) {
            val id = org.robolectric.shadows.ShadowDisplayManager.addDisplay("w1920dp-h532dp-mdpi", 5)
            displayIds.add(id)
            check(id <= 4)
        }
        val display = manager.getDisplay(4)
        shadowOf(display).setFlags(android.view.Display.FLAG_PRESENTATION)
        shadowOf(display).setState(android.view.Display.STATE_ON)
        BoyueLSettings.select(app, true)
        NativeDashboardSettings.setStyle(app, DashboardStyle.PANORAMA)
        val controller = Robolectric.buildService(NativeDashboardService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(Intent(app, NativeDashboardService::class.java), 0, 1)
            shadowOf(Looper.getMainLooper()).idle()
            val first = ReflectionHelpers.getField<android.app.Presentation?>(service, "panel")
            assertNotNull(first)
            assertTrue(first!!.isShowing)
            shadowOf(display).setState(android.view.Display.STATE_OFF)
            service.onDisplayChanged(4)
            assertNull(ReflectionHelpers.getField<Any?>(service, "panel"))
            assertFalse(first.isShowing)
            shadowOf(display).setState(android.view.Display.STATE_ON)
            service.onDisplayChanged(4)
            val next = ReflectionHelpers.getField<android.app.Presentation?>(service, "panel")
            assertNotNull(next)
            assertNotSame(first, next)
            assertTrue(next!!.isShowing)
        } finally {
            controller.destroy()
            displayIds.forEach { org.robolectric.shadows.ShadowDisplayManager.removeDisplay(it) }
        }
    }
    @Test fun serviceWaitsForDisplayWithoutConnectingPhoneAndStopReleasesWheelOwner() {
        BoyueLSettings.select(app, true)
        NativeDashboardSettings.setStyle(app, DashboardStyle.MIDNIGHT)
        val controller = Robolectric.buildService(NativeDashboardService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(app, NativeDashboardService::class.java), 0, 1)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(ReflectionHelpers.getField(GeelyWheelKeys, "dashboardOwner"))
            assertTrue(NativeDashboardService.status.contains("Display 4"))
            assertNull(ReflectionHelpers.getField<Any?>(controller.get(), "panel"))
        } finally { controller.destroy() }
        assertFalse(ReflectionHelpers.getField(GeelyWheelKeys, "dashboardOwner"))
    }
}
