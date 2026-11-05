package com.shilapi.xcertplay.media

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class CallLevelTrimTest {
    private val rate = 48_000

    private fun tone(amplitude: Int, channels: Int = 1, seconds: Double = 0.2): ByteArray {
        val frames = (rate * seconds).toInt()
        return ByteArray(frames * channels * 2).also { out ->
            for (i in 0 until frames) {
                val s = (amplitude * sin(2 * PI * 1_000 * i / rate)).toInt()
                for (c in 0 until channels) {
                    val at = (i * channels + c) * 2
                    out[at] = s.toByte(); out[at + 1] = (s shr 8).toByte()
                }
            }
        }
    }

    private fun rms(pcm: ByteArray): Double {
        var sum = 0.0
        val count = pcm.size / 2
        for (i in 0 until count) {
            val s = ((pcm[i * 2 + 1].toInt() shl 8) or (pcm[i * 2].toInt() and 0xff)).toDouble()
            sum += s * s
        }
        return sqrt(sum / count)
    }

    private fun changeDb(pcm: ByteArray, trim: CallLevelTrim): Double {
        val before = rms(pcm)
        trim.process(pcm, 0, pcm.size)
        return 20 * log10(rms(pcm) / before)
    }

    @Test fun theDefaultLowersTheCallBy18Decibels() = assertEquals(-18.0, changeDb(tone(12_000), CallLevelTrim()), 0.1)

    @Test fun anyGivenAmountIsApplied() = assertEquals(-6.0, changeDb(tone(12_000), CallLevelTrim(-6.0)), 0.1)

    @Test fun zeroDecibelsLeavesTheAudioUntouched() {
        val pcm = tone(12_000)
        val copy = pcm.copyOf()
        CallLevelTrim(0.0).process(pcm, 0, pcm.size)
        assertArrayEquals(copy, pcm)
    }

    @Test fun stereoChannelsAreBothLowered() = assertEquals(-18.0, changeDb(tone(12_000, channels = 2), CallLevelTrim()), 0.1)

    @Test fun fullScaleSamplesStayInRange() {
        val pcm = byteArrayOf(0xff.toByte(), 0x7f, 0x00, 0x80.toByte())
        CallLevelTrim(0.0).process(pcm, 0, pcm.size)
        assertEquals(Short.MAX_VALUE.toInt(), (pcm[1].toInt() shl 8) or (pcm[0].toInt() and 0xff))
        assertEquals(Short.MIN_VALUE.toInt(), (pcm[3].toInt() shl 8) or (pcm[2].toInt() and 0xff))
    }

    @Test fun onlyTheGivenRangeIsChanged() {
        val pcm = tone(12_000)
        val untouched = pcm.copyOfRange(0, 100)
        CallLevelTrim().process(pcm, 100, pcm.size - 100)
        assertArrayEquals(untouched, pcm.copyOfRange(0, 100))
    }

    @Test fun anOddTrailingByteIsLeftAlone() {
        val pcm = byteArrayOf(0x10, 0x27, 0x55)
        CallLevelTrim().process(pcm, 0, pcm.size)
        assertEquals(0x55.toByte(), pcm[2])
    }
}
