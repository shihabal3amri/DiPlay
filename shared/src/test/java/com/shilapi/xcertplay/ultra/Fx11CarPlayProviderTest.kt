package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.transport.*
import org.junit.Assert.*
import org.junit.Test

class Fx11CarPlayProviderTest {
    private var now = 10000L
    private val sources = mutableListOf<Source>()
    private var values = readings()
    private val provider = Fx11CarPlayProvider({ Source().also { sources += it } }, { now })
    private fun readings() = mapOf(
        VehicleSignal.SPEED_KPH to VehicleReading(36.0, now, 0x11600207, now - 100),
        VehicleSignal.GEAR to VehicleReading("R", now, 557874334, 10),
        VehicleSignal.AMBIENT_C to VehicleReading(-4.5, now, 0x11600703, now),
        VehicleSignal.RANGE_KM to VehicleReading(500.0, now, 0x11600308, now),
    )
    private inner class Source : UltraVehicleSource {
        var started = false
        var closed = false
        override fun start() { started = true }
        override fun snapshot() = if (closed) emptyMap() else values
        override fun close() { closed = true }
    }
    @Test fun boundedPreflightDeclaresOnlyTemperatureAndReleasesSource() {
        assertTrue(sources.isEmpty())
        val status = provider.awaitSnapshot(0)!!
        assertEquals(setOf(4), Iap2VehicleStatus.capabilities(status))
        assertEquals(-4.5, status.outsideTemperatureCelsius!!, 0.0)
        assertNull(status.batteryPercent)
        assertNull(status.rangeKm)
        assertTrue(provider.available())
        assertTrue(sources.single().closed)
        assertNull(provider.drain())
        assertNull(provider.snapshot())
    }
    @Test fun convertsUnitsUsesEventTimeAndNeverRepeatsCachedSample() {
        provider.start()
        val reading = provider.drain()!!
        assertEquals(VehicleGear.REVERSE, reading.gear)
        assertEquals(9900L, reading.samples.single().elapsedMillis)
        assertEquals(10.0, reading.samples.single().metersPerSecond, 0.0)
        assertNull(provider.drain())
        now += 250; values = readings()
        assertNotNull(provider.drain())
        provider.stop()
        assertNull(provider.drain())
    }
    @Test fun subscriptionsStopIndependentlyAndCloseCannotRestart() {
        provider.start(); provider.onSubscriptionChanged(true)
        provider.onSubscriptionChanged(true)
        assertEquals(1, sources.size)
        provider.stop(); assertFalse(sources.single().closed)
        assertNotNull(provider.snapshot())
        provider.onSubscriptionChanged(false); assertTrue(sources.single().closed)
        provider.onSubscriptionChanged(true); provider.start()
        provider.onSubscriptionChanged(false); assertFalse(sources.last().closed)
        provider.close(); assertTrue(sources.last().closed)
        provider.start(); provider.onSubscriptionChanged(true)
        assertEquals(2, sources.size)
        assertNull(provider.snapshot()); assertNull(provider.awaitSnapshot(0))
    }
    @Test fun invalidMissingStaleOrFutureSignalsAreOmitted() {
        provider.start()
        val valid = readings()
        for (bad in listOf(Double.NaN, -1.0, 500.0)) {
            values = valid + (VehicleSignal.SPEED_KPH to valid.getValue(VehicleSignal.SPEED_KPH).copy(value = bad))
            assertNull(provider.drain())
        }
        for (time in listOf<Long?>(null, now - 2001, now + 1)) {
            values = valid + (VehicleSignal.SPEED_KPH to valid.getValue(VehicleSignal.SPEED_KPH).copy(sourceElapsedMillis = time))
            assertNull(provider.drain())
        }
        values = valid - VehicleSignal.GEAR; assertNull(provider.drain())
        values = valid + (VehicleSignal.GEAR to valid.getValue(VehicleSignal.GEAR).copy(value = "unknown"))
        assertNull(provider.drain())
        values = valid; now += 2501
        assertNull(provider.drain()); assertNull(provider.snapshot())
        values = emptyMap(); assertNull(provider.drain()); assertNull(provider.snapshot())
    }
    @Test fun staleOrFutureTemperatureIsNotRefreshedByGetter() {
        provider.onSubscriptionChanged(true)
        val valid = readings()
        for (time in listOf<Long?>(null, now - 30_001, now + 1)) {
            values = valid + (VehicleSignal.AMBIENT_C to valid.getValue(VehicleSignal.AMBIENT_C).copy(sourceElapsedMillis = time))
            assertNull(provider.snapshot())
        }
        values = valid
        assertNotNull(provider.snapshot())
    }
    @Test fun aRepeatedGetterCannotRefreshOldSpeedTimestamp() {
        val telemetry = VehicleTelemetry({ now })
        telemetry.accept(VehicleSignal.SPEED_KPH, 20.0, 1, now - 2001)
        assertTrue(telemetry.snapshot().isEmpty())
        telemetry.accept(VehicleSignal.SPEED_KPH, 20.0, 1, now)
        assertNotNull(telemetry.snapshot()[VehicleSignal.SPEED_KPH])
        telemetry.accept(VehicleSignal.SPEED_KPH, null, 1, now)
        assertTrue(telemetry.snapshot().isEmpty())
    }
}
