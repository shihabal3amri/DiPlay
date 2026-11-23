package com.shilapi.xcertplay

import com.shilapi.xcertplay.host.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Only DiPlay's own service, out of `dumpsys accessibility` as AOSP prints it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class WheelKeyServiceStateTest {
    /** A start of the service clears what [WheelKeyService.settle] keeps for the process. */
    @After fun tearDown() {
        Robolectric.buildService(WheelKeyService::class.java).create().get().let {
            it.javaClass.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(it)
            it.onDestroy()
        }
        WheelKeyLog.reset()
    }

    private val ours = "com.shihab.diplay/com.shilapi.xcertplay.WheelKeyService"
    private val usb = "com.shihab.diplay/com.shilapi.xcertplay.UsbAutoConfirmService"
    private val other = "car.package/car.package.Accessibility"

    private fun service(label: String) = "Service[label=$label, feedbackType[FEEDBACK_GENERIC], capabilities=8, " +
        "eventTypes=TYPE_WINDOW_STATE_CHANGED, notificationTimeout=100, requestA11yBtn=false]"

    private fun dump(
        bound: String = "", enabled: String = "", binding: String = "", crashed: String? = "",
        attributes: String = "id=0, currentUser=true, touchExplorationEnabled=false",
    ) = "User state[attributes:{$attributes, serviceHandlesDoubleTap=false}\n" +
        "     shortcut key:{}\n     button:{null}\n     button target:{null}}\n" +
        "     Bound services:{$bound}\n     Enabled services:{$enabled}\n     Binding services:{$binding}\n" +
        crashed?.let { "     Crashed services:{$it}\n" }.orEmpty() + "\n" +
        "Window[displayId=0, title=Trip to Jane's house, layer=1]\n"

    private fun parse(dump: String?) = WheelKeyServiceState.parse(dump, setOf(ours), setOf("DiPlay wheel keys", "DiPlay 方向盘按键"))

    @Test fun aServiceLeftInTheBindingListIsStuck() {
        val state = parse("ACCESSIBILITY MANAGER (dumpsys accessibility)\n\n" +
            dump(bound = service("Car"), enabled = "{$other}, {$ours}", binding = "{$ours}"))
        assertEquals(WheelKeyServiceState(enabled = true, bound = false, binding = true, crashed = false), state)
        assertTrue(state.stuck)
        assertEquals("enabled=true bound=false binding=true crashed=false", state.toString())
    }

    @Test fun aCrashedServiceIsStuck() {
        val state = parse(dump(enabled = "{$ours}", crashed = "{$other}, {$ours}"))
        assertEquals(WheelKeyServiceState(true, false, false, true), state)
        assertTrue(state.stuck)
    }

    @Test fun aBoundServiceIsFoundByItsLabelInAnyLanguageAndOnAnyLine() {
        for (label in listOf("DiPlay wheel keys", "DiPlay 方向盘按键")) {
            val state = parse(dump(bound = service("Car") + ", \n                     " + service(label), enabled = "{$ours}"))
            assertEquals(WheelKeyServiceState(true, true, false, false), state)
            assertFalse(state.stuck)
        }
        // USB auto-confirm carries the app's own label.
        assertEquals(false, parse(dump(bound = service("DiPlay") + ", \n" + service("DiPlay wheel keys plus"))).bound)
    }

    @Test fun otherComponentsAndWindowsAreNotMistakenForTheService() {
        val state = parse(dump(
            enabled = "{$usb}, {x$ours}", binding = "{com.shihab.diplay.hudtest/com.shilapi.xcertplay.WheelKeyService}",
            crashed = "{${ours}Two}",
        ) + "Window[title={$ours}]\n     label=DiPlay wheel keys,")
        assertEquals(WheelKeyServiceState(false, false, false, false), state)
    }

    @Test fun onlyTheCurrentUsersListsCount() {
        val state = parse(
            dump(enabled = "{$ours}", crashed = "{$ours}", attributes = "id=0, currentUser=false") +
                dump(bound = service("DiPlay wheel keys"), enabled = "{$ours}", attributes = "id=10, currentUser=true"),
        )
        assertEquals(WheelKeyServiceState(true, true, false, false), state)
    }

    @Test fun listsTheDumpDoesNotHaveAreUnknown() {
        for (dump in listOf(null, "", "Permission Denial: can't dump accessibility from pid=1, uid=2000")) {
            val state = parse(dump)
            assertEquals("enabled=unknown bound=unknown binding=unknown crashed=unknown", state.toString())
            assertFalse(state.stuck)
        }
        assertEquals(WheelKeyServiceState(true, false, true, null), parse(dump(enabled = "{$ours}", binding = "{$ours}", crashed = null)))
        // Android 9 and older.
        val old = parse("User state[attributes:{id=0, currentUser=true}\r\n     services:{${service("DiPlay wheel keys")}}]\r\n\r\n")
        assertEquals(WheelKeyServiceState(null, true, null, null), old)
    }

    @Test fun theReadAsksForTheDumpOnceAndKnowsEveryTranslationOfTheLabel() {
        val context = RuntimeEnvironment.getApplication()
        val commands = mutableListOf<String>()
        val label = context.createConfigurationContext(android.content.res.Configuration().apply {
            setLocale(java.util.Locale.SIMPLIFIED_CHINESE)
        }).getString(R.string.wheel_key_service_label)
        val state = WheelKeyServiceState.read(context) { command ->
            commands += command
            dump(bound = service(label)) + "\nDIPLAY_WHEEL_EXIT:0"
        }
        assertEquals(1, commands.size)
        assertTrue(commands.single(), commands.single().startsWith("( dumpsys accessibility );"))
        assertEquals(true, state.bound)
        assertEquals(WheelKeyServiceState(), WheelKeyServiceState.read(context) { null })
    }

    @Test fun nothingElseOfTheDumpReachesTheReportAndTheRedactorKeepsTheLine() {
        val context = RuntimeEnvironment.getApplication()
        val component = android.content.ComponentName(context, WheelKeyService::class.java).flattenToString()
        WheelKeyLog.reset()
        assertFalse(WheelKeyService.settle(context, {
            dump(bound = service("Car"), enabled = "{$other}, {$component}", binding = "{$component}") + "\nDIPLAY_WHEEL_EXIT:0"
        }, {}))
        val line = WheelKeyService.report(context)
        assertTrue(line, line.contains(" stateAfterLastFailedRestore=[enabled=true bound=false binding=true crashed=false ageMs="))
        assertEquals(line, DiagnosticRedactor.redact(line))
        for (text in listOf(line) + WheelKeyLog.report(false)) {
            assertFalse(text, "Jane" in text || "car.package" in text || "Car," in text)
        }
        assertTrue(WheelKeyLog.report(false).last()
            .endsWith("  wheel key service allowed over adb, not started: enabled=true bound=false binding=true crashed=false"))
        WheelKeyLog.reset()
    }
}
