package com.shilapi.xcertplay

import android.graphics.*
import android.os.Build

/** Small, cached cover blur works on the FX11 API level without RenderEffect or per-frame copies. */
internal class ReplicaMusicBackdrop {
    private var source: Bitmap? = null
    private var generation = 0
    private var blurred: Bitmap? = null
    private var tint = Color.rgb(13, 31, 29)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun update(artwork: Bitmap?) {
        val valid = artwork?.takeUnless { it.isRecycled }
        if (valid === source && (valid == null || valid.generationId == generation)) return
        clear()
        if (valid == null) return
        source = valid; generation = valid.generationId
        var copy: Bitmap? = null
        var small: Bitmap? = null
        try {
            val readable = if (Build.VERSION.SDK_INT >= 26 && valid.config == Bitmap.Config.HARDWARE) {
                valid.copy(Bitmap.Config.ARGB_8888, false)?.also { copy = it } ?: return
            } else valid
            small = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
            val side = minOf(readable.width, readable.height)
            Canvas(small).drawBitmap(readable, Rect((readable.width-side)/2, (readable.height-side)/2,
                (readable.width+side)/2, (readable.height+side)/2), Rect(0, 0, 24, 24), paint)
            var pixels = IntArray(576).also { small.getPixels(it, 0, 24, 0, 0, 24, 24) }
            var red = 0L; var green = 0L; var blue = 0L
            for (color in pixels) { red += Color.red(color); green += Color.green(color); blue += Color.blue(color) }
            tint = Color.rgb((red/576).toInt(), (green/576).toInt(), (blue/576).toInt())
            repeat(3) {
                val next = IntArray(576)
                for (y in 0..23) for (x in 0..23) {
                    var r = 0; var g = 0; var b = 0
                    for (dy in -2..2) for (dx in -2..2) {
                        val color = pixels[(y+dy).coerceIn(0,23)*24+(x+dx).coerceIn(0,23)]
                        r += Color.red(color); g += Color.green(color); b += Color.blue(color)
                    }
                    next[y*24+x] = Color.rgb(r/25, g/25, b/25)
                }
                pixels = next
            }
            small.setPixels(pixels, 0, 24, 0, 0, 24, 24)
            blurred = small; small = null
        } catch (_: RuntimeException) {
            // Recycled/unreadable artwork falls back to a neutral panel, never a failed dashboard.
            tint = Color.rgb(13, 31, 29)
        } finally { copy?.recycle(); small?.recycle() }
    }

    fun draw(canvas: Canvas, bounds: RectF, fadeEdges: Boolean = true, light: Boolean = false) {
        paint.shader = null; paint.alpha = 255; paint.color = tint
        canvas.drawRect(bounds, paint)
        blurred?.let { canvas.drawBitmap(it, null, bounds, paint) }
        paint.color = if(light) Color.argb(218,248,250,247) else Color.argb(100, 0, 0, 0); canvas.drawRect(bounds, paint)
        paint.alpha = 255
        if (!fadeEdges) return
        val edge = if(light) ReplicaDayPalette.background else Color.BLACK
        // Fade to the surrounding palette at the two dial centers and at the status strip/footer, cover color in the middle.
        paint.shader = LinearGradient(bounds.left, 0f, bounds.right, 0f,
            intArrayOf(edge, Color.TRANSPARENT, Color.TRANSPARENT, edge),
            floatArrayOf(0f, .23f, .77f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(bounds, paint)
        paint.shader = LinearGradient(0f, 0f, 0f, 532f,
            intArrayOf(edge, Color.TRANSPARENT, Color.TRANSPARENT, edge),
            floatArrayOf(0f, .23f, .80f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(bounds, paint); paint.shader = null
    }

    fun clear() {
        blurred?.recycle(); blurred = null; source = null; generation = 0
        tint = Color.rgb(13, 31, 29)
    }
}
