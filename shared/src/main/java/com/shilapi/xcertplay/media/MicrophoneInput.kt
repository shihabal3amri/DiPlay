package com.shilapi.xcertplay.media

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord

/** Input source IDs are Android capture presets, not playback stream/channel numbers. */
object MicrophoneInput {
    const val AUTO = -1
    val sources = listOf(AUTO, 7, 1, 6, 0, 5, 9)
    fun sanitize(source: Int): Int = source.takeIf { it in sources } ?: AUTO
    fun resolve(source: Int, automatic: Int): Int = sanitize(source).let { if (it == AUTO) automatic else it }
    fun name(source: Int): String = when (source) {
        AUTO -> "AUTO"
        7 -> "VOICE_COMMUNICATION"
        1 -> "MIC"
        6 -> "VOICE_RECOGNITION"
        0 -> "DEFAULT"
        5 -> "CAMCORDER"
        9 -> "UNPROCESSED"
        else -> "UNKNOWN"
    }

    // Device IDs can change on reboot or USB reconnect. Match Carlink's stable device identity.
    fun key(device: AudioDeviceInfo): String = "${device.type}|${device.address}|${device.productName}"
    fun devices(manager: AudioManager?): List<AudioDeviceInfo> =
        manager?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.filter { it.isSource }.orEmpty()

    fun applyDevice(recorder: AudioRecord, manager: AudioManager?, key: String) {
        if (key.isEmpty()) return
        val device = devices(manager).firstOrNull { key(it) == key }
            ?: throw IllegalStateException("Selected microphone input unavailable")
        check(recorder.setPreferredDevice(device)) { "Selected microphone input rejected" }
    }
}
