package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import org.junit.Assert.*
import org.junit.Test

class Iap2LocationSubscriptionTest {
    @Test fun onlyRequestedSentencesAreSentWithMinimumInterval() {
        var now = 100L
        val subscription = Iap2LocationSubscription { now }
        assertTrue(subscription.request(Iap2Messages.buildRaw(0xfffa) { void(1); u32(0x8001, 2_000) }))
        val nmea = "\$GPGGA,x\r\n\$GPRMC,x\r\n\$PASCD,x\r\n"
        assertEquals("\$GPGGA,x\r\n", subscription.filter(nmea))
        now += 1_999
        assertNull(subscription.filter(nmea))
        now += 1
        assertEquals("\$GPGGA,x\r\n", subscription.filter(nmea))
        subscription.stop()
        assertNull(subscription.filter(nmea))
        assertTrue(subscription.request(Iap2Messages.buildRaw(0xfffa) { void(4) }))
        assertEquals("\$PASCD,x\r\n", subscription.filter(nmea))
    }
    @Test fun malformedKnownSelectorOrIntervalRevokesAuthorization() {
        val subscription = Iap2LocationSubscription { 100 }
        assertTrue(subscription.request(Iap2Messages.buildRaw(0xfffa) { void(1) }))
        assertFalse(subscription.request(Iap2Messages.buildRaw(0xfffa) { u8(1, 1) }))
        assertTrue(subscription.components.isEmpty())
        assertFalse(subscription.request(Iap2Messages.buildRaw(0xfffa) { void(4); u16(0x8004, 20) }))
        assertTrue(subscription.components.isEmpty())
    }
    @Test fun sourceRecoveryCannotExpandThisLinksAdvertisedComponents() {
        val subscription = Iap2LocationSubscription { 100 }
        assertFalse(subscription.request(Iap2Messages.buildRaw(0xfffa) { void(4) }, setOf(1, 2)))
        assertNull(subscription.filter("\$PASCD,newly-readable\r\n"))
        assertTrue(subscription.request(Iap2Messages.buildRaw(0xfffa) { void(4) }, setOf(4)))
        assertNotNull(subscription.filter("\$PASCD,newly-readable\r\n"))
    }
    @Test fun multipleSentencesUseRepeatedNmeaParameters() {
        val fields = Iap2ParameterList.parse(Iap2LocationMessages.locationInformation(
            "\$GPGGA,x\r\n\$GPRMC,x\r\n").payload).asList()
        assertEquals(listOf(0, 0), fields.map { it.id })
        assertEquals("\$GPGGA,x\r\n\u0000", fields[0].payload.decodeToString())
        assertEquals("\$GPRMC,x\r\n\u0000", fields[1].payload.decodeToString())
    }
}
