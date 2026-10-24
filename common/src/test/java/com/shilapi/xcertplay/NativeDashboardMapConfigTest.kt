package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class NativeDashboardMapConfigTest {
    private val styles = listOf(DashboardStyle.MIDNIGHT, DashboardStyle.PANORAMA,
        DashboardStyle.REPLICA, DashboardStyle.OEM_OVERLAY)

    @Test fun localMapSizeChangesPixelsImmediatelyWithoutMovingWindowOrRequiringNegotiation() {
        val app = RuntimeEnvironment.getApplication()
        for (style in styles) {
            NativeDashboardSettings.setStyle(app, style)
            val view = NativeDashboardView(app, preview = true)
            view.artworkView.style = style; view.artworkView.mode = DashboardMode.NAVIGATION
            var firstBounds: android.graphics.Rect? = null
            var previousScale = 0f
            for (size in DashboardMapSize.entries) {
                NativeDashboardSettings.setMapSize(app, size)
                view.measure(android.view.View.MeasureSpec.makeMeasureSpec(1920,1073741824),
                    android.view.View.MeasureSpec.makeMeasureSpec(532,1073741824))
                view.layout(0,0,1920,532)
                // Match the runtime tick without detaching or reconnecting the video surface.
                ReflectionHelpers.callInstanceMethod<Unit>(view,"updateMap")
                val texture = view.getChildAt(0) as android.view.TextureView
                val bounds = android.graphics.Rect(texture.left,texture.top,texture.right,texture.bottom)
                if (firstBounds == null) firstBounds = bounds else assertEquals(firstBounds,bounds)
                val matrix = texture.getTransform(android.graphics.Matrix())
                val values = FloatArray(9); matrix.getValues(values)
                assertEquals(size.renderScale,values[android.graphics.Matrix.MSCALE_X],0.001f)
                assertTrue(values[0] > previousScale); previousScale = values[0]
                val center = floatArrayOf(texture.width/2f,texture.height/2f)
                matrix.mapPoints(center)
                assertEquals(texture.width/2f,center[0],0.01f)
                assertEquals(texture.height/2f,center[1],0.01f)
                assertEquals(NativeDashboardMapConfig.config(style,DashboardMapSize.SMALL),NativeDashboardMapConfig.config(style,size))
            }
        }
    }

    @Test fun advertisedFocusMovesInsideVisibleMapWithoutChangingVideoOrPrimaryDisplay() {
        val main = AirPlayDisplayConfig(1920, 1080, safeArea = AirPlayInsets(left = 80))
        fun displays(cluster: AirPlayDisplayConfig) = (AirPlayInfoPlist.build(AirPlayConfig(
            deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:02",
            sourceVersion = "366.0", main = main, cluster = cluster))["displays"] as List<*>).map { it as Map<*, *> }
        for (style in styles) for (size in DashboardMapSize.entries) {
            val config = NativeDashboardMapConfig.config(style, size)
            val expectedCenter = when (style) { DashboardStyle.REPLICA -> 694; DashboardStyle.PANORAMA -> 556
                DashboardStyle.MIDNIGHT -> 704; else -> 480 }
            val safe = requireNotNull(config.safeArea)
            assertEquals(expectedCenter * 2, safe.left + config.widthPixels - safe.right)
            var lastCenter = -1
            for (percent in NativeDashboardMapConfig.horizontalPositions) {
                val shifted = NativeDashboardMapConfig.config(style, size, percent)
                assertEquals(config, shifted.copy(safeArea = safe))
                val entries = displays(shifted)
                assertEquals(displays(config).first { it["uuid"] == AirPlayInfoPlist.MAIN_UUID },
                    entries.first { it["uuid"] == AirPlayInfoPlist.MAIN_UUID })
                val view = ((entries.first { it["uuid"] == AirPlayInfoPlist.ALT_UUID }["viewAreas"] as List<*>).single() as Map<*, *>)
                assertEquals(config.widthPixels, view["widthPixels"])
                assertEquals(config.heightPixels, view["heightPixels"])
                assertEquals(0, view["originXPixels"])
                val focus = view["safeArea"] as Map<*, *>
                val x = focus["originXPixels"] as Int
                val w = focus["widthPixels"] as Int
                assertTrue(x >= 0 && w > 0 && x + w <= config.widthPixels)
                assertEquals(true, focus["drawUIOutsideSafeArea"])
                assertTrue(x * 2 + w > lastCenter); lastCenter = x * 2 + w
                if (style == DashboardStyle.REPLICA) {
                    assertTrue(x >= 282 && x + w <= config.widthPixels - 282)
                    assertEquals(160, focus["originYPixels"])
                    assertEquals(304, focus["heightPixels"])
                }
                if (style == DashboardStyle.PANORAMA) assertTrue(x >= 24 && x + w <= config.widthPixels - 400)
            }
            assertEquals(config, NativeDashboardMapConfig.config(style, size, -999))
        }
    }

    @Test fun calibrationIsPerStyleAndHostAdvertisesSavedPositionForEveryPage() {
        val app = RuntimeEnvironment.getApplication()
        val oldAspect = MapMirrors.streamAspect
        BoyueLSettings.select(app, true)
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        try {
            for ((index, style) in styles.withIndex()) {
                assertEquals(50, NativeDashboardSettings.mapHorizontalPosition(app, style))
                NativeDashboardSettings.setMapHorizontalPosition(app, 35 + index * 5, style)
            }
            for ((index, style) in styles.withIndex()) {
                NativeDashboardSettings.setStyle(app, style)
                assertEquals(35 + index * 5, NativeDashboardSettings.mapHorizontalPosition(app))
                for (mode in DashboardMode.choices(style)) {
                    NativeDashboardSettings.setMode(app, mode)
                    val actual = ReflectionHelpers.callInstanceMethod<AirPlayDisplayConfig>(host, "clusterDisplayConfig")
                    assertEquals(NativeDashboardMapConfig.config(style, NativeDashboardSettings.mapSize(app), 35 + index * 5), actual)
                }
            }
            app.getSharedPreferences("native_dashboard", 0).edit().putInt("map_horizontal_REPLICA", 100).commit()
            assertEquals(50, NativeDashboardSettings.mapHorizontalPosition(app, DashboardStyle.REPLICA))
        } finally {
            MapMirrors.streamAspect = oldAspect
            for (field in listOf("teardownExecutor", "airPlayCommandExecutor"))
                ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(host, field).shutdownNow()
        }
    }
}
