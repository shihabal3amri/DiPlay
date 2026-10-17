package com.shilapi.xcertplay

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.update.UpdateAvailability
import com.shilapi.xcertplay.update.UpdateRelease
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w900dp-h600dp-land")
class ManualUpdateUiTest {
    private val application: Application = RuntimeEnvironment.getApplication()

    @After fun clear() = UpdateAvailability.clearAllForTest(application)

    @Test fun settingsAboutDisplaysInstalledVersionAndCheckAction() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()

        openAboutCategory(activity)

        val views = descendants(activity.window.decorView).filterIsInstance<TextView>().toList()
        val installedVersion = ReflectionHelpers.callInstanceMethod<String>(activity, "version")
        val installedText = activity.getString(R.string.update_installed_version, installedVersion)
        assertTrue(views.any { it.text == installedText })
        assertTrue(views.any { it.text == activity.getString(R.string.update_check) })

        controller.pause().stop().destroy()
    }

    @Test fun checkForUpdatesBehaviorallySucceedsWhenNewerReleaseFound() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()

        val expectedRelease = UpdateRelease(
            tagName = "v99.0.0",
            apkName = "DiPlay-99.0.0.apk",
            apkUrl = "https://example.com/DiPlay.apk",
            checksumsUrl = "https://example.com/SHA256SUMS.txt",
            releaseNotes = "New features and performance improvements.",
        )
        activity.latestReleaseSupplier = { expectedRelease }

        openAboutCategory(activity)

        ReflectionHelpers.callInstanceMethod<Unit>(activity, "checkForUpdates")
        waitForUpdateThreads()

        assertEquals("AVAILABLE", ReflectionHelpers.getField<Any>(activity, "updateStage").toString())
        assertEquals(expectedRelease, ReflectionHelpers.getField<UpdateRelease>(activity, "updateRelease"))
        assertEquals(expectedRelease, UpdateAvailability.available(application, "0.1.0"))

        val views = descendants(activity.window.decorView).filterIsInstance<TextView>().toList()
        assertTrue(views.any { it.text == activity.getString(R.string.update_latest_version, "v99.0.0") })
        assertTrue(views.any { it.text == "New features and performance improvements." })
        assertTrue(views.any { it.text == activity.getString(R.string.update_download) })

        controller.pause().stop().destroy()
    }

    @Test fun checkForUpdatesBehaviorallyClearsStaleAvailabilityWhenServerReturnsNull() {
        val staleRelease = UpdateRelease(
            tagName = "v99.0.0",
            apkName = "DiPlay-99.0.0.apk",
            apkUrl = "https://example.com/DiPlay.apk",
            checksumsUrl = "https://example.com/SHA256SUMS.txt",
        )
        UpdateAvailability.save(application, staleRelease)

        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()

        activity.latestReleaseSupplier = { null }

        openAboutCategory(activity)
        assertEquals("AVAILABLE", ReflectionHelpers.getField<Any>(activity, "updateStage").toString())

        ReflectionHelpers.callInstanceMethod<Unit>(activity, "checkForUpdates")
        waitForUpdateThreads()

        val installedVersion = ReflectionHelpers.callInstanceMethod<String>(activity, "version")
        assertEquals("IDLE", ReflectionHelpers.getField<Any>(activity, "updateStage").toString())
        assertNull(ReflectionHelpers.getField<UpdateRelease>(activity, "availableUpdate"))
        assertNull(ReflectionHelpers.getField<UpdateRelease>(activity, "updateRelease"))
        assertNull(UpdateAvailability.available(application, installedVersion))

        val views = descendants(activity.window.decorView).filterIsInstance<TextView>().toList()
        assertTrue(views.any { it.text == activity.getString(R.string.update_installed_version, installedVersion) })
        assertTrue(views.any { it.text == activity.getString(R.string.update_latest_version, installedVersion) })
        assertTrue(views.any { it.text == activity.getString(R.string.update_up_to_date) })

        controller.pause().stop().destroy()
    }

    @Test fun checkForUpdatesBehaviorallyHandlesNetworkFailure() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()

        activity.latestReleaseSupplier = { throw UnknownHostException("api.github.com") }

        openAboutCategory(activity)

        ReflectionHelpers.callInstanceMethod<Unit>(activity, "checkForUpdates")
        waitForUpdateThreads()

        assertEquals("FAILED", ReflectionHelpers.getField<Any>(activity, "updateStage").toString())
        assertEquals(activity.getString(R.string.update_no_internet), ReflectionHelpers.getField<String>(activity, "updateMessage"))

        val views = descendants(activity.window.decorView).filterIsInstance<TextView>().toList()
        assertTrue(views.any { it.text == activity.getString(R.string.update_no_internet) })

        controller.pause().stop().destroy()
    }

    @Test fun downloadUpdateBehaviorallyClearsAvailabilityWhenServerReturnsNullAndDoesNotDownload() {
        val cachedRelease = UpdateRelease(
            tagName = "v99.0.0",
            apkName = "DiPlay-99.0.0.apk",
            apkUrl = "https://example.com/DiPlay.apk",
            checksumsUrl = "https://example.com/SHA256SUMS.txt",
        )
        UpdateAvailability.save(application, cachedRelease)

        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()

        activity.latestReleaseSupplier = { null }

        openAboutCategory(activity)
        assertEquals("AVAILABLE", ReflectionHelpers.getField<Any>(activity, "updateStage").toString())

        ReflectionHelpers.callInstanceMethod<Unit>(activity, "downloadUpdate")
        waitForUpdateThreads()

        val installedVersion = ReflectionHelpers.callInstanceMethod<String>(activity, "version")
        assertEquals("IDLE", ReflectionHelpers.getField<Any>(activity, "updateStage").toString())
        assertNull(ReflectionHelpers.getField<UpdateRelease>(activity, "updateRelease"))
        assertNull(ReflectionHelpers.getField<UpdateRelease>(activity, "availableUpdate"))
        assertNull(UpdateAvailability.available(application, installedVersion))

        val views = descendants(activity.window.decorView).filterIsInstance<TextView>().toList()
        assertTrue(views.any { it.text == activity.getString(R.string.update_up_to_date) })

        controller.pause().stop().destroy()
    }

    @Test fun longReleaseNotesCanBeExpandedAndCollapsed() {
        val longNotes = (1..10).joinToString("\n") { "Line $it: detailed release info" }
        val release = UpdateRelease(
            tagName = "v99.0.0",
            apkName = "DiPlay-99.0.0.apk",
            apkUrl = "https://example.com/DiPlay.apk",
            checksumsUrl = "https://example.com/SHA256SUMS.txt",
            releaseNotes = longNotes,
        )
        UpdateAvailability.save(application, release)

        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()

        openAboutCategory(activity)

        val expandButton = descendants(activity.window.decorView).filterIsInstance<Button>()
            .single { it.text == activity.getString(R.string.update_release_notes_more) }

        assertFalse(ReflectionHelpers.getField<Boolean>(activity, "releaseNotesExpanded"))

        expandButton.performClick()

        assertTrue(ReflectionHelpers.getField<Boolean>(activity, "releaseNotesExpanded"))
        val collapseButton = descendants(activity.window.decorView).filterIsInstance<Button>()
            .single { it.text == activity.getString(R.string.update_release_notes_less) }

        collapseButton.performClick()

        assertFalse(ReflectionHelpers.getField<Boolean>(activity, "releaseNotesExpanded"))

        controller.pause().stop().destroy()
    }

    private fun openAboutCategory(activity: DiPlayActivity) {
        ReflectionHelpers.setField(activity, "page", "settings")
        ReflectionHelpers.setField(activity, "settingsCategory", SettingsCategory.ABOUT)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
    }

    private fun waitForUpdateThreads() {
        Thread.getAllStackTraces().keys
            .filter { it.name.startsWith("diplay-update-") }
            .forEach { runCatching { it.join(3000) } }
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            yieldAll(descendants(view.getChildAt(index)))
        }
    }
}
