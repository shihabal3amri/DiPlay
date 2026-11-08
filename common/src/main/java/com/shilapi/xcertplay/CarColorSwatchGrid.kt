package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.SweepGradient
import android.view.View
import android.view.ViewGroup
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.AdasCarColor
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** The names and swatch colors the car color picker shows. */
internal object AdasCarColorLabels {
    /** The name of [color]: "Original" for none, a preset's name, or "Custom". */
    fun of(context: Context, color: AdasCarColor?): String = when (color) {
        null -> context.getString(R.string.settings_adas_scene_color_original)
        else -> AdasCarColor.presetOf(color)?.let { preset(context, it) } ?: context.getString(R.string.settings_adas_scene_color_custom)
    }

    fun preset(context: Context, preset: AdasCarColor.Preset): String = context.getString(presetRes(preset.key))

    fun presetRes(key: String): Int = when (key) {
        "white" -> R.string.settings_adas_scene_color_white
        "black" -> R.string.settings_adas_scene_color_black
        "gray" -> R.string.settings_adas_scene_color_gray
        "red" -> R.string.settings_adas_scene_color_red
        "orange" -> R.string.settings_adas_scene_color_orange
        "gold" -> R.string.settings_adas_scene_color_gold
        "green" -> R.string.settings_adas_scene_color_green
        "teal" -> R.string.settings_adas_scene_color_teal
        "blue" -> R.string.settings_adas_scene_color_blue
        "purple" -> R.string.settings_adas_scene_color_purple
        "pink" -> R.string.settings_adas_scene_color_pink
        else -> error("no name for the car color $key")
    }

    /**
     * The color a swatch or preview shows for [color]. Brightness above 100 % counts as 100 %, and the dark end is
     * drawn darker than a plain HSV value so that a black car reads as black; it is a hint of the result, not its pixels.
     */
    fun swatchRgb(color: AdasCarColor): Int {
        val hue = color.hue / 60f
        val value = min(1f, color.brightness / 100f).pow(1.6f)
        val chroma = value * color.saturation / 100f
        val second = chroma * (1f - abs(hue % 2f - 1f))
        val (r, g, b) = when (hue.toInt()) {
            0 -> Triple(chroma, second, 0f)
            1 -> Triple(second, chroma, 0f)
            2 -> Triple(0f, chroma, second)
            3 -> Triple(0f, second, chroma)
            4 -> Triple(second, 0f, chroma)
            else -> Triple(chroma, 0f, second)
        }
        val base = value - chroma
        return Color.rgb(((r + base) * 255f).roundToInt(), ((g + base) * 255f).roundToInt(), ((b + base) * 255f).roundToInt())
    }
}

/**
 * The colors of the car in the cluster's ADAS scene as tappable swatches: "Original" (BYD's own car), the ready-made
 * colors, and "Custom". A tap on a swatch other than "Custom" selects it and calls [onPicked]; "Custom" calls
 * [onCustom], which asks for a color and then hands it to [choose].
 */
