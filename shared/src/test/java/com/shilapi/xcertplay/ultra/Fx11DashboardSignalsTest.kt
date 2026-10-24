package com.shilapi.xcertplay.ultra

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class Fx11DashboardSignalsTest {
    @Test fun coolantUsesStandardCelsiusAndParkingUsesInvertedFx11Feedback() {
        val coolant=Fx11DashboardSignals.bindings.single { it.signal==VehicleSignal.ENGINE_COOLANT_C }
        assertEquals(0x11600301,coolant.propertyId)
        assertFalse(coolant.allowMissingConfig)
        assertEquals(90.0,coolant.decode(90f)); assertEquals(-20.0,coolant.decode(-20f))
        for(value in listOf(-41,216,65535,Double.NaN)) assertNull(coolant.decode(value))
        val brake=Fx11DashboardSignals.bindings.single { it.signal==VehicleSignal.PARKING_BRAKE_ON }
        assertEquals(557850632,brake.propertyId); assertFalse(brake.allowMissingConfig)
        assertTrue(brake.allowFloatPayload)
        assertEquals(true,brake.decode(0)); assertEquals(false,brake.decode(1))
        assertEquals(true,brake.decode(0f)); assertEquals(false,brake.decode(1f))
        for(value in listOf(-1,2,255,1.5,Double.NaN,Double.POSITIVE_INFINITY)) assertNull(brake.decode(value))
        assertTrue(Fx11DashboardSignals.bindings.none { it.propertyId==0x11200402 })
        assertTrue(Fx11DashboardSignals.bindings.none { it.signal in setOf(VehicleSignal.AUTO_HOLD_ACTIVE,VehicleSignal.HILL_DESCENT_ACTIVE) })
    }
    @Test fun frontCompartmentIsSeparateFromCoolantAndRejectsInvalidNumbers() {
        val binding = Fx11DashboardSignals.bindings.single { it.signal == VehicleSignal.FRONT_COMPARTMENT_C }
        assertEquals(559970554, binding.propertyId)
        assertTrue(binding.allowMissingConfig)
        assertEquals(23.5, binding.decode(23.5f))
        for (raw in listOf(-41,126,65535,Float.NaN,Float.POSITIVE_INFINITY)) assertNull(binding.decode(raw))
        assertNotEquals(VehicleSignal.ENGINE_COOLANT_C, binding.signal)
    }
    private fun decode(signal: VehicleSignal, value: Number) = Fx11DashboardSignals.bindings.first { it.signal == signal }.decode(value)
    private fun pa(data: Int, availability: Int = 1) = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(availability).putInt(data).putInt(0).putInt(0).array()

    @Test fun paEnvelopeDistinguishesAvailableZeroFromUnavailableAndRejectsMalformedPayloads() {
        assertEquals(0, Fx11DashboardSignals.paInteger(pa(0)))
        assertEquals(2400, Fx11DashboardSignals.paInteger(pa(2400)))
        for (status in listOf(0, 2, 3, 4, 255)) assertNull(Fx11DashboardSignals.paInteger(pa(2400, status)))
        for (size in listOf(0, 4, 8, 15, 17, 32)) assertNull(Fx11DashboardSignals.paInteger(ByteArray(size)))
    }
    @Test fun tirePressureMatchesLiveFx11Raw23AndOem23BarInsteadOfGenericSdkKpaScale() {
        for (signal in listOf(VehicleSignal.TIRE_FL_BAR, VehicleSignal.TIRE_FR_BAR, VehicleSignal.TIRE_RL_BAR, VehicleSignal.TIRE_RR_BAR)) {
            assertEquals(2.3, decode(signal, Fx11DashboardSignals.paInteger(pa(23))!!))
            assertEquals(2.4, decode(signal, 24))
            assertEquals(0.0, decode(signal, 0))
            for (invalid in listOf(-1,61,2400,65535,Double.NaN)) assertNull(decode(signal, invalid))
        }
        assertEquals(17.0, decode(VehicleSignal.FUEL_PERCENT, Fx11DashboardSignals.paInteger(pa(17))!!))
        assertEquals(61.0, decode(VehicleSignal.FUEL_PERCENT, 61))
        assertNull(decode(VehicleSignal.FUEL_PERCENT, 101))
        assertEquals(43821.0, decode(VehicleSignal.ODOMETER_KM, 43821))
    }
    @Test fun onlyExplicitNativeFeedbackPropertiesMayBypassMissingConfiguration() {
        assertEquals(setOf(559970554,557874773,561024416,561024488,561024399,561024400,561024401,561024402,559970552,557873489,557873490),
            Fx11DashboardSignals.bindings.filter { it.allowMissingConfig }.map { it.propertyId }.toSet())
        assertEquals(setOf(557874334), Fx11Signals.bindings.filter { it.allowMissingConfig }.map { it.propertyId }.toSet())
        val temperature=Fx11DashboardSignals.bindings.single { it.signal==VehicleSignal.INDICATED_AMBIENT_C }
        assertEquals(23.3f.toDouble(),temperature.decode(23.3f))
        assertEquals(-12.5,temperature.decode(-12.5))
        assertEquals(0.0,temperature.decode(0))
        for(invalid in listOf(-61,86,65535,Double.NaN,Double.POSITIVE_INFINITY)) assertNull(temperature.decode(invalid))
    }
    @Test fun indicatedSpeedUsesDocumentedIntegerKilometresPerHourAndExpiresByEventAge() {
        val binding=Fx11DashboardSignals.bindings.single { it.signal==VehicleSignal.INDICATED_SPEED_KPH }
        assertEquals(557874773,binding.propertyId)
        for(value in listOf(0,1,3,10,60,120,350)) assertEquals(value.toDouble(),binding.decode(value))
        for(value in listOf(-1,351,65535,1.5,Double.NaN,Double.POSITIVE_INFINITY)) assertNull(binding.decode(value))
        var now=2000L
        val telemetry=VehicleTelemetry({now})
        telemetry.accept(binding.signal,binding.decode(10),binding.propertyId,1000)
        assertEquals(10.0,telemetry.snapshot()[binding.signal]?.value)
        now=3001 // Received only 1001 ms ago, but the speed event is already stale.
        assertNull(telemetry.snapshot()[binding.signal])
    }
    @Test fun rangePaUsesKilometresAndClearsUnavailableEnvelopeInsteadOfInventingRange() {
        val binding=Fx11DashboardSignals.bindings.single { it.signal==VehicleSignal.INDICATED_RANGE_KM }
        assertEquals(561024488,binding.propertyId)
        assertTrue(binding.paInteger)
        assertEquals(326.0,binding.decode(Fx11DashboardSignals.paInteger(pa(326))!!))
        assertEquals(0.0,binding.decode(Fx11DashboardSignals.paInteger(pa(0))!!))
        assertNull(Fx11DashboardSignals.paInteger(pa(326,2)))
        for (invalid in listOf(-1,65535,Double.NaN)) assertNull(binding.decode(invalid))
    }
    @Test fun accTargetUsesDocumentedStandardUnitsButDoesNotGuessVendorDisplayUnits() {
        val binding=Fx11DashboardSignals.bindings.single { it.signal==VehicleSignal.ACC_SET_SPEED_KPH }
        assertEquals(0x11601013,binding.propertyId)
        assertEquals(72.0,binding.decode(20.0))
        for (invalid in listOf(-1,255,65535,Double.NaN)) assertNull(binding.decode(invalid))
        val raw=Fx11DashboardSignals.bindings.single { it.signal==VehicleSignal.ACC_SET_SPEED_RAW }
        assertEquals(557849502,raw.propertyId)
        assertEquals(80.0,raw.decode(80))
        assertEquals(1.5,raw.decode(1.5)) // Diagnostic raw value, never a displayed km/h reading.
        assertNull(raw.decode(Double.NaN))
        assertFalse(Fx11DashboardSignals.bindings.any { it.propertyId==557849448 })
        var now=100L
        val telemetry=VehicleTelemetry({now})
        for (speed in listOf(20.0,25.0,30.0)) {
            telemetry.accept(binding.signal,binding.decode(speed),binding.propertyId)
            assertEquals(speed*3.6,telemetry.snapshot()[VehicleSignal.ACC_SET_SPEED_KPH]?.value)
        }
        telemetry.accept(binding.signal,null,binding.propertyId)
        assertFalse(telemetry.snapshot().containsKey(VehicleSignal.ACC_SET_SPEED_KPH))
        telemetry.accept(binding.signal,binding.decode(20),binding.propertyId)
        now+=2501
        assertFalse(telemetry.snapshot().containsKey(VehicleSignal.ACC_SET_SPEED_KPH))
    }
    @Test fun assistanceWhitelistDoesNotInventCenteringFromGpilotOrLaneColour() {
        assertEquals("GPILOT", decode(VehicleSignal.ASSIST_MODE, 2))
        assertNull(decode(VehicleSignal.ASSIST_MODE, 3))
        for (reserved in listOf(0, 3, 9, 255)) assertNull(decode(VehicleSignal.ACC_STATE, reserved))
        assertEquals(2, decode(VehicleSignal.ACC_STATE, 2))
        assertNull(decode(VehicleSignal.LANE_LEFT_COLOR, 255))
        assertTrue(Fx11DashboardSignals.bindings.none { it.signal == VehicleSignal.LANE_CENTERING_ACTIVE })
        assertTrue(Fx11DashboardSignals.bindings.none { it.propertyId == 557871511 })
    }
    @Test fun followGapUsesThreeOemLevelsAndClearsOnNoneInvalidOrMissingFeedback() {
        var now=100L
        val telemetry=VehicleTelemetry({ now })
        val binding=Fx11DashboardSignals.bindings.single { it.signal == VehicleSignal.FOLLOW_GAP }
        assertEquals(557849293,binding.propertyId)
        // Simulate actual feedback changes, not wheel events. No CarPlay state is involved.
        for (level in listOf(3,2,1,2,3)) {
            telemetry.accept(binding.signal,binding.decode(level),binding.propertyId)
            assertEquals(level,telemetry.snapshot()[binding.signal]?.value)
        }
        for (invalid in listOf(0,-1,4,255,1.5,Double.NaN,Double.POSITIVE_INFINITY)) {
            telemetry.accept(binding.signal,binding.decode(2),binding.propertyId)
            telemetry.accept(binding.signal,binding.decode(invalid),binding.propertyId)
            assertFalse(telemetry.snapshot().containsKey(binding.signal))
        }
        telemetry.accept(binding.signal,binding.decode(3),binding.propertyId)
        now+=2501
        assertFalse(telemetry.snapshot().containsKey(binding.signal))
    }
    @Test fun lateralDiagnosticsNeverBecomeConfirmedCenteringEvenWithBlueLanesAndActiveGpilot() {
        val telemetry=VehicleTelemetry({ 100L })
        for ((signal,raw) in listOf(VehicleSignal.ASSIST_MODE to 2, VehicleSignal.ACC_STATE to 2,
            VehicleSignal.LANE_LEFT_COLOR to 2,VehicleSignal.LANE_RIGHT_COLOR to 2,
            VehicleSignal.LATERAL_INDICATOR_RAW to 2,VehicleSignal.LATERAL_INDICATOR_TIMEOUT to 0)) {
            telemetry.accept(signal,decode(signal,raw),0)
        }
        assertEquals(2,telemetry.snapshot()[VehicleSignal.LATERAL_INDICATOR_RAW]?.value)
        assertEquals(false,telemetry.snapshot()[VehicleSignal.LATERAL_INDICATOR_TIMEOUT]?.value)
        assertFalse(telemetry.snapshot().containsKey(VehicleSignal.LANE_CENTERING_ACTIVE))
        assertNull(decode(VehicleSignal.LATERAL_INDICATOR_RAW, -1))
        assertNull(decode(VehicleSignal.LATERAL_INDICATOR_RAW, 1.5))
        assertNull(decode(VehicleSignal.LATERAL_INDICATOR_TIMEOUT, 255))
    }
    @Test fun unreliableStandardAutoLampIsExcludedAndOrdinaryCruiseStillExpires() {
        assertTrue(Fx11DashboardSignals.bindings.none { it.signal == VehicleSignal.AUTO_HEADLIGHTS || it.propertyId == 0x11400e10 })
        assertEquals("OFF",decode(VehicleSignal.CRUISE_STATE,1))
        assertEquals("STANDBY",decode(VehicleSignal.CRUISE_STATE,2))
        assertEquals("ACTIVE",decode(VehicleSignal.CRUISE_STATE,3))
        for(value in listOf(0,4,255,1.5,Double.NaN)) assertNull(decode(VehicleSignal.CRUISE_STATE,value))
        var now=0L
        val telemetry=VehicleTelemetry({now})
        telemetry.accept(VehicleSignal.CRUISE_STATE,decode(VehicleSignal.CRUISE_STATE,3),557849289)
        now=2501
        assertTrue(telemetry.snapshot().isEmpty())
    }
    @Test fun lampsAndSeatbeltsAreTriStateAndExpireWithoutPretendingOffOrFastened() {
        var now=1L
        val telemetry=VehicleTelemetry({now})
        for (signal in listOf(VehicleSignal.LOW_BEAM, VehicleSignal.LEFT_TURN, VehicleSignal.DRIVER_BELT_FASTENED)) {
            assertEquals(false,decode(signal,0)); assertEquals(true,decode(signal,1)); assertNull(decode(signal,255))
            telemetry.accept(signal, decode(signal,0),0)
        }
        assertEquals(3,telemetry.snapshot().size)
        now+=2501
        assertTrue(telemetry.snapshot().isEmpty())
        assertTrue(Fx11Signals.bindings.none { it.paInteger }) // Optional native extension only.
    }
    @Test fun nativeNumericSlotCompatibilityDoesNotBroadenPaOrStandardBindings() {
        for (binding in Fx11DashboardSignals.bindings) {
            assertEquals(binding.propertyId and 0xffff0000.toInt() == 0x21400000, binding.allowFloatPayload)
        }
        assertTrue(Fx11Signals.bindings.none { it.allowFloatPayload })
        assertTrue(Fx11DashboardSignals.bindings.none { it.signal == VehicleSignal.FRONT_FOG })
    }

}
