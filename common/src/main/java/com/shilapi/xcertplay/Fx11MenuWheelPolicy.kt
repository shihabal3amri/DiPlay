package com.shilapi.xcertplay

/** DIM menu feedback, never inferred from MENU press parity or the current audio volume. */
internal enum class Fx11WheelMode { UNKNOWN, MEDIA, DASHBOARD, PHONE }

internal object Fx11MenuWheelPolicy {
    const val MENU_KEY = 200082
    val trackKeys = listOf(GeelyWheelKeyCodes.KEY_NEXT, GeelyWheelKeyCodes.KEY_PREVIOUS)

    // IDimMenuInteraction.DimMenuTab. This is a menu state, not getControlIndex(),
    // which the captured FX11 InputService implements as phone=3, otherwise=2.
    fun modeForTab(tab: Int): Fx11WheelMode = when (tab) {
        0, 2, 3, 4 -> Fx11WheelMode.DASHBOARD
        1 -> Fx11WheelMode.PHONE
        5 -> Fx11WheelMode.MEDIA
        else -> Fx11WheelMode.UNKNOWN
    }

    fun direction(key: Int): Int = when (key) {
        GeelyWheelKeyCodes.KEY_NEXT -> 1
        GeelyWheelKeyCodes.KEY_PREVIOUS -> -1
        else -> 0
    }
}
