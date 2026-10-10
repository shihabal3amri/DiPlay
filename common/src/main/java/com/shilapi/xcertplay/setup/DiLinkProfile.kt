package com.shilapi.xcertplay.setup

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.host.R

/**
 * Controller-version-based DiLink hardware platform profiles,
 * as classified in the BYD DiLink Version Guide (DiLink 版本大全).
 */
enum class DiLinkProfile(val key: String) {
    DILINK_150("dilink150"),   // 31.x / 34.x (DiLink 5.1 / 6.0, BYD 9000, 8+ Gen1, 8295)
    DILINK_100("dilink100"),   // 23.x (DiLink 5.0, Snapdragon 778G / 782G)
    DILINK_50_4("dilink50_4"), // 16.x / 17.x / 21.x (DiLink 4.0, Snapdragon 690 / 665)
    DILINK_50_3("dilink50_3"), // 13.x / 15.x / 18.x (DiLink 3.0, Snapdragon 665)
    DILINK_20("dilink20"),     // 2.x / 4.x / 5.x / 8.x / 26.x (DiLink 2.0 / 2.1, MTK P35 / Snapdragon 625)
    GENERIC("generic");        // Non-BYD or unclassified

    fun titleRes(): Int = when (this) {
        DILINK_150 -> R.string.settings_dilink_150_title
        DILINK_100 -> R.string.settings_dilink_100_title
        DILINK_50_4 -> R.string.settings_dilink_50_4_title
        DILINK_50_3 -> R.string.settings_dilink_50_3_title
        DILINK_20 -> R.string.settings_dilink_20_title
        GENERIC -> R.string.settings_dilink_generic_title
    }

    companion object {
        private const val PREFS = "diplay"
        private const val KEY_OVERRIDE = "setup_dilink_profile_override"

        fun fromKey(key: String?): DiLinkProfile? = entries.firstOrNull { it.key == key }

        fun fromControllerVersion(version: String?): DiLinkProfile? {
            if (version.isNullOrBlank()) return null
            val trimmed = version.trim()
            val prefix = trimmed.substringBefore('.') + "."
            return when {
                prefix in listOf("31.", "34.") -> DILINK_150
                prefix == "23." -> DILINK_100
                prefix in listOf("16.", "17.", "21.") -> DILINK_50_4
                prefix in listOf("13.", "15.", "18.") -> DILINK_50_3
                prefix in listOf("2.", "4.", "5.", "8.", "26.") -> DILINK_20
                else -> null
            }
        }

        fun fromGeneration(generation: DiLinkGeneration): DiLinkProfile = when (generation) {
            DiLinkGeneration.DILINK_5 -> DILINK_100
            DiLinkGeneration.DILINK_4 -> DILINK_50_4
            DiLinkGeneration.DILINK_3 -> DILINK_50_3
            DiLinkGeneration.UNKNOWN -> GENERIC
        }

        fun toGeneration(profile: DiLinkProfile): DiLinkGeneration = when (profile) {
            DILINK_150, DILINK_100 -> DiLinkGeneration.DILINK_5
            DILINK_50_4 -> DiLinkGeneration.DILINK_4
            DILINK_50_3 -> DiLinkGeneration.DILINK_3
            DILINK_20, GENERIC -> DiLinkGeneration.UNKNOWN
        }

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun manualOverride(context: Context): DiLinkProfile? =
            fromKey(prefs(context).getString(KEY_OVERRIDE, null))

        fun setManualOverride(context: Context, profile: DiLinkProfile?) {
            if (profile == null) {
                prefs(context).edit().remove(KEY_OVERRIDE).apply()
            } else {
                prefs(context).edit().putString(KEY_OVERRIDE, profile.key).apply()
            }
        }

        fun detect(product: String?, fingerprint: String?, display: String?, sdkInt: Int): DiLinkProfile {
            // First check if controller version or DiLink generation is detectable
            val gen = DiLinkGeneration.detect(product, fingerprint, display, sdkInt)
            if (gen.generation == DiLinkGeneration.DILINK_5) {
                if (sdkInt >= 33 || fingerprint?.contains("IVI", ignoreCase = true) == true) {
                    return DILINK_150
                }
                return DILINK_100
            }
            return fromGeneration(gen.generation)
        }

        fun detect(): DiLinkProfile = detect(Build.PRODUCT, Build.FINGERPRINT, Build.DISPLAY, Build.VERSION.SDK_INT)

        fun buildDefault(context: Context): DiLinkProfile? {
            val key = runCatching { context.getString(R.string.build_dilink_profile) }.getOrNull()
            return if (!key.isNullOrBlank()) fromKey(key) else null
        }

        fun buildAutoSettings(context: Context): Boolean =
            runCatching { context.getString(R.string.build_dilink_auto_settings).toBoolean() }.getOrDefault(false)

        fun buildGeek(context: Context): Boolean =
            runCatching { context.getString(R.string.build_dilink_geek).toBoolean() }.getOrDefault(false)

        fun current(context: Context): DiLinkProfile =
            manualOverride(context)
                ?: buildDefault(context)
                ?: DiLinkGeneration.confirmed(context)?.let(::fromGeneration)
                ?: detect()
    }
}
