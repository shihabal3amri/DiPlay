package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** Separate route lifetime: DIM must clear on disconnect, even when a BYD overlay is retained. */
object GeelyDimNavigation {
    private val route = BydHudRouteState()
    @Synchronized fun onFrame(frame: Iap2Frame) {
        if (frame.messageId == BydHudRouteState.ROUTE_GUIDANCE_UPDATE ||
            frame.messageId == BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE) {
            route.accept(frame.messageId, frame.payload)
        }
    }
    @Synchronized fun clear() { route.clear() }
    @Synchronized fun snapshot(): ClusterTurnGuidance? = route.currentApple()?.let { apple ->
        ClusterTurnGuidance.from(BydClusterFrame.from(apple)).copy(
            arrivalEpochSeconds = apple.arrivalEpochSeconds,
            remainingSeconds = apple.remainingSeconds, remainingMeters = apple.remainingMeters)
    }
}
