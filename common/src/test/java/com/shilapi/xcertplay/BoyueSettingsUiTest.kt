package com.shilapi.xcertplay

import android.content.Intent
import android.app.AlertDialog
import android.os.Looper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog
import java.time.Duration
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BoyueSettingsUiTest {
    @Test @Config(qualifiers = "zh-rCN-w360dp-h800dp-mdpi")
    fun compactLargeFont() = capture("compact")

    @Test @Config(qualifiers = "zh-rCN-w1280dp-h800dp-mdpi")
    fun fullLargeFont() = capture("full")

    @Test @Config(qualifiers = "ar-w1280dp-h800dp-mdpi")
    fun arabicLargeFont() = capture("arabic")

    @Test @Config(qualifiers = "zh-rCN-w360dp-h800dp-mdpi")
    fun panoramaCompactLargeFont() = capture("panorama-compact", DashboardStyle.PANORAMA)

    @Test @Config(qualifiers = "zh-rCN-w1280dp-h800dp-mdpi")
    fun panoramaFullLargeFont() = capture("panorama-full", DashboardStyle.PANORAMA)

    @Test @Config(qualifiers = "ar-w1280dp-h800dp-mdpi")
    fun panoramaArabicLargeFont() = capture("panorama-arabic", DashboardStyle.PANORAMA)

    @Test @Config(qualifiers = "zh-rCN-w360dp-h800dp-mdpi")
    fun replicaCompactLargeFont() = capture("replica-compact", DashboardStyle.REPLICA)

    @Test @Config(qualifiers = "zh-rCN-w1280dp-h800dp-mdpi")
    fun replicaFullLargeFont() = capture("replica-full", DashboardStyle.REPLICA)

    @Test @Config(qualifiers = "ar-w1280dp-h800dp-mdpi")
    fun replicaArabicLargeFont() = capture("replica-arabic", DashboardStyle.REPLICA)

    @Test @Config(qualifiers = "zh-rCN-w360dp-h800dp-mdpi")
    fun overlayCompactLargeFont() = capture("overlay-compact", DashboardStyle.OEM_OVERLAY)

    @Test @Config(qualifiers = "zh-rCN-w1280dp-h800dp-mdpi")
    fun overlayFullLargeFont() = capture("overlay-full", DashboardStyle.OEM_OVERLAY)

    @Test @Config(qualifiers = "ar-w1280dp-h800dp-mdpi")
    fun overlayArabicLargeFont() = capture("overlay-arabic", DashboardStyle.OEM_OVERLAY)

    @Test fun classicAutomaticChoiceSavesWithoutChangingCarPlayOrDashboardStyle() {
        val app = RuntimeEnvironment.getApplication()
        BoyueLSettings.select(app, true)
        NativeDashboardSettings.setStyle(app, DashboardStyle.REPLICA)
        val phoneMode = AirPlayPersistence.loadCarPlayNightMode(app)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
        val screen = controller.get()
        fun nodes(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
                ReflectionHelpers.ClassParameter.from(SettingsCategory::class.java, SettingsCategory.ADVANCED))
            val title = screen.getString(R.string.settings_dashboard_appearance)
            nodes(screen.window.decorView).filterIsInstance<android.widget.Button>()
                .single { it.text.startsWith(title + " · ") }.performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val index = DashboardAppearance.AUTO.ordinal
            dialog.listView.performItemClick(null, index, index.toLong())
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(DashboardAppearance.AUTO, NativeDashboardSettings.appearance(app))
            assertEquals(DashboardStyle.REPLICA, NativeDashboardSettings.style(app))
            assertEquals(phoneMode, AirPlayPersistence.loadCarPlayNightMode(app))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun wheelLongPressChoiceSavesIndependentlyFromShortPress() {
        val app = RuntimeEnvironment.getApplication()
        BoyueLSettings.select(app, true)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
        val screen = controller.get()
        fun nodes(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
                ReflectionHelpers.ClassParameter.from(SettingsCategory::class.java, SettingsCategory.VEHICLE))
            val title = screen.getString(R.string.settings_boyue_long_press, screen.getString(R.string.settings_boyue_key_mode))
            nodes(screen.window.decorView).filterIsInstance<android.widget.Button>()
                .single { it.text.startsWith(title + " · ") }.performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val index = BoyueWheelAction.CYCLE_DASHBOARD.ordinal
            dialog.listView.performItemClick(null, index, index.toLong())
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val key = NativeDashboardSettings.modeKey(app)
            assertEquals(BoyueWheelAction.CYCLE_DASHBOARD, BoyueLSettings.action(app,key,true))
            assertEquals(BoyueWheelAction.TOGGLE_DASHBOARD, BoyueLSettings.action(app,key,false))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun pageKeyRequiresConfirmationAndLeavingTheScreenCancelsLearning() {
        val app = RuntimeEnvironment.getApplication()
        BoyueLSettings.select(app, true)
        NativeDashboardSettings.setPageKey(app, 0)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
        val screen = controller.get()
        fun learn() = ReflectionHelpers.callInstanceMethod<Unit>(screen, "startDashboardPageKeyLearning")
        fun press(code: Int) {
            ReflectionHelpers.callInstanceMethod<Unit>(GeelyWheelKeys, "forward",
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, code),
                ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, false))
            shadowOf(Looper.getMainLooper()).idle()
        }
        try {
            learn(); press(200082) // A test candidate, never a default physical-key assignment.
            assertEquals(0, NativeDashboardSettings.pageKey(app))
            val refused = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(refused.isShowing)
            assertTrue(shadowOf(refused).message.toString().contains("200082"))
            refused.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            // AlertDialog dispatches button callbacks and dismissal through the main handler.
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(refused.isShowing)
            assertEquals(0, NativeDashboardSettings.pageKey(app))
            learn(); press(200082)
            val confirmed = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(confirmed.isShowing)
            assertEquals(0, NativeDashboardSettings.pageKey(app))
            confirmed.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(confirmed.isShowing)
            assertEquals(200082, NativeDashboardSettings.pageKey(app))
            learn()
            val waiting = ShadowAlertDialog.getLatestAlertDialog()
            controller.pause()
            assertFalse(waiting.isShowing)
            press(210001)
            assertEquals("A paused activity must not learn a replacement",200082, NativeDashboardSettings.pageKey(app))
        } finally {
            controller.pause().stop().destroy()
            GeelyWheelKeys.stopDashboard(); GeelyWheelKeys.stop()
            NativeDashboardSettings.setPageKey(app,0)
        }
    }

    @Test fun pageKeyLearningTimesOutWithoutChangingAnExistingBinding() {
        val app = RuntimeEnvironment.getApplication()
        BoyueLSettings.select(app,true)
        NativeDashboardSettings.setPageKey(app,200082)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app,DiPlayActivity::class.java).putExtra("page","settings")).setup()
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(controller.get(),"startDashboardPageKeyLearning")
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(46))
            assertFalse(dialog.isShowing)
            assertEquals(200082,NativeDashboardSettings.pageKey(app))
        } finally {
            controller.pause().stop().destroy()
            GeelyWheelKeys.stopDashboard(); GeelyWheelKeys.stop()
            NativeDashboardSettings.setPageKey(app,0)
        }
    }

    private fun capture(name: String, style: DashboardStyle = DashboardStyle.MIDNIGHT) {
        val app = RuntimeEnvironment.getApplication()
        RuntimeEnvironment.setFontScale(1.5f)
        BoyueLSettings.select(app, true)
        NativeDashboardSettings.setStyle(app, style)
        if (style == DashboardStyle.REPLICA) NativeDashboardSettings.setAppearance(app, DashboardAppearance.AUTO)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
        val screen = controller.get()
        try {
            File("build/reports/boyue-ui").mkdirs()
            File("build/reports/boyue-ui/$name-geely-icon.png").writeBytes(CarButtonBranding.defaultIconPng(screen.resources))
            for (category in listOf(SettingsCategory.VEHICLE, SettingsCategory.ADVANCED, SettingsCategory.ABOUT)) {
                ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
                    ReflectionHelpers.ClassParameter.from(SettingsCategory::class.java, category))
                val root = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val metrics = screen.resources.displayMetrics
                root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
                fun texts(view: View): List<String> = (if (view is TextView) listOf(view.text.toString()) else emptyList()) +
                    (if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList())
                val expected = screen.getString(when (category) {
                    SettingsCategory.VEHICLE -> R.string.settings_boyue_vehicle
                    SettingsCategory.ABOUT -> R.string.settings_disable_app_update_prompts
                    else -> R.string.settings_boyue_cluster
                })
                assertTrue("Boyue settings must be reachable in $category", texts(root).any { it.contains(expected) })
                if (category == SettingsCategory.VEHICLE) {
                    val keys = listOf(R.string.settings_boyue_key_mode,
                        R.string.settings_boyue_key_play, R.string.settings_boyue_key_next,
                        R.string.settings_boyue_key_previous)
                    for (key in listOf(R.string.settings_boyue_key_menu,R.string.settings_boyue_key_voice)) {
                        for (press in listOf(R.string.settings_boyue_short_press,R.string.settings_boyue_long_press)) {
                            val removed=screen.getString(press,screen.getString(key))
                            assertFalse(texts(root).any { it.startsWith("$removed · ") })
                        }
                    }
                    val titles = keys.flatMap { key -> listOf(R.string.settings_boyue_short_press, R.string.settings_boyue_long_press)
                        .map { screen.getString(it, screen.getString(key)) } }
                    for (title in titles) assertTrue("Missing wheel action: $title", texts(root).any { it.startsWith("$title · ") })
                    fun checkRows(view: View) {
                        if (view is android.widget.Button && titles.any { view.text.startsWith("$it · ") }) {
                            val layout = requireNotNull(view.layout)
                            assertTrue("Clipped setting: ${view.text}",
                                layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
                            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                            view.draw(Canvas(image))
                            val index = titles.indexOfFirst { view.text.startsWith("$it · ") }
                            File("build/reports/boyue-ui/$name-wheel-$index.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
                            image.recycle()
                        }
                        if (view is ViewGroup) (0 until view.childCount).forEach { checkRows(view.getChildAt(it)) }
                    }
                    checkRows(root)
                }
                if (category == SettingsCategory.ADVANCED) {
                    for (removed in listOf(R.string.settings_boyue_ultra, R.string.settings_boyue_vehicle_data,
                        R.string.settings_boyue_map, R.string.settings_boyue_diagnostics, R.string.settings_dashboard_page_key)) {
                        assertFalse("Removed entry must not remain: $removed", texts(root).any { it.startsWith(screen.getString(removed)) })
                    }
                    assertTrue(texts(root).any { it.startsWith(screen.getString(R.string.settings_dashboard_title)) })
                    assertTrue(texts(root).contains(screen.getString(R.string.settings_dashboard_preview)))
                    assertTrue(texts(root).contains(screen.getString(if (style == DashboardStyle.OEM_OVERLAY)
                        R.string.settings_dashboard_oem_overlay_toggle else R.string.settings_dashboard_projection_toggle)))
                    if (style == DashboardStyle.OEM_OVERLAY) {
                        val rows = listOf(R.string.settings_overlay_path, R.string.settings_overlay_output).map(screen::getString)
                        for (row in rows) assertTrue(texts(root).any { it.startsWith("$row · ") })
                        fun captureOverlayRows(view: View) {
                            if (view is android.widget.Button && rows.any { view.text.startsWith("$it · ") }) {
                                assertTrue("Overlay setting clipped", view.layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
                                val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                                view.draw(Canvas(image))
                                val index = rows.indexOfFirst { view.text.startsWith("$it · ") }
                                File("build/reports/boyue-ui/$name-overlay-row-$index.png").outputStream().use {
                                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                                }
                                image.recycle()
                            }
                            if (view is ViewGroup) (0 until view.childCount).forEach { captureOverlayRows(view.getChildAt(it)) }
                        }
                        captureOverlayRows(root)
                    }
                    assertEquals(style == DashboardStyle.REPLICA, texts(root).any { it.startsWith(screen.getString(R.string.settings_dashboard_appearance) + " · ") })
                    if (style == DashboardStyle.REPLICA) {
                        assertTrue(texts(root).any { it.contains(screen.getString(R.string.settings_dashboard_replica)) })
                        assertEquals(listOf(DashboardMode.MUSIC,DashboardMode.NAVIGATION,DashboardMode.TIRES),NativeDashboardSettings.modes(screen))
                    }
                    fun captureContentKey(view: View) {
                        val mapRows = mapOf(R.string.settings_dashboard_appearance to "appearance", R.string.settings_dashboard_map_size to "map-size",
                            R.string.settings_dashboard_map_position to "map-position")
                        val mapRow = mapRows.keys.firstOrNull { view is android.widget.Button && view.text.startsWith(screen.getString(it) + " · ") }
                        if (view is android.widget.Button && mapRow != null) {
                            assertTrue("Map size row must not be clipped", view.layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
                            val mapBitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                            view.draw(Canvas(mapBitmap))
                            File("build/reports/boyue-ui/$name-${mapRows[mapRow]}.png").outputStream().use {
                                assertTrue(mapBitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                            }
                            mapBitmap.recycle()
                        }
                        if (view is android.widget.Button && view.text.startsWith(screen.getString(R.string.settings_dashboard_page_key) + " · ")) {
                            val layout = requireNotNull(view.layout)
                            assertTrue("Content-key setting must not be clipped", layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
                            val keyBitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                            view.draw(Canvas(keyBitmap))
                            File("build/reports/boyue-ui/$name-content-key.png").outputStream().use { assertTrue(keyBitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
                            keyBitmap.recycle()
                        }
                        if (view is ViewGroup) (0 until view.childCount).forEach { captureContentKey(view.getChildAt(it)) }
                    }
                    captureContentKey(root)
                    assertTrue(texts(root).any { it.startsWith(screen.getString(R.string.settings_dashboard_map_size) + " · ") })
                    assertTrue(texts(root).contains(screen.getString(R.string.settings_dashboard_map_size_hint)))
                    assertTrue(texts(root).any { it.startsWith(screen.getString(R.string.settings_dashboard_map_position) + " · ") })
                    assertTrue(texts(root).contains(screen.getString(R.string.settings_dashboard_map_position_hint)))
                    if (style == DashboardStyle.PANORAMA) {
                        assertTrue(texts(root).any { it.contains(screen.getString(R.string.settings_dashboard_map_music)) })
                        assertEquals(listOf(DashboardMode.MUSIC,DashboardMode.TIRES),NativeDashboardSettings.modes(screen))
                    }
                }
                if (category == SettingsCategory.ABOUT) {
                    fun findToggle(view: View): View? {
                        if (view is android.widget.Switch && view.contentDescription == expected) return view
                        if (view is ViewGroup) for (i in 0 until view.childCount) {
                            findToggle(view.getChildAt(i))?.let { return it }
                        }
                        return null
                    }
                    val target = requireNotNull(findToggle(root))
                    val row = target.parent as View
                    val preview = Bitmap.createBitmap(row.width, row.height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(preview)
                    canvas.drawColor(android.graphics.Color.rgb(21, 31, 44))
                    row.draw(canvas)
                    File("build/reports/boyue-ui/$name-update-controls.png").outputStream().use {
                        assertTrue(preview.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                    preview.recycle()
                }
                if (category == SettingsCategory.VEHICLE) {
                    val title = screen.getString(R.string.settings_boyue_short_press, screen.getString(R.string.settings_boyue_key_mode))
                    fun findRow(view: View): View? {
                        if (view is android.widget.Button && view.text.startsWith(title + " · ")) return view
                        if (view is ViewGroup) for (i in 0 until view.childCount) findRow(view.getChildAt(i))?.let { return it }
                        return null
                    }
                    val row = requireNotNull(findRow(root))
                    var ancestor = row.parent
                    while (ancestor != null && ancestor !is android.widget.ScrollView) ancestor = ancestor.parent
                    (ancestor as? android.widget.ScrollView)?.let { scroll ->
                        val rect = android.graphics.Rect(0,0,row.width,row.height)
                        scroll.offsetDescendantRectToMyCoords(row,rect)
                        scroll.scrollTo(0, rect.top)
                    }
                }
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                val folder = File("build/reports/boyue-ui").apply { mkdirs() }
                File(folder, "$name-${category.name.lowercase()}.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                bitmap.recycle()
            }
        } finally { controller.pause().stop().destroy() }
    }
}
