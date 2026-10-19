package com.shilapi.xcertplay.media

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Corrects a head unit whose audio clock is off, without letting Wi-Fi move the pitch (#610).
 *
 * A Humax BHCN-T3103C played 48 kHz music 0.76 % fast, 13 cents sharp, and the start buffer ran dry about
 * every 70 s. The trim measures that drift from the slow trend of the buffer and plays the track at one
 * fixed rate that cancels it, so the music is in tune and the buffer holds.
 *
 * The old trim steered the playback rate from the queued level itself. Over Wi-Fi that level jumps with
 * every burst and stall, so the pitch wobbled by up to 26 cents with the connection (#807, #976). Now only
 * the highest level of each [WINDOW_NS] window counts: just after a burst everything the iPhone sent is
 * queued, and a late packet can lower that level but never raise it. The drift is a robust slope through
 * those peaks, and a measured change must move the buffer by [SIGNIFICANT_FRAMES_MS] before it changes
 * the rate; a drift below [FAST_DRIFT] must also hold for [SLOW_DRIFT_SPAN_SECONDS]. A stall,
 * a rebuffer or a skip breaks the line, so the measurement starts over. The rate then glides to the new
 * value at [MAX_GLIDE_PER_SECOND]. An accurate head unit stays at exactly the stream's rate: no
 * resampling at all.
 *
 * The drift is a hardware constant, so the next music stream starts from the trim this one learned.
 */
internal class MediaClockTrim(
    private val sampleRate: Int,
    private val targetFrames: Long,
    private var trim: Double = learnedTrim,
) {
    /** End time and highest buffered level of each finished window since the last break. */
    private val windowEndsNs = LongArray(MAX_WINDOWS)
    private val windowPeaks = LongArray(MAX_WINDOWS)
    private var windows = 0
    private var windowStartNs: Long? = null
    private var windowPeak = Long.MIN_VALUE
    private var lastSampleNs: Long? = null
    private var applied = trim
    private var lastUpdateNs: Long? = null

    /** The playback rate last asked of the track, in Hz. */
    var rate = sampleRate
        private set

    /**
     * Call after each write while music plays. Include compressed packets waiting for decode: those frames are already queued audio.
     */
    fun sample(queuedFrames: Long, nowNs: Long, compressedFrames: Long = 0L) {
        if (targetFrames <= 0L) return
        val previous = lastSampleNs
        lastSampleNs = nowNs
        // A pause or a stall is not drift; neither is decoder backpressure (#806), which holds a large
        // compressed queue that the head unit's clock did not make.
        if (previous == null || nowNs - previous > STALE_NS || compressedFrames > targetFrames) {
            restart(nowNs)
            return
        }
        // Windows measured while the rate glides would mix two rates.
        if (applied != trim) return
        val buffered = queuedFrames.coerceAtLeast(0L) + compressedFrames.coerceAtLeast(0L)
        val start = windowStartNs ?: nowNs.also { windowStartNs = it }
        windowPeak = maxOf(windowPeak, buffered)
        if (nowNs - start < WINDOW_NS) return
        if (windows == MAX_WINDOWS) {
            windowEndsNs.copyInto(windowEndsNs, 0, 1)
            windowPeaks.copyInto(windowPeaks, 0, 1)
            windows--
        }
        windowEndsNs[windows] = nowNs
        windowPeaks[windows] = windowPeak
        windows++
        windowStartNs = nowNs
        windowPeak = Long.MIN_VALUE
    }

    /** A rebuffer, a skip or dropped packets moved the level at once: measure the drift again from here. */
    fun restart(nowNs: Long? = null) {
        windows = 0
        windowStartNs = nowNs
        windowPeak = Long.MIN_VALUE
    }

    /** The playback rate to set now, or null to keep the current one. */
    fun rateDue(nowNs: Long): Int? {
        val previousUpdate = lastUpdateNs
        if (previousUpdate != null && nowNs - previousUpdate < UPDATE_NS) return null
        lastUpdateNs = nowNs
        measure()
        if (applied != trim) {
            val seconds = if (previousUpdate == null) Double.MAX_VALUE else (nowNs - previousUpdate) / 1e9
            val step = MAX_GLIDE_PER_SECOND * seconds.coerceAtMost(MAX_STEP_SECONDS)
            // The first rate of a stream is the trim the last one learned: no glide into it.
            applied = if (previousUpdate == null || abs(trim - applied) <= step) trim
            else applied + step * Math.signum(trim - applied)
            if (applied == trim) restart(nowNs)
        }
        val next = (sampleRate * (1.0 + applied)).roundToInt()
        if (next == rate) return null
        rate = next
        return next
    }

    private fun measure() {
        if (applied != trim || windows < MIN_WINDOWS) return
        val slope = robustSlope() // frames per second the buffer gains
        val spanSeconds = (windowEndsNs[windows - 1] - windowEndsNs[0]) / 1e9
        if (abs(slope) * spanSeconds < sampleRate * SIGNIFICANT_FRAMES_MS / 1000.0) return
        // A Wi-Fi slowdown can hold the level down for a minute; only a faulty clock is that fast for longer.
        if (abs(slope) < sampleRate * FAST_DRIFT && spanSeconds < SLOW_DRIFT_SPAN_SECONDS) return
        // The buffer gains what the iPhone sends minus what the track plays: play that much faster or slower.
        trim = (trim + slope / sampleRate).coerceIn(-MAX_TRIM, MAX_TRIM)
        learnedTrim = trim
        restart()
    }

    /** Theil–Sen: the median slope of every pair of windows, so one window with a stall cannot sway it. */
    private fun robustSlope(): Double {
        val slopes = DoubleArray(windows * (windows - 1) / 2)
        var count = 0
        for (i in 0 until windows) for (j in i + 1 until windows) {
            slopes[count++] = (windowPeaks[j] - windowPeaks[i]) / ((windowEndsNs[j] - windowEndsNs[i]) / 1e9)
        }
        slopes.sort()
        return if (count % 2 == 1) slopes[count / 2] else (slopes[count / 2 - 1] + slopes[count / 2]) / 2
    }

    internal companion object {
        /** At most 1.5 % slower or faster, above the 0.76 % seen on #610. */
        const val MAX_TRIM = 0.015
        /** Long enough to hold several of the iPhone's 0.4 s Wi-Fi bursts, so its peak comes after an on-time one. */
        const val WINDOW_NS = 5_000_000_000L
        /** 20 s of peaks before a first estimate; a 0.76 % drift moves the buffer 150 ms in that time. */
        const val MIN_WINDOWS = 4
        /** A drift this large (13 cents, #610 was 0.76 %) is tuned after [MIN_WINDOWS]; a smaller one needs longer. */
        const val FAST_DRIFT = 0.003
        /** On a wireless head unit a slower iPhone send rate held the level down by 0.065 % for a minute. */
        const val SLOW_DRIFT_SPAN_SECONDS = 120.0
        /** Five minutes of peaks: a drift of 0.013 % then also moves the buffer by [SIGNIFICANT_FRAMES_MS]. */
        const val MAX_WINDOWS = 60
        /** Far above the tens of ms the peaks scatter by, so Wi-Fi alone never changes the rate. */
        const val SIGNIFICANT_FRAMES_MS = 40
        /** About 0.9 cents a second: a 0.76 % correction takes 15 s, once per run. */
        const val MAX_GLIDE_PER_SECOND = 0.0005
        const val UPDATE_NS = 1_000_000_000L
        /** Longer than the 0.4 s between the iPhone's bursts over Wi-Fi: a longer silence is a pause or a stall. */
        const val STALE_NS = 1_000_000_000L
        const val MAX_STEP_SECONDS = 2.0

        /** The drift the last music stream settled on. Each process plays to one head unit. */
        @Volatile var learnedTrim = 0.0
    }
}
