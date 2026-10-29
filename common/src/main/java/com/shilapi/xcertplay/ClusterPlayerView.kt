package com.shilapi.xcertplay

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.text.StaticLayout
import android.text.Layout
import android.view.View
import com.shilapi.xcertplay.airplay.ClusterPlayerSettings
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.ClusterVehicleReading
import java.text.NumberFormat
import kotlin.math.sin

/** A local dashboard page. No video, controls, per-frame bitmap blur or vehicle writes. */
internal class ClusterPlayerView(
    context: Context,
    private val observeVehicle: ((ClusterVehicleReading) -> Unit) -> (() -> Unit) = { changed ->
        com.shilapi.xcertplay.hud.BydClusterVehicleReader.observe(context, changed)
    },
    private val observeSpeed: ((Int?) -> Unit) -> (() -> Unit) = { changed ->
        com.shilapi.xcertplay.hud.BydClusterSpeedReader.observe(context, changed)
    },
) : View(context) {
    private val main = Handler(Looper.getMainLooper())
    private var stopState: (() -> Unit)? = null
    private var stopSettings: (() -> Unit)? = null
    private var stopSpeed: (() -> Unit)? = null
    private var stopVehicle: (() -> Unit)? = null
    private var vehicleGeneration = 0
    private var vehicle = ClusterVehicleReading()
    private var generation = 0
    private var speedKmh: Int? = null
    private var snapshot = ClusterMusicState.Snapshot()
    private var cover: Bitmap? = null
    private var colors = intArrayOf(Color.rgb(36, 45, 65), Color.rgb(78, 45, 84), Color.rgb(26, 62, 74))
    private var shaders = emptyList<RadialGradient>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val matrix = Matrix()
    private val clip = Path()
    private val expireSpeed = Runnable { speedKmh = null; invalidate() }
    private val expireVehicle = Runnable { vehicle = ClusterVehicleReading(); invalidate() }
    private val frame = Runnable { invalidate() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        stopSettings = ClusterPlayerSettings.observe(context) { refresh() }
        stopState = ClusterMusicState.observe {
            snapshot = it
            if (cover !== it.artwork) {
                cover = it.artwork?.takeUnless { bitmap -> bitmap.isRecycled }
                colors = palette(cover)
                rebuildShaders()
            }
            invalidate()
        }
        refresh()
    }

    private fun refresh() {
        val enabled = ClusterPlayerSettings.enabled(context)
        visibility = if (enabled) VISIBLE else GONE
        if (enabled && isShown && windowVisibility == VISIBLE && ClusterPlayerSettings.speed(context) && stopSpeed == null) {
            val token = ++generation
            stopSpeed = observeSpeed { value ->
                val received = SystemClock.elapsedRealtime()
                main.post {
                    if (generation == token && isAttachedToWindow) {
                        speedKmh = value?.takeIf { it in 0..300 && SystemClock.elapsedRealtime() - received < 2_000 }
                        main.removeCallbacks(expireSpeed)
                        main.postDelayed(expireSpeed, 2_000)
                        invalidate()
                    }
                }
            }
        } else if (!enabled || !isShown || windowVisibility != VISIBLE || !ClusterPlayerSettings.speed(context)) {
            ++generation
            stopSpeed?.invoke(); stopSpeed = null
            speedKmh = null
            main.removeCallbacks(expireSpeed)
        }
        val readVehicle = enabled && isShown && windowVisibility == VISIBLE && ClusterPlayerSettings.vehicle(context)
        if (readVehicle && stopVehicle == null) {
            val token = ++vehicleGeneration
            stopVehicle = observeVehicle { reading ->
                main.post {
                    if (vehicleGeneration == token && isAttachedToWindow) {
                        vehicle = reading.fresh(SystemClock.elapsedRealtime())
                        main.removeCallbacks(expireVehicle)
                        main.postDelayed(expireVehicle, 3_000)
                        invalidate()
                    }
                }
            }
        } else if (!readVehicle) {
            ++vehicleGeneration
            stopVehicle?.invoke(); stopVehicle = null
            vehicle = ClusterVehicleReading()
            main.removeCallbacks(expireVehicle)
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        ++vehicleGeneration
        stopVehicle?.invoke(); stopVehicle = null
        vehicle = ClusterVehicleReading()
        ++generation
        stopSpeed?.invoke(); stopSpeed = null
        stopState?.invoke(); stopState = null
        stopSettings?.invoke(); stopSettings = null
        main.removeCallbacksAndMessages(null)
        speedKmh = null
        cover = null
        snapshot = ClusterMusicState.Snapshot()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // Visibility callbacks may run from View's constructor, before our fields initialize.
        if (isAttachedToWindow) { main.removeCallbacks(frame); refresh() }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (isAttachedToWindow) { main.removeCallbacks(frame); refresh() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { rebuildShaders() }
    private fun rebuildShaders() {
        val radius = maxOf(width, height).coerceAtLeast(1) * .48f
        shaders = colors.map { RadialGradient(0f, 0f, radius, it, Color.TRANSPARENT, Shader.TileMode.CLAMP) }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val w = width.toFloat(); val backgroundHeight = height.toFloat()
        val now = SystemClock.elapsedRealtime()
        val phase = now / 5_000.0
        canvas.drawColor(Color.rgb(16, 19, 27))
        shaders.forEachIndexed { index, shader ->
            matrix.setTranslate(w * (.5f + .48f * sin(phase + index * 2.1).toFloat()),
                backgroundHeight * (.5f + .5f * sin(phase * .73 + index * 2.7).toFloat()))
            shader.setLocalMatrix(matrix)
            paint.shader = shader; paint.alpha = 255
            canvas.drawRect(0f, 0f, w, backgroundHeight, paint)
        }
        paint.shader = null
        paint.color = Color.argb(48, 0, 0, 0)
        canvas.drawRect(0f, 0f, w, backgroundHeight, paint)
        // Temporary layout for the reported native strips. The gradient still fills the canvas;
        // only foreground content is laid out inside the unobscured projection area.
        val fit = ClusterPlayerSettings.fitVisible(context)
        val contentTop = if (fit) backgroundHeight * .18f else 0f
        val contentBottom = if (fit) backgroundHeight * .76f else backgroundHeight
        val foreground = canvas.save()
        canvas.clipRect(0f,contentTop,w,contentBottom)
        canvas.translate(0f,contentTop)
        val h = contentBottom - contentTop
        val stacked = w / h < 1.65f
        val side = if (stacked) minOf(w * .64f, h * .30f) else minOf(h * .84f, w * .27f)
        val left = if (stacked) (w - side) / 2 else w * .075f
        val top = if (stacked) h * .22f else (h - side) / 2
        val rect = RectF(left, top, left + side, top + side)
        clip.reset(); clip.addRoundRect(rect, side * .045f, side * .045f, Path.Direction.CW)
        val saved = canvas.save(); canvas.clipPath(clip)
        val artwork = cover?.takeUnless { it.isRecycled }
        if (artwork != null) {
            val crop = minOf(artwork.width, artwork.height)
            val source = Rect((artwork.width - crop) / 2, (artwork.height - crop) / 2,
                (artwork.width + crop) / 2, (artwork.height + crop) / 2)
            paint.color = Color.WHITE
            canvas.drawBitmap(artwork, source, rect, paint)
        } else {
            paint.color = Color.argb(35, 255, 255, 255); canvas.drawRect(rect, paint)
            text.textAlign = Paint.Align.CENTER; text.textSize = side * .40f; text.color = Color.WHITE
            canvas.drawText("♫", rect.centerX(), rect.centerY() + side * .14f, text)
        }
        canvas.restoreToCount(saved)
        text.textAlign = Paint.Align.LEFT
        val x = if (stacked) w * .08f else rect.right + w * .055f
        val available = (w * .93f - x).coerceAtLeast(1f)
        val showSpeed = ClusterPlayerSettings.speed(context)
        val speedSize = minOf(h * .20f, w * .17f)
        text.textSize = speedSize
        val speedLeft = w * .91f - text.measureText("888")
        val metadataWidth = if (showSpeed && !stacked)
            (minOf(w * .93f, speedLeft - w * .025f) - x).coerceAtLeast(1f)
        else available
        val scale = resources.configuration.fontScale.coerceIn(1f, 1.5f)
        var base = (minOf(h * (if (stacked) .070f else .16f), available * .085f) * scale).coerceAtMost(h * .18f)
        val showVehicle = ClusterPlayerSettings.vehicle(context)
        val timeSize = minOf(backgroundHeight * .033f * scale, h * .063f)
        val barY = if (stacked) h * (if (showVehicle) .80f else .84f)
            else rect.bottom - timeSize * 2.15f - (if (showVehicle) h * .045f else 0f)
        val titleY = if (stacked) h * .61f else h * .30f
        text.textSize = base; text.color = Color.WHITE
        val title = snapshot.info.title ?: context.getString(R.string.settings_cluster_player_idle)
        fun titleBlock() = StaticLayout.Builder.obtain(title, 0, title.length, text, metadataWidth.toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false).setLineSpacing(0f, 1.02f).build()
        var titleLayout = titleBlock()
        var titleTop = titleY + text.fontMetrics.ascent
        // Reserve actual room for artist/album before the progress bar, including large fonts.
        repeat(10) {
            val metadataBottom = titleTop + titleLayout.height + base * 1.42f
            if (metadataBottom > barY - h * .025f) {
                base *= .90f; text.textSize = base
                titleLayout = titleBlock(); titleTop = titleY + text.fontMetrics.ascent
            }
        }
        val titleSave = canvas.save(); canvas.translate(x, titleTop); titleLayout.draw(canvas); canvas.restoreToCount(titleSave)
        val artistY = titleTop + titleLayout.height + base * .68f
        line(canvas, snapshot.info.artist.orEmpty(), x, artistY, metadataWidth, base * .53f, 235)
        line(canvas, snapshot.info.album.orEmpty(), x, artistY + base * .61f, metadataWidth, base * .43f, 205)
        val barHeight = maxOf(3f, backgroundHeight * .008f)
        paint.color = Color.argb(65, 255, 255, 255)
        canvas.drawRoundRect(x, barY, x + available, barY + barHeight, barHeight, barHeight, paint)
        val duration = snapshot.info.durationMillis?.takeIf { it > 0 }
        val elapsed = elapsed(snapshot, now)
        if (duration != null && elapsed != null) {
            paint.color = Color.WHITE
            canvas.drawRoundRect(x, barY, x + available * (elapsed.toFloat() / duration).coerceIn(0f, 1f), barY + barHeight, barHeight, barHeight, paint)
        }
        line(canvas, formatTime(elapsed), x, barY + timeSize * 1.9f, available / 2, timeSize, 225)
        text.textAlign = Paint.Align.RIGHT
        text.textSize = timeSize
        canvas.drawText(formatTime(duration), x + available, barY + timeSize * 1.9f, text)
        if (showSpeed) {
            text.textAlign = Paint.Align.RIGHT; text.color = Color.WHITE
            text.textSize = speedSize
            canvas.drawText(speedKmh?.toString() ?: "—", w * .91f, h * .23f, text)
            text.textSize = minOf(h * .05f, w * .05f)
            canvas.drawText("km/h", w * .91f, h * .30f, text)
        }
        if (showVehicle) {
            val current = vehicle.fresh(now)
            val numbers = NumberFormat.getIntegerInstance(resources.configuration.locales[0])
            val percent = current.batteryPercent?.let { numbers.format(kotlin.math.round(it)) + "%" } ?: "—"
            val range = current.rangeKm?.let { numbers.format(it) + " km" } ?: "—"
            val value = context.getString(R.string.cluster_player_vehicle_readout, current.gear ?: "—", percent, range)
            text.textAlign = Paint.Align.LEFT; text.color = Color.argb(230,255,255,255)
            text.textSize = minOf(backgroundHeight * .034f * scale, w * .033f)
            fun footer() = StaticLayout.Builder.obtain(value,0,value.length,text,(w*.85f).toInt().coerceAtLeast(1))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(2).setIncludePad(false)
                .setEllipsize(TextUtils.TruncateAt.END).build()
            var layout = footer()
            if (layout.height > h * .095f) {
                text.textSize *= h * .095f / layout.height
                layout = footer()
            }
            val save = canvas.save(); canvas.translate(w*.075f,h*.90f)
            layout.draw(canvas); canvas.restoreToCount(save)
        }
        canvas.restoreToCount(foreground)
        main.removeCallbacks(frame)
        // 20 fps for slow gradients, no animation or progress polling while paused/hidden.
        if (snapshot.info.playing && isShown && windowVisibility == VISIBLE) main.postDelayed(frame, 50)
    }

    private fun line(canvas: Canvas, value: String, x: Float, y: Float, available: Float, size: Float, alpha: Int) {
        text.textAlign = Paint.Align.LEFT; text.textSize = size; text.color = Color.argb(alpha, 255, 255, 255)
        canvas.drawText(TextUtils.ellipsize(value, text, available, TextUtils.TruncateAt.END).toString(), x, y, text)
    }

    companion object {
        internal fun elapsed(snapshot: ClusterMusicState.Snapshot, now: Long): Long? {
            val start = snapshot.info.elapsedMillis?.coerceAtLeast(0) ?: return null
            val value = start + if (snapshot.info.playing) (now - snapshot.positionAt).coerceAtLeast(0) else 0
            return snapshot.info.durationMillis?.takeIf { it > 0 }?.let { value.coerceAtMost(it) } ?: value
        }
        internal fun formatTime(value: Long?): String = value?.let {
            val seconds = it.coerceAtLeast(0) / 1_000
            "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        } ?: "—:—"
        internal fun palette(art: Bitmap?): IntArray {
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
