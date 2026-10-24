package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.*
import org.junit.Test

class Fx11Iap2IntegrationTest {
    private fun config() = Iap2IdentificationConfig("DiPlay", "FX11", "Geely", "test", "1", "1", 3,
        vehicleDataEnabled = true, locationInformationEnabled = true, gpsLocationEnabled = false, vehicleSpeedEnabled = true)
    private class Status : VehicleStatusProvider {
        var value: VehicleStatusSnapshot? = VehicleStatusSnapshot(outsideTemperatureCelsius = -5.0)
        var starts = 0; var stops = 0; var reads = 0
        override fun snapshot(): VehicleStatusSnapshot? { reads++; return value }
        override fun onSubscriptionChanged(active: Boolean) { if (active) starts++ else stops++ }
    }
    @Test fun temperatureDoesNotDeclareElectricEngineOrChargingConnectors() {
        val identified = config().withVehicleStatusFrom(Status())
        assertFalse(identified.vehicleStatusEnabled)
        assertEquals(setOf(4), identified.vehicleStatusCapabilities)
        val body = Iap2BodyReader.of(Iap2IdentificationClient.identificationInformation(identified))
        assertNull(body.group(20).first(2)); assertNull(body.group(20).first(11))
        assertEquals(setOf(0, 1, 4), body.group(21).list().map { it.id }.toSet())
        assertEquals(setOf(0, 1, 20), body.group(22).list().map { it.id }.toSet())
        assertTrue(body.u16List(6).contains(0xa101))
        val disabled = config().copy(vehicleDataEnabled = false).withVehicleStatusFrom(Status())
        assertTrue(disabled.vehicleStatusCapabilities.isEmpty())
    }
    @Test fun requestedAdvertisedAndValidFieldsAreAllRequired() {
        val sample = VehicleStatusSnapshot(outsideTemperatureCelsius = -4.5)
        assertNull(Iap2VehicleStatus.update(sample, setOf(4), emptySet()))
        assertNull(Iap2VehicleStatus.update(sample, emptySet(), setOf(4)))
        assertNull(Iap2VehicleStatus.update(sample.copy(outsideTemperatureCelsius = Double.NaN), setOf(4), setOf(4)))
        val fields = Iap2BodyReader.of(Iap2VehicleStatus.update(sample, setOf(4), setOf(4))!!)
        assertEquals(listOf(4), fields.list().map { it.id })
        assertEquals(-4, fields.i16(4))
    }
    @Test fun noStatusBeforeSubscriptionAndStopMalformedAndCloseReleaseExactlyOnce() {
        val source = Status(); var now = 0L
        val link = Iap2VehicleStatusReporter(source, {}, setOf(4), { now })
        val frames = mutableListOf<Iap2Frame>()
        val send: (Iap2Frame) -> Unit = { frames += it }
        link.tick(send); assertTrue(frames.isEmpty())
        val start = Iap2Messages.buildRaw(0xa100) { void(4) }
        link.handle(start, send); assertEquals(1, source.starts); assertEquals(1, frames.size)
        link.handle(start, send); assertEquals(1, source.starts)
        link.handle(Iap2Messages.buildRaw(0xa100) { u8(4, 1) }, send)
        assertEquals(1, source.stops)
        val count = frames.size
        now += 60_000_000_000L; link.tick(send); assertEquals(count, frames.size)
        link.handle(start, send)
        link.handle(Iap2Messages.buildRaw(0xa102) {}, send); link.stop()
        assertEquals(2, source.starts); assertEquals(2, source.stops)
    }
    @Test fun absentReadingsBackOffAndDoNotFabricateZeros() {
        val source = Status().apply { value = null }; var now = 0L
        val link = Iap2VehicleStatusReporter(source, {}, setOf(4), { now })
        link.handle(Iap2Messages.buildRaw(0xa100) { void(4) }) { fail("No reading") }
        repeat(100) { link.tick { fail("No reading") } }
        assertEquals(1, source.reads)
        now = 30_000_000_000
        source.value = VehicleStatusSnapshot(outsideTemperatureCelsius = 0.0)
        val sent = mutableListOf<Iap2Frame>(); link.tick { sent += it }
        assertEquals(1, sent.size)
        assertEquals(0, Iap2BodyReader.of(sent.single()).i16(4))
    }
    @Test fun fullBydBatteryFieldsRemainAvailableWithoutInventingEnergy() {
        val value = VehicleStatusSnapshot(150, false, 25.0, null, null, 600, true)
        val fields = Iap2BodyReader.of(Iap2VehicleStatus.update(value,
            Iap2VehicleStatus.capabilities(value), setOf(11))!!)
        assertEquals(150, fields.u16(11)); assertNull(fields.first(3))
        assertEquals(25000L, fields.u32(24)); assertTrue(fields.bool(25))
        assertEquals(600, fields.u16(30)); assertNull(fields.first(22)); assertNull(fields.first(23))
    }
    @Test fun locationSubscriptionCannotGainFieldsAfterIdentificationOrSurviveMalformedRequest() {
        var starts = 0; var stops = 0
        val position = object : Iap2LocationProvider {
            override fun supportedComponents() = setOf(1, 2, 4)
            override fun start(): Boolean { starts++; return true }
            override fun stop() { stops++ }
            override fun latestNmea() = "\$GPGGA,x\r\n\$GPRMC,x\r\n\$PASCD,x\r\n"
        }
        val link = Iap2LocationReporter(position, {}, advertised = setOf(1))
        val sent = mutableListOf<Iap2Frame>()
        link.handle(Iap2Messages.buildRaw(0xfffa) { void(4) }) { sent += it }
        assertEquals(0, starts); assertTrue(sent.isEmpty())
        link.handle(Iap2Messages.buildRaw(0xfffa) { void(1); void(4) }) { sent += it }
        assertEquals(1, starts)
        val text = Iap2BodyReader.of(sent.single()).string(0)
        assertEquals("\$GPGGA,x\r\n", text)
        link.handle(Iap2Messages.buildRaw(0xfffa) { void(1); u16(0x8001, 1000) }) { fail("Malformed request") }
        assertEquals(3, stops)
    }
    @Test fun removingSpeedSubscriptionStopsSamplerAndGpsOnlyIsNotAdvertisedAsSpeed() {
        var running = false
        val source = object : VehicleSpeedSource {
            override fun start() { running = true }
            override fun stop() { running = false }
            override fun drain(): VehicleSpeedReading? = null
        }
        val provider = VehicleSpeedLocationProvider(null, source)
        provider.onRequested(setOf(4)); provider.start(); assertTrue(running)
        provider.onRequested(setOf(1)); provider.start(); assertFalse(running)
        val identified = config().withLocationFrom(provider)
        assertFalse(identified.gpsLocationEnabled); assertTrue(identified.vehicleSpeedEnabled)
        assertTrue(identified.locationInformationEnabled)
        provider.stop()
    }
    @Test fun encoderRejectsNonfiniteNegativeOrUnorderedSamples() {
        for (speed in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 151.0))
            assertNull(PascdEncoder.encode(VehicleSpeedReading(VehicleGear.DRIVE, listOf(VehicleSpeedSample(1000, speed)))))
        assertNull(PascdEncoder.encode(VehicleSpeedReading(VehicleGear.DRIVE,
            listOf(VehicleSpeedSample(1000, 10.0), VehicleSpeedSample(900, 10.0)))))
    }
}
