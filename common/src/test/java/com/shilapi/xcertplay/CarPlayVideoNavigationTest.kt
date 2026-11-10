package com.shilapi.xcertplay

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.airplay.VideoInCar
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.lang.ref.WeakReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayVideoNavigationTest {
    private lateinit var host: CarPlayHostActivity

    @Before fun setUp() {
        CarPlayVideo.activity = null
        CarPlayVideo.onVideoSessionEnded()
        idle()
        CarPlayVideo.attach(RuntimeEnvironment.getApplication(), mock(CarPlayController::class.java))
        VideoInCar.allowed = true
        host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        ReflectionHelpers.setField(CarPlayScreen, "open", WeakReference(host))
    }

    @After fun tearDown() {
        CarPlayVideo.activity = null
        CarPlayVideo.onVideoSessionEnded()
        idle()
        VideoInCar.allowed = false
        ReflectionHelpers.setField(CarPlayScreen, "open", null)
    }

    @Test fun playerOpensOverTheLiveHostWithoutCreatingAnotherTaskOrDuplicateLaunches() {
        play()
        val started = shadowOf(host).nextStartedActivity
        assertEquals(CarPlayVideoActivity::class.java.name, started.component?.className)
        assertEquals(0, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        rate()
        CarPlayVideo.onVideoUiRequested()
        idle()
        assertNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun sameTaskLauncherHostAlsoOwnsThePlayerLaunch() {
        host = Robolectric.buildActivity(SameTaskCarPlayHostActivity::class.java).get()
        ReflectionHelpers.setField(CarPlayScreen, "open", WeakReference(host))
        play()
        val started = shadowOf(host).nextStartedActivity
        assertEquals(CarPlayVideoActivity::class.java.name, started.component?.className)
        assertEquals(0, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test fun dismissImmediatelyPausesAndRejectsDelayedCommandsAndPendingActivityStarts() {
        play()
        shadowOf(host).nextStartedActivity
        CarPlayVideo.dismissPlayer(12_345)
        assertFalse(CarPlayVideo.playing)
        assertFalse(CarPlayVideo.attachPlayer(mock(CarPlayVideoActivity::class.java)))
        rate()
        queue()
        rate()
        assertFalse(CarPlayVideo.playing)
        assertEquals(12_345, CarPlayVideo.startMillis)
        assertNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun delayedVideoUiRequestCannotReopenTheDismissedItem() {
        play()
        shadowOf(host).nextStartedActivity
        CarPlayVideo.dismissPlayer(12_345)
        assertFalse(CarPlayVideo.attachPlayer(mock(CarPlayVideoActivity::class.java)))
        CarPlayVideo.onVideoUiRequested()
        idle()
        assertNull(shadowOf(host).nextStartedActivity)
        assertEquals(12_345, CarPlayVideo.startMillis)
    }

    @Test fun choosingANewItemCanPlayAfterDismissal() {
        play()
        shadowOf(host).nextStartedActivity
        CarPlayVideo.dismissPlayer(12_345)
        assertFalse(CarPlayVideo.attachPlayer(mock(CarPlayVideoActivity::class.java)))
        queue("second")
        rate()
        assertTrue(CarPlayVideo.playing)
        assertEquals(CarPlayVideoActivity::class.java.name, shadowOf(host).nextStartedActivity.component?.className)
    }

    @Test fun lateActivityStartAfterStopClosesWithoutCreatingAPlayer() {
        play()
        message(mapOf("type" to "stop"))
        val screen = Robolectric.buildActivity(CarPlayVideoActivity::class.java).create()
        assertTrue(screen.get().isFinishing)
        screen.destroy() // No ExoPlayer was initialized; teardown must also be safe.
        assertNull(CarPlayVideo.activity)
    }

    @Test fun backToCarPlayButtonPausesAndTargetsTheExistingHostInsteadOfTheStartPage() {
        play()
        shadowOf(host).nextStartedActivity
        val screen = Robolectric.buildActivity(CarPlayVideoActivity::class.java).get()
        val controls = ReflectionHelpers.callInstanceMethod<View>(screen, "controlBar")
        descendants(controls).filterIsInstance<TextView>()
            .single { it.text.contains(screen.getString(R.string.video_back_to_carplay)) }.performClick()
        assertFalse(CarPlayVideo.playing)
        assertTrue(screen.isFinishing)
        assertEquals(host.javaClass.name, shadowOf(screen).nextStartedActivity.component?.className)
        rate()
        assertFalse(CarPlayVideo.playing)
        assertNull(shadowOf(host).nextStartedActivity)
    }

    private fun play() { queue(); rate() }
    private fun queue(id: String = "first") = message(mapOf(
        "type" to "insertPlayQueueItem",
        "item" to mapOf("uuid" to id, "Content-Location" to "https://example.com/$id.mp4"),
    ))
    private fun rate() = message(mapOf("type" to "setRate", "rate" to 1.0))
    private fun message(message: Map<String, Any?>) { CarPlayVideo.onVideoMessage(3, message); idle() }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
