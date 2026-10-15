package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the next-turn arrow, distance and road name on a secondary display.
 *
 * The maneuver icons use the AMap NEW_ICON vocabulary already decoded by
 * [com.shilapi.xcertplay.hud.ClusterTurnGuidance.icon]; the same subset used
 * by the BYD dashboard overlay.
 */
internal class GeelyHudGuidanceView(
    context: Context,
    private var scalePercent: Int,
) : View(context) {

    private var guidance: ClusterTurnGuidance? = null
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(64, 255, 198)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val arrow = Path()

    fun setScalePercent(value: Int) {
        scalePercent = value
        invalidate()
    }

    fun setGuidance(value: ClusterTurnGuidance?) {
        guidance = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = guidance ?: return
        val saveCount = canvas.save()
        val scale = scalePercent / 100.0f
        canvas.scale(scale, scale, width * 0.5f, height * 0.5f)
        canvas.translate(-width * 0.08f, 0.0f)

        val base = min(height, width * 0.45f)
        val centerY = height * 0.67f
        drawArrow(canvas, current.icon, width * 0.031f, centerY, base * 0.38f)

        val distanceText = if (current.distanceMeters >= 1000) {
            String.format(Locale.getDefault(), "%.1f km", current.distanceMeters / 1000.0f)
        } else {
            String.format(Locale.getDefault(), "%d m", max(current.distanceMeters, 0))
        }

        val textX = width * 0.17f
        val outlineStroke = 20.0f / scale
        val textBaseline = (centerY - height * 0.025f) - outlineStroke

        accent.textSize = base * 0.17f
        canvas.drawText(distanceText, textX, textBaseline + accent.textSize * 0.18f, accent)

        if (current.road.isNotBlank()) {
            roadPaint.textSize = base * 0.10f
            val maxWidth = max((width - textX - width * 0.07f).toInt(), 0)
            val road = TextUtils.ellipsize(
                current.road,
                TextPaint(roadPaint),
                maxWidth.toFloat(),
                TextUtils.TruncateAt.END,
            ).toString()
            canvas.drawText(road, textX, textBaseline + accent.textSize * 1.55f, roadPaint)
        }
        canvas.restoreToCount(saveCount)
    }

    private fun drawArrow(canvas: Canvas, maneuver: Int, x: Float, y: Float, size: Float) {
        arrow.reset()
        when (maneuver) {
            1, 2 -> {
                arrow.moveTo(18f, 36f)
                arrow.lineTo(18f, 0f)
                arrow.quadTo(18f, -20f, -2f, -20f)
                arrow.lineTo(-38f, -20f)
                arrow.moveTo(-22f, -34f)
                arrow.lineTo(-38f, -20f)
                arrow.lineTo(-22f, -6f)
            }
            3, 5 -> {
                arrow.moveTo(18f, 36f)
                arrow.lineTo(18f, 0f)
                arrow.lineTo(-28f, -36f)
                arrow.moveTo(-30f, -16f)
                arrow.lineTo(-28f, -36f)
                arrow.lineTo(-8f, -36f)
            }
            11 -> {
                arrow.moveTo(0f, 36f)
                arrow.lineTo(0f, -38f)
                arrow.moveTo(-14f, -22f)
                arrow.lineTo(0f, -38f)
                arrow.lineTo(14f, -22f)
            }
            7, 8 -> {
                arrow.moveTo(22f, 36f)
                arrow.lineTo(22f, -8f)
                arrow.cubicTo(22f, -48f, -22f, -48f, -22f, -8f)
                arrow.lineTo(-22f, 16f)
                arrow.moveTo(-36f, 0f)
                arrow.lineTo(-22f, 16f)
                arrow.lineTo(-8f, 0f)
            }
            else -> return
        }
        val saveCount = canvas.save()
        canvas.translate(x, y)
        val scale = size / 88f
        val flip = maneuver in listOf(2, 5, 8)
        canvas.scale(if (flip) -scale else scale, scale)
        accent.style = Paint.Style.STROKE
        accent.strokeWidth = 7f
        accent.strokeCap = Paint.Cap.ROUND
        accent.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(arrow, accent)
        accent.style = Paint.Style.FILL
        canvas.restoreToCount(saveCount)
    }
}
