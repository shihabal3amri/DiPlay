package com.shilapi.xcertplay.media

import org.concentus.OpusApplication
import org.concentus.OpusEncoder
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SoftwareOpusDecoderTest {
    @Test fun decodesRealOpusAcrossFrameLengthsAndChannelCounts() {
        for (channels in 1..2) for (frames in listOf(120, 960, 2880)) {
            val encoder = OpusEncoder(48_000, channels, OpusApplication.OPUS_APPLICATION_AUDIO)
            val decoder = SoftwareOpusDecoder(48_000, channels)
            val output = ByteArray(5_760 * channels * 2)
            var peak = 0
            repeat(5) { frame ->
                val samples = ShortArray(frames * channels) { i ->
                    (12_000 * sin(2 * PI * 440 * (frame * frames + i / channels) / 48_000)).toInt().toShort()
                }
                val packet = ByteArray(4096)
                val length = encoder.encode(samples, 0, frames, packet, 0, packet.size)
                output.fill(0x55)
                val bytes = decoder.decode(packet.copyOf(length), output)
                assertEquals(frames * channels * 2, bytes)
                for (i in 0 until bytes step 2) {
                    val value = ((output[i].toInt() and 255) or (output[i + 1].toInt() shl 8)).toShort().toInt()
                    peak = maxOf(peak, kotlin.math.abs(value))
                }
                // Do not turn the unused end of a reusable buffer into audible trailing silence.
                assertEquals(0x55.toByte(), output[bytes])
            }
            assertTrue("decoded audio is non-silent: frames=$frames channels=$channels", peak > 1000)
            decoder.close()
            assertEquals(0, decoder.decode(byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte()), output))
        }
    }

    @Test fun handlesShortSilenceAndRejectsMalformedPacketWithoutEndingTheStream() {
        val decoder = SoftwareOpusDecoder(48_000, 1)
        val output = ByteArray(11_520)
        assertEquals(0, decoder.decode(byteArrayOf(), output))
        val silence = byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())
        assertEquals(1920, decoder.decode(silence, output))
        try {
            decoder.decode(byteArrayOf(3), output) // missing code-3 frame count
            fail("malformed packet accepted")
        } catch (_: org.concentus.OpusException) { }
        assertEquals(1920, decoder.decode(silence, output))
        decoder.close()
    }

    @Test fun decoderStateIsIndependentForEachAudioStream() {
        val packet = SoftwareOpusEncoder(48_000).use { encoder ->
            encoder.encode(ByteArray(1920) { if (it % 2 == 0) 40 else 10 }).single()
        }
        val first = SoftwareOpusDecoder(48_000, 1)
        val second = SoftwareOpusDecoder(48_000, 1)
        val a = ByteArray(11_520)
        val b = ByteArray(11_520)
        assertEquals(first.decode(packet, a), second.decode(packet, b))
        assertArrayEquals(a, b)
        first.close()
        assertEquals(1920, second.decode(packet, b))
        second.close()
    }
}