internal class CarColorSwatchGrid(
    context: Context,
    private val accent: Int,
    initial: AdasCarColor?,
    private val onPicked: (AdasCarColor?) -> Unit,
    private val onCustom: () -> Unit,
) : ViewGroup(context) {
    var selection: AdasCarColor? = initial
        private set
    private val density = context.resources.displayMetrics.density
    private val original = Swatch(context, AdasCarColorLabels.of(context, null)) { choose(null) }
    private val presets = AdasCarColor.PRESETS.map { preset ->
        preset.color to Swatch(context, AdasCarColorLabels.preset(context, preset)) { choose(preset.color) }
    }
    private val custom = Swatch(context, context.getString(R.string.settings_adas_scene_color_custom)) { if (isEnabled) onCustom() }

    init {
        addView(original)
        presets.forEach { addView(it.second) }
        addView(custom)
        refresh()
    }

    /** Selects [color], or "Original" for null, and calls [onPicked] unless it was selected already or the grid is off. */
    fun choose(color: AdasCarColor?) {
        if (!isEnabled || color == selection) return
        selection = color
        refresh()
        onPicked(color)
    }

    fun setSelection(color: AdasCarColor?) {
        selection = color
        refresh()
    }

    fun setInteractive(enabled: Boolean) {
        isEnabled = enabled
        for (index in 0 until childCount) getChildAt(index).isEnabled = enabled
        alpha = if (enabled) 1f else 0.4f
    }

    private fun refresh() {
        val customColor = selection?.takeIf { AdasCarColor.presetOf(it) == null }
        original.show(selected = selection == null, fill = null, slash = true)
        presets.forEach { (color, swatch) -> swatch.show(selected = selection == color, fill = AdasCarColorLabels.swatchRgb(color)) }
        custom.show(selected = customColor != null, fill = customColor?.let(AdasCarColorLabels::swatchRgb), rainbow = customColor == null)
    }

    // Swatches wrap onto as many rows as the available width needs.
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val available = MeasureSpec.getSize(widthSpec)
        var x = 0
        var y = 0
        var rowHeight = 0
        var widest = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            measureChildWithMargins(child, widthSpec, 0, heightSpec, 0)
            val lp = child.layoutParams as MarginLayoutParams
            val w = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val h = child.measuredHeight + lp.topMargin + lp.bottomMargin
            if (x > 0 && x + w > available) {
                x = 0
                y += rowHeight
                rowHeight = 0
            }
            x += w
            widest = maxOf(widest, x)
            rowHeight = maxOf(rowHeight, h)
        }
        setMeasuredDimension(resolveSize(widest, widthSpec), resolveSize(y + rowHeight, heightSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val available = right - left
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        var x = 0
        var y = 0
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as MarginLayoutParams
            val w = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val h = child.measuredHeight + lp.topMargin + lp.bottomMargin
            if (x > 0 && x + w > available) {
                x = 0
                y += rowHeight
                rowHeight = 0
            }
            val start = if (rtl) available - x - w else x
            child.layout(start + lp.leftMargin, y + lp.topMargin, start + lp.leftMargin + child.measuredWidth, y + lp.topMargin + child.measuredHeight)
            x += w
            rowHeight = maxOf(rowHeight, h)
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams = MarginLayoutParams(-2, -2)
    override fun generateLayoutParams(attrs: android.util.AttributeSet?): LayoutParams = MarginLayoutParams(context, attrs)
    override fun generateLayoutParams(p: LayoutParams): LayoutParams = MarginLayoutParams(p)
    override fun checkLayoutParams(p: LayoutParams): Boolean = p is MarginLayoutParams

    private fun dp(value: Int) = (value * density).roundToInt()

    /** One circle: filled with a color, a rainbow ("Custom" before a custom color is chosen), or empty with a slash ("Original"). */
    private inner class Swatch(context: Context, name: String, private val tap: () -> Unit) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glyph = Path()
        private var sweep: SweepGradient? = null
        private var fill: Int? = null
        private var rainbow = false
        private var slash = false

        init {
            layoutParams = MarginLayoutParams(dp(SWATCH_DP), dp(SWATCH_DP)).apply { setMargins(dp(MARGIN_DP), dp(MARGIN_DP), dp(MARGIN_DP), dp(MARGIN_DP)) }
            contentDescription = name
            isClickable = true
            isFocusable = true
            setOnClickListener { tap() }
        }

        fun show(selected: Boolean, fill: Int?, rainbow: Boolean = false, slash: Boolean = false) {
            this.fill = fill
            this.rainbow = rainbow
            this.slash = slash
            isSelected = selected
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            sweep = SweepGradient(w / 2f, h / 2f, RAINBOW, null)
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val ring = dp(if (isSelected) 3 else 1).toFloat()
            val radius = min(cx, cy) - ring / 2f
            val shown = fill
            paint.reset()
            paint.isAntiAlias = true
            paint.style = Paint.Style.FILL
            if (shown != null) {
                paint.color = shown
                canvas.drawCircle(cx, cy, radius, paint)
            } else if (rainbow) {
                paint.shader = sweep
                canvas.drawCircle(cx, cy, radius, paint)
                paint.shader = null
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ring
            paint.color = if (isSelected) accent else UNSELECTED_RING
            canvas.drawCircle(cx, cy, radius, paint)
            paint.strokeWidth = dp(2).toFloat()
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            if (slash) {
                paint.color = SLASH
                canvas.drawLine(width * 0.28f, height * 0.72f, width * 0.72f, height * 0.28f, paint)
            }
            if (isSelected && shown != null) {
                paint.color = if (isLight(shown)) Color.BLACK else Color.WHITE
                glyph.reset()
                glyph.moveTo(width * 0.29f, height * 0.52f)
                glyph.lineTo(width * 0.43f, height * 0.66f)
                glyph.lineTo(width * 0.72f, height * 0.36f)
                canvas.drawPath(glyph, paint)
            }
            if (isFocused) {
                paint.color = accent
                canvas.drawCircle(cx, cy, radius - dp(5), paint)
            }
        }

        private fun isLight(rgb: Int): Boolean =
            (0.299 * Color.red(rgb) + 0.587 * Color.green(rgb) + 0.114 * Color.blue(rgb)) > 150
    }

    internal companion object {
        private const val SWATCH_DP = 44
        private const val MARGIN_DP = 5
        private const val UNSELECTED_RING = 0x55808080
        private const val SLASH = 0x99808080.toInt()
        private val RAINBOW = intArrayOf(
            0xFFFF3B30.toInt(), 0xFFFFCC00.toInt(), 0xFF34C759.toInt(), 0xFF00C7BE.toInt(), 0xFF007AFF.toInt(), 0xFFAF52DE.toInt(), 0xFFFF3B30.toInt(),
        )
    }
}
