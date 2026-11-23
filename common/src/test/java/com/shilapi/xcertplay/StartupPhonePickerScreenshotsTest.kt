package com.shilapi.xcertplay

import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

/** The start-up iPhone picker on a 1024×600 head unit at 1.5x text size: usual driver, new owner, RTL. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1024dp-h600dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StartupPhonePickerScreenshotsTest {
    private data class Shot(val filename: String, val widthDp: Int, val language: String, val lastUsed: String?)

    @Test fun pickerRendersForTheUsualDriverAndANewOwnerInThreeLayouts() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        val activity = controller.get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        controller.setup()
        val adapter = activity.getSystemService(BluetoothManager::class.java).adapter
        fun device(address: String, name: String): BluetoothDevice =
            adapter.getRemoteDevice(address).also { shadowOf(it).setName(name) }
        val alex = device("AA:00:00:00:00:01", "Alex’s iPhone")
        val sam = device("AA:00:00:00:00:02", "Sam’s iPhone")
        try {
            for (shot in listOf(
                Shot("usual-full-en", 560, "en-US", alex.address),
                Shot("new-owner-full-en", 560, "en-US", null),
                Shot("usual-compact-ja", 320, "ja-JP", alex.address),
                Shot("usual-compact-ar-rtl", 320, "ar-SA", alex.address),
            )) {
                val locale = Locale.forLanguageTag(shot.language)
                val config = Configuration(activity.resources.configuration).apply {
                    fontScale = 1.5f
                    setLocale(locale)
                    setLayoutDirection(locale)
                }
                @Suppress("DEPRECATION")
                activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
                val candidates = listOf(alex, sam).map {
                    StartupPhonePicker.Candidate(it.address, it.name, listOf(StartupPhonePicker.IAP2_IPHONE_UUID), isPhone = true, connected = false)
                }
                val ask = StartupPhonePicker.decide(candidates, shot.lastUsed) as StartupPhonePicker.Decision.Ask
                DiPlayActivity::class.java.getDeclaredMethod(
                    "showStartupPhonePicker", StartupPhonePicker.Decision.Ask::class.java, List::class.java, Boolean::class.java,
                ).apply { isAccessible = true }.invoke(activity, ask, listOf(alex, sam), false)
                // Attaches the dialog window. Only due tasks run, so the countdown still shows 8 s.
                shadowOf(Looper.getMainLooper()).idle()
                val dialog = ReflectionHelpers.getField<AlertDialog>(activity, "startupPickerDialog")
                val decor = dialog.window!!.decorView
                val texts = allViews(decor).filterIsInstance<TextView>().map { it.text.toString() }.toList()
                val hint = if (shot.lastUsed == null) activity.getString(R.string.startup_picker_tap)
                    else activity.getString(R.string.startup_picker_countdown, "Alex’s iPhone", 8)
                for (expected in listOf(activity.getString(R.string.startup_picker_title), "Alex’s iPhone", "Sam’s iPhone", hint)) {
                    assertTrue("${shot.filename}: $expected in $texts", expected in texts)
                }
                // With three or four iPhones on a short screen, the list and countdown scroll above the buttons.
                val hintView = allViews(decor).filterIsInstance<TextView>().first { it.text.toString() == hint }
                assertTrue(shot.filename, generateSequence(hintView.parent) { it.parent }.any { it is ScrollView })
                capture(dialog, shot.widthDp, shot.filename)
                dialog.dismiss()
                shadowOf(Looper.getMainLooper()).idle()
                assertNull(ReflectionHelpers.getField<AlertDialog?>(activity, "startupPickerDialog"))
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun capture(dialog: AlertDialog, widthDp: Int, filename: String) {
        val window = dialog.window!!
        // The window lays the dialog out at this width, as on a head unit. Re-measuring the attached
        // view by hand instead misplaces right-to-left titles and stacked buttons.
        window.setLayout((widthDp * window.context.resources.displayMetrics.density).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT)
        shadowOf(Looper.getMainLooper()).idle()
        val view = window.decorView
        assertTrue("$filename was not laid out", view.width > 0 && view.height > 0)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val directory = File("build/reports/startup-phone-picker").apply { mkdirs() }
            FileOutputStream(File(directory, "$filename.png")).use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun allViews(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(allViews(view.getChildAt(i)))
    }
}
