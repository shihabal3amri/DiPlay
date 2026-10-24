package com.shilapi.xcertplay

/** Explicit display/menu/media constants from the FX11 ECARX KeyCode SDK, not physical-key guesses. */
internal object DashboardWheelKeyPolicy {
    // Excludes MODE, ADAS controls, arrows, volume/gap rollers and unrecognised codes.
    val learningCandidates: List<Int> = listOf(
        200082, // KEYCODE_R_MENU
        200600, // KEYCODE_R_MULTI_FUNCTION
        300050, // KEYCODE_R_DIM_MAP_FULL_SCREEN; observe only until the physical key is confirmed.
        200003, // KEYCODE_R_HOME
        200004, // KEYCODE_R_BACK
        200176, // KEYCODE_R_SETTINGS
        210003, // KEYCODE_R_MEDIA
        210001, // KEYCODE_R_NAVI
        210004, // KEYCODE_R_SRC
        210002, // KEYCODE_R_RADIO
        200400, // KEYCODE_R_WECHAT
        GeelyWheelKeyCodes.KEY_PLAY_PAUSE,
        GeelyWheelKeyCodes.KEY_NEXT,
        GeelyWheelKeyCodes.KEY_PREVIOUS,
    )
    fun isModeKeyAllowed(code: Int) = code == NativeDashboardSettings.MODE_KEY || code in learningCandidates
    fun isPageKeyAllowed(code: Int, modeKey: Int = NativeDashboardSettings.MODE_KEY) =
        code in learningCandidates && code != modeKey
    fun passivelyObservesCandidates(dashboardOwner: Boolean, pageKey: Int, learning: Boolean) =
        dashboardOwner && pageKey == 0 && !learning
    fun registeredCodes(mediaOwner: Boolean, dashboardOwner: Boolean, bluetoothAudio: Boolean,
                        pageKey: Int, learning: Boolean,
                        modeKey: Int = NativeDashboardSettings.MODE_KEY,
                        menuPaging: Boolean = false): IntArray {
        val confirmedMode = modeKey.takeIf(::isModeKeyAllowed) ?: NativeDashboardSettings.MODE_KEY
        if (learning) return learningCandidates.filter { isPageKeyAllowed(it, confirmedMode) }.toIntArray()
        val codes = linkedSetOf<Int>()
        if (mediaOwner) codes.addAll(GeelyWheelKeyCodes.registeredCodes(bluetoothAudio).toList())
        if (dashboardOwner) {
            codes.add(confirmedMode)
            if (isPageKeyAllowed(pageKey, confirmedMode)) codes.add(pageKey)
            else if (pageKey == 0) codes.addAll(learningCandidates.filter { isPageKeyAllowed(it, confirmedMode) })
            // Observe track keys offline and with Bluetooth audio. Media forwarding still uses its
            // existing Bluetooth exclusion; no OEM interception is requested.
            if (menuPaging) codes.addAll(Fx11MenuWheelPolicy.trackKeys)
        }
        return codes.toIntArray()
    }
}

/** Debounce per key: a nearby MODE press must not swallow a page press. */
internal class DashboardWheelDebounce(private val duplicateMillis: Long = 350) {
    private val lastClicks = mutableMapOf<Int, Long>()
    fun accept(code: Int, longPress: Boolean, now: Long): Boolean {
        if (longPress) return false
        if (lastClicks[code]?.let { now - it in 0 until duplicateMillis } == true) return false
        lastClicks[code] = now
        return true
    }
    fun reset() { lastClicks.clear() }
}
