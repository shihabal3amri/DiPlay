package com.shilapi.xcertplay

import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.os.Looper
import java.time.Duration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Head units keep DiPlay in memory between drives: each drive asks again, and off screen nothing connects. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class StartupPhonePickerLifecycleTest {
    @Test fun showingDiPlayAgainAsksAgainAndLeavingTheScreenStopsTheQuestion() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        val activity = controller.get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        controller.setup()
        try {
            // The next drive: DiPlay comes back on screen and the earlier answer no longer holds.
            ReflectionHelpers.setField(activity, "iphoneQuestionAnswered", true)
            controller.pause().stop().start().resume()
            assertFalse(ReflectionHelpers.getField<Boolean>(activity, "iphoneQuestionAnswered"))

            val adapter = activity.getSystemService(BluetoothManager::class.java).adapter
            val alex = adapter.getRemoteDevice("AA:00:00:00:00:01").also { shadowOf(it).setName("Alex’s iPhone") }
            val sam = adapter.getRemoteDevice("AA:00:00:00:00:02").also { shadowOf(it).setName("Sam’s iPhone") }
            val ask = StartupPhonePicker.decide(listOf(alex, sam).map {
                StartupPhonePicker.Candidate(it.address, it.name, isPhone = true, connected = false)
            }, alex.address) as StartupPhonePicker.Decision.Ask
            DiPlayActivity::class.java.getDeclaredMethod(
                "showStartupPhonePicker", StartupPhonePicker.Decision.Ask::class.java, List::class.java,
            ).apply { isAccessible = true }.invoke(activity, ask, listOf(alex, sam))
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(ReflectionHelpers.getField<AlertDialog?>(activity, "startupPickerDialog"))

            // Leaving the screen closes the question without answering it.
            controller.pause().stop()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(ReflectionHelpers.getField<AlertDialog?>(activity, "startupPickerDialog"))
            assertFalse(ReflectionHelpers.getField<Boolean>(activity, "iphoneQuestionAnswered"))

            // Past the countdown, nothing was chosen or connected in the background.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
            assertNull(DiPlayPreferences.phoneAddress(activity))
        } finally {
            controller.destroy()
        }
    }
}
