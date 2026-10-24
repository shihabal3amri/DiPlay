package com.shilapi.xcertplay.vehicle

import android.os.Bundle
import com.shilapi.xcertplay.hud.ClusterTurnGuidance

internal object BoyueDimNavigationAdapter {
    fun convert(turn: ClusterTurnGuidance): NavigationInfo {
        val maneuver = when (turn.icon) {
            2, 4, 6, 8 -> NavigationInfo.Maneuver.LEFT
            3, 5, 7, 9 -> NavigationInfo.Maneuver.RIGHT
            10, 19 -> NavigationInfo.Maneuver.U_TURN
            12, 18 -> NavigationInfo.Maneuver.ROUNDABOUT
            15 -> NavigationInfo.Maneuver.ARRIVED
            else -> NavigationInfo.Maneuver.STRAIGHT
        }
        val seconds = turn.remainingSeconds?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0
        return NavigationInfo("carplay", "", turn.road, "", maneuver,
            turn.distanceMeters.coerceAtLeast(0),
            turn.remainingMeters?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0,
            (seconds.toLong() / 60).toInt(), 0, -1, 0, 0, 0, false, false,
            Double.NaN, Double.NaN, turn.icon, turn.roundaboutExit)
            .withDetails(NavigationDetails(Bundle().apply { putInt("remainingSeconds", seconds) }))
    }
}
