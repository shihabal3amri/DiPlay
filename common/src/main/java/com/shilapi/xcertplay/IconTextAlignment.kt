package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.widget.TextView
import kotlin.math.roundToInt

/** The same locale policy for Settings labels and icon/text buttons. */
internal fun alignedTextCentreY(
    view: TextView,
    text: CharSequence,
    bounds: Rect,
    drawingBaseline: Int = view.baseline,
): Float {
    if (usesChineseGlyphAlignment(view.resources.configuration.locales[0].language, text)) {
        val value = text.toString()
        view.paint.getTextBounds(value, 0, value.length, bounds)
        return drawingBaseline + (bounds.top + bounds.bottom) / 2f
    }
    // Keep the baseline stable when labels contain descenders or accents.
    val metrics = view.paint.fontMetrics
    return drawingBaseline + (metrics.ascent + metrics.descent) / 2f
}

internal fun usesChineseGlyphAlignment(language: String, text: CharSequence): Boolean =
    language == "zh" && text.isNotBlank() && text.none { it in 'A'..'Z' || it in 'a'..'z' }

/** Align the icon with the text, then centre the pair. Recalculate only during layout. */
internal class IconTextAlignment {
    private val glyphBounds = Rect()

    fun align(view: TextView): Float {
        val layout = view.layout ?: return 0f
        if (view.baseline < 0 || view.text.isEmpty()) return 0f
        val lineSpan = layout.getLineBaseline(layout.lineCount - 1) - layout.getLineBaseline(0)
        val textCentre = alignedTextCentreY(view, view.text, glyphBounds) + lineSpan / 2f
        val contentCentre = view.paddingTop + (view.height - view.paddingTop - view.paddingBottom) / 2f
        val iconOffset = (textCentre - contentCentre).roundToInt()
        for (drawable in view.compoundDrawablesRelative) {
            if (drawable == null) continue
            val bounds = drawable.bounds
            drawable.setBounds(bounds.left, iconOffset, bounds.right, iconOffset + bounds.height())
        }
        return contentCentre - textCentre
    }
}

internal class IconAlignedTextView(context: Context) : TextView(context) {
    private val alignment = IconTextAlignment()
    private var contentOffsetY = 0f

    init {
        includeFontPadding = false
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        contentOffsetY = alignment.align(this)
    }

    override fun onDraw(canvas: Canvas) {
        val checkpoint = canvas.save()
        canvas.translate(0f, contentOffsetY)
        super.onDraw(canvas)
        canvas.restoreToCount(checkpoint)
    }
}
