package com.shilapi.xcertplay

import android.graphics.Canvas
import android.graphics.Picture
import android.os.SystemClock
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

/** A bounded display list, not a bitmap of the map/cover. At most one face per dial. */
internal class DashboardDialFaceCache {
    private var picture: Picture? = null
    private var lastDay = false
    private var lastValue: Double? = null
    var buildCount = 0
        private set

    fun draw(canvas: Canvas, day: Boolean, value: Double?, render: (Canvas) -> Unit) {
        var current = picture
        if (current == null || day != lastDay || value != lastValue) {
            current = Picture()
            val recording = current.beginRecording(416, 416)
            try { recording.translate(208f, 208f); render(recording) }
            finally { current.endRecording() }
            picture = current; lastDay = day; lastValue = value; buildCount++
        }
        canvas.save()
        canvas.translate(-208f, -208f)
        canvas.drawPicture(current)
        canvas.restore()
    }
    fun clear() { picture = null }
}

/** Bounded in-memory evidence, included in diagnostics without disk work on the UI thread. */
internal object DashboardPerformanceLog {
    private val history = ArrayDeque<String>()
    @Synchronized fun record(line: String) {
        val safe = DiagnosticRedactor.redact(line) ?: return
        if (history.size == 120) history.removeFirst()
        history.addLast("epochMs=${System.currentTimeMillis()} $safe")
    }
    @Synchronized fun report(): String = history.joinToString("\n")
}

internal class DashboardRenderMetrics {
    private val id = ids.incrementAndGet()
    private var start = SystemClock.elapsedRealtimeNanos()
    private var draws = 0
    private var maxDrawNs = 0L
    private var sumDrawNs = 0L
    private var lastBuilds = 0
    fun drawFinished(started: Long, style: String, map: Boolean, builds: Int) {
        val now = SystemClock.elapsedRealtimeNanos()
        val duration = (now - started).coerceAtLeast(0)
        draws++; sumDrawNs += duration; maxDrawNs = maxOf(maxDrawNs, duration)
        if (now - start < 5_000_000_000L) return
        DashboardPerformanceLog.record("Dashboard: render view=$id style=$style map=$map " +
            "windowMs=${(now-start)/1_000_000} draws=$draws " +
            "maxUiDrawUs=${maxDrawNs/1000} meanUiDrawUs=${sumDrawNs/draws/1000} " +
            "dialRebuilds=${builds-lastBuilds}")
        start = now; draws = 0; sumDrawNs = 0; maxDrawNs = 0; lastBuilds = builds
    }
    companion object { private val ids = AtomicInteger() }
}
