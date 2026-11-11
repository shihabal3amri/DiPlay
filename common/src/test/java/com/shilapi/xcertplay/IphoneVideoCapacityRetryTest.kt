package com.shilapi.xcertplay

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.Loader
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class IphoneVideoCapacityRetryTest {
    private val spec = DataSpec(Uri.parse("https://example.invalid/video"))
    private fun pumpUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        assertTrue("Loader callback did not arrive", condition())
    }

    private class Resource : Loader.Loadable {
        val attempts = AtomicInteger()
        override fun cancelLoad() = Unit
        override fun load() {
            if (attempts.incrementAndGet() <= 4) {
                throw IphoneVideoResourceRejectedException("request_admission", 17, "queue_full")
            }
        }
    }

    private inner class Callback(private val policy: IphoneVideoPlaybackPolicy) : Loader.Callback<Resource> {
        var failures = 0
        var completed = false
        var cancelled = false
        var delay = 0L
        override fun onLoadCompleted(value: Resource, elapsed: Long, duration: Long) { completed = true }
        override fun onLoadCanceled(value: Resource, elapsed: Long, duration: Long, released: Boolean) { cancelled = true }
        override fun onLoadError(value: Resource, elapsed: Long, duration: Long, error: IOException, count: Int): Loader.LoadErrorAction {
            failures = count
            val event = LoadEventInfo.Builder(1, spec, elapsed).setLoadDurationMs(duration).build()
            delay = policy.getRetryDelayMsFor(LoadErrorHandlingPolicy.LoadErrorInfo(
                event, MediaLoadData(C.DATA_TYPE_MANIFEST), error, count))
            return if (delay == C.TIME_UNSET) Loader.DONT_RETRY_FATAL else Loader.createRetryAction(false, delay)
        }
    }

    @Test fun aRealMedia3LoaderSurvivesTheFourthCapacityFailureAndLoadsWhenCapacityReturns() {
        val policy = IphoneVideoPlaybackPolicy()
        val loader = Loader("capacity-recovery-test")
        val resource = Resource()
        val callback = Callback(policy)
        try {
            loader.startLoading(resource, callback, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MANIFEST))
            for (attempt in 1..4) {
                pumpUntil { callback.failures == attempt }
                assertFalse("Capacity failure $attempt became fatal", loader.hasFatalError())
                loader.maybeThrowError()
                assertTrue(callback.delay >= 0)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(callback.delay))
            }
            pumpUntil { callback.completed }
            assertEquals(5, resource.attempts.get())
            assertFalse(loader.hasFatalError())
        } finally { loader.release(); shadowOf(Looper.getMainLooper()).idle() }
    }

    @Test fun cancellingDuringCapacityBackoffDoesNotRestartTheOldLoad() {
        val policy = IphoneVideoPlaybackPolicy()
        val loader = Loader("capacity-cancel-test")
        val resource = Resource()
        val callback = Callback(policy)
        try {
            loader.startLoading(resource, callback, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MANIFEST))
            pumpUntil { callback.failures == 1 }
            loader.cancelLoading()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
            assertEquals(1, resource.attempts.get())
            assertTrue(callback.cancelled)
            assertFalse(callback.completed)
        } finally { loader.release(); shadowOf(Looper.getMainLooper()).idle() }
    }
}
