package com.shilapi.xcertplay.media

import android.graphics.SurfaceTexture
import android.media.MediaCrypto
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaCodec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class VideoLifecycleSinkTest {
    private val config = byteArrayOf(1, 0x42, 0xC0.toByte(), 0x1E, 0xFF.toByte(), 0xE1.toByte(), 0, 4,
        0x67, 0x42, 0xC0.toByte(), 0x1E, 1, 0, 2, 0x68, 0xCE.toByte())
    private val frame = byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte(), 0x21)

    @Test fun closingWhileConfigureIsNativeBlockedRetainsOwnershipAndDoesNotPublishTheCandidate() {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val reports = CopyOnWriteArrayList<String>()
        val codec = object : ShadowMediaCodec.CodecConfig.Codec {
            override fun process(input: ByteBuffer, output: ByteBuffer) = Unit
            override fun onConfigured(format: MediaFormat?, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
                entered.countDown()
                var interrupted = false
                while (true) {
                    try { unblock.await(); break } catch (_: InterruptedException) { interrupted = true }
                }
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
        ShadowMediaCodec.addDecoder(MediaFormat.MIMETYPE_VIDEO_AVC, ShadowMediaCodec.CodecConfig(64 * 1024, 64 * 1024, codec))
        val texture = SurfaceTexture(0); val surface = Surface(texture)
        val sink = AndroidMediaSink(surface = surface, videoLifecycleGuardEnabled = true)
        sink.setVideoDiagnosticHandler(110) { reports += it }
        try {
            sink.onVideoConfig(110, config)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val workersField = sink.javaClass.getDeclaredField("videoDecoders").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val worker = (workersField.get(sink) as Map<Int, Any>).getValue(110)
            // Keep observing this worker after close clears the sink diagnostic-handler map.
            val report: (String) -> Unit = { reports += it }
            worker.javaClass.getDeclaredField("report").apply { isAccessible = true }.set(worker, report)
            sink.close()
            assertFalse("close cannot cancel an already blocked native configure", sink.awaitVideoReleased(30))
            assertNotNull(VideoCodecLifecycleGuard.process.blockedPhase())
            unblock.countDown()
            assertTrue(sink.awaitVideoReleased(5_000))
            assertNull(VideoCodecLifecycleGuard.process.blockedPhase())
            assertFalse("the closed candidate is never published as configured", reports.any { it.startsWith("decoder=") })
            assertTrue(reports.toString(), reports.any { it.contains("StartupAborted") })
        } finally {
            unblock.countDown(); sink.close(); sink.awaitVideoReleased(5_000)
            surface.release(); texture.release(); ShadowMediaCodec.clearCodecs()
        }
    }

    @Test fun busyLifecycleDefersAnotherDisplayWithoutRetiringTheHealthyMainDecoder() {
        val configured = CopyOnWriteArrayList<Surface?>()
        val decoded = CountDownLatch(2)
        val codec = object : ShadowMediaCodec.CodecConfig.Codec {
            override fun process(input: ByteBuffer, output: ByteBuffer) { decoded.countDown() }
            override fun onConfigured(format: MediaFormat?, surface: Surface?, crypto: MediaCrypto?, flags: Int) { configured += surface }
        }
        ShadowMediaCodec.addDecoder(MediaFormat.MIMETYPE_VIDEO_AVC, ShadowMediaCodec.CodecConfig(64 * 1024, 64 * 1024, codec))
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val sink = AndroidMediaSink(surface = surface, videoLifecycleGuardEnabled = true)
        val firstRendered = CountDownLatch(1)
        val deferred = CountDownLatch(1)
        val mainDeferred = CountDownLatch(1)
        sink.setVideoDiagnosticHandler(110) {
            if (it == "first frame rendered") firstRendered.countDown()
            if (it.startsWith("video lifecycle admission deferred")) mainDeferred.countDown()
        }
        sink.setVideoDiagnosticHandler(111) { if (it.startsWith("video lifecycle admission deferred")) deferred.countDown() }
        var held: VideoCodecLifecycleGuard.Lease? = null
        try {
            sink.onVideoConfig(110, config); sink.onVideoFrame(110, frame)
            assertTrue(firstRendered.await(5, TimeUnit.SECONDS))
            held = VideoCodecLifecycleGuard.process.startup("simulated-stalled-peer", { true })!!
            sink.onVideoConfig(111, config); sink.onVideoFrame(111, frame)
            assertTrue(deferred.await(5, TimeUnit.SECONDS))
            sink.onVideoFrame(110, frame)
            assertTrue("main frame feeding continues while another startup is denied", decoded.await(5, TimeUnit.SECONDS))
            assertEquals("no second codec factory was admitted", 1, configured.size)
            assertEquals(1, configured.count { it === surface })
            val workersField = sink.javaClass.getDeclaredField("videoDecoders").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val workers = workersField.get(sink) as Map<Int, Any>
            val worker = workers.getValue(110)
            val codecField = worker.javaClass.getDeclaredField("decoder").apply { isAccessible = true }
            val healthyCodec = codecField.get(worker)
            assertNotNull(healthyCodec)
            sink.onVideoConfig(110, config.copyOf().apply { this[3] = 0x1f })
            assertTrue(mainDeferred.await(5, TimeUnit.SECONDS))
            assertSame("deferred reconfiguration keeps the healthy codec until admission", healthyCodec, codecField.get(worker))
            assertEquals(1, configured.size)
        } finally {
            held?.close()
            sink.close()
            assertTrue(sink.awaitVideoReleased(5_000))
            surface.release(); texture.release(); ShadowMediaCodec.clearCodecs()
        }
    }
}
