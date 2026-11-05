package com.shilapi.xcertplay.media

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Lowers call audio by a fixed amount before it is played. Head units such as BYD's DiLink 3 play a
 * third-party call on their voice-call path, which sounds about 13 dB louder than music at the same volume
 * step, and a caller reaches it hotter than a reference speech level (a median of about -18 dBFS in 50 ms
 * windows on a real call), so the call stays too loud even at low volume steps. [DEFAULT_DB] brings a call to
 * the loudness of music at the same step on that car.
 */
internal class CallLevelTrim(decibels: Double = DEFAULT_DB) {
    private val gain = 10.0.pow(decibels / 20.0)

    /** Scales 16-bit little-endian PCM in place; works for any channel layout. */
    fun process(pcm: ByteArray, offset: Int, length: Int) {
        val end = offset + length
        var at = offset
        while (at + 1 < end) {
            val sample = (pcm[at + 1].toInt() shl 8) or (pcm[at].toInt() and 0xff)
            val scaled = (sample * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcm[at] = scaled.toByte()
            pcm[at + 1] = (scaled shr 8).toByte()
            at += 2
        }
    }

    companion object {
        const val DEFAULT_DB = -18.0
    }
}
