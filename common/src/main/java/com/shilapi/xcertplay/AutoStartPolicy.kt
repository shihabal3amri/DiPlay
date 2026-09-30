package com.shilapi.xcertplay

/** Decides what an automatic start trigger should do. Pure logic, no Android dependencies. */
internal object AutoStartPolicy {
    enum class Trigger { BOOT, BLUETOOTH }

    sealed class Decision {
        /** Nothing to do; [reason] is for logs only. */
        data class Ignore(val reason: String) : Decision()

        /** Open DiPlay. */
        object Launch : Decision()

        /** Android will not let DiPlay open itself from the background; offer a notification instead. */
        object Notify : Decision()
    }

    data class State(
        val startOnBoot: Boolean,
        val startOnBluetooth: Boolean,
        val selectedPhoneAddress: String?,
        val sessionActive: Boolean,
        val canDrawOverlays: Boolean,
        val sdkInt: Int,
        val lastLaunchMillis: Long,
        val nowMillis: Long,
    )

    /** A reboot followed by the iPhone reconnecting counts as one start. */
    const val DEBOUNCE_MILLIS = 30_000L

    fun decide(trigger: Trigger, connectedAddress: String?, state: State): Decision {
        when (trigger) {
            Trigger.BOOT -> if (!state.startOnBoot) return Decision.Ignore("start on boot is off")
            Trigger.BLUETOOTH -> {
                if (!state.startOnBluetooth) return Decision.Ignore("start on Bluetooth is off")
                val selected = state.selectedPhoneAddress?.takeIf { it.isNotBlank() }
                    ?: return Decision.Ignore("no iPhone selected")
                if (connectedAddress == null || !connectedAddress.equals(selected, ignoreCase = true)) {
                    return Decision.Ignore("another Bluetooth device")
                }
            }
        }
        if (state.sessionActive) return Decision.Ignore("CarPlay session already running")
        val sinceLast = state.nowMillis - state.lastLaunchMillis
        if (sinceLast in 0 until DEBOUNCE_MILLIS) return Decision.Ignore("started ${sinceLast / 1000} s ago")
        if (state.sdkInt >= 29 && !state.canDrawOverlays) return Decision.Notify
        return Decision.Launch
    }
}
