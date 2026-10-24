package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import kotlin.math.roundToInt

/** Request the camera focus inside the unobscured map, without translating or cropping its video.
 * This is a safe-area request, not a command to set the map app's camera; iPhone behavior needs
 * vehicle verification, particularly in route overview mode. Main CarPlay is never changed here.
 */
internal object NativeDashboardMapConfig {
    val horizontalPositions = (35..65 step 5).toList()

    fun config(style: DashboardStyle, size: DashboardMapSize, horizontalPercent: Int = 50): AirPlayDisplayConfig {
        val (width, height) = when (style) {
            DashboardStyle.OEM_OVERLAY -> 960 to 400
            DashboardStyle.MIDNIGHT -> DashboardLayout.MIDNIGHT_MAP_WIDTH to DashboardLayout.MIDNIGHT_MAP_HEIGHT
            DashboardStyle.REPLICA -> DashboardLayout.REPLICA_MAP_WIDTH to DashboardLayout.REPLICA_MAP_HEIGHT
            else -> DashboardLayout.PANORAMA_MAP_WIDTH to DashboardLayout.PANORAMA_MAP_HEIGHT
        }
        val unobscured = when (style) {
            DashboardStyle.PANORAMA -> AirPlayInsets(top = 136, bottom = 24, left = 24, right = 400)
            DashboardStyle.REPLICA -> AirPlayInsets(top = DashboardLayout.REPLICA_SAFE_TOP,
                bottom = DashboardLayout.REPLICA_SAFE_BOTTOM, left = DashboardLayout.REPLICA_SAFE_SIDE,
                right = DashboardLayout.REPLICA_SAFE_SIDE)
            else -> AirPlayInsets()
        }
        val clearWidth = width - unobscured.left - unobscured.right
        val percent = horizontalPercent.takeIf { it in horizontalPositions } ?: 50
        val center = (unobscured.left + clearWidth * percent / 100.0).roundToInt()
        // A narrower focus band discourages placement against a gauge. Keep draw-outside enabled
        // so roads still fill the original full window. These proportions are a calibration default.
        val halfWidth = minOf(clearWidth * 0.2, width * 0.1).roundToInt().coerceAtLeast(1)
        return AirPlayDisplayConfig(width, height, widthPhysicalMm = size.widthMm,
            fps = 30, primaryInputDevice = 0, features = 0, initialUrl = CarPlayClusterDisplay.MAP_URL,
            safeArea = unobscured.copy(left = center - halfWidth, right = width - center - halfWidth),
            safeAreaDrawOutside = true)
    }
}
