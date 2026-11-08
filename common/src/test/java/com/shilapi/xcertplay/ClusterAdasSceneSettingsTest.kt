package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.AdasCarColor
import com.shilapi.xcertplay.hud.BydClusterAdasPlacement
import com.shilapi.xcertplay.hud.BydClusterAdasPlacement.Outcome
import com.shilapi.xcertplay.hud.BydClusterAdasPlacement.State
import com.shilapi.xcertplay.hud.BydClusterAdasPlacementControl
import com.shilapi.xcertplay.hud.BydOutputSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

/** The ADAS scene card moves the large scene beside the map, and only after the driver agrees to the cluster reload. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1000dp-h700dp-land")
class ClusterAdasSceneSettingsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val opened = mutableListOf<DiPlayActivity>()
    private val placement = FakePlacement()

    private class FakePlacement : BydClusterAdasPlacementControl {
        override var status = BydClusterAdasPlacement.Status(State.NOT_INSTALLED)
        override var busy = false
        override var onChange: (() -> Unit)? = null
        var outcome = Outcome.INSTALLED
        var colorOutcome = Outcome.COLORED
        val calls = mutableListOf<String>()
        val installColors = mutableListOf<AdasCarColor?>()
        val paintedColors = mutableListOf<AdasCarColor?>()

        override fun refreshIfStale(app: Context) { calls += "refresh" }

        override fun install(app: Context, color: AdasCarColor?, done: (Outcome) -> Unit) {
            calls += "install"
            installColors += color
            done(outcome)
        }

        override fun remove(app: Context, done: (Outcome) -> Unit) {
            calls += "remove"
            done(outcome)
        }

        override fun applyColor(app: Context, color: AdasCarColor?, done: (Outcome) -> Unit) {
            calls += "color"
            paintedColors += color
            done(colorOutcome)
        }

        override fun settingChanged(app: Context) { calls += "changed" }
    }

    @After fun tearDown() {
        opened.forEach { it.finish() }
        AppAppearanceRuntime.resetForTest()
        BydOutputSettings.setAdasSceneBesideMap(context, false)
        BydOutputSettings.setAdasCarColor(context, null)
    }

    private fun installDiLink3Adapter() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.example.amapservice"
            applicationInfo = ApplicationInfo().apply {
                packageName = "com.example.amapservice"
                flags = ApplicationInfo.FLAG_SYSTEM
            }
        })
    }

    private fun advancedSettings(state: State = State.NOT_INSTALLED, panel: Boolean = true, code: String? = null): DiPlayActivity {
        placement.status = BydClusterAdasPlacement.Status(state, code)
        val screen = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).create().get()
        opened += screen
        screen.adasPlacement = placement
        screen.adasScenePanel = { panel }
        ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.ADVANCED)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        return screen
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun texts(screen: DiPlayActivity): List<String> =
        descendants(screen.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()

    private fun button(screen: DiPlayActivity, title: Int): Button =
        descendants(screen.window.decorView).filterIsInstance<Button>()
            .first { it.text.toString() == screen.getString(title) }

    private fun toggle(screen: DiPlayActivity): Switch = descendants(screen.window.decorView).filterIsInstance<Switch>()
        .single { it.contentDescription == screen.getString(R.string.settings_adas_scene_toggle) }

    private fun dialog(): AlertDialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())

    private fun message(dialog: AlertDialog): String = dialog.findViewById<TextView>(android.R.id.message).text.toString()

    private fun press(dialog: AlertDialog, which: Int) {
        dialog.getButton(which).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun grid(screen: DiPlayActivity): CarColorSwatchGrid =
        descendants(screen.window.decorView).filterIsInstance<CarColorSwatchGrid>().single()

    private fun swatches(screen: DiPlayActivity): List<View> = grid(screen).let { grid -> (0 until grid.childCount).map(grid::getChildAt) }

    private fun swatch(screen: DiPlayActivity, name: String): View = swatches(screen).first { it.contentDescription == name }

    private fun original(screen: DiPlayActivity) = screen.getString(R.string.settings_adas_scene_color_original)

    private fun custom(screen: DiPlayActivity) = screen.getString(R.string.settings_adas_scene_color_custom)

    private fun preset(key: String): AdasCarColor = AdasCarColor.PRESETS.first { it.key == key }.color

    private fun presetName(screen: DiPlayActivity, key: String): String =
        AdasCarColorLabels.preset(screen, AdasCarColor.PRESETS.first { it.key == key })

    private fun drag(slider: SeekBar, progress: Int) {
        val listener = SeekBar::class.java.getDeclaredField("mOnSeekBarChangeListener")
            .apply { isAccessible = true }.get(slider) as SeekBar.OnSeekBarChangeListener
        slider.progress = progress
        listener.onProgressChanged(slider, progress, true)
    }

    @Test fun aHeadUnitWithoutTheDiLink3AdapterIsNotOfferedTheCard() {
        val screen = advancedSettings()
        assertFalse(screen.getString(R.string.settings_adas_scene_title) in texts(screen))
    }

    @Test fun theCardDescribesWhatItDoesAndIsOffByDefault() {
        installDiLink3Adapter()
        val screen = advancedSettings()
        val description = screen.getString(R.string.settings_adas_scene_description)
        assertTrue(texts(screen).toString(), description in texts(screen))
        assertTrue("Nothing is hidden, closed or sent to the car" in description && "network ADB" in description)
        assertFalse(toggle(screen).isChecked)
        assertFalse(BydOutputSettings.adasSceneBesideMap(context))
        assertTrue("refresh" in placement.calls)
    }

    @Test fun anotherClusterPanelDisablesTheToggleAndSaysWhy() {
        installDiLink3Adapter()
        val screen = advancedSettings(panel = false)
        assertTrue(screen.getString(R.string.settings_adas_scene_status_wrong_panel) in texts(screen))
        assertFalse(toggle(screen).isEnabled)
    }

    @Test fun everyStateShowsItsOwnLine() {
        installDiLink3Adapter()
        val expected = mapOf(
            State.UNKNOWN to R.string.settings_adas_scene_status_checking,
            State.NOT_INSTALLED to R.string.settings_adas_scene_status_not_installed,
            State.CURRENT to R.string.settings_adas_scene_status_installed,
            State.STALE to R.string.settings_adas_scene_status_stale,
            State.OTHER to R.string.settings_adas_scene_status_other,
            State.NO_ADB to R.string.settings_adas_scene_status_no_adb,
            State.NO_CLUSTER to R.string.settings_adas_scene_status_no_cluster,
        )
        for ((state, line) in expected) {
            val screen = advancedSettings(state)
            assertTrue(state.name, screen.getString(line) in texts(screen))
        }
        val screen = advancedSettings(State.UNSUPPORTED, code = "missing-adasUi")
        assertTrue(screen.getString(R.string.settings_adas_scene_status_unsupported, "missing-adasUi") in texts(screen))
    }

    @Test fun theToggleIsOfferedOnlyWhereInstallingCouldWork() {
        installDiLink3Adapter()
        for (state in listOf(State.UNKNOWN, State.NOT_INSTALLED, State.CURRENT, State.STALE)) {
            assertTrue(state.name, toggle(advancedSettings(state)).isEnabled)
        }
        for (state in listOf(State.OTHER, State.UNSUPPORTED, State.NO_ADB, State.NO_CLUSTER)) {
            assertFalse(state.name, toggle(advancedSettings(state)).isEnabled)
        }
    }

    @Test fun turningItOnAsksBeforeInstallingAndThenSwitchesItOn() {
        installDiLink3Adapter()
        val screen = advancedSettings()
        toggle(screen).performClick()
        val asked = dialog()
        assertTrue(message(asked), screen.getString(R.string.settings_adas_scene_confirm_install) in message(asked))
        assertEquals(emptyList<String>(), placement.calls.filter { it == "install" })
        assertFalse(BydOutputSettings.adasSceneBesideMap(context))
        press(asked, AlertDialog.BUTTON_POSITIVE)
        assertEquals(1, placement.calls.count { it == "install" })
        assertTrue(BydOutputSettings.adasSceneBesideMap(context))
        assertEquals(screen.getString(R.string.settings_adas_scene_installed), ShadowToast.getTextOfLatestToast())
        assertTrue("changed" in placement.calls)
    }

    @Test fun cancellingTheInstallLeavesItOff() {
        installDiLink3Adapter()
        val screen = advancedSettings()
        toggle(screen).performClick()
        press(dialog(), AlertDialog.BUTTON_NEGATIVE)
        assertEquals(0, placement.calls.count { it == "install" })
        assertFalse(BydOutputSettings.adasSceneBesideMap(context))
        assertFalse(toggle(screen).isChecked)
    }

    @Test fun anInstallThatIsRefusedExplainsWhyAndLeavesItOff() {
        installDiLink3Adapter()
        val screen = advancedSettings()
        placement.outcome = Outcome.NOT_PARKED
        toggle(screen).performClick()
        press(dialog(), AlertDialog.BUTTON_POSITIVE)
        assertEquals(screen.getString(R.string.settings_adas_scene_not_parked), ShadowToast.getTextOfLatestToast())
        assertFalse(BydOutputSettings.adasSceneBesideMap(context))
    }

    @Test fun everyOutcomeHasItsOwnMessage() {
        installDiLink3Adapter()
        val expected = mapOf(
            Outcome.ALREADY to R.string.settings_adas_scene_already,
            Outcome.GEAR_UNKNOWN to R.string.settings_adas_scene_gear_unknown,
            Outcome.OTHER_ACTIVE to R.string.settings_adas_scene_other_active,
            Outcome.UNSUPPORTED to R.string.settings_adas_scene_unsupported,
            Outcome.NO_ADB to R.string.settings_adas_scene_status_no_adb,
            Outcome.NO_CLUSTER to R.string.settings_adas_scene_no_cluster,
            Outcome.BUSY to R.string.settings_adas_scene_busy,
            Outcome.FAILED to R.string.settings_adas_scene_failed,
        )
        for ((outcome, text) in expected) {
            val screen = advancedSettings()
            placement.outcome = outcome
            toggle(screen).performClick()
            press(dialog(), AlertDialog.BUTTON_POSITIVE)
            assertEquals(outcome.name, screen.getString(text), ShadowToast.getTextOfLatestToast())
        }
    }

    @Test fun aSavedOnCanAlwaysBeSwitchedOff() {
        installDiLink3Adapter()
        BydOutputSettings.setAdasSceneBesideMap(context, true)
        for (state in listOf(State.OTHER, State.UNSUPPORTED, State.NO_ADB, State.NO_CLUSTER)) {
            assertTrue(state.name, toggle(advancedSettings(state)).isEnabled)
        }
        assertTrue(toggle(advancedSettings(panel = false)).isEnabled)
        val screen = advancedSettings(State.OTHER)
        toggle(screen).performClick()
        assertFalse(BydOutputSettings.adasSceneBesideMap(context))
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        assertTrue("changed" in placement.calls)
    }

    @Test fun turningItOnWhenInstalledAsksNothingAndAppliesAtOnce() {
        installDiLink3Adapter()
        val screen = advancedSettings(State.CURRENT)
        toggle(screen).performClick()
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        assertTrue(BydOutputSettings.adasSceneBesideMap(context))
        assertEquals(listOf("refresh", "changed"), placement.calls)
    }

    @Test fun turningItOffIsSavedAndAppliedAtOnce() {
        installDiLink3Adapter()
        BydOutputSettings.setAdasSceneBesideMap(context, true)
        val screen = advancedSettings(State.CURRENT)
        assertTrue(toggle(screen).isChecked)
        toggle(screen).performClick()
        assertFalse(BydOutputSettings.adasSceneBesideMap(context))
        assertTrue("changed" in placement.calls)
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
    }

    @Test fun removingIsOfferedOnlyWhenInstalledAndAsksFirst() {
        installDiLink3Adapter()
        val notInstalled = advancedSettings(State.NOT_INSTALLED)
        assertEquals(View.GONE, button(notInstalled, R.string.settings_adas_scene_remove).visibility)
        BydOutputSettings.setAdasSceneBesideMap(context, true)
        val screen = advancedSettings(State.CURRENT)
        val remove = button(screen, R.string.settings_adas_scene_remove)
        assertEquals(View.VISIBLE, remove.visibility)
        placement.outcome = Outcome.REMOVED
        remove.performClick()
        val asked = dialog()
        assertTrue(screen.getString(R.string.settings_adas_scene_confirm_remove) in message(asked))
        assertEquals(0, placement.calls.count { it == "remove" })
        press(asked, AlertDialog.BUTTON_POSITIVE)
        assertEquals(1, placement.calls.count { it == "remove" })
        assertFalse(BydOutputSettings.adasSceneBesideMap(context))
        assertEquals(screen.getString(R.string.settings_adas_scene_removed), ShadowToast.getTextOfLatestToast())
    }

    @Test fun reinstallingIsOfferedOnlyForAnOutOfDateFile() {
        installDiLink3Adapter()
        assertEquals(View.GONE, button(advancedSettings(State.CURRENT), R.string.settings_adas_scene_reinstall).visibility)
        val screen = advancedSettings(State.STALE)
        val reinstall = button(screen, R.string.settings_adas_scene_reinstall)
        assertEquals(View.VISIBLE, reinstall.visibility)
        reinstall.performClick()
        press(dialog(), AlertDialog.BUTTON_POSITIVE)
        assertEquals(1, placement.calls.count { it == "install" })
    }

    @Test fun theStatusFollowsThePlacementWhileTheScreenIsOpen() {
        installDiLink3Adapter()
        val screen = advancedSettings(State.NOT_INSTALLED)
        assertNotNull(placement.onChange)
        placement.status = BydClusterAdasPlacement.Status(State.CURRENT)
        placement.onChange?.invoke()
        assertTrue(screen.getString(R.string.settings_adas_scene_status_installed) in texts(screen))
        placement.busy = true
        placement.onChange?.invoke()
        assertTrue(screen.getString(R.string.settings_adas_scene_busy) in texts(screen))
        assertFalse(toggle(screen).isEnabled)
    }

    @Test fun theScreenLetsGoOfThePlacementWhenItCloses() {
        installDiLink3Adapter()
        placement.status = BydClusterAdasPlacement.Status(State.NOT_INSTALLED)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).create()
        val screen = controller.get()
        screen.adasPlacement = placement
        screen.adasScenePanel = { true }
        ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.ADVANCED)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        assertNotNull(placement.onChange)
        controller.destroy()
        assertNull(placement.onChange)
    }

    @Test fun theCardOffersTheOriginalCarTheReadyMadeColorsAndACustomOne() {
        installDiLink3Adapter()
        val screen = advancedSettings(State.CURRENT)
        val expected = listOf(original(screen)) + AdasCarColor.PRESETS.map { AdasCarColorLabels.preset(screen, it) } + custom(screen)
        assertEquals(expected, swatches(screen).map { it.contentDescription.toString() })
        assertEquals(expected.size, expected.toSet().size)
        assertTrue(screen.getString(R.string.settings_adas_scene_color_title) in texts(screen))
        assertTrue(screen.getString(R.string.settings_adas_scene_color_description) in texts(screen))
        assertEquals(listOf(original(screen)), swatches(screen).filter { it.isSelected }.map { it.contentDescription.toString() })
        assertTrue(screen.getString(R.string.settings_adas_scene_color_selected, original(screen)) in texts(screen))
    }

    @Test fun everyReadyMadeColorHasItsOwnName() {
        val names = AdasCarColor.PRESETS.map { AdasCarColorLabels.preset(context, it) }
        assertEquals(names.toString(), names.size, names.toSet().size)
        assertTrue(names.none { it.isBlank() })
    }

    @Test fun aColorChosenWhileTheFileIsInstalledIsAppliedAtOnceAndSaved() {
        installDiLink3Adapter()
        val screen = advancedSettings(State.CURRENT)
        swatch(screen, presetName(screen, "blue")).performClick()
        assertEquals(listOf<AdasCarColor?>(preset("blue")), placement.paintedColors)
        assertEquals(preset("blue"), BydOutputSettings.adasCarColor(context))
        assertEquals(screen.getString(R.string.settings_adas_scene_color_applied), ShadowToast.getTextOfLatestToast())
        assertTrue(swatch(screen, presetName(screen, "blue")).isSelected)
        assertFalse(swatch(screen, original(screen)).isSelected)
        assertTrue(screen.getString(R.string.settings_adas_scene_color_selected, presetName(screen, "blue")) in texts(screen))
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        assertEquals(0, placement.calls.count { it == "install" })
        assertTrue(grid(screen).isEnabled)
    }

    @Test fun choosingOriginalGivesBackBydsOwnCar() {
        installDiLink3Adapter()
        BydOutputSettings.setAdasCarColor(context, preset("red"))
        val screen = advancedSettings(State.CURRENT)
        assertTrue(swatch(screen, presetName(screen, "red")).isSelected)
        swatch(screen, original(screen)).performClick()
        assertEquals(listOf<AdasCarColor?>(null), placement.paintedColors)
        assertNull(BydOutputSettings.adasCarColor(context))
        assertEquals(screen.getString(R.string.settings_adas_scene_color_original_restored), ShadowToast.getTextOfLatestToast())
        assertTrue(swatch(screen, original(screen)).isSelected)
    }

    @Test fun aColorThatCouldNotBeAppliedIsNotSavedAndTheSelectionGoesBack() {
        installDiLink3Adapter()
        BydOutputSettings.setAdasCarColor(context, preset("red"))
        val screen = advancedSettings(State.CURRENT)
        placement.colorOutcome = Outcome.FAILED
        swatch(screen, presetName(screen, "blue")).performClick()
        assertEquals(screen.getString(R.string.settings_adas_scene_color_failed), ShadowToast.getTextOfLatestToast())
        assertEquals(preset("red"), BydOutputSettings.adasCarColor(context))
        assertTrue(swatch(screen, presetName(screen, "red")).isSelected)
        assertFalse(swatch(screen, presetName(screen, "blue")).isSelected)
        assertTrue(screen.getString(R.string.settings_adas_scene_color_selected, presetName(screen, "red")) in texts(screen))
        assertTrue(grid(screen).isEnabled)
        placement.colorOutcome = Outcome.NO_ADB
        swatch(screen, presetName(screen, "green")).performClick()
        assertEquals(screen.getString(R.string.settings_adas_scene_status_no_adb), ShadowToast.getTextOfLatestToast())
        assertEquals(preset("red"), BydOutputSettings.adasCarColor(context))
    }

    @Test fun withoutTheFileAColorAsksToInstallItFirstAndLeavesTheSceneWhereItWas() {
        installDiLink3Adapter()
        val screen = advancedSettings(State.NOT_INSTALLED)
        swatch(screen, presetName(screen, "blue")).performClick()
        val asked = dialog()
        assertTrue(message(asked), screen.getString(R.string.settings_adas_scene_confirm_install) in message(asked))
        assertEquals(emptyList<String>(), placement.calls.filter { it == "install" || it == "color" })
        assertNull(BydOutputSettings.adasCarColor(context))
        press(asked, AlertDialog.BUTTON_POSITIVE)
        assertEquals(listOf<AdasCarColor?>(preset("blue")), placement.installColors)
        assertEquals(preset("blue"), BydOutputSettings.adasCarColor(context))
        assertFalse(BydOutputSettings.adasSceneBesideMap(context))
        assertEquals(0, placement.calls.count { it == "color" })
    }

    @Test fun cancellingThatInstallSavesNothingAndTheSwatchGoesBack() {
        installDiLink3Adapter()
        val screen = advancedSettings(State.NOT_INSTALLED)
        swatch(screen, presetName(screen, "blue")).performClick()
        press(dialog(), AlertDialog.BUTTON_NEGATIVE)
        assertEquals(0, placement.calls.count { it == "install" })
        assertNull(BydOutputSettings.adasCarColor(context))
        assertEquals(listOf(original(screen)), swatches(screen).filter { it.isSelected }.map { it.contentDescription.toString() })
    }

    @Test fun anInstallThatIsRefusedDoesNotSaveTheColor() {
        installDiLink3Adapter()
        val screen = advancedSettings(State.NOT_INSTALLED)
        placement.outcome = Outcome.NOT_PARKED
        swatch(screen, presetName(screen, "blue")).performClick()
        press(dialog(), AlertDialog.BUTTON_POSITIVE)
        assertEquals(screen.getString(R.string.settings_adas_scene_not_parked), ShadowToast.getTextOfLatestToast())
        assertNull(BydOutputSettings.adasCarColor(context))
    }

    @Test fun originalBeforeTheFileIsInstalledOnlyForgetsTheSavedChoice() {
        installDiLink3Adapter()
        BydOutputSettings.setAdasCarColor(context, preset("blue"))
        val screen = advancedSettings(State.NOT_INSTALLED)
        assertTrue(swatch(screen, presetName(screen, "blue")).isSelected)
        swatch(screen, original(screen)).performClick()
        assertNull(BydOutputSettings.adasCarColor(context))
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        assertEquals(emptyList<String>(), placement.calls.filter { it == "install" || it == "color" })
    }

    @Test fun theSavedColorIsShownAndEveryInstallCarriesIt() {
        installDiLink3Adapter()
        BydOutputSettings.setAdasCarColor(context, preset("red"))
        val screen = advancedSettings(State.NOT_INSTALLED)
        assertTrue(swatch(screen, presetName(screen, "red")).isSelected)
        toggle(screen).performClick()
        press(dialog(), AlertDialog.BUTTON_POSITIVE)
        assertEquals(listOf<AdasCarColor?>(preset("red")), placement.installColors)
        assertEquals(preset("red"), BydOutputSettings.adasCarColor(context))
        assertTrue(BydOutputSettings.adasSceneBesideMap(context))
        val stale = advancedSettings(State.STALE)
        button(stale, R.string.settings_adas_scene_reinstall).performClick()
        press(dialog(), AlertDialog.BUTTON_POSITIVE)
        assertEquals(listOf<AdasCarColor?>(preset("red"), preset("red")), placement.installColors)
    }

    @Test fun removingTheFileKeepsTheSavedColor() {
        installDiLink3Adapter()
        BydOutputSettings.setAdasCarColor(context, preset("red"))
        val screen = advancedSettings(State.CURRENT)
        placement.outcome = Outcome.REMOVED
        button(screen, R.string.settings_adas_scene_remove).performClick()
        press(dialog(), AlertDialog.BUTTON_POSITIVE)
        assertEquals(1, placement.calls.count { it == "remove" })
        assertEquals(preset("red"), BydOutputSettings.adasCarColor(context))
    }

    @Test fun theColorCanOnlyBeChosenWhereItCanBeAppliedOrInstalled() {
        installDiLink3Adapter()
        for (state in listOf(State.NOT_INSTALLED, State.CURRENT)) assertTrue(state.name, grid(advancedSettings(state)).isEnabled)
        for (state in listOf(State.UNKNOWN, State.STALE, State.OTHER, State.UNSUPPORTED, State.NO_ADB, State.NO_CLUSTER)) {
            assertFalse(state.name, grid(advancedSettings(state)).isEnabled)
        }
        assertFalse(grid(advancedSettings(State.CURRENT, panel = false)).isEnabled)
        val screen = advancedSettings(State.CURRENT)
        placement.busy = true
        placement.onChange?.invoke()
        assertFalse(grid(screen).isEnabled)
        placement.busy = false
        placement.onChange?.invoke()
        assertTrue(grid(screen).isEnabled)
    }

    @Test fun aTapOnADisabledPickerDoesNothing() {
        installDiLink3Adapter()
        val screen = advancedSettings(State.OTHER)
        swatch(screen, presetName(screen, "blue")).performClick()
        swatch(screen, custom(screen)).performClick()
        assertEquals(emptyList<String>(), placement.calls.filter { it == "install" || it == "color" })
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        assertNull(BydOutputSettings.adasCarColor(context))
        assertTrue(swatch(screen, original(screen)).isSelected)
    }

    @Test fun aCustomColorIsChosenWithSlidersAndHandledLikeAnyOther() {
        installDiLink3Adapter()
        val screen = advancedSettings(State.CURRENT)
        swatch(screen, custom(screen)).performClick()
        val asked = dialog()
        val sliders = descendants(asked.window!!.decorView).filterIsInstance<SeekBar>().toList()
        assertEquals(listOf(360, 101, AdasCarColor.BRIGHTNESS_MAX - AdasCarColor.BRIGHTNESS_MIN + 1), sliders.map { it.max + 1 })
        for (title in listOf(R.string.settings_adas_scene_color_hue, R.string.settings_adas_scene_color_saturation, R.string.settings_adas_scene_color_brightness)) {
            assertTrue(screen.getString(title), screen.getString(title) in descendants(asked.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() })
        }
        drag(sliders[0], 100)
        drag(sliders[1], 50)
        drag(sliders[2], 80 - AdasCarColor.BRIGHTNESS_MIN)
        assertEquals(emptyList<String>(), placement.calls.filter { it == "color" })
        press(asked, AlertDialog.BUTTON_POSITIVE)
        val chosen = AdasCarColor(100, 50, 80)
        assertEquals(listOf<AdasCarColor?>(chosen), placement.paintedColors)
        assertEquals(chosen, BydOutputSettings.adasCarColor(context))
        assertEquals(listOf(custom(screen)), swatches(screen).filter { it.isSelected }.map { it.contentDescription.toString() })
        assertTrue(screen.getString(R.string.settings_adas_scene_color_selected, custom(screen)) in texts(screen))
    }

    @Test fun theCustomDialogStartsFromTheColorInUseAndCancellingChangesNothing() {
        installDiLink3Adapter()
        BydOutputSettings.setAdasCarColor(context, AdasCarColor(40, 70, 90))
        val screen = advancedSettings(State.CURRENT)
        assertTrue(swatch(screen, custom(screen)).isSelected)
        swatch(screen, custom(screen)).performClick()
        val asked = dialog()
        val sliders = descendants(asked.window!!.decorView).filterIsInstance<SeekBar>().toList()
        assertEquals(listOf(40, 70, 90 - AdasCarColor.BRIGHTNESS_MIN), sliders.map { it.progress })
        drag(sliders[0], 300)
        press(asked, AlertDialog.BUTTON_NEGATIVE)
        assertEquals(emptyList<String>(), placement.calls.filter { it == "color" })
        assertEquals(AdasCarColor(40, 70, 90), BydOutputSettings.adasCarColor(context))
    }
}
