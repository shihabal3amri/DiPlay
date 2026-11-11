package com.shilapi.xcertplay

import android.os.Build

/**
 * Firmware builds whose cluster fission layers match the DiLink 5.1 layout in `common`.
 * Standalone HUD receiver compatibility is gated separately below.
 */
object DiLink51Firmware {
    /** The measured build: three 1920x720 fission layers. */
    const val FINGERPRINT = "BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260722.221155:user/release-keys"
    // Leopard 5 Ultra 2025, system 34.1.23.2604176.1: the same three 1920x720 fission layers, and its own
    // navigation switches between a full and a side map on the cluster (#301).
    const val LEOPARD5_FINGERPRINT = "BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260705.021352:user/release-keys"
    // Denza N9 2025, OTA V1.8: the same three fission layers, but 2560x720 (#301).
    const val DENZA_N9_FINGERPRINT = "BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260705.011226:user/release-keys"
    // BYD Tang L: a 2026-04-23 build on the same TP1A.220624.014 base, with the same three 1920x720
    // fission layers and the same verified receiver build 10601004 (0.2.21 diagnostic report).
    const val TANG_L_FINGERPRINT = "BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260423.233011:user/release-keys"

    /** Builds whose cluster layers/layout are known to match. */
    val FINGERPRINTS: Set<String> = setOf(FINGERPRINT, LEOPARD5_FINGERPRINT, DENZA_N9_FINGERPRINT, TANG_L_FINGERPRINT)

    /** Builds with a report or physical validation for the exact stock HUD receiver. */
    val HUD_RECEIVER_FINGERPRINTS: Set<String> = setOf(FINGERPRINT, TANG_L_FINGERPRINT)

    fun supported(fingerprint: String = Build.FINGERPRINT): Boolean = fingerprint in FINGERPRINTS

    fun hudReceiverSupported(fingerprint: String = Build.FINGERPRINT): Boolean =
        fingerprint in HUD_RECEIVER_FINGERPRINTS
}
