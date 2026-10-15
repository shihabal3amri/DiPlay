package com.shilapi.xcertplay.vehicle

import android.media.AudioAttributes
import android.media.MediaRecorder
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Reads the vendor's factory CarPlay configuration from `/vendor/etc/carplay`.
 *
 * On Geely/Lynk and Meizu Flyme automotive head units this directory contains a JSON
 * description of the OEM identity, CarPlay icons and audio routing. When it exists,
 * the values fill in the experimental accessory identity without requiring a separately
 * provisioned MFi certificate.
 */
object GeelyFactoryCarPlay {
    private const val CONFIG_FILE = "/vendor/etc/carplay/carplay.json"

    /** True when the head unit ships its own CarPlay configuration. */
    val available: Boolean by lazy { load() != null }

    val manufacturer: String? by lazy { load()?.optJSONObject("ManufacturerInfo")?.optString("Manufacturer")?.takeIf { it.isNotBlank() } }
    val model: String? by lazy { load()?.optJSONObject("ManufacturerInfo")?.optString("Model")?.takeIf { it.isNotBlank() } }
    val iconLabel: String? by lazy { load()?.optJSONObject("OemIconInfo")?.optString("OemIconLabel")?.takeIf { it.isNotBlank() } }

    /**
     * Resolves a vendor-defined `AudioAttributes` usage constant (`AUDIO_USAGE_CP_*`) for
     * the given CarPlay audio channel. Returns [fallback] when no vendor constant exists.
     */
    fun audioUsage(kind: String, fallback: Int): Int {
        val key = "AUDIO_USAGE_CP_$kind"
        val frameworkValue = try {
            AudioAttributes::class.java.getField(key).getInt(null)
        } catch (_: ReflectiveOperationException) {
            null
        }
        if (frameworkValue != null && frameworkValue > 0) return frameworkValue
        val configValue = load()
            ?.optJSONObject("AudioAttrs")
            ?.optJSONObject("AudioUsage")
            ?.optInt(key, -1)
        if (configValue != null && configValue > 0 && (kind == "MEDIA" || configValue != 1)) return configValue
        return fallback
    }

    /**
     * Resolves a vendor-defined `MediaRecorder.AudioSource` constant (`AUDIO_SOURCE_*CP_*`)
     * for the given microphone type. Returns null when the standard source should be used.
     */
    fun microphoneSource(audioType: String, wireless: Boolean): Int? {
        val prefix = if (wireless) "WIRELESS_" else ""
        val category = when (audioType.lowercase(Locale.ROOT)) {
            "speechrecognition" -> "SIRI"
            "facetime" -> "FACETIME"
            "telephony" -> "TELEPHONY"
            else -> return null
        }
        val key = "AUDIO_SOURCE_${prefix}CP_$category"
        val frameworkValue = try {
            MediaRecorder.AudioSource::class.java.getField(key).getInt(null)
        } catch (_: ReflectiveOperationException) {
            null
        }
        if (frameworkValue != null && frameworkValue > 1) return frameworkValue
        return load()
            ?.optJSONObject("AudioSource")
            ?.optInt(key, -1)
            ?.takeIf { it > 1 }
    }

    private fun load(): JSONObject? {
        val file = File(CONFIG_FILE)
        if (!file.isFile) return null
        return try { JSONObject(file.readText()) } catch (_: Exception) { null }
    }
}
