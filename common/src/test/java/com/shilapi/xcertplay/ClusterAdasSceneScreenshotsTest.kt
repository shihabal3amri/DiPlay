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
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydClusterAdasPlacement
import com.shilapi.xcertplay.hud.BydClusterAdasPlacementControl
import java.io.File
import java.io.FileOutputStream
import org.junit.After
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

/** The ADAS scene card, with every control showing, stays inside its card at a large font on a narrow window, full width and right to left. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClusterAdasSceneScreenshotsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private var previousFontScale = 1f

    /** An out-of-date file shows the status line and both buttons, the fullest the card gets. */
    private class OutOfDatePlacement : BydClusterAdasPlacementControl {
        override val status = BydClusterAdasPlacement.Status(BydClusterAdasPlacement.State.STALE)
        override val busy = false
        override var onChange: (() -> Unit)? = null
        override fun refreshIfStale(app: Context) = Unit
        override fun install(app: Context, done: (BydClusterAdasPlacement.Outcome) -> Unit) = Unit
        override fun remove(app: Context, done: (BydClusterAdasPlacement.Outcome) -> Unit) = Unit
        override fun settingChanged(app: Context) = Unit
    }

    @After fun tearDown() {
        RuntimeEnvironment.setQualifiers("en")
        context.resources.updateConfiguration(
            Configuration(context.resources.configuration).apply { fontScale = previousFontScale },
            context.resources.displayMetrics,
        )
    }

    @Test fun theCardStaysUsableAtLargeFontInCompactFullAndRtl() {
        previousFontScale = context.resources.configuration.fontScale
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.example.amapservice"
            applicationInfo = ApplicationInfo().apply { packageName = "com.example.amapservice"; flags = ApplicationInfo.FLAG_SYSTEM }
        })
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
                screen.adasPlacement = OutOfDatePlacement()
                screen.adasScenePanel = { true }
                ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.ADVANCED)
                ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
                val toggle = descendants(screen.window.decorView).filterIsInstance<Switch>()
                    .single { it.contentDescription == screen.getString(R.string.settings_adas_scene_toggle) }
                val title = screen.getString(R.string.settings_adas_scene_title)
                var card: View = toggle
                while (descendants(card).filterIsInstance<TextView>().none { it.text == title }) card = card.parent as View
                val width = (widthDp * screen.resources.displayMetrics.density).toInt()
                // The activity is not attached to a window here, so the direction a window would give is set by hand.
                card.layoutDirection = screen.resources.configuration.layoutDirection
                card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                card.layout(0, 0, width, card.measuredHeight)

                val labels = descendants(card).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }.toList()
                val texts = labels.map { it.text.toString() }
                assertTrue(name, screen.getString(R.string.settings_adas_scene_description) in texts)
                assertTrue(name, screen.getString(R.string.settings_adas_scene_status_stale) in texts)
                val buttons = labels.filter { it is Button && it !is Switch }
                assertTrue(name, buttons.size == 2)
                for (view in labels) assertTrue("$name: ${view.text} fits the card", view.measuredWidth in 1..width && view.measuredHeight > 0)

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
