package com.shilapi.xcertplay.airplay

/**
 * Physical screen width reported to the iPhone. A smaller reported screen requests larger controls;
 * iOS chooses the actual layout. This does not change the video canvas or touch coordinates.
 */
enum class CarPlaySize(val label: String, val widthMillimeters: Int) {
    LARGEST("Largest", 150),
    EXTRA_LARGE("Extra large", 200),
    LARGE("Large", 250),
    MEDIUM("Medium", 300),
    SMALL("Small", 350);

    companion object {
        val DEFAULT = MEDIUM

        /** Maps any stored width, including values from older builds, to the nearest preset. */
        fun fromWidthMillimeters(millimeters: Int): CarPlaySize =
            entries.minBy { kotlin.math.abs(it.widthMillimeters - millimeters) }
    }
}
