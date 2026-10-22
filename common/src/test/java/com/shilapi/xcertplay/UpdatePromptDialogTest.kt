package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.setup.SetupGuide
import com.shilapi.xcertplay.update.UpdateAvailability
import com.shilapi.xcertplay.update.UpdateRelease
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers

/**
 * A release the background check found is offered in a dialog when the driver comes back to Home (#945).
 * The offer interrupts once per version, so **Later** leaves the notice on Home without asking again on
 * every later visit, and only a newer release is news.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1000dp-h700dp-land")
class UpdatePromptDialogTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private var home: ActivityController<DiPlayActivity>? = null

    @After fun tearDown() {
        home?.let { controller -> runCatching { controller.get().finish() } }
        home = null
        UpdateAvailability.clearAllForTest(context)
        AppAppearanceRuntime.resetForTest()
    }

    /** `page=home` also keeps the first-run setup guide from taking the screen instead. */
    private fun openHome(): ActivityController<DiPlayActivity> =
        Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "home")).setup().also { home = it }

    private fun openSettings(): ActivityController<DiPlayActivity> =
        Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup().also { home = it }

    /** Every offer shown so far, told apart from the other dialogs Home raises. */
    private fun offers(): List<AlertDialog> {
        val title = context.getString(R.string.update_available_home)
        return ShadowDialog.getShownDialogs().filterIsInstance<AlertDialog>()
            .filter { shadowOf(it).title?.toString() == title }
    }

    private fun messageOf(dialog: AlertDialog): String? = shadowOf(dialog).message?.toString()

    private fun release(tag: String) = UpdateRelease(
        tagName = tag,
        apkName = "DiPlay-$tag.apk",
        apkUrl = "https://example.com/DiPlay-$tag.apk",
        checksumsUrl = "https://example.com/SHA256SUMS.txt",
    )

    @Test fun aFoundReleaseIsOfferedWhenHomeOpens() {
        UpdateAvailability.save(context, release("v0.2.99"))

        openHome()

        val offer = offers().single()
        assertEquals(
            context.getString(R.string.update_available_dialog_message, "v0.2.99"),
            messageOf(offer),
        )
        assertEquals(
            context.getString(R.string.update_download),
            offer.getButton(AlertDialog.BUTTON_POSITIVE).text.toString(),
        )
        assertEquals(
            context.getString(R.string.later),
            offer.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString(),
        )
        // The offer is only spent once its window closes, so an unseen offer is retried.
        assertTrue(UpdateAvailability.shouldPrompt(context, release("v0.2.99")))
        offer.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(UpdateAvailability.shouldPrompt(context, release("v0.2.99")))
    }

    @Test fun nothingIsOfferedWithoutASavedRelease() {
        openHome()

        assertTrue(offers().isEmpty())
    }

    @Test fun aFoundReleaseIsNotOfferedOutsideHome() {
        val found = release("v0.2.99")
        UpdateAvailability.save(context, found)

        openSettings()

        assertTrue(offers().isEmpty())
        assertTrue(UpdateAvailability.shouldPrompt(context, found))
    }

    @Test fun laterIsNotAskedAgainOnTheNextHome() {
        UpdateAvailability.save(context, release("v0.2.99"))
        val controller = openHome()
        offers().single().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        controller.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, offers().size)
    }

    @Test fun aNewerReleaseIsOfferedAgain() {
        UpdateAvailability.save(context, release("v0.2.99"))
        val controller = openHome()
        offers().single().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        UpdateAvailability.save(context, release("v0.3.0"))
        controller.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(2, offers().size)
        assertEquals(
            context.getString(R.string.update_available_dialog_message, "v0.3.0"),
            messageOf(offers().last()),
        )
    }

    /** The check runs after Home opened, so **Later** must still leave its notice on Home. */
    @Test fun laterKeepsTheNoticeWhenTheReleaseWasFoundAfterHomeOpened() {
        val controller = openHome()

        UpdateAvailability.save(context, release("v0.2.99"))
        controller.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()

        offers().single().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertNotNull(
            "Home should keep the update notice after Later",
            homeNotice(controller.get()),
        )
    }

    /** Back from Settings lands on Home without resuming the activity, and still offers the release. */
    @Test fun returningToHomeFromSettingsOffersTheRelease() {
        UpdateAvailability.save(context, release("v0.2.99"))
        val controller = openSettings()

        assertTrue(offers().isEmpty())
        controller.get().onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            context.getString(R.string.update_available_dialog_message, "v0.2.99"),
            messageOf(offers().single()),
        )
    }

    /** A download already under way or ready to install must not be restarted by the offer. */
    @Test fun anUpdateAlreadyUnderWayIsNotOfferedAgain() {
        UpdateAvailability.save(context, release("v0.2.99"))
        val controller = openSettings()
        setUpdateStage(controller.get(), "READY")

        controller.get().onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(offers().isEmpty())
        assertTrue(UpdateAvailability.shouldPrompt(context, release("v0.2.99")))
    }

    /** The offer follows the Home page: leaving Home closes it instead of covering the next page. */
    @Test fun leavingHomeClosesTheOffer() {
        UpdateAvailability.save(context, release("v0.2.99"))
        val controller = openHome()
        val offer = offers().single()

        controller.newIntent(Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"))
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(offer.isShowing)
    }

    /** A destroyed activity must not leave the offer's window behind. */
    @Test fun closingTheActivityClosesTheOffer() {
        UpdateAvailability.save(context, release("v0.2.99"))
        val controller = openHome()
        val offer = offers().single()

        controller.pause().stop().destroy()

        assertFalse(offer.isShowing)
    }

    /** A dialog the driver opened on Home stays; the offer waits for the next visit. */
    @Test fun aDialogTheDriverOpenedIsNotCovered() {
        val controller = openHome()
        val activity = controller.get()
        descendants(activity.window.decorView).filterIsInstance<TextView>()
            .first { it.text == activity.getString(R.string.choose_iphone) }
            .performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val prompt = choosePhoneDialogs().single()

        UpdateAvailability.save(context, release("v0.2.99"))
        controller.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(offers().isEmpty())
        assertTrue(prompt.isShowing)
    }

    /** Turning the background check off stops the interruption; the cached notice stays on Home. */
    @Test fun aDisabledBackgroundCheckLeavesOnlyTheNotice() {
        UpdateAvailability.saveBackgroundChecksEnabled(context, false)
        UpdateAvailability.save(context, release("v0.2.99"))

        val controller = openHome()

        assertTrue(offers().isEmpty())
        assertNotNull(homeNotice(controller.get()))
    }

    /** The automatic connection owns the launch, so the offer waits for the next return to Home. */
    @Test fun autoConnectOnLaunchLeavesTheOfferForTheNextHome() {
        SetupGuide.markSeen(context)
        DiPlayPreferences.saveAutoConnect(context, true)
        UpdateAvailability.save(context, release("v0.2.99"))

        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, Intent(Intent.ACTION_MAIN)).create()
        home = controller
        ReflectionHelpers.setField(controller.get(), "setupError", null)
        controller.start().resume()

        assertTrue(offers().isEmpty())
    }

    /** About carries the release the offer names, without asking the driver to check again. */
    @Test fun aboutOffersACachedReleaseWithoutAnotherCheck() {
        UpdateAvailability.save(context, release("v0.2.99"))
        val state = Bundle().apply { putString("settings_category", "ABOUT") }
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"))
            .create(state).start().resume().also { home = it }

        assertNotNull(
            "About should offer the cached release without another check",
            descendants(controller.get().window.decorView).filterIsInstance<TextView>()
                .singleOrNull { it.text == context.getString(R.string.update_download) },
        )
    }

    private fun choosePhoneDialogs(): List<AlertDialog> {
        val titles: Set<String?> = setOf(
            context.getString(R.string.wireless_carplay_2),
            context.getString(R.string.turn_on_bluetooth),
            context.getString(R.string.pair_your_iphone),
            context.getString(R.string.choose_your_iphone),
        )
        return ShadowDialog.getShownDialogs().filterIsInstance<AlertDialog>()
            .filter { it.isShowing && shadowOf(it).title?.toString() in titles }
    }

    private fun homeNotice(activity: DiPlayActivity): TextView? =
        descendants(activity.window.decorView).filterIsInstance<TextView>()
            .singleOrNull { it.text == activity.getString(R.string.update_available_home) }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            yieldAll(descendants(view.getChildAt(index)))
        }
    }

    private fun setUpdateStage(activity: DiPlayActivity, name: String) {
        val stage = DiPlayActivity::class.java.declaredClasses.first { it.simpleName == "UpdateStage" }
        val value = stage.enumConstants?.firstOrNull { it.toString() == name }
            ?: error("UpdateStage.$name is missing")
        ReflectionHelpers.setField(activity, "updateStage", value)
    }
}
