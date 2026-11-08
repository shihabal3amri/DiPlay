package com.shilapi.xcertplay.hud

/**
 * The paint of the car in the cluster's ADAS scene: [hue] in degrees, [saturation] and [brightness] in percent.
 *
 * Saturation 0 gives a gray, white or black according to [brightness], which scales the light and dark of BYD's own
 * picture (100 leaves it as it is). The recolor needs no per-model data: it picks out the body paint by its
 * brightness and low saturation, so it works on the car picture of every BYD model.
 */
data class AdasCarColor(val hue: Int, val saturation: Int, val brightness: Int) {
    init {
        require(hue in 0..359) { "hue $hue" }
        require(saturation in 0..100) { "saturation $saturation" }
        require(brightness in BRIGHTNESS_MIN..BRIGHTNESS_MAX) { "brightness $brightness" }
    }

    /** The saved form, `hue,saturation,brightness`. */
    fun toPreference(): String = "$hue,$saturation,$brightness"

    /** A color the picture's body paint is tinted towards, for swatches; not the exact pixels of the recolor. */
    class Preset(val key: String, val color: AdasCarColor)

    companion object {
        const val BRIGHTNESS_MIN = 30
        const val BRIGHTNESS_MAX = 150

        /** Reads [toPreference]; null for anything else, so a damaged value means BYD's own color. */
        fun fromPreference(value: String?): AdasCarColor? {
            val parts = value?.split(',') ?: return null
            if (parts.size != 3) return null
            val numbers = parts.map { it.trim().toIntOrNull() ?: return null }
            val (hue, saturation, brightness) = numbers
            if (hue !in 0..359 || saturation !in 0..100 || brightness !in BRIGHTNESS_MIN..BRIGHTNESS_MAX) return null
            return AdasCarColor(hue, saturation, brightness)
        }

        /** Ready-made colors, in the order the picker shows them after "Original". */
        val PRESETS: List<Preset> = listOf(
            Preset("white", AdasCarColor(0, 0, 135)),
            Preset("black", AdasCarColor(0, 0, 38)),
            Preset("gray", AdasCarColor(0, 0, 62)),
            Preset("red", AdasCarColor(355, 85, 100)),
            Preset("orange", AdasCarColor(24, 88, 100)),
            Preset("gold", AdasCarColor(45, 80, 100)),
            Preset("green", AdasCarColor(135, 62, 100)),
            Preset("teal", AdasCarColor(170, 65, 100)),
            Preset("blue", AdasCarColor(215, 80, 100)),
            Preset("purple", AdasCarColor(278, 62, 100)),
            Preset("pink", AdasCarColor(325, 55, 105)),
        )

        /** The preset [color] is, or null for a custom color. */
        fun presetOf(color: AdasCarColor?): Preset? = color?.let { chosen -> PRESETS.firstOrNull { it.color == chosen } }
    }
}
