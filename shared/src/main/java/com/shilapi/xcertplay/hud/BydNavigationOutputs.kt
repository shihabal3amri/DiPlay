package com.shilapi.xcertplay.hud

/** App-facing entry point for the BYD navigation outputs. */
object BydNavigationOutputs {
    /** Safe to call on every app start: ends guidance a force-stopped previous process left on the cluster. */
    fun initialize(context: android.content.Context) {
        runCatching { BydClusterBridge.initialize(context) }
    }

    /** Ends guidance on the cluster (which the HUD mirrors) and the HUD before the process may die. */
    fun endNow() {
        runCatching { BydClusterBridge.endNow() }
        runCatching { BydHudBridge.clearNow() }
    }
}
