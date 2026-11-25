package com.shilapi.xcertplay

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.IOException

internal class IphoneVideoResourceRejectedException(val reason: String, val requestId: Long? = null, val admissionReason: String? = null) : IOException("iPhone video resource rejected: $reason")

/** Keep sample buffers separate from the unchanged control-body admission budget. */
@OptIn(UnstableApi::class)
internal class IphoneVideoPlaybackPolicy(private val diagnostic: (String) -> Unit = {}) : DefaultLoadErrorHandlingPolicy() {
    override fun getFallbackSelectionFor(options: LoadErrorHandlingPolicy.FallbackOptions,
        info: LoadErrorHandlingPolicy.LoadErrorInfo): LoadErrorHandlingPolicy.FallbackSelection? {
        if (resourcePressure(info.exception) == null) return super.getFallbackSelectionFor(options, info)
        return null // Preserve the selected quality; resource pressure is not a reason to blacklist it.
    }

    // Media3 also throws a pending error from maybeThrowError() when this threshold is exceeded.
    override fun getMinimumLoadableRetryCount(dataType: Int): Int =
        maxOf(super.getMinimumLoadableRetryCount(dataType), MAX_CAPACITY_RETRIES)

    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val pressure = resourcePressure(info.exception)
        if (pressure?.reason == "request_admission" && pressure.admissionReason == "queue_full") {
            val remaining = CAPACITY_RETRY_WINDOW_MS - info.loadEventInfo.loadDurationMs
            val delay = if (info.errorCount > MAX_CAPACITY_RETRIES || remaining <= 0) C.TIME_UNSET
                else minOf(250L shl (info.errorCount - 1).coerceIn(0, 3), remaining)
            try {
                diagnostic("event=capacity_retry request=${pressure.requestId ?: -1} count=${info.errorCount} " +
                    "elapsedMs=${info.loadEventInfo.loadDurationMs} delayMs=$delay exhausted=${delay == C.TIME_UNSET}")
            } catch (_: Exception) { }
            return delay
        }
        if (pressure != null) return if (info.errorCount <= 3) 250L else C.TIME_UNSET
        // A larger Loader threshold must not extend ordinary network failures' previous retry budget.
        return if (info.errorCount > super.getMinimumLoadableRetryCount(info.mediaLoadData.dataType)) C.TIME_UNSET
            else super.getRetryDelayMsFor(info)
    }

    companion object {
        private const val MAX_CAPACITY_RETRIES = 8
        private const val CAPACITY_RETRY_WINDOW_MS = 30_000L

        fun targetBufferBytes(maxHeap: Long): Int =
            (maxHeap / 8).coerceIn(16L * 1024 * 1024, 48L * 1024 * 1024).toInt()

        fun loadControl(maxHeap: Long, allocator: androidx.media3.exoplayer.upstream.DefaultAllocator): DefaultLoadControl = DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setTargetBufferBytes(targetBufferBytes(maxHeap))
            .setBufferDurationsMs(10_000, 30_000, 1_500, 2_000)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        private fun resourcePressure(error: Throwable): IphoneVideoResourceRejectedException? {
            var current: Throwable? = error
            repeat(8) {
                if (current is IphoneVideoResourceRejectedException) return current as IphoneVideoResourceRejectedException
                current = current?.cause
            }
            return null
        }
    }
}
