package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.host.R
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers

/** When a write over the car's adb counts as started, and what the driver is offered when it does not. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w600dp-h700dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WheelKeyRestartOfferTest {
    private lateinit var activity: DiPlayActivity
    private var service: WheelKeyService? = null

    @Before fun setUp() {
        bind()
        unbind()
        WheelKeyServiceReset.resetForTest()
        WheelKeyLog.reset()
        val context = RuntimeEnvironment.getApplication()
        WheelZoomSettings.setMediaPlayPause(context, true)
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ComponentName(context, WheelKeyService::class.java).flattenToString())
        activity = Robolectric.buildActivity(
            DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"),
        ).setup().get()
    }

    @After fun tearDown() {
        bind()
        unbind()
        WheelKeyServiceReset.resetForTest()
        WheelZoomSettings.setMediaPlayPause(activity, false)
        WheelKeyLog.reset()
        activity.finish()
        RuntimeEnvironment.setFontScale(1f)
        AppAppearanceRuntime.resetForTest()
    }

    private fun bind() {
        service = Robolectric.buildService(WheelKeyService::class.java).create().get().also {
            it.javaClass.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(it)
        }
    }

    private fun unbind() {
        service?.onDestroy()
        service = null
    }

    private fun fail() = assertFalse(WheelKeyService.settle(activity, { null }, {}))

    @Test fun theBoundStateIsReadOnlyOnceAndroidHadTimeToStartTheService() {
        val sleeps = mutableListOf<Long>()
        assertTrue(WheelKeyService.waitUntilBound({ sleeps.size == 3 }) { sleeps += it })
        assertEquals(750L, sleeps.sum())
        sleeps.clear()
        assertFalse(WheelKeyService.waitUntilBound({ false }) { sleeps += it })
        assertEquals(5_000L, sleeps.sum())
    }

    @Test fun aServiceThatStartsInTimeIsReportedAsStarted() {
        var dumps = 0
        assertTrue(WheelKeyService.settle(activity, { dumps++; null }, { if (service == null) bind() }))
        assertEquals(0, dumps)
        assertFalse(WheelKeyService.restartOffered(activity))
        assertTrue(WheelKeyLog.report(true).last().endsWith("  wheel key service allowed over adb, started"))
    }

    @Test fun aServiceThatStartsWhileTheListsAreReadIsReportedAsStarted() {
        assertTrue(WheelKeyService.settle(activity, { bind(); null }, {}))
        assertFalse(WheelKeyService.restartOffered(activity))
    }

    @Test fun theRestartIsOfferedAfterAFailedWriteUntilTheServiceStarts() {
        assertFalse(WheelKeyService.restartOffered(activity))
        fail()
        assertTrue(WheelKeyService.restartOffered(activity))
        bind()
        assertFalse(WheelKeyService.restartOffered(activity))
        // The start cleared it: a later stop is a new story.
        unbind()
        assertFalse(WheelKeyService.restartOffered(activity))
    }

    @Test fun aServiceThatStartsRightAfterItWasAskedForIsNotLeftAsFailed() {
        var read = false
        WheelKeyService.settle(activity, { read = true; null }, {}) {
            WheelKeyService.connected().also { if (read && service == null) bind() }
        }
        unbind()
        assertFalse(WheelKeyService.restartOffered(activity))
    }

    @Test fun aServiceFoundOnTheLastCheckIsReportedAsStarted() {
        var read = false
        assertTrue(WheelKeyService.settle(activity, { read = true; null }, {}) { read })
        assertFalse(WheelKeyService.restartOffered(activity))
        assertTrue(WheelKeyLog.report(true).last().endsWith("  wheel key service allowed over adb, started"))
    }

    @Test fun aServiceThatTheDriverTurnedOffIsOffAndNothingElse() {
        openVehicleSettings()
        fail()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertNotNull(restartButton())
        Settings.Secure.putString(activity.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertFalse(WheelKeyService.restartOffered(activity))
        assertNull(restartButton())
        val shown = texts().map { it.text.toString() }.toList()
        assertTrue(activity.getString(R.string.wheel_keys_service_off) in shown)
        assertFalse(activity.getString(R.string.settings_wheel_keys_restart_description) in shown)
    }

    @Test fun aSecondConfirmationDoesNothingWhileTheFirstOneRuns() {
        val answer = CountDownLatch(1)
        val connects = AtomicInteger()
        WheelKeyServiceReset.openAdb = {
            object : WheelKeyServiceReset.Adb {
                override fun connect(mayAsk: Boolean): LocalAdb.Access {
                    connects.incrementAndGet()
                    answer.await(10, TimeUnit.SECONDS)
                    return LocalAdb.Access.NOT_APPROVED
                }
                override fun shell(command: String): String? = null
                override fun close() = Unit
            }
        }
        openVehicleSettings()
        fail()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        val first = restartButton()!!
        // A double tap opens the question twice.
        first.performClick()
        val question = ShadowDialog.getLatestDialog() as AlertDialog
        first.performClick()
        val again = ShadowDialog.getLatestDialog() as AlertDialog
        question.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        await { connects.get() == 1 }
        again.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        await { Thread.getAllStackTraces().keys.count { it.name == "diplay-wheel-keys-restart" } == 1 }
        assertEquals(1, connects.get())

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertNull(restartButton())
        val running = texts().filterIsInstance<Button>().single { it.text.toString() == activity.getString(R.string.adb_checking) }
        assertFalse(running.isEnabled)

        answer.countDown()
        await { !WheelKeyServiceReset.restarting() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertTrue(restartButton()!!.isEnabled)
    }

    private fun await(done: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!done()) {
            assertTrue("timed out", System.nanoTime() < end)
            Thread.sleep(5)
        }
    }

    @Test fun theButtonAppearsOnTheOpenPageAfterAFailedWriteAndAsksBeforeItDoesAnything() {
        openVehicleSettings()
        assertTrue(texts().any { it.text == activity.getString(R.string.wheel_keys_service_starting) })
        assertNull(restartButton())
        fail()
        // The page is already open: its one-second tick draws the card again.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        ShadowDialog.reset()
        restartButton()!!.performClick()
        assertTrue(ShadowDialog.getLatestDialog().isShowing)
        // Nothing is sent or stored before the driver confirms.
        assertEquals(WheelKeyServiceReset.Outcome.NONE, WheelKeyServiceReset.load(activity).outcome)
        ShadowDialog.getLatestDialog().dismiss()

        bind()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertNull(restartButton())
        assertTrue(texts().any { it.text == activity.getString(R.string.wheel_keys_service_on) })
    }

    @Test
    @Config(sdk = [29], qualifiers = "en-w960dp-h900dp")
    fun fullScreenshot() = screenshot("full-en")

    @Test
    @Config(sdk = [29], qualifiers = "ru-w320dp-h1400dp-xhdpi")
    fun compactScreenshot() = screenshot("compact-ru")

    @Test
    @Config(sdk = [29], qualifiers = "ar-ldrtl-w320dp-h1400dp-xhdpi")
    fun compactRightToLeftScreenshot() = screenshot("compact-ar-rtl")

    /** At 1.5x text size, with the Settings page's own spacing. */
    private fun screenshot(name: String) {
        RuntimeEnvironment.setFontScale(1.5f)
        openVehicleSettings()
        fail()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val metrics = activity.resources.displayMetrics
        root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
        shadowOf(Looper.getMainLooper()).idle()
        val button = restartButton()!!
        for (line in 0 until button.layout.lineCount) assertEquals(0, button.layout.getEllipsisCount(line))
        val scroll = ReflectionHelpers.getField<ScrollView>(activity, "rootScroll")
        val top = Rect().also { button.getDrawingRect(it); scroll.offsetDescendantRectToMyCoords(button, it) }.top
        scroll.scrollTo(0, (top - metrics.heightPixels / 2).coerceAtLeast(0))
        val bitmap = Bitmap.createBitmap(metrics.widthPixels, metrics.heightPixels, Bitmap.Config.ARGB_8888)
        try {
            // View.draw() leaves a view's own scroll offset to its parent.
            root.draw(Canvas(bitmap).apply { translate(-root.scrollX.toFloat(), -root.scrollY.toFloat()) })
            val directory = File("build/reports/wheel-restart-ui").apply { mkdirs() }
            FileOutputStream(File(directory, "$name.png")).use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun openVehicleSettings() {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "openSettingsCategory",
            ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.VEHICLE))
    }

    private fun texts() = allViews(activity.window.decorView).filterIsInstance<TextView>()

    private fun restartButton(): Button? = texts().filterIsInstance<Button>().firstOrNull {
        it.text.toString() == activity.getString(R.string.settings_wheel_keys_restart)
    }

    private fun allViews(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(allViews(view.getChildAt(i)))
    }
}
