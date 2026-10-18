package com.shilapi.xcertplay.media

import android.graphics.SurfaceTexture
import android.media.MediaCrypto
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaCodec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class VideoFirstFrameNotificationTest {
    @Test fun onlyTheCurrentDisplayDecoderCanReleaseItsWaitingCover() {
        val codec = object : ShadowMediaCodec.CodecConfig.Codec {
            override fun process(input: ByteBuffer, output: ByteBuffer) = Unit
            override fun onConfigured(format: MediaFormat?, surface: Surface?, crypto: MediaCrypto?, flags: Int) = Unit
        }
        ShadowMediaCodec.addDecoder(MediaFormat.MIMETYPE_VIDEO_AVC, ShadowMediaCodec.CodecConfig(64 * 1024, 64 * 1024, codec))
        val texture = SurfaceTexture(0); val mirrorTexture = SurfaceTexture(0)
        val surface = Surface(texture); val mirrorSurface = Surface(mirrorTexture)
        val callbacks = AtomicInteger()
        val tokens = java.util.concurrent.CopyOnWriteArrayList<Any>()
        val bothRendered = CountDownLatch(2)
        val sink = AndroidMediaSink(surface = surface, onScreenFirstFrame = { type, token ->
            assertEquals(110, type); tokens += token; callbacks.incrementAndGet()
        })
        try {
            sink.setVideoDiagnosticHandler(110) { if (it == "first frame rendered") bothRendered.countDown() }
            sink.setMirrorSurface(110, "test", mirrorSurface)
            sink.onVideoConfig(110, byteArrayOf(1, 0x42, 0xC0.toByte(), 0x1E, 0xFF.toByte(), 0xE1.toByte(), 0, 4,
                0x67, 0x42, 0xC0.toByte(), 0x1E, 1, 0, 2, 0x68, 0xCE.toByte()))
            sink.onVideoFrame(110, byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte(), 0x21))
            assertTrue("both display and mirror decode", bothRendered.await(5, TimeUnit.SECONDS))
            assertEquals("a mirror cannot release the display cover", 1, callbacks.get())
            val queuedToken = tokens.single()
            assertTrue(sink.isCurrentVideoFrame(110, queuedToken))
            val dismissals = AtomicInteger()
            val queuedUi = Runnable { if (sink.isCurrentVideoFrame(110, queuedToken)) dismissals.incrementAndGet() }
            assertTrue(sink.beginSurfaceDetach(surface, parkMain = false).await())
            assertFalse("releasing the codec invalidates the same worker output revision", sink.isCurrentVideoFrame(110, queuedToken))
            queuedUi.run()
            assertEquals(0, dismissals.get())
            // A callback can sit on the main queue while this same sink restarts stream 110.
            sink.onScreenStreamActive(110, false)
            sink.onScreenStreamActive(110, true)
            sink.onVideoConfig(110, byteArrayOf(1, 0x42, 0xC0.toByte(), 0x1E, 0xFF.toByte(), 0xE1.toByte(), 0, 4,
                0x67, 0x42, 0xC0.toByte(), 0x1E, 1, 0, 2, 0x68, 0xCE.toByte()))
            queuedUi.run()
            assertEquals("an old queued frame cannot hide a new stream waiting page", 0, dismissals.get())
        } finally {
            sink.close(); assertTrue(sink.awaitVideoReleased(5_000))
            surface.release(); mirrorSurface.release(); texture.release(); mirrorTexture.release()
            ShadowMediaCodec.clearCodecs()
        }
    }
}
