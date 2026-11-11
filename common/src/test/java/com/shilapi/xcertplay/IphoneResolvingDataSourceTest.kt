package com.shilapi.xcertplay

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import com.shilapi.xcertplay.airplay.VideoResourceStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@OptIn(UnstableApi::class)
class IphoneResolvingDataSourceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val forbidden = listOf("file:///sdcard/v.mp4", "content://media/1", "asset:///v.mp4",
        "rawresource:///123", "android.resource://app/raw/video", "skd://key", "/sdcard/v.mp4",
        "relative.mp4", "https", "FILE:///sdcard/v.mp4")

    @Test fun rejectsLocalAndSchemeLessPlaylistSourcesBeforeLoading() {
        val upstream = RecordingSource()
        val source = IphoneResolvingDataSource(upstream) { fail("Must not ask the iPhone for local resources"); null }
        for (url in forbidden) {
            assertThrows(url, IOException::class.java) { source.open(spec(url)) }
            source.close()
        }
        assertTrue(upstream.opened.isEmpty())
    }

    @Test fun remoteMediaCannotOpenAnAppPrivateFile() {
        val file = java.io.File(context.filesDir, "synthetic-private-media")
        file.writeText("synthetic private data")
        val source = IphoneResolvingDataSource(DefaultDataSource.Factory(context).createDataSource())
        try {
            assertThrows(IOException::class.java) { source.open(DataSpec(Uri.fromFile(file))) }
        } finally { source.close(); file.delete() }
    }

    @Test fun validatesEveryIphoneRedirectBeforeDelegating() {
        for (url in forbidden) {
            val upstream = RecordingSource()
            val source = IphoneResolvingDataSource(upstream) {
                CarPlayVideo.LoadedUrl(302, null, url)
            }
            assertThrows(url, IOException::class.java) { source.open(spec("appvideo://playlist")) }
            source.close()
            assertTrue(url, upstream.opened.isEmpty())
        }
    }

    @Test fun appRedirectsStayOnTheIphoneAndHttpRedirectsPreserveTheRange() {
        val requested = mutableListOf<String>()
        val upstream = RecordingSource()
        val source = IphoneResolvingDataSource(upstream) { url ->
            requested += url
            CarPlayVideo.LoadedUrl(302, null,
                if (url == "appvideo://first") "appvideo://second" else "https://example.com/video")
        }
        val range = DataSpec.Builder().setUri("appvideo://first").setPosition(7).setLength(12).build()
        source.open(range)
        assertEquals(listOf("appvideo://first", "appvideo://second"), requested)
        assertEquals("https://example.com/video", upstream.opened.single().uri.toString())
        assertEquals(7L, upstream.opened.single().position)
        assertEquals(12L, upstream.opened.single().length)
        source.close()
        assertEquals(1, upstream.closes)
    }

    @Test fun redirectLoopsAreBounded() {
        var requests = 0
        val source = IphoneResolvingDataSource(RecordingSource()) {
            requests++
            CarPlayVideo.LoadedUrl(302, null, "appvideo://loop")
        }
        assertThrows(IOException::class.java) { source.open(spec("appvideo://loop")) }
        assertEquals(6, requests)
    }

    @Test fun iphoneDataWithStatusZeroCanBeReadAndSeeked() {
        val source = IphoneResolvingDataSource(RecordingSource()) {
            CarPlayVideo.LoadedUrl(0, byteArrayOf(1, 2, 3, 4), null)
        }
        assertEquals(2L, source.open(DataSpec.Builder().setUri("appvideo://segment").setPosition(1).setLength(2).build()))
        val bytes = ByteArray(2)
        assertEquals(2, source.read(bytes, 0, 2))
        assertArrayEquals(byteArrayOf(2, 3), bytes)
        source.close()
    }

    @Test fun failedHttpOpenClosesTheSelectedDelegateAndAllowsRetry() {
        val upstream = RecordingSource().apply { failNextOpen = true }
        val delegate = DefaultDataSource.Factory(context, DataSource.Factory { upstream }).createDataSource()
        val source = IphoneResolvingDataSource(delegate)
        val request = spec("https://example.com/video")
        assertThrows(IOException::class.java) { source.open(request) }
        assertEquals(1, upstream.closes)
        source.close() // ExoPlayer also closes after a failed open.
        assertEquals(1L, source.open(request))
        source.close()
        assertEquals(2, upstream.closes)
    }

    @Test fun failedRedirectedHttpOpenAlsoAllowsRetry() {
        val upstream = RecordingSource().apply { failNextOpen = true }
        val delegate = DefaultDataSource.Factory(context, DataSource.Factory { upstream }).createDataSource()
        val source = IphoneResolvingDataSource(delegate) {
            CarPlayVideo.LoadedUrl(302, null, "https://example.com/video")
        }
        val request = spec("appvideo://video")
        assertThrows(IOException::class.java) { source.open(request) }
        assertEquals(1, upstream.closes)
        assertEquals(1L, source.open(request))
        source.close()
    }

    @Test fun iphoneModeLoadsPlaylistAndSegmentsWithoutOpeningTheReceiverNetwork() {
        val upstream = RecordingSource()
        val requested = mutableListOf<String>()
        val playlist = "http://192.0.2.1:50007/channel.m3u8"
        val source = IphoneResolvingDataSource(upstream, true) { url ->
            requested += url
            CarPlayVideo.LoadedUrl(200, if (url == playlist) "#EXTM3U\npart.ts".toByteArray() else byteArrayOf(4, 5), null)
        }
        source.open(spec(playlist))
        assertEquals(Uri.parse(playlist), source.uri)
        source.close()
        source.open(spec("http://192.0.2.1:50007/part.ts"))
        val bytes = ByteArray(2)
        assertEquals(2, source.read(bytes, 0, 2))
        assertArrayEquals(byteArrayOf(4, 5), bytes)
        source.close()
        assertEquals(2, requested.size)
        assertTrue(upstream.opened.isEmpty())
    }

    @Test fun iphoneModeKeepsRelativeAndAbsoluteHttpRedirectsOnTheIphone() {
        val upstream = RecordingSource()
        val requested = mutableListOf<String>()
        val source = IphoneResolvingDataSource(upstream, true) { url ->
            requested += url
            when (requested.size) {
                1 -> CarPlayVideo.LoadedUrl(302, null, "../media/part.ts")
                2 -> CarPlayVideo.LoadedUrl(302, null, "https://example.com/final.ts")
                else -> CarPlayVideo.LoadedUrl(200, byteArrayOf(0, 1, 2, 3), null,
                    mapOf("Content-Type" to listOf("video/mp2t")))
            }
        }
        assertEquals(4L, source.open(spec("http://example.com/live/start")))
        assertEquals(listOf("http://example.com/live/start", "http://example.com/media/part.ts",
            "https://example.com/final.ts"), requested)
        assertEquals(Uri.parse("https://example.com/final.ts"), source.uri)
        assertEquals(listOf("video/mp2t"), source.responseHeaders["Content-Type"])
        val bytes = ByteArray(4)
        assertEquals(4, source.read(bytes, 0, bytes.size))
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), bytes)
        source.close()
        assertTrue(source.responseHeaders.isEmpty())
        assertTrue(upstream.opened.isEmpty())
    }

    @Test fun iphoneModeRejectsByteRangesBeforeSendingARequest() {
        val upstream = RecordingSource()
        val source = IphoneResolvingDataSource(upstream, true) {
            throw AssertionError("A byte-range load must not ask the iPhone for the whole file")
        }
        val url = "https://example.com/segments.ts"
        for (request in listOf(
            DataSpec.Builder().setUri(url).setPosition(7).build(),
            DataSpec.Builder().setUri(url).setLength(12).build(),
            DataSpec.Builder().setUri(url).setHttpRequestHeaders(mapOf("rAnGe" to "bytes=7-12")).build(),
        )) {
            assertThrows(IOException::class.java) { source.open(request) }
        }
        assertTrue(upstream.opened.isEmpty())
    }

    @Test fun iphoneFailureDoesNotSilentlyFallbackToTheReceiver() {
        for (answer in listOf(null, CarPlayVideo.LoadedUrl(403, byteArrayOf(), null),
            CarPlayVideo.LoadedUrl(200, null, null), CarPlayVideo.LoadedUrl(206, byteArrayOf(1), null))) {
            val upstream = RecordingSource()
            val source = IphoneResolvingDataSource(upstream, true) { answer }
            assertThrows(IOException::class.java) { source.open(spec("http://example.com/live")) }
            source.close()
            assertTrue(upstream.opened.isEmpty())
        }
    }

    @Test fun iphoneModeStillRejectsLocalResourcesAndFairPlayAfterRedirects() {
        for (url in forbidden) {
            val upstream = RecordingSource()
            val source = IphoneResolvingDataSource(upstream, true) { CarPlayVideo.LoadedUrl(302, null, url) }
            // A relative URL is resolved against its remote parent; absolute local URLs remain forbidden.
            if (url.startsWith("/") || url == "relative.mp4" || url == "https") continue
            assertThrows(url, IOException::class.java) { source.open(spec("https://example.com/start")) }
            source.close()
            assertTrue(upstream.opened.isEmpty())
        }
    }

    @Test fun iphoneModeRejectsOversizedResourcesAndNonGetRequests() {
        val source = IphoneResolvingDataSource(RecordingSource(), true) {
            CarPlayVideo.LoadedUrl(200, ByteArray(32 * 1024 * 1024 + 1), null)
        }
        assertThrows(IOException::class.java) { source.open(spec("http://example.com/live")) }
        source.close()
        val post = IphoneResolvingDataSource(RecordingSource(), true) { fail("Must not send POST as GET"); null }
        assertThrows(IOException::class.java) {
            post.open(DataSpec.Builder().setUri("http://example.com/live").setHttpMethod(DataSpec.HTTP_METHOD_POST).build())
        }
    }

    @Test fun dataUrlsStayLocalEvenInIphoneMode() {
        val upstream = RecordingSource()
        val source = IphoneResolvingDataSource(upstream, true) { fail("Must not send data URLs to iPhone"); null }
        source.open(spec("data:text/plain,hello"))
        assertEquals(1, upstream.opened.size)
        source.close()
    }

    @Test fun legacyFileBackedIphoneResourcePreservesRemoteUriAndOffsetsAndDeletesOnClose() {
        val file = VideoResourceStore.shared(context.cacheDir).save(byteArrayOf(0, 1, 2, 3))
        val source = IphoneResolvingDataSource(RecordingSource()) {
            CarPlayVideo.LoadedUrl(200, null, null, file = file)
        }
        val remote = "appvideo://clip.mp4"
        assertEquals(2L, source.open(DataSpec.Builder().setUri(remote).setPosition(1).setLength(2).build()))
        assertEquals(Uri.parse(remote), source.uri)
        val bytes = ByteArray(2)
        assertEquals(2, source.read(bytes, 0, 2))
        assertArrayEquals(byteArrayOf(1, 2), bytes)
        source.close()
        assertFalse(file.file.exists())
    }

    @Test fun rejectedIphoneFileResponseDoesNotLeakItsCacheEntry() {
        val file = VideoResourceStore.shared(context.cacheDir).save(byteArrayOf(1, 2, 3))
        val source = IphoneResolvingDataSource(RecordingSource(), true) {
            CarPlayVideo.LoadedUrl(403, null, null, file = file)
        }
        assertThrows(IOException::class.java) { source.open(spec("https://example.com/clip.mp4")) }
        source.close()
        assertFalse(file.file.exists())
    }

    private fun spec(url: String) = DataSpec(Uri.parse(url))

    @Test fun directReceiverResourceAboveOneMiBDoesNotUseTheIphoneControlChannel() {
        val size = 2 * 1024 * 1024
        val source = IphoneResolvingDataSource(androidx.media3.datasource.ByteArrayDataSource(ByteArray(size)), false) {
            throw AssertionError("Direct receiver traffic must not use FCUP")
        }
        try {
            assertEquals(size.toLong(), source.open(spec("https://example.com/large-segment.ts")))
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count < 0) break
                total += count
            }
            assertEquals(size, total)
        } finally { source.close() }
    }

    @Test fun iphoneFetchUpdatesBandwidthEstimateUsingDeliveryTimeRatherThanFileReadTime() {
        val meter = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(context)
            .setInitialBitrateEstimate(1_000_000L).setResetOnNetworkTypeChange(false).build()
        val file = VideoResourceStore.shared(context.cacheDir).save(ByteArray(1_000_000))
        val source = IphoneResolvingDataSource(RecordingSource(), true) {
            CarPlayVideo.notifyUrlLoadStarted()
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1))
            CarPlayVideo.LoadedUrl(200, null, null, file = file)
        }
        source.addTransferListener(meter)
        try {
            source.open(spec("https://example.com/video.ts"))
            assertEquals(8_000_000L, meter.bitrateEstimate)
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(10))
            source.read(ByteArray(16), 0, 16)
            assertEquals(8_000_000L, meter.bitrateEstimate)
        } finally { source.close() }
        assertFalse(file.file.exists())
    }

    @Test fun queuedTimeDoesNotLowerTheMeasuredTransferBandwidth() {
        val meter = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(context)
            .setInitialBitrateEstimate(1_000_000L).setResetOnNetworkTypeChange(false).build()
        val source = IphoneResolvingDataSource(RecordingSource(), true) {
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(4))
            CarPlayVideo.notifyUrlLoadStarted()
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1))
            CarPlayVideo.LoadedUrl(200, ByteArray(1_000_000), null)
        }
        source.addTransferListener(meter)
        try {
            source.open(spec("https://example.com/segment.ts"))
            assertEquals(8_000_000L, meter.bitrateEstimate)
        } finally { source.close() }
    }

    @Test fun iphoneTransferCallbacksAreBalancedOnFailureAndNotRepeatedWhileReading() {
        val events = mutableListOf<String>()
        val listener = object : TransferListener {
            override fun onTransferInitializing(source: DataSource, spec: DataSpec, isNetwork: Boolean) { events += "init:$isNetwork" }
            override fun onTransferStart(source: DataSource, spec: DataSpec, isNetwork: Boolean) { events += "start:$isNetwork" }
            override fun onBytesTransferred(source: DataSource, spec: DataSpec, isNetwork: Boolean, bytes: Int) { events += "bytes:$bytes:$isNetwork" }
            override fun onTransferEnd(source: DataSource, spec: DataSpec, isNetwork: Boolean) { events += "end:$isNetwork" }
        }
        var fail = true
        val source = IphoneResolvingDataSource(RecordingSource(), true) {
            CarPlayVideo.notifyUrlLoadStarted()
            if (fail) throw IOException("test")
            CarPlayVideo.LoadedUrl(200, ByteArray(32), null)
        }
        source.addTransferListener(listener)
        source.addTransferListener(listener)
        assertThrows(IOException::class.java) { source.open(spec("https://example.com/video.ts")) }
        source.close()
        assertEquals(listOf("init:true", "start:true", "end:true"), events)
        fail = false
        events.clear()
        source.open(spec("https://example.com/video.ts"))
        source.read(ByteArray(32), 0, 32)
        source.close()
        assertEquals(listOf("init:true", "start:true", "bytes:32:true", "end:true"), events)
    }

    @Test fun factoryUsesItsBoundResolverAndClosingReturnsTheFileQuota() {
        val file = VideoResourceStore.shared(context.cacheDir).save(ByteArray(300_000))
        var requests = 0
        val factory = IphoneResolvingDataSource.Factory(DataSource.Factory { RecordingSource() }, true,
            resolver = { requests++; CarPlayVideo.LoadedUrl(200, null, null, file = file) })
        val source = factory.createDataSource()
        assertEquals(300_000L, source.open(spec("https://example.com/video.ts")))
        assertEquals(1, requests)
        source.close()
        source.close()
        assertFalse(file.file.exists())
    }

    private class RecordingSource : DataSource {
        val opened = mutableListOf<DataSpec>()
        var closes = 0
        var failNextOpen = false
        override fun addTransferListener(listener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long {
            opened += dataSpec
            if (failNextOpen) { failNextOpen = false; throw IOException("Transient opening failure") }
            return 1L
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = -1
        override fun getUri(): Uri? = opened.lastOrNull()?.uri
        override fun close() { closes++ }
    }
}
