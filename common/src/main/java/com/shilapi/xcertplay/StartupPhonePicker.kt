package com.shilapi.xcertplay

/**
 * Decides what wireless CarPlay does when DiPlay connects and several iPhones are paired with the
 * car, for example two drivers who share it. Off unless the driver turns on Ask which iPhone.
 *
 * It reads only what the car's Bluetooth reports at that moment and stores no names, so it works
 * for a new driver or a new owner without setup. The decision is kept free of Android types so it
 * can be unit tested.
 */
internal object StartupPhonePicker {
    /** The iAP2 service an iPhone offers over Bluetooth; `CarPlayController` opens it over RFCOMM. */
    const val IAP2_IPHONE_UUID = "00000000-deca-fade-deca-deafdecacafe"

    /**
     * A device paired with the car. [uuids] are the services the car cached from the device, null or
     * empty when it has none yet. [isPhone] is null when the car reports no device class.
     * [connected] is true when the device is on the car's Bluetooth now.
     */
    data class Candidate(
        val address: String,
        val name: String?,
        val uuids: List<String>?,
        val isPhone: Boolean?,
        val connected: Boolean,
    )

    sealed interface Decision {
        /** Fewer than two iPhones are paired: DiPlay keeps its usual flow. */
        data object Skip : Decision

        /** Exactly one of the iPhones is on the car's Bluetooth: its driver is in the car. */
        data class Connect(val address: String) : Decision

        /**
         * Ask the driver. [choices] lists the last iPhone used first. After the countdown DiPlay
         * connects [fallbackAddress]; it is null for a driver who has not chosen an iPhone yet.
         */
        data class Ask(val choices: List<Candidate>, val fallbackAddress: String?) : Decision
    }

    /** What the question last did since the app started, for the diagnostic report. */
    enum class Outcome(val report: String) {
        SKIPPED("skipped"),
        CONNECTED_ONLY_IPHONE_ON_BLUETOOTH("connected_only_iphone_on_bluetooth"),
        ASKED("asked"),
        TAPPED("tapped"),
        COUNTDOWN("countdown"),
        CANCELLED("cancelled"),
        COVERED_CONNECTED_LAST_USED("covered_connected_last_used"),
        COVERED_DROPPED("covered_dropped"),
    }

    @Volatile var lastOutcome: Outcome? = null
    /** Whether the hidden `BluetoothDevice.isConnected` answered at the last decision; null before one. */
    @Volatile var connectionStateReadable: Boolean? = null

    /** An iPhone offers the iAP2 service. Only a device whose services are not cached yet falls back to its class. */
    fun isIphone(candidate: Candidate, lastUsedAddress: String?): Boolean {
        val uuids = candidate.uuids
        if (!uuids.isNullOrEmpty()) return uuids.any { it.equals(IAP2_IPHONE_UUID, ignoreCase = true) }
        // Headsets, OBD adapters and other devices that are not phones never join the choice.
        // A device without a class stays, and so does the iPhone the driver already chose.
        return candidate.isPhone != false || candidate.address == lastUsedAddress
    }

    fun decide(paired: List<Candidate>, lastUsedAddress: String?): Decision {
        val phones = paired.filter { isIphone(it, lastUsedAddress) }
        if (phones.size < 2) return Decision.Skip
        phones.singleOrNull { it.connected }?.let { return Decision.Connect(it.address) }
        val choices = phones.sortedWith(
            compareBy<Candidate>({ it.address != lastUsedAddress }, { it.name?.lowercase().orEmpty() }),
        )
        val fallback = lastUsedAddress?.takeIf { address -> phones.any { it.address == address } }
        return Decision.Ask(choices, fallback)
    }

    /** One diagnostic report line. It holds no device names or addresses. */
    fun diagnosticLine(enabled: Boolean): String =
        "Startup iPhone question: ask_which_iphone=$enabled; since app start: " +
            "last=${lastOutcome?.report ?: "none"} " +
            "isConnectedReadable=${connectionStateReadable?.toString() ?: "unknown"}"
}
