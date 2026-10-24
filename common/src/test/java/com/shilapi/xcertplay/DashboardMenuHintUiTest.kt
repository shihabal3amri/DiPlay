package com.shilapi.xcertplay

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.ultra.VehicleReading
import com.shilapi.xcertplay.ultra.VehicleSignal
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-w1920dp-h532dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardMenuHintUiTest {
    @Test fun rendersAndRemovesHintOnEveryPageWithoutMovingOtherInstruments() {
        val app = RuntimeEnvironment.getApplication()
        val out = File("build/reports/dashboard-menu-hint").apply { mkdirs() }
        val album = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        Canvas(album).apply {
            drawColor(Color.rgb(41, 66, 79))
            drawCircle(120f, 96f, 65f, Paint().apply { color = Color.rgb(218, 155, 107) })
            drawRect(0f, 155f, 240f, 240f, Paint().apply { color = Color.rgb(21, 42, 57) })
        }
        // Fixtures for screenshots only; production never invents car readings or map frames.
        val state = DashboardSnapshot(mapOf(
            VehicleSignal.INDICATED_SPEED_KPH to VehicleReading(68, 0, 0),
            VehicleSignal.ENGINE_RPM to VehicleReading(1600, 0, 0),
            VehicleSignal.GEAR to VehicleReading("D", 0, 0),
            VehicleSignal.ENGINE_COOLANT_C to VehicleReading(90, 0, 0),
            VehicleSignal.INDICATED_AMBIENT_C to VehicleReading(23, 0, 0),
            VehicleSignal.INDICATED_RANGE_KM to VehicleReading(111, 0, 0),
            VehicleSignal.FUEL_PERCENT to VehicleReading(30, 0, 0),
            VehicleSignal.LOW_BEAM to VehicleReading(true, 0, 0),
            VehicleSignal.AUTO_HEADLIGHTS to VehicleReading(true, 0, 0),
            VehicleSignal.ASSIST_MODE to VehicleReading("GPILOT", 0, 0),
            VehicleSignal.ACC_STATE to VehicleReading(2, 0, 0),
            VehicleSignal.ACC_SET_SPEED_KPH to VehicleReading(80, 0, 0),
            VehicleSignal.FOLLOW_GAP to VehicleReading(2, 0, 0),
            VehicleSignal.TIRE_FL_BAR to VehicleReading(2.3, 0, 0),
            VehicleSignal.TIRE_FR_BAR to VehicleReading(2.3, 0, 0),
            VehicleSignal.TIRE_RL_BAR to VehicleReading(2.3, 0, 0),
            VehicleSignal.TIRE_RR_BAR to VehicleReading(2.3, 0, 0)),
            ClusterMusicState.Snapshot(CarPlayNowPlaying(title = "夜曲", artist = "周杰伦",
                durationMillis = 223000, elapsedMillis = 97000, playing = true), album),
            ClusterTurnGuidance(3, 0, 800, "滨江大道 · 测试导航", remainingSeconds = 720, remainingMeters = 8400))

        for (style in listOf(DashboardStyle.MIDNIGHT, DashboardStyle.PANORAMA, DashboardStyle.REPLICA))
        for (mode in DashboardMode.choices(style))
        for ((locale, width, fontScale) in listOf(Triple("zh-CN", 1920, 1f),
            Triple("zh-CN", 1920, 1.5f), Triple("zh-CN", 960, 1.5f),
            Triple("ar", 1920, 1.5f), Triple("ar", 960, 1.5f))) {
            val context = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                this.fontScale = fontScale; setLocale(Locale.forLanguageTag(locale))
            })
            val height = width * 532 / 1920
            val view = DashboardCanvas(context).apply { this.style = style; this.mode = mode; snapshot = state }
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            fun render(active: Boolean): Bitmap {
                view.menuPagingActive = active
                return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    val canvas = Canvas(bitmap)
                    if (view.geometry.map != null) {
                        canvas.save(); canvas.scale(width / 1920f, height / 532f)
                        canvas.drawColor(Color.rgb(13, 27, 35))
                        val road = Paint().apply { color = Color.rgb(55, 68, 75); strokeWidth = 3f }
                        for (x in 400..1800 step 120) canvas.drawLine(1120f + (x - 1120) * .25f, 0f, x.toFloat(), 532f, road)
                        for (y in 180..532 step 50) canvas.drawLine(300f, y.toFloat(), 1920f, y.toFloat(), road)
                        road.color = Color.rgb(48, 153, 231); road.strokeWidth = 18f
                        canvas.drawLine(1120f, 532f, 1120f, 240f, road)
                        canvas.drawLine(1120f, 240f, 1390f, 240f, road)
                        canvas.restore()
                        view.mapActive = true; view.mapWindow = view.geometry.map!!
                    }
                    view.draw(canvas)
                }
            }
            val off = render(false)
            val on = render(true)
            val cleared = render(false)
            val designBounds = DashboardMenuHint.bounds(style)
            val scale = width / 1920f
            val affected = Rect((designBounds.left * scale).toInt() - 2, (designBounds.top * scale).toInt() - 2,
                (designBounds.right * scale).toInt() + 2, (designBounds.bottom * scale).toInt() + 2)
            var changed = 0
            // Exclude the footer's wall clock; every other pixel outside the pill must be unchanged.
            for (y in 0 until height * 478 / 532) for (x in 0 until width) {
                val before = off.getPixel(x, y)
                assertEquals("Exit must remove the entire pill", before, cleared.getPixel(x, y))
                if (before != on.getPixel(x, y)) {
                    assertTrue("Hint overlapped other UI at $style/$mode/$x,$y", affected.contains(x, y))
                    changed++
                }
            }
            assertTrue("Visible even at compact resolution: $style/$mode", changed > 300)
            for ((name, bitmap) in listOf("on" to on, "off" to off)) {
                File(out, "${style.name.lowercase()}-${mode.name.lowercase()}-$locale-$width-font$fontScale-$name.png")
                    .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
            }
            cleared.recycle()
        }
        album.recycle()
    }

    @Test fun allTranslationsFitBetweenThePhysicalTrackSymbols() {
        val app = RuntimeEnvironment.getApplication()
        for (locale in listOf("en", "ar", "es", "ja", "ru", "uk", "zh-CN", "zh-TW")) {
            val context = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                fontScale = 1.5f; setLocale(Locale.forLanguageTag(locale))
            })
            val bitmap = Bitmap.createBitmap(1920, 532, Bitmap.Config.ARGB_8888)
            var texts = 0
            DashboardMenuHint(context).draw(object : Canvas(bitmap) {
                override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
                    assertEquals(context.getString(R.string.dashboard_switch_mode), text)
                    assertEquals(960f, x, .01f)
                    assertTrue(paint.measureText(text) <= 204.1f)
                    assertTrue(y + paint.fontMetrics.ascent >= 442f)
                    assertTrue(y + paint.fontMetrics.descent <= 476f)
                    texts++
                    super.drawText(text, x, y, paint)
                }
            }, DashboardStyle.REPLICA)
            assertEquals(1, texts)
            bitmap.recycle()
        }
    }
}
