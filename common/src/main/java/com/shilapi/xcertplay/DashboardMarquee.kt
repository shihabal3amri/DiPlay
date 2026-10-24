package com.shilapi.xcertplay

/** Read the beginning, pan to the end, hold it, then restart; playback ticks do not reset time. */
internal class DashboardMarquee {
    private data class Run(val text: String, val overflow: Float, val start: Long)
    private val runs = linkedMapOf<String, Run>()

    fun offset(slot: String, text: String, overflow: Float, now: Long): Float {
        if (overflow <= 0f || !overflow.isFinite()) { runs.remove(slot); return 0f }
        val old = runs[slot]
        val run = if (old == null || old.text != text || old.overflow != overflow || now < old.start) {
            Run(text, overflow, now).also {
                runs[slot] = it
                while (runs.size > 16) runs.remove(runs.keys.first())
            }
        } else old
        val travel = (overflow / PIXELS_PER_SECOND * 1000).toLong().coerceAtLeast(1)
        val phase = (now - run.start) % (START_HOLD + travel + END_HOLD)
        return when {
            phase < START_HOLD -> 0f
            phase >= START_HOLD + travel -> overflow
            else -> overflow * (phase - START_HOLD).toFloat() / travel
        }
    }

    fun nextDelay(slot: String, now: Long): Long {
        val run = runs[slot] ?: return START_HOLD
        val travel = (run.overflow / PIXELS_PER_SECOND * 1000).toLong().coerceAtLeast(1)
        val cycle = START_HOLD + travel + END_HOLD
        val phase = (now - run.start).coerceAtLeast(0) % cycle
        return when {
            phase < START_HOLD -> START_HOLD - phase
            phase >= START_HOLD + travel -> cycle - phase
            else -> minOf(33L, START_HOLD + travel - phase)
        }
    }

    fun clear() = runs.clear()

    companion object {
        const val START_HOLD = 1500L
        const val END_HOLD = 1500L
        const val PIXELS_PER_SECOND = 28f
    }
}
