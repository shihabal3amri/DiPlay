package com.shilapi.xcertplay

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class IphoneVideoPlaybackPolicyTest {
    private fun info(error: IOException, count: Int, dataType: Int = C.DATA_TYPE_MEDIA, duration: Long = 0) =
        LoadErrorHandlingPolicy.LoadErrorInfo(
            LoadEventInfo.Builder(1, DataSpec(Uri.parse("https://example.invalid/video")), 0)
                .setLoadDurationMs(duration).build(), MediaLoadData(dataType), error, count)

    @Test fun pressureRetriesPromptlyButDoesNotBlacklistHighQualityTracksOrRetryForever() {
        val policy = IphoneVideoPlaybackPolicy()
        val options = LoadErrorHandlingPolicy.FallbackOptions(1, 0, 2, 0)
        val wrapped = IOException(IphoneVideoResourceRejectedException("memory_budget"))
        for (count in 1..3) {
            val error = info(wrapped, count)
            assertNull(policy.getFallbackSelectionFor(options, error))
            assertEquals(250L, policy.getRetryDelayMsFor(error))
        }
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(wrapped, 4)))
    }

    @Test fun ordinaryNetworkFailureBudgetsDoNotExpandWithCapacityRetries() {
        val policy = IphoneVideoPlaybackPolicy()
        val original = DefaultLoadErrorHandlingPolicy()
        for (type in listOf(C.DATA_TYPE_MEDIA, C.DATA_TYPE_MANIFEST, C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE)) {
            val previousMinimum = original.getMinimumLoadableRetryCount(type)
            for (count in 1..previousMinimum) {
                val error = info(IOException("network failure"), count, type)
                assertEquals(original.getRetryDelayMsFor(error), policy.getRetryDelayMsFor(error))
            }
            assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(IOException("network failure"), previousMinimum + 1, type)))
        }
    }

    @Test fun onlyQueueFullUsesBoundedBackoffAndNeverQualityFallback() {
        val policy = IphoneVideoPlaybackPolicy()
        val error = IOException(IphoneVideoResourceRejectedException("request_admission", 42, "queue_full"))
        val options = LoadErrorHandlingPolicy.FallbackOptions(1, 0, 2, 0)
        val delays = (1..8).map { count ->
            assertNull(policy.getFallbackSelectionFor(options, info(error, count)))
            policy.getRetryDelayMsFor(info(error, count))
        }
        assertEquals(listOf(250L, 500L, 1000L, 2000L, 2000L, 2000L, 2000L, 2000L), delays)
        assertEquals(8, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MANIFEST))
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(error, 9)))
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(error, 1, duration = 30_000)))
        assertEquals(100L, policy.getRetryDelayMsFor(info(error, 8, duration = 29_900)))
        for (reason in listOf("url_length", "queue_memory_budget", "wait_timeout", null)) {
            val other = IphoneVideoResourceRejectedException("request_admission", 42, reason)
            assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(other, 4)))
        }
    }
}
