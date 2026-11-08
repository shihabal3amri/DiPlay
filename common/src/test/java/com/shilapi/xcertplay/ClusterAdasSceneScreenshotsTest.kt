package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.AdasCarColor
import com.shilapi.xcertplay.hud.BydClusterAdasPlacement
import com.shilapi.xcertplay.hud.BydClusterAdasPlacementControl
import com.shilapi.xcertplay.hud.BydOutputSettings
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

/** The ADAS scene card with its car color picker stays usable at a large font on a narrow window, full width and right to left. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClusterAdasSceneScreenshotsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private var previousFontScale = 1f

    private class InstalledPlacement : BydClusterAdasPlacementControl {
        override val status = BydClusterAdasPlacement.Status(BydClusterAdasPlacement.State.CURRENT)
        override val busy = false
        override var onChange: (() -> Unit)? = null
        override fun refreshIfStale(app: Context) = Unit
        override fun install(app: Context, color: AdasCarColor?, done: (BydClusterAdasPlacement.Outcome) -> Unit) = Unit
        override fun remove(app: Context, done: (BydClusterAdasPlacement.Outcome) -> Unit) = Unit
        override fun applyColor(app: Context, color: AdasCarColor?, done: (BydClusterAdasPlacement.Outcome) -> Unit) = Unit
        override fun settingChanged(app: Context) = Unit
    }

    @After fun tearDown() {
        RuntimeEnvironment.setQualifiers("en")
        context.resources.updateConfiguration(
            Configuration(context.resources.configuration).apply { fontScale = previousFontScale },
            context.resources.displayMetrics,
        )
        BydOutputSettings.setAdasCarColor(context, null)
    }

    @Test fun theCardAndItsPickerStayUsableAtLargeFontInCompactFullAndRtl() {
        previousFontScale = context.resources.configuration.fontScale
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.example.amapservice"
            applicationInfo = ApplicationInfo().apply { packageName = "com.example.amapservice"; flags = ApplicationInfo.FLAG_SYSTEM }
        })
        BydOutputSettings.setAdasCarColor(context, AdasCarColor.PRESETS.first { it.key == "blue" }.color)
        for ((name, qualifiers, widthDp) in listOf(
            Triple("compact-en", "en-w360dp-h900dp-mdpi", 360),
            Triple("full-en", "en-w1100dp-h900dp-mdpi", 1100),
            Triple("compact-ar", "ar-rSA-ldrtl-w360dp-h900dp-mdpi", 360),
        )) {
            RuntimeEnvironment.setQualifiers(qualifiers)
            context.resources.updateConfiguration(
                Configuration(context.resources.configuration).apply { fontScale = 1.5f },
                context.resources.displayMetrics,
            )
            val screen = Robolectric.buildActivity(DiPlayActivity::class.java,
                Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).create().get()
            try {
                screen.applicationInfo.flags = screen.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
                screen.adasPlacement = InstalledPlacement()
                screen.adasScenePanel = { true }
                ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.ADVANCED)
                ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
                val grid = descendants(screen.window.decorView).filterIsInstance<CarColorSwatchGrid>().single()
                val title = screen.getString(R.string.settings_adas_scene_title)
                var card: View = grid
                while (descendants(card).filterIsInstance<TextView>().none { it.text == title }) card = card.parent as View
                val width = (widthDp * screen.resources.displayMetrics.density).toInt()
                // The activity is not attached to a window here, so the direction a window would give is set by hand.
                card.layoutDirection = screen.resources.configuration.layoutDirection
                card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                card.layout(0, 0, width, card.measuredHeight)

                val swatches = (0 until grid.childCount).map(grid::getChildAt)
                assertEquals(name, 1 + AdasCarColor.PRESETS.size + 1, swatches.size)
                for (swatch in swatches) {
                    assertTrue("$name: a swatch is inside the picker", swatch.left >= 0 && swatch.right <= grid.width && swatch.width > 0)
                    assertTrue("$name: a swatch is inside the card", swatch.height > 0 && grid.top + swatch.bottom <= card.measuredHeight)
                }
                val rtl = name.endsWith("ar")
                assertTrue("$name: the first swatch starts the row", if (rtl) swatches[0].left > swatches[1].left else swatches[0].left < swatches[1].left)
                assertEquals(name, widthDp < 600, swatches.map { it.top }.toSet().size > 1)
                val labels = descendants(card).filterIsInstance<TextView>().map { it.text.toString() }.toList()
                assertTrue(name, screen.getString(R.string.settings_adas_scene_color_title) in labels)
                assertTrue(name, screen.getString(R.string.settings_adas_scene_color_description) in labels)

                val bitmap = Bitmap.createBitmap(width, card.measuredHeight, Bitmap.Config.ARGB_8888)
                try {
                    card.draw(Canvas(bitmap))
                    val folder = File("build/reports/adas-scene-ui").apply { mkdirs() }
                    FileOutputStream(File(folder, "$name.png")).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally {
                    bitmap.recycle()
                }
            } finally {
                screen.finish()
            }
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
