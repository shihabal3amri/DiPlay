package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.ImageView

/** A short local still bridges a view-area switch; it is never an acknowledgement from the iPhone. */
internal class SplitResizeBridge(context: Context) : ImageView(context) {
    private val main = Handler(Looper.getMainLooper())
    private var ready: () -> Boolean = { false }
    private var started = 0L
    private val tick = object : Runnable {
        override fun run() {
            val elapsed = SystemClock.uptimeMillis() - started
            if (elapsed >= MAX_WAIT_MS || (elapsed >= MIN_WAIT_MS && ready())) clear()
            else main.postDelayed(this, POLL_MS)
        }
    }
    init {
        scaleType = ScaleType.FIT_CENTER
        setBackgroundColor(Color.BLACK)
        visibility = GONE
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    fun show(frame: Bitmap?, hasNewFrame: () -> Boolean): Boolean {
        val snapshot = frame?.takeUnless { it.isRecycled }
        if (snapshot == null) { clear(); return false }
        main.removeCallbacks(tick)
        ready = hasNewFrame
        setImageBitmap(snapshot)
        started = SystemClock.uptimeMillis()
        visibility = VISIBLE
        main.postDelayed(tick, MIN_WAIT_MS)
        return true
    }
    fun clear() {
        main.removeCallbacks(tick)
        ready = { false }
        visibility = GONE
        // Do not recycle: a hardware display list can still reference the snapshot.
        setImageDrawable(null)
    }
    override fun onDetachedFromWindow() { clear(); super.onDetachedFromWindow() }
    companion object {
        const val MIN_WAIT_MS = 150L
        const val MAX_WAIT_MS = 1_200L
        private const val POLL_MS = 50L
    }
}
