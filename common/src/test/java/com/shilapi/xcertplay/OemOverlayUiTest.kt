package com.shilapi.xcertplay

import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.os.Looper
import android.view.View
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OemOverlayUiTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun canvas(context: Context, mode: DashboardMode) = DashboardCanvas(context).apply {
        style = DashboardStyle.OEM_OVERLAY; this.mode = mode
        measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(532, View.MeasureSpec.EXACTLY))
        layout(0, 0, 1920, 532)
    }
    private fun render(view: View) = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }

    @Test fun emptyDisconnectedContentLeavesEveryOemPixelUntouched() {
        for (mode in DashboardMode.choices(DashboardStyle.OEM_OVERLAY)) {
            val bitmap = render(canvas(app, mode))
            val pixels = IntArray(bitmap.width * bitmap.height); bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue(pixels.all { Color.alpha(it) == 0 })
        }
    }

    @Test fun informationAndMenuHintStayWithinTheCenterAndClearCompletely() {
        val view = canvas(app, DashboardMode.ALL)
        view.snapshot = DashboardSnapshot(music = ClusterMusicState.Snapshot(CarPlayNowPlaying(title = "测试音乐", artist = "测试歌手")),
            navigation = ClusterTurnGuidance(3, 0, 800, "滨江大道"))
        view.menuPagingActive = true
        val bitmap = render(view)
        assertTrue(Color.alpha(bitmap.getPixel(620, 65)) > 0)
        assertTrue(Color.alpha(bitmap.getPixel(620, 390)) > 0)
        for (y in 0 until 532) for (x in 0 until 1920) {
            if (x < 600 || x >= 1320 || y < 48 || y >= 526)
                assertEquals("OEM pixel at $x,$y", 0, Color.alpha(bitmap.getPixel(x,y)))
        }
        view.snapshot = DashboardSnapshot(); view.menuPagingActive = false
        assertTrue(render(view).sameAs(Bitmap.createBitmap(1920, 532, Bitmap.Config.ARGB_8888)))
    }

    @Test fun overlaySelectionKeepsOldStyleOrdinalsPagesAndUsesOrdinaryCarPlay() {
        BoyueLSettings.select(app, true)
        NativeDashboardSettings.setStyle(app, DashboardStyle.REPLICA)
        NativeDashboardSettings.setMode(app, DashboardMode.TIRES)
        NativeDashboardSettings.setStyle(app, DashboardStyle.OEM_OVERLAY)
        assertEquals(4, DashboardStyle.OEM_OVERLAY.ordinal)
        assertEquals(DashboardMode.NAVIGATION, NativeDashboardSettings.mode(app))
        assertEquals(DashboardMode.MUSIC, NativeDashboardSettings.cycle(app))
        assertEquals(DashboardMode.ALL, NativeDashboardSettings.cycle(app))
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        try {
            val config = ReflectionHelpers.callInstanceMethod<com.shilapi.xcertplay.airplay.AirPlayDisplayConfig>(host, "clusterDisplayConfig")
            assertEquals(960, config.widthPixels); assertEquals(400, config.heightPixels)
            assertNotNull(config.initialUrl)
            assertEquals(com.shilapi.xcertplay.airplay.AirPlayInsets(left = 384, right = 384), config.safeArea)
            assertEquals(true, config.safeAreaDrawOutside)
        } finally {
            for (field in listOf("teardownExecutor", "airPlayCommandExecutor"))
                ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(host, field).shutdownNow()
        }
        NativeDashboardSettings.setStyle(app, DashboardStyle.REPLICA)
        assertEquals(DashboardMode.TIRES, NativeDashboardSettings.mode(app))
    }

    @Test fun missingCardsReleaseMapSpaceAndTheActualWindowUsesACropNotAFullDashboardScale() {
        assertEquals(OemOverlayPath.FACTORY, NativeDashboardSettings.overlayPath(app))
        val full = canvas(app, DashboardMode.ALL)
        val bounds = Fx11OemOverlayWindow.bounds()
        val cropped = DashboardCanvas(app, bounds).apply {
            style = DashboardStyle.OEM_OVERLAY; mode = DashboardMode.ALL
            measure(View.MeasureSpec.makeMeasureSpec(bounds.width().toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(bounds.height().toInt(), View.MeasureSpec.EXACTLY))
            layout(0, 0, measuredWidth, measuredHeight)
        }
        for (navigation in listOf(false, true)) for (music in listOf(false, true)) {
            val state = DashboardSnapshot(
                navigation = if (navigation) ClusterTurnGuidance(3, 0, 800, "滨江大道") else null,
                music = if (music) ClusterMusicState.Snapshot(CarPlayNowPlaying(title = "测试", artist = "歌手"))
                    else ClusterMusicState.Snapshot(CarPlayNowPlaying()),
            )
            full.snapshot = state; cropped.snapshot = state
            assertEquals(navigation, full.geometry.navigation != null)
            assertEquals(music, full.geometry.music != null)
            assertEquals(if (navigation) 136f else 48f, full.geometry.map!!.top, 0f)
            assertEquals(if (music) 368f else 488f, full.geometry.map!!.bottom, 0f)
            val fullImage = render(full)
            val crop = Bitmap.createBitmap(fullImage, bounds.left.toInt(), bounds.top.toInt(), bounds.width().toInt(), bounds.height().toInt())
            val actual = render(cropped)
            // Skia's rounded-rectangle edge may differ by a few coverage steps when its
            // device origin moves. Compare premultiplied colors, not unstable RGB at alpha≈0.
            var differing = 0
            for (y in 0 until actual.height) for (x in 0 until actual.width) {
                val a = actual.getPixel(x, y); val b = crop.getPixel(x, y)
                if (a == b) continue
                differing++
                assertTrue(kotlin.math.abs(Color.alpha(a) - Color.alpha(b)) <= 4)
                for (channel in listOf(Color::red, Color::green, Color::blue))
                    assertTrue(kotlin.math.abs(channel(a) * Color.alpha(a) / 255 - channel(b) * Color.alpha(b) / 255) <= 4)
            }
            assertTrue("Only a few antialiased corner pixels may differ; text and layout must match", differing <= 16)
        }
    }

    @Test fun centralMapTextureAndArtworkAreLaidOutInsideThePhysicalWindow() {
        val previousAspect = MapMirrors.STREAM_ASPECT
        try {
            // This case models OEM_OVERLAY's negotiated 960×400 stream, independently of
            // the full-dashboard tests that exercise other streams in the same JVM.
            MapMirrors.streamAspect = 960.0 / 400
            val bounds = Fx11OemOverlayWindow.bounds()
            val view = NativeDashboardView(app, viewport = bounds)
            view.artworkView.style = DashboardStyle.OEM_OVERLAY
            view.artworkView.mode = DashboardMode.ALL
            view.artworkView.snapshot = DashboardSnapshot(
                navigation = ClusterTurnGuidance(3, 0, 800, "滨江大道"),
                music = ClusterMusicState.Snapshot(CarPlayNowPlaying(title = "测试")),
            )
            view.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(478, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 720, 478)
            val texture = view.getChildAt(0)
            assertTrue(texture.width >= 500)
            assertTrue(texture.left >= 0 && texture.right <= 720)
            assertEquals(88, texture.top)
            assertTrue(texture.bottom <= 320)
            assertEquals(0f, texture.alpha, 0f) // No stream/frame: no opaque black map rectangle.
            assertEquals(720, view.artworkView.width)
            assertEquals(478, view.artworkView.height)
        } finally { MapMirrors.streamAspect = previousAspect }
    }

    @Test fun renderReviewImagesAtTwoSizesAndLargeFontIncludingRtl() {
        val output = File("build/reports/oem-overlay").apply { mkdirs() }
        for (locale in listOf("zh-CN", "ar")) for (width in listOf(1920, 960))
        for (mode in DashboardMode.choices(DashboardStyle.OEM_OVERLAY)) {
            val context = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                fontScale = 1.5f; setLocale(Locale.forLanguageTag(locale))
            })
            val view = canvas(context, mode)
            view.snapshot = DashboardSnapshot(music = ClusterMusicState.Snapshot(CarPlayNowPlaying(title = "夜曲 · 测试音乐", artist = "周杰伦")),
                navigation = ClusterTurnGuidance(3, 0, 800, "滨江大道 · 测试导航"))
            val height = width * 532 / 1920
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val c = Canvas(image)
            // Test-only neutral background and map fixture, not a claim of an OEM screenshot.
            c.drawColor(Color.rgb(14, 21, 27))
            c.save(); c.scale(width / 1920f, height / 532f)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; style = Paint.Style.STROKE; strokeWidth = 5f }
            c.drawCircle(300f, 266f, 230f, paint); c.drawCircle(1620f, 266f, 230f, paint)
            view.geometry.map?.let { box ->
                c.save(); c.clipRect(box); paint.style = Paint.Style.FILL; paint.color = Color.rgb(20, 35, 44); c.drawRect(box, paint)
                paint.color = Color.rgb(64, 160, 232); paint.strokeWidth = 14f
                c.drawLine(960f, box.bottom, 960f, box.centerY(), paint); c.drawLine(960f, box.centerY(), 1250f, box.centerY(), paint)
                c.restore()
            }
            c.restore(); view.draw(c)
            File(output, "$locale-$width-${mode.name}.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
    }
}
