package com.shilapi.xcertplay.media

import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.shadows.ShadowMediaCodec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE,
    shadows = [NavigationOpusFallbackTest.FailingCodec::class, NavigationOpusFallbackTest.PcmTrack::class])
class NavigationOpusFallbackTest {
    @Before fun reset() {
        FailingCodec.failure = "create"
        FailingCodec.releases = 0
        PcmTrack.bytes = 0
        PcmTrack.nonZero = false
    }

    @Test fun missingSystemDecoderPlaysActualOpusOnConfiguredNavigationChannel() {
        for (failure in listOf("create", "start")) {
            FailingCodec.failure = failure
            FailingCodec.releases = 0
            val reports = mutableListOf<String>()
            val renderer = renderer(AudioCodecKind.OPUS, "default", reports)
            try {
                call(renderer, "configureCodec", MediaFormat.MIMETYPE_AUDIO_OPUS)
                assertNotNull(field(renderer, "softwareOpusDecoder"))
                assertNull(field(renderer, "codec"))
                assertEquals(if (failure == "start") 1 else 0, FailingCodec.releases)
                call(renderer, "createTrack")
                assertEquals(AudioChannel.NAVIGATION, field(renderer, "mappedChannel"))
                assertEquals(2, field(renderer, "actualLegacyStreamType"))
                assertTrue(reports.any { it.contains("route=streamType=2") })
                SoftwareOpusEncoder(48_000).use { encoder ->
                    repeat(12) { index ->
                        val pcm = ByteArray(1920)
                        for (i in 0 until 960) {
                            val value = (10_000 * kotlin.math.sin(2 * Math.PI * 440 * (index * 960 + i) / 48_000)).toInt()
                            pcm[i * 2] = value.toByte(); pcm[i * 2 + 1] = (value shr 8).toByte()
                        }
                        packet(renderer, encoder.encode(pcm).single())
                    }
                }
                assertTrue(PcmTrack.bytes >= 12 * 1920)
                assertTrue(PcmTrack.nonZero)
                assertEquals(true, field(renderer, "playbackStarted"))
                assertEquals(12, field(renderer, "outputBuffers"))
                assertEquals(0, field(renderer, "decoderUnavailablePackets"))
                assertTrue(reports.any { it.contains("backend=software fallback=platform-startup-failed") })
                assertEquals(1, reports.count { it.contains("first decoded PCM") })
                // Bad packets are bounded diagnostics; later valid silence still reaches playback.
                repeat(8) { packet(renderer, byteArrayOf(3)) }
                assertEquals(3, reports.count { it.contains("software decode rejected") })
                packet(renderer, byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte()))
                assertEquals(13, field(renderer, "outputBuffers"))
            } finally {
                call(renderer, "release")
                assertNull(field(renderer, "softwareOpusDecoder"))
            }
        }
    }

    @Test fun aWorkingPlatformOpusDecoderStaysPreferred() {
        FailingCodec.failure = "none"
        val renderer = renderer(AudioCodecKind.OPUS, "default", mutableListOf())
        try {
            call(renderer, "configureCodec", MediaFormat.MIMETYPE_AUDIO_OPUS)
            assertNotNull(field(renderer, "codec"))
            assertNull(field(renderer, "softwareOpusDecoder"))
        } finally { call(renderer, "release") }
    }

    @Test fun aacFailureNeverUsesAnOpusDecoderAndMusicKeepsAutomaticRouting() {
        val renderer = renderer(AudioCodecKind.AAC_LC, "media", mutableListOf())
        try {
            call(renderer, "configureCodec", MediaFormat.MIMETYPE_AUDIO_AAC)
            assertNull(field(renderer, "softwareOpusDecoder"))
            call(renderer, "createTrack")
            assertEquals(AudioChannel.MEDIA, field(renderer, "mappedChannel"))
            assertNull(field(renderer, "actualLegacyStreamType"))
        } finally { call(renderer, "release") }
    }

    private fun renderer(codec: AudioCodecKind, audioType: String, reports: MutableList<String>): Any =
        Class.forName("com.shilapi.xcertplay.media.AudioRenderer").declaredConstructors
            .single { it.parameterCount == 13 }.apply { isAccessible = true }.newInstance(
                AudioFormat(codec, 48_000, 1, 101, audioType), false, false, 0, 2,
                AudioFocusCoordinator(null, false, false), 14, 300,
                { line: String -> reports.add(line); Unit }, 0L, false, null, false,
            )

    private fun field(renderer: Any, name: String): Any? = renderer.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(renderer)

    private fun call(renderer: Any, name: String, vararg args: Any) = renderer.javaClass.declaredMethods
        .single { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }
        .invoke(renderer, *args)

    private fun packet(renderer: Any, opus: ByteArray) {
        val packetType = Class.forName("com.shilapi.xcertplay.media.AudioRenderer\$AudioPacket")
        val packet = packetType.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(ByteArray(12) + opus, 0)
        call(renderer, "handle", packet)
    }

    @Implements(MediaCodec::class)
    class FailingCodec : ShadowMediaCodec() {
        @Implementation override fun __constructor__(name: String, type: Boolean, encoder: Boolean) {
            if (failure == "create") throw IllegalArgumentException("FX11 native_setup failure")
            super.__constructor__(name, type, encoder)
        }
        @Implementation override fun native_start() {
            if (failure == "start") throw IllegalStateException("start rejected")
            super.native_start()
        }
        @Implementation fun release() { releases++ }
        companion object { var failure = "create"; var releases = 0 }
    }

    @Implements(AudioTrack::class)
    class PcmTrack : ShadowAudioTrack() {
        @Implementation fun write(data: ByteArray, offset: Int, size: Int, mode: Int): Int {
            bytes += size
            if ((offset until offset + size).any { data[it] != 0.toByte() }) nonZero = true
            return size
        }
        companion object { var bytes = 0; var nonZero = false }
    }
}
