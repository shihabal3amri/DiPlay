package com.shilapi.xcertplay

import android.view.Display
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.vehicle.BoyueDimNavigationAdapter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BoyueLTest {
    @Test fun navigationIsNewDefaultAndUpgradeChangesItOnlyOnce() {
        val app = RuntimeEnvironment.getApplication()
        BoyueLSettings.select(app,true)
        for (style in listOf(DashboardStyle.MIDNIGHT,DashboardStyle.REPLICA,DashboardStyle.OEM_OVERLAY)) {
            NativeDashboardSettings.setStyle(app,style)
            assertEquals(DashboardMode.NAVIGATION,NativeDashboardSettings.mode(app))
            NativeDashboardSettings.setMode(app,DashboardMode.MUSIC)
        }
        BoyueLSettings.migrateDashboardControls(app)
        for (style in listOf(DashboardStyle.MIDNIGHT,DashboardStyle.REPLICA,DashboardStyle.OEM_OVERLAY)) {
            NativeDashboardSettings.setStyle(app,style)
            assertEquals(DashboardMode.NAVIGATION,NativeDashboardSettings.mode(app))
            NativeDashboardSettings.setMode(app,DashboardMode.MUSIC)
        }
        BoyueLSettings.migrateDashboardControls(app)
        assertEquals(DashboardMode.MUSIC,NativeDashboardSettings.mode(app))
    }
    @Test fun fx11WithoutCalibrationMigratesBothModeActionsAfterV36Upgrade() {
        val app = RuntimeEnvironment.getApplication()
        org.robolectric.shadows.ShadowBuild.setModel("FX11_LOW")
        val saved = app.getSharedPreferences("boyue_l", 0)
        saved.edit().putBoolean("selected", true).putBoolean("dashboard_controls_v36", true)
            .putString("key_200110", "CYCLE_DASHBOARD")
            .putString("long_key_200110", "TOGGLE_DASHBOARD").apply()
        BoyueLSettings.migrateDashboardControls(app)
        assertEquals(200400, NativeDashboardSettings.modeKey(app))
        assertEquals(BoyueWheelAction.CYCLE_DASHBOARD, BoyueLSettings.action(app, 200400))
        assertEquals(BoyueWheelAction.TOGGLE_DASHBOARD, BoyueLSettings.action(app, 200400, true))
        assertTrue(BoyueLSettings.configurableKeys(app).contains(200400))
        assertFalse(BoyueLSettings.configurableKeys(app).contains(200110))
        BoyueLSettings.setAction(app, 200400, BoyueWheelAction.NONE)
        BoyueLSettings.migrateDashboardControls(app)
        assertEquals(BoyueWheelAction.NONE, BoyueLSettings.action(app, 200400))
    }

    @Test fun fx11CorrectionPreservesCalibrationAndExplicitTargetActions() {
        val app = RuntimeEnvironment.getApplication()
        org.robolectric.shadows.ShadowBuild.setModel("FX11_LOW")
        val saved = app.getSharedPreferences("boyue_l", 0)
        saved.edit().putBoolean("selected", true).putString("key_200110", "CYCLE_DASHBOARD")
            .putString("key_200400", "NONE").apply()
        BoyueLSettings.migrateDashboardControls(app)
        assertEquals(BoyueWheelAction.NONE, BoyueLSettings.action(app, 200400))
        NativeDashboardSettings.setModeKey(app, 200600)
        BoyueLSettings.migrateDashboardControls(app)
        assertEquals(200600, NativeDashboardSettings.modeKey(app))
        BoyueLSettings.select(app, false)
        app.getSharedPreferences("native_dashboard", 0).edit().remove("mode_key").apply()
        assertEquals(200110, NativeDashboardSettings.modeKey(app))
        org.robolectric.shadows.ShadowBuild.setModel("Other Geely")
        BoyueLSettings.select(app, true)
        assertEquals(200110, NativeDashboardSettings.modeKey(app))
    }

    @Test fun removedExperimentsAreMigratedOnceAndDimFollowsStyleSelection() {
        val app = RuntimeEnvironment.getApplication()
        BoyueLSettings.select(app, true)
        BoyueLSettings.setMap(app, true); BoyueLSettings.setUltra(app, true); BoyueLSettings.setVehicleData(app, true)
        NativeDashboardSettings.setStyle(app, DashboardStyle.REPLICA)
        BoyueLSettings.migrateDashboardControls(app)
        assertFalse(BoyueLSettings.map(app)); assertFalse(BoyueLSettings.ultra(app)); assertFalse(BoyueLSettings.vehicleData(app))
        assertFalse(BoyueLSettings.dim(app)); assertTrue(BoyueLSettings.clusterEnabled(app))
        NativeDashboardSettings.setStyle(app, DashboardStyle.OFF)
        assertTrue(BoyueLSettings.dim(app)); assertFalse(BoyueLSettings.clusterEnabled(app))
        NativeDashboardSettings.setStyle(app, DashboardStyle.OEM_OVERLAY)
        assertTrue(BoyueLSettings.dim(app)); assertTrue(BoyueLSettings.clusterEnabled(app))
        NativeDashboardSettings.setStyle(app, DashboardStyle.PANORAMA)
        assertFalse(BoyueLSettings.dim(app))
        BoyueLSettings.setDim(app, true)
        BoyueLSettings.migrateDashboardControls(app)
        assertTrue("Migration must not overwrite a later confirmed factory mode", BoyueLSettings.dim(app))
    }

    @Test fun shortLongChoicesAreIndependentAndLegacyVoiceBindingRemainsLongOnly() {
        val app = RuntimeEnvironment.getApplication()
        val key = GeelyWheelKeyCodes.KEY_NEXT
        BoyueLSettings.setAction(app, key, BoyueWheelAction.CYCLE_DASHBOARD, true)
        assertEquals(BoyueWheelAction.NEXT, BoyueLSettings.action(app,key))
        assertEquals(BoyueWheelAction.CYCLE_DASHBOARD, BoyueLSettings.action(app,key,true))
        val voice = GeelyWheelKeyCodes.KEY_VOICE_ASSIST
        app.getSharedPreferences("boyue_l",0).edit().putString("key_$voice", "SIRI").apply()
        assertEquals(BoyueWheelAction.NONE, BoyueLSettings.action(app,voice))
        assertEquals(BoyueWheelAction.SIRI, BoyueLSettings.action(app,voice,true))
    }

    @Test fun vehicleDataIsIndependentOptInAndPreservesOtherPreferences() {
        val app = RuntimeEnvironment.getApplication()
        assertFalse(BoyueLSettings.vehicleData(app))
        BoyueLSettings.setVehicleData(app, true)
        assertFalse(BoyueLSettings.vehicleData(app))
        BoyueLSettings.select(app, true)
        assertTrue(BoyueLSettings.vehicleData(app))
        assertFalse(BoyueLSettings.ultra(app))
        assertFalse(BoyueLSettings.map(app))
        assertFalse(BoyueLSettings.dim(app))
        BoyueLSettings.select(app, false)
        assertFalse(BoyueLSettings.vehicleData(app))
        BoyueLSettings.select(app, true)
        assertTrue(BoyueLSettings.vehicleData(app))
    }

    @Test fun ultraRequiresExplicitVehicleSelectionAndPreservesMapChoice() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        BoyueLSettings.select(app, false)
        BoyueLSettings.setUltra(app, true)
        assertFalse(BoyueLSettings.ultra(app))
        BoyueLSettings.select(app, true)
        BoyueLSettings.setMap(app, false)
        assertTrue(BoyueLSettings.clusterEnabled(app))
        assertFalse(BoyueLSettings.map(app))
        BoyueLSettings.setUltra(app, false)
        assertFalse(BoyueLSettings.clusterEnabled(app))
    }

    @Test fun selectionDoesNotEnableDimOrMapAndDoesNotOverwriteBydSettings() {
        val app = RuntimeEnvironment.getApplication()
        AirPlayPersistence.saveClusterMapEnabled(app, true)
        BoyueLSettings.select(app, true)
        assertFalse(BoyueLSettings.dim(app))
        assertFalse(AirPlayPersistence.loadClusterMapEnabled(app))
        BoyueLSettings.setMap(app, true)
        assertTrue(AirPlayPersistence.loadClusterMapEnabled(app))
        BoyueLSettings.setMap(app, false)
        BoyueLSettings.select(app, false)
        assertTrue(AirPlayPersistence.loadClusterMapEnabled(app))
    }

    @Test fun onlyTheMeasuredPublicLiveFx11DisplayCanBeSelected() {
        val flags = Display.FLAG_PRESENTATION
        assertTrue(BoyueLSettings.acceptsDisplay(4, 1920, 532, true, true, flags))
        assertFalse(BoyueLSettings.acceptsDisplay(0, 1920, 532, true, true, flags))
        assertFalse(BoyueLSettings.acceptsDisplay(4, 1920, 720, true, true, flags))
        assertFalse(BoyueLSettings.acceptsDisplay(4, 1920, 532, false, true, flags))
        assertFalse(BoyueLSettings.acceptsDisplay(4, 1920, 532, true, false, flags))
        assertFalse(BoyueLSettings.acceptsDisplay(4, 1920, 532, true, true, 0))
        assertFalse(BoyueLSettings.acceptsDisplay(4, 1920, 532, true, true, flags or Display.FLAG_PRIVATE))
        assertEquals(532, BoyueLSettings.streamConfig().heightPixels)
        assertEquals(1920, BoyueLSettings.streamConfig().widthPixels)
    }

    @Test fun wheelMappingPersistsAndBluetoothNeverDuplicatesMediaKeys() {
        val app = RuntimeEnvironment.getApplication()
        BoyueLSettings.setAction(app, GeelyWheelKeyCodes.KEY_NEXT, BoyueWheelAction.SIRI)
        assertEquals(BoyueWheelAction.SIRI, BoyueLSettings.action(app, GeelyWheelKeyCodes.KEY_NEXT))
        assertTrue(BoyueWheelPolicy.accepts(GeelyWheelKeyCodes.KEY_NEXT, false, false))
        assertFalse(BoyueWheelPolicy.accepts(GeelyWheelKeyCodes.KEY_NEXT, true, false))
        assertFalse(BoyueWheelPolicy.accepts(GeelyWheelKeyCodes.KEY_NEXT, false, true))
        assertTrue(BoyueWheelPolicy.accepts(GeelyWheelKeyCodes.KEY_VOICE_ASSIST, true, true))
        assertFalse(BoyueWheelPolicy.accepts(GeelyWheelKeyCodes.KEY_VOICE_ASSIST, false, false))
        assertFalse(BoyueWheelPolicy.accepts(123, false, false))
    }

    @Test fun navigationKeepsOriginalIconExitAndSecondsInsteadOfGuessingSpeed() {
        val converted = BoyueDimNavigationAdapter.convert(ClusterTurnGuidance(12, 3, 85,
            "Test Road", remainingSeconds = 125, remainingMeters = 2345))
        assertEquals(12, converted.sourceTurnIconId)
        assertEquals(3, converted.roundaboutExit)
        assertEquals(125, converted.remainingSeconds())
        assertEquals(2345, converted.routeRemainingMeters)
        assertEquals("Test Road", converted.nextRoadName)
        assertEquals(-1, converted.currentSpeedKmh)
        assertTrue(converted.latitude.isNaN())
    }
}
