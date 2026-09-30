package com.shilapi.xcertplay

import com.shilapi.xcertplay.AutoStartPolicy.Decision
import com.shilapi.xcertplay.AutoStartPolicy.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoStartPolicyTest {
    private val phone = "AA:BB:CC:DD:EE:FF"

    private fun state(
        startOnBoot: Boolean = true,
        startOnBluetooth: Boolean = true,
        selectedPhoneAddress: String? = phone,
        sessionActive: Boolean = false,
        canDrawOverlays: Boolean = true,
        sdkInt: Int = 33,
        lastLaunchMillis: Long = 0L,
        nowMillis: Long = 1_000_000L,
    ) = AutoStartPolicy.State(
        startOnBoot, startOnBluetooth, selectedPhoneAddress, sessionActive, canDrawOverlays, sdkInt, lastLaunchMillis, nowMillis,
    )

    @Test
    fun bootIsIgnoredWhenTheOptionIsOff() {
        assertTrue(AutoStartPolicy.decide(Trigger.BOOT, null, state(startOnBoot = false)) is Decision.Ignore)
    }

    @Test
    fun bootLaunchesWhenEnabled() {
        assertEquals(Decision.Launch, AutoStartPolicy.decide(Trigger.BOOT, null, state()))
    }

    @Test
    fun bluetoothIsIgnoredWhenTheOptionIsOff() {
        assertTrue(AutoStartPolicy.decide(Trigger.BLUETOOTH, phone, state(startOnBluetooth = false)) is Decision.Ignore)
    }

    @Test
    fun bluetoothIsIgnoredWithoutASelectedPhone() {
        assertTrue(AutoStartPolicy.decide(Trigger.BLUETOOTH, phone, state(selectedPhoneAddress = null)) is Decision.Ignore)
        assertTrue(AutoStartPolicy.decide(Trigger.BLUETOOTH, phone, state(selectedPhoneAddress = " ")) is Decision.Ignore)
    }

    @Test
    fun bluetoothIsIgnoredForAnotherDevice() {
        assertTrue(AutoStartPolicy.decide(Trigger.BLUETOOTH, "11:22:33:44:55:66", state()) is Decision.Ignore)
        assertTrue(AutoStartPolicy.decide(Trigger.BLUETOOTH, null, state()) is Decision.Ignore)
    }

    @Test
    fun bluetoothLaunchesForTheSelectedPhoneRegardlessOfCase() {
        assertEquals(Decision.Launch, AutoStartPolicy.decide(Trigger.BLUETOOTH, phone.lowercase(), state()))
    }

    @Test
    fun nothingHappensWhileASessionRuns() {
        assertTrue(AutoStartPolicy.decide(Trigger.BLUETOOTH, phone, state(sessionActive = true)) is Decision.Ignore)
        assertTrue(AutoStartPolicy.decide(Trigger.BOOT, null, state(sessionActive = true)) is Decision.Ignore)
    }

    @Test
    fun repeatedTriggersInsideTheWindowCountOnce() {
        val now = 1_000_000L
        assertTrue(
            AutoStartPolicy.decide(Trigger.BLUETOOTH, phone, state(lastLaunchMillis = now - 5_000, nowMillis = now)) is Decision.Ignore,
        )
        assertEquals(
            Decision.Launch,
            AutoStartPolicy.decide(Trigger.BLUETOOTH, phone, state(lastLaunchMillis = now - AutoStartPolicy.DEBOUNCE_MILLIS, nowMillis = now)),
        )
    }

    @Test
    fun aClockThatWentBackwardsDoesNotBlockTheStart() {
        assertEquals(Decision.Launch, AutoStartPolicy.decide(Trigger.BOOT, null, state(lastLaunchMillis = 2_000_000L, nowMillis = 1_000_000L)))
    }

    @Test
    fun android10WithoutOverlayPermissionNotifiesInstead() {
        assertEquals(Decision.Notify, AutoStartPolicy.decide(Trigger.BLUETOOTH, phone, state(canDrawOverlays = false, sdkInt = 29)))
        assertEquals(Decision.Notify, AutoStartPolicy.decide(Trigger.BOOT, null, state(canDrawOverlays = false, sdkInt = 34)))
    }

    @Test
    fun android9LaunchesWithoutOverlayPermission() {
        assertEquals(Decision.Launch, AutoStartPolicy.decide(Trigger.BOOT, null, state(canDrawOverlays = false, sdkInt = 28)))
    }
}
