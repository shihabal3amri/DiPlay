package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.View
import com.shilapi.xcertplay.host.R

/** DiPlay artwork drawn locally. The animation stops after five seconds, even without video. */
internal class ProjectionWaitingView(context: Context, dark: Boolean = true) : View(context) {
    val waitingBackgroundColor = if (dark) Color.BLACK else Color.rgb(233, 238, 246)
    val waitingForegroundColor = if (dark) Color.WHITE else Color.DKGRAY
    private val secondaryColor = if (dark) Color.rgb(182, 196, 214) else Color.rgb(58, 76, 94)
    private val main = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var frameWait = false
    private var started = 0L
    private var animating = false
    private val finishWait = Runnable {
        animating = false
        if (frameWait) visibility = GONE else invalidate()
    }
    init {
        setBackgroundColor(waitingBackgroundColor)
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** A disconnected page remains static; a startup cover always yields after the deadline. */
    fun waitForFrame(hideAtDeadline: Boolean = true) {
        main.removeCallbacks(finishWait)
        animate().cancel()
        alpha = 1f
        frameWait = hideAtDeadline
        started = android.os.SystemClock.uptimeMillis()
        animating = true
        visibility = VISIBLE
        invalidate()
        main.postDelayed(finishWait, MAXIMUM_WAIT_MILLIS)
    }

    fun presented() {
        main.removeCallbacks(finishWait)
        animating = false
        visibility = GONE
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacks(finishWait)
        animating = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(waitingBackgroundColor)
        val density = resources.displayMetrics.scaledDensity
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        paint.textSize = minOf(36f * density, width * .16f, height * .24f)
        paint.color = waitingForegroundColor
        canvas.drawText("DiPlay", width * .5f, height * .46f, paint)
        paint.typeface = android.graphics.Typeface.DEFAULT
        val label = context.getString(R.string.cluster_waiting_for_map)
        paint.textSize = minOf(18f * density, height * .10f)
        if (paint.measureText(label) > width * .86f) paint.textSize *= width * .86f / paint.measureText(label)
        paint.color = secondaryColor
        canvas.drawText(label, width * .5f, height * .65f, paint)
        val radius = minOf(width, height) * .06f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(2f, radius * .12f)
        paint.color = Color.rgb(84, 190, 230)
        val angle = if (animating) ((android.os.SystemClock.uptimeMillis() - started) % 1600L) * 360f / 1600f else 0f
        canvas.drawArc(width * .5f - radius, height * .80f - radius,
            width * .5f + radius, height * .80f + radius, angle, 250f, false, paint)
        paint.style = Paint.Style.FILL
        if (animating) postInvalidateOnAnimation()
    }

    companion object { const val MAXIMUM_WAIT_MILLIS = 5_000L }
}
