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

        /** The preset that sends exactly this width, or null for a length set in the CarPlay menu. */
        fun exact(millimeters: Int): CarPlaySize? = entries.firstOrNull { it.widthMillimeters == millimeters }
    }
}
