package com.shilapi.xcertplay.media

import java.io.Closeable

/** Worker-owned fallback for ROMs which advertise Opus but cannot create its MediaCodec. */
internal class SoftwareOpusDecoder(sampleRate: Int, private val channels: Int) : Closeable {
    init {
        // CarPlay Opus is rendered at 48 kHz, independently of its RTP timestamp clock.
        require(sampleRate == 48_000 && channels in 1..2)
    }

    private var decoder: org.concentus.OpusDecoder? = org.concentus.OpusDecoder(sampleRate, channels)
    private val samples = ShortArray(MAX_FRAME_SAMPLES * channels)

    /** Returns valid little-endian PCM bytes only; empty packets do not synthesize lost audio. */
    fun decode(packet: ByteArray, output: ByteArray): Int {
        val current = decoder ?: return 0
        if (packet.isEmpty()) return 0
        require(output.size >= samples.size * 2)
        val frames = current.decode(packet, 0, packet.size, samples, 0, MAX_FRAME_SAMPLES, false)
        check(frames in 0..MAX_FRAME_SAMPLES)
        val count = frames * channels
        for (index in 0 until count) {
            val value = samples[index].toInt()
            output[index * 2] = value.toByte()
            output[index * 2 + 1] = (value shr 8).toByte()
        }
        return count * 2
    }

    override fun close() { decoder = null }

    private companion object {
        // Opus permits up to 120 ms in one packet. Storage is reused for the whole stream.
        const val MAX_FRAME_SAMPLES = 5_760
    }
}
