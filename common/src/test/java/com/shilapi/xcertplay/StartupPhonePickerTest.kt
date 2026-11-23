package com.shilapi.xcertplay

import com.shilapi.xcertplay.StartupPhonePicker.Candidate
import com.shilapi.xcertplay.StartupPhonePicker.Decision
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class StartupPhonePickerTest {
    private val iap2 = StartupPhonePicker.IAP2_IPHONE_UUID
    private val handsFreeGateway = "0000111f-0000-1000-8000-00805f9b34fb"
    private val audioSource = "0000110a-0000-1000-8000-00805f9b34fb"
    private val handsFree = "0000111e-0000-1000-8000-00805f9b34fb"

    private val alex = Candidate("AA:00:00:00:00:01", "Alex’s iPhone", listOf(handsFreeGateway, iap2), isPhone = true, connected = false)
    private val sam = Candidate("AA:00:00:00:00:02", "Sam’s iPhone", listOf(audioSource, iap2), isPhone = true, connected = false)
    private val headset = Candidate("AA:00:00:00:00:03", "Headset", listOf(handsFree), isPhone = false, connected = true)
    // An Android phone is a PHONE in its Bluetooth class too, but it offers no iAP2 service.
    private val android = Candidate("AA:00:00:00:00:05", "Pixel", listOf(handsFreeGateway, audioSource), isPhone = true, connected = false)

    @After fun tearDown() {
        StartupPhonePicker.lastOutcome = null
        StartupPhonePicker.connectionStateReadable = null
    }

    @Test fun oneOrNoPairedIphoneKeepsTheUsualFlow() {
        assertEquals(Decision.Skip, StartupPhonePicker.decide(listOf(alex), alex.address))
        assertEquals(Decision.Skip, StartupPhonePicker.decide(emptyList(), null))
    }

    @Test fun pairedDevicesThatAreNotPhonesNeverCauseTheQuestion() {
        assertEquals(Decision.Skip, StartupPhonePicker.decide(listOf(alex, headset), alex.address))
    }

    @Test fun anAndroidPhoneIsNotAnIphone() {
        assertEquals(Decision.Skip, StartupPhonePicker.decide(listOf(alex, android), alex.address))
        // Alone on the car's Bluetooth, the Android phone is still neither chosen nor connected.
        assertEquals(Decision.Skip, StartupPhonePicker.decide(listOf(alex, android.copy(connected = true)), alex.address))
        assertEquals(Decision.Ask(listOf(alex, sam), alex.address),
            StartupPhonePicker.decide(listOf(sam, android.copy(connected = true), alex), alex.address))
    }

    @Test fun cachedServicesDecideOverTheDeviceClass() {
        // An iPhone whose class the car reports oddly is still an iPhone.
        val oddClass = sam.copy(isPhone = false)
        assertEquals(Decision.Ask(listOf(alex, oddClass), alex.address),
            StartupPhonePicker.decide(listOf(oddClass, alex), alex.address))
        // A device chosen earlier but known not to be an iPhone is left out.
        assertEquals(Decision.Skip, StartupPhonePicker.decide(listOf(alex, android), android.address))
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

    @Test fun withoutCachedServicesTheDeviceClassDecides() {
        for (uuids in listOf(null, emptyList<String>())) {
            val unknown = Candidate("AA:00:00:00:00:04", "iPhone", uuids, isPhone = null, connected = false)
            assertEquals(Decision.Ask(listOf(alex, unknown), alex.address),
                StartupPhonePicker.decide(listOf(unknown, alex), alex.address))
            val notAPhone = unknown.copy(isPhone = false)
            assertEquals(Decision.Skip, StartupPhonePicker.decide(listOf(notAPhone, alex), alex.address))
        }
    }

    @Test fun theChosenIphoneStaysWhenItsClassLooksWrongAndItsServicesAreNotCached() {
        val chosen = alex.copy(uuids = null, isPhone = false)
        assertEquals(Decision.Ask(listOf(chosen, sam), chosen.address),
            StartupPhonePicker.decide(listOf(sam, chosen), chosen.address))
    }

    @Test fun theDiagnosticLineHasNoNamesOrAddresses() {
        assertEquals("Startup iPhone question: ask_which_iphone=false; since app start: last=none isConnectedReadable=unknown",
            StartupPhonePicker.diagnosticLine(false))
        StartupPhonePicker.lastOutcome = StartupPhonePicker.Outcome.COUNTDOWN
        StartupPhonePicker.connectionStateReadable = false
        assertEquals("Startup iPhone question: ask_which_iphone=true; since app start: last=countdown isConnectedReadable=false",
            StartupPhonePicker.diagnosticLine(true))
    }
}
