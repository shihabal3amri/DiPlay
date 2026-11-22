package com.shilapi.xcertplay

import com.shilapi.xcertplay.StartupPhonePicker.Candidate
import com.shilapi.xcertplay.StartupPhonePicker.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class StartupPhonePickerTest {
    private val alex = Candidate("AA:00:00:00:00:01", "Alex’s iPhone", isPhone = true, connected = false)
    private val sam = Candidate("AA:00:00:00:00:02", "Sam’s iPhone", isPhone = true, connected = false)
    private val headset = Candidate("AA:00:00:00:00:03", "Headset", isPhone = false, connected = true)

    @Test fun oneOrNoPairedIphoneKeepsTheUsualFlow() {
        assertEquals(Decision.Skip, StartupPhonePicker.decide(listOf(alex), alex.address))
        assertEquals(Decision.Skip, StartupPhonePicker.decide(emptyList(), null))
    }

    @Test fun pairedDevicesThatAreNotPhonesNeverCauseTheQuestion() {
        assertEquals(Decision.Skip, StartupPhonePicker.decide(listOf(alex, headset), alex.address))
    }

    @Test fun theOnlyIphoneOnBluetoothConnectsWithoutAsking() {
        assertEquals(Decision.Connect(sam.address),
            StartupPhonePicker.decide(listOf(alex, sam.copy(connected = true), headset), alex.address))
    }

    @Test fun twoIphonesOnBluetoothAsk() {
        val onAlex = alex.copy(connected = true)
        val onSam = sam.copy(connected = true)
        assertEquals(Decision.Ask(listOf(onSam, onAlex), sam.address),
            StartupPhonePicker.decide(listOf(onAlex, onSam), sam.address))
    }

    @Test fun theLastIphoneUsedComesFirstAndConnectsAfterTheCountdown() {
        assertEquals(Decision.Ask(listOf(sam, alex), sam.address), StartupPhonePicker.decide(listOf(alex, sam), sam.address))
    }

    @Test fun aNewDriverOrOwnerIsAskedWithoutACountdown() {
        // The last iPhone used is no longer paired, for example after the car was sold.
        assertEquals(Decision.Ask(listOf(alex, sam), null), StartupPhonePicker.decide(listOf(sam, alex), "AA:00:00:00:00:09"))
        assertEquals(Decision.Ask(listOf(alex, sam), null), StartupPhonePicker.decide(listOf(sam, alex), null))
    }

    @Test fun aDeviceWithoutAClassIsOffered() {
        val unknown = Candidate("AA:00:00:00:00:04", "iPhone", isPhone = null, connected = false)
        assertEquals(Decision.Ask(listOf(alex, unknown), alex.address),
            StartupPhonePicker.decide(listOf(unknown, alex), alex.address))
    }

    @Test fun theChosenIphoneStaysWhenItsClassLooksWrong() {
        val chosen = alex.copy(isPhone = false)
        assertEquals(Decision.Ask(listOf(chosen, sam), chosen.address),
            StartupPhonePicker.decide(listOf(sam, chosen), chosen.address))
    }
}
