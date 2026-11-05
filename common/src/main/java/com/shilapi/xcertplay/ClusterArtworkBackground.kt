package com.shilapi.xcertplay

import android.graphics.*
import kotlin.math.sin

/** Shared artwork rendering; coordinates and opacity are owned by each host view. */
internal class ClusterArtworkBackground {
    private var colors = palette(null)
    private var shaders = emptyList<RadialGradient>()
    private var width = 0f
    private var height = 0f
    private val matrix = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun setArtwork(art: Bitmap?) {
        colors = palette(art)
        rebuild()
    }

    private fun rebuild() {
        val radius = maxOf(width, height).coerceAtLeast(1f) * .48f
        shaders = colors.map { RadialGradient(0f, 0f, radius, it, Color.TRANSPARENT, Shader.TileMode.CLAMP) }
    }

    fun draw(canvas: Canvas, w: Float, h: Float, now: Long) {
        if (w != width || h != height || shaders.isEmpty()) {
            width = w; height = h; rebuild()
        }
        val phase = now / 5_000.0
        canvas.drawColor(Color.rgb(16, 19, 27))
        shaders.forEachIndexed { index, shader ->
            matrix.setTranslate(w * (.5f + .48f * sin(phase + index * 2.1).toFloat()),
                h * (.5f + .5f * sin(phase * .73 + index * 2.7).toFloat()))
            shader.setLocalMatrix(matrix)
            paint.shader = shader; paint.alpha = 255
            canvas.drawRect(0f, 0f, w, h, paint)
        }
        paint.shader = null
        paint.color = Color.argb(48, 0, 0, 0)
        canvas.drawRect(0f, 0f, w, h, paint)
    }

    companion object {
        fun palette(art: Bitmap?): IntArray {
            if (art == null || art.isRecycled) return intArrayOf(Color.rgb(36,45,65), Color.rgb(78,45,84), Color.rgb(26,62,74))
            // Count visible area, not saturation: a small vivid detail must not replace the sleeve.
            val hsv = FloatArray(3)
            val buckets = Array(27) { LongArray(4) }
            for (y in 0..23) for (x in 0..23) {
                val color = art.getPixel(x * art.width / 24, y * art.height / 24)
                if (Color.alpha(color) < 128) continue
                Color.colorToHSV(color, hsv)
                val key = if (hsv[1] < .08f || hsv[2] < .08f)
                    24 + (hsv[2] * 3).toInt().coerceIn(0, 2)
                else (hsv[0] / 15).toInt().coerceIn(0, 23)
                val bucket = buckets[key]
                bucket[0] += Color.red(color).toLong()
                bucket[1] += Color.green(color).toLong()
                bucket[2] += Color.blue(color).toLong()
                bucket[3]++
            }
            val ranked = buckets.filter { it[3] > 0 }.sortedByDescending { it[3] }
            if (ranked.isEmpty()) return intArrayOf(Color.DKGRAY, Color.DKGRAY, Color.DKGRAY)
            val selected = mutableListOf<LongArray>()
            val selectedTones = mutableListOf<FloatArray>()
            for (bucket in ranked) {
                val tone = FloatArray(3)
                Color.colorToHSV(Color.rgb((bucket[0]/bucket[3]).toInt(),
                    (bucket[1]/bucket[3]).toInt(), (bucket[2]/bucket[3]).toInt()), tone)
                val distinct = selectedTones.all { previous ->
                    if (tone[1] < .08f || previous[1] < .08f)
                        (tone[1] < .08f) != (previous[1] < .08f)
                    else {
                        val distance = kotlin.math.abs(tone[0]-previous[0])
                        minOf(distance,360f-distance) >= 40f
                    }
                }
                if (distinct) { selected.add(bucket); selectedTones.add(tone) }
                if (selected.size == 3) break
            }
            return (0..2).map { index ->
                val bucket = selected.getOrElse(index) { selected.first() }
                val color = Color.rgb((bucket[0]/bucket[3]).toInt(),
                    (bucket[1]/bucket[3]).toInt(), (bucket[2]/bucket[3]).toInt())
                Color.colorToHSV(color, hsv)
                // Restore the three tonal layers of preview 4, slightly brighter.
                // Keep sampled hue/saturation, including neutral sleeves.
                hsv[2] = floatArrayOf(.80f, .49f, .67f)[index]
                Color.HSVToColor(hsv)
            }.toIntArray()
        }
    }
}
