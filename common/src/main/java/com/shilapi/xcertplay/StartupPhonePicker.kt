package com.shilapi.xcertplay

/**
 * Decides what wireless CarPlay does when DiPlay opens and several iPhones are paired with the car,
 * for example two drivers who share it.
 *
 * It reads only what the car's Bluetooth reports at that moment and stores no names, so it works
 * for a new driver or a new owner without setup. The decision is kept free of Android types so it
 * can be unit tested.
 */
internal object StartupPhonePicker {
    /**
     * A device paired with the car. [isPhone] is null when the car reports no device class;
     * [connected] is true when the device is on the car's Bluetooth now.
     */
    data class Candidate(
        val address: String,
        val name: String?,
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

    fun decide(paired: List<Candidate>, lastUsedAddress: String?): Decision {
        // Headsets, OBD adapters and other paired devices that are not phones never join the choice.
        // A device without a class stays, and so does the iPhone the driver already chose.
        val phones = paired.filter { it.isPhone != false || it.address == lastUsedAddress }
        if (phones.size < 2) return Decision.Skip
        phones.singleOrNull { it.connected }?.let { return Decision.Connect(it.address) }
        val choices = phones.sortedWith(
            compareBy<Candidate>({ it.address != lastUsedAddress }, { it.name?.lowercase().orEmpty() }),
        )
        val fallback = lastUsedAddress?.takeIf { address -> phones.any { it.address == address } }
        return Decision.Ask(choices, fallback)
    }
}
