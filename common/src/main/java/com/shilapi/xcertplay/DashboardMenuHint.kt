package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.shilapi.xcertplay.host.R

/** Shared, non-interactive MENU feedback in the dashboard's 1920 × 532 design space. */
internal class DashboardMenuHint(private val context: Context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cyan = Color.rgb(74, 235, 245)

    fun draw(canvas: Canvas, style: DashboardStyle) {
        val rect = bounds(style)
        val radius = HEIGHT / 2
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(224, 2, 16, 22)
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.8f
        paint.color = cyan
        // A centered stroke must stay inside the pixels reserved for the OEM overlay.
        val strokeRect = if (style == DashboardStyle.OEM_OVERLAY) RectF(rect).apply {
            inset(paint.strokeWidth / 2, paint.strokeWidth / 2)
        } else rect
        canvas.drawRoundRect(strokeRect, radius, radius, paint)

        // Track symbols retain their physical previous/next meaning in RTL languages too.
        trackGlyph(canvas, rect.left + ICON_INSET, rect.centerY(), previous = true)
        trackGlyph(canvas, rect.right - ICON_INSET, rect.centerY(), previous = false)
        val label = context.getString(R.string.dashboard_switch_mode)
        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = (22f * context.resources.configuration.fontScale).coerceIn(22f, 26f)
        val available = WIDTH - 2 * TEXT_INSET
        val measured = paint.measureText(label)
        if (measured > available) paint.textSize *= available / measured
        paint.color = Color.rgb(200, 250, 255)
        val metrics = paint.fontMetrics
        canvas.drawText(label, rect.centerX(), rect.centerY() - (metrics.ascent + metrics.descent) / 2, paint)
    }

    private fun trackGlyph(canvas: Canvas, x: Float, y: Float, previous: Boolean) {
        canvas.save()
        canvas.translate(x, y)
        if (previous) canvas.scale(-1f, 1f)
        paint.style = Paint.Style.FILL
        paint.color = cyan
        canvas.drawPath(Path().apply {
            moveTo(-7f, -8f); lineTo(5f, 0f); lineTo(-7f, 8f); close()
        }, paint)
        canvas.drawRoundRect(RectF(7f, -8f, 9f, 8f), 1f, 1f, paint)
        canvas.restore()
    }

    companion object {
        private const val WIDTH = 340f
        private const val HEIGHT = 34f
        private const val ICON_INSET = 42f
        private const val TEXT_INSET = 68f
        fun bounds(style: DashboardStyle): RectF {
            // Replica sits below the music progress bar. The other styles leave their footer clear.
            val top = when (style) { DashboardStyle.OEM_OVERLAY -> 492f; DashboardStyle.REPLICA -> 442f; else -> 424f }
            val center = if (style == DashboardStyle.PANORAMA) 1176f else 960f
            return RectF(center - WIDTH / 2, top, center + WIDTH / 2, top + HEIGHT)
        }
    }
}
