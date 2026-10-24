package com.shilapi.xcertplay.ultra

import android.os.Looper
import android.os.SystemClock
import java.time.Duration
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Fx11DashboardSourceTest {
    @Test fun vendorParkingLampReadsInvertedInt32AndClearsInvalidOrExpiredFeedback() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        val binding=Fx11DashboardSignals.bindings.single { it.signal==VehicleSignal.PARKING_BRAKE_ON }
        val source=Fx11VehicleSource(setOf(binding.signal),listOf(binding))
        val vehicle=Vehicle()
        ReflectionHelpers.setField(source,"vehicle",vehicle)
        ReflectionHelpers.setField(source,"valueClass",Value::class.java)
        try {
            assertTrue(selectTargets(source,emptyList()).isEmpty())
            assertEquals(listOf(binding),selectTargets(source,listOf(PropertyConfig(binding.propertyId))))
            assertTrue(selectTargets(source,listOf(PropertyConfig(0x11200402))).isEmpty())
            assertTrue(selectTargets(source,listOf(PropertyConfig(binding.propertyId,access=2))).isEmpty())
            for(value in listOf(0,1,0,1,2,255)) {
                vehicle.reply=Value().apply { prop=binding.propertyId; timestamp=SystemClock.elapsedRealtimeNanos(); this.value.int32Values.add(value) }
                read(source,binding)
                assertEquals(when(value) { 0->true; 1->false; else->null },source.snapshot()[binding.signal]?.value)
            }
            vehicle.reply.value.int32Values=arrayListOf(0); read(source,binding)
            assertEquals(true,source.snapshot()[binding.signal]?.value)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2501))
            assertNull(source.snapshot()[binding.signal])
        } finally { source.close() }
    }
    @Test fun parkingLampAcceptsVendorFloatPayloadButCannotKeepOnAfterFailedOrWrongSourceRead() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        val binding=Fx11DashboardSignals.bindings.single { it.signal==VehicleSignal.PARKING_BRAKE_ON }
        val source=Fx11VehicleSource(setOf(binding.signal),listOf(binding))
        val vehicle=Vehicle()
        ReflectionHelpers.setField(source,"vehicle",vehicle)
        ReflectionHelpers.setField(source,"valueClass",Value::class.java)
        fun valid() {
            vehicle.code=0
            vehicle.reply=Value().apply {
                prop=binding.propertyId; timestamp=SystemClock.elapsedRealtimeNanos(); value.floatValues.add(0f)
            }
            read(source,binding)
            assertEquals(true,source.snapshot()[binding.signal]?.value)
        }
        try {
            valid()
            vehicle.reply.value.floatValues[0]=1f; read(source,binding)
            assertEquals(false,source.snapshot()[binding.signal]?.value)
            val invalid=listOf<Value.()->Unit>(
                { value.floatValues[0]=1.5f }, { value.floatValues[0]=2f },
                { prop=0x11200402; value.floatValues.clear(); value.int32Values.add(1) },
                { status=1 }, { areaId=1 }, { timestamp=0 },
                { value.floatValues.clear() })
            for(change in invalid) {
                valid(); vehicle.reply.change(); read(source,binding)
                assertNull(source.snapshot()[binding.signal])
            }
            valid(); vehicle.code=1; read(source,binding)
            assertNull(source.snapshot()[binding.signal])
        } finally { source.close() }
    }
    class Raw {
        @JvmField var int32Values = arrayListOf<Int>()
        @JvmField var floatValues = arrayListOf<Float>()
        @JvmField var bytes = arrayListOf<Byte>()
    }
    class Value {
        @JvmField var prop = 0
        @JvmField var areaId = 0
        @JvmField var status = 0
        @JvmField var timestamp = 0L
        @JvmField var value = Raw()
    }
    interface Callback { fun onValues(code: Int, value: Value) }
    interface NullableCallback { fun onValues(code: Int, value: Value?) }
    class NullVehicle {
        fun get(request: Value, callback: NullableCallback) {
            assertEquals(0, request.areaId)
            callback.onValues(0, null)
        }
    }
    class Area(@JvmField var areaId: Int)
    class PropertyConfig(
        @JvmField var prop: Int,
        @JvmField var access: Int = 1,
        @JvmField var areaConfigs: List<Area> = emptyList(),
    )
    class Vehicle {
        var code = 0
        var reply = Value()
        fun get(request: Value, callback: Callback) {
            assertEquals(0,request.areaId)
            callback.onValues(code,reply)
        }
    }
    private fun read(source: Fx11VehicleSource, binding: Fx11Signals.Binding) =
        ReflectionHelpers.callInstanceMethod<Unit>(source,"read",ReflectionHelpers.ClassParameter.from(Fx11Signals.Binding::class.java,binding))
    private fun selectTargets(source: Fx11VehicleSource, configs: List<PropertyConfig>) =
        ReflectionHelpers.callInstanceMethod<List<Fx11Signals.Binding>>(source, "selectTargets",
            ReflectionHelpers.ClassParameter.from(List::class.java, configs))
    private fun paValue(binding: Fx11Signals.Binding, data: Int) = Value().apply {
        prop = binding.propertyId
        timestamp = SystemClock.elapsedRealtimeNanos()
        value.bytes.addAll(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(1).putInt(data).putInt(0).putInt(0).array().toList())
    }

    @Test fun onlyVerifiedBindingsReadDirectlyWhenFx11OmitsTheirConfigs() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        val signals = setOf(VehicleSignal.FRONT_COMPARTMENT_C, VehicleSignal.FUEL_PERCENT, VehicleSignal.INDICATED_RANGE_KM,
            VehicleSignal.TIRE_FL_BAR, VehicleSignal.TIRE_FR_BAR, VehicleSignal.TIRE_RL_BAR, VehicleSignal.TIRE_RR_BAR,
            VehicleSignal.INDICATED_AMBIENT_C, VehicleSignal.LEFT_TURN, VehicleSignal.RIGHT_TURN, VehicleSignal.GEAR,
            VehicleSignal.INDICATED_SPEED_KPH)
        val source = Fx11VehicleSource(extraBindings = Fx11DashboardSignals.bindings)
        val vehicle = Vehicle()
        ReflectionHelpers.setField(source, "vehicle", vehicle)
        ReflectionHelpers.setField(source, "valueClass", Value::class.java)
        try {
            val targets = selectTargets(source, emptyList())
            assertEquals(signals, targets.map { it.signal }.toSet())
            for (binding in targets) {
                val raw = when (binding.signal) {
                    VehicleSignal.FUEL_PERCENT -> 17
                    VehicleSignal.INDICATED_RANGE_KM -> 111
                    else -> 23
                }
                vehicle.reply = if (binding.paInteger) paValue(binding, raw) else Value().apply {
                    prop = binding.propertyId
                    timestamp = SystemClock.elapsedRealtimeNanos()
                    if (binding.signal == VehicleSignal.GEAR) value.int32Values.add(0)
                    else value.floatValues.add(if (binding.signal == VehicleSignal.INDICATED_AMBIENT_C) 23.3f else 1f)
                }
                read(source, binding)
            }
            assertEquals(true, source.snapshot()[VehicleSignal.LEFT_TURN]?.value)
            assertEquals("P", source.snapshot()[VehicleSignal.GEAR]?.value)
            assertEquals(1.0, source.snapshot()[VehicleSignal.INDICATED_SPEED_KPH]?.value)
            assertEquals(true, source.snapshot()[VehicleSignal.RIGHT_TURN]?.value)
            assertEquals(17.0, source.snapshot()[VehicleSignal.FUEL_PERCENT]?.value)
            assertEquals(111.0, source.snapshot()[VehicleSignal.INDICATED_RANGE_KM]?.value)
            for (tire in listOf(VehicleSignal.TIRE_FL_BAR, VehicleSignal.TIRE_FR_BAR, VehicleSignal.TIRE_RL_BAR, VehicleSignal.TIRE_RR_BAR)) {
                assertEquals(2.3, (source.snapshot()[tire]?.value as Number).toDouble(), 0.0001)
            }
            assertEquals(23.3, (source.snapshot()[VehicleSignal.INDICATED_AMBIENT_C]?.value as Number).toDouble(), 0.0001)
            assertTrue(source.diagnosticReport().contains("source=allowlisted direct read"))
            assertTrue(source.diagnosticReport().contains("accepted; result=0 status=0"))
            assertFalse(source.snapshot().containsKey(VehicleSignal.ACC_SET_SPEED_KPH))
            assertFalse(source.snapshot().containsKey(VehicleSignal.LANE_CENTERING_ACTIVE))
        } finally { source.close() }
    }

    @Test fun indicatedSpeedReadsLowSpeedsWithoutConfigAndClearsInvalidOrStaleReadings() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5000))
        val binding=Fx11DashboardSignals.bindings.single { it.signal==VehicleSignal.INDICATED_SPEED_KPH }
        val source=Fx11VehicleSource(setOf(binding.signal),listOf(binding))
        val vehicle=Vehicle()
        ReflectionHelpers.setField(source,"vehicle",vehicle)
        ReflectionHelpers.setField(source,"valueClass",Value::class.java)
        fun reply(speed:Int)=Value().apply {
            prop=binding.propertyId; timestamp=SystemClock.elapsedRealtimeNanos()
            value.int32Values.add(speed)
        }
        try {
            assertEquals(listOf(binding),selectTargets(source,emptyList()))
            assertTrue(selectTargets(source,listOf(PropertyConfig(binding.propertyId,access=2))).isEmpty())
            assertTrue(selectTargets(source,listOf(PropertyConfig(binding.propertyId,areaConfigs=listOf(Area(1))))).isEmpty())
            for(speed in listOf(0,1,3,10,60,0)) {
                vehicle.reply=reply(speed); read(source,binding)
                assertEquals(speed.toDouble(),source.snapshot()[binding.signal]?.value)
            }
            val invalid=listOf<Value.()->Unit>(
                { status=1 }, { areaId=1 }, { prop++ }, { value.int32Values.clear() },
                { value.int32Values[0]=65535 }, { timestamp=0 },
                { timestamp-=2_001_000_000L }, { timestamp+=1_000_000_000L })
            for(change in invalid) {
                vehicle.reply=reply(10); read(source,binding)
                assertEquals(10.0,source.snapshot()[binding.signal]?.value)
                vehicle.reply.change(); read(source,binding)
                assertNull(source.snapshot()[binding.signal])
            }
            vehicle.reply=reply(10); read(source,binding)
            vehicle.code=1; read(source,binding); assertNull(source.snapshot()[binding.signal])
            vehicle.code=0; vehicle.reply=reply(1); read(source,binding)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2001))
            assertNull(source.snapshot()[binding.signal])
        } finally { source.close() }
    }

    @Test fun omittedGearReadsVendorEnumAndClearsInvalidUnavailableAndExpiredFeedback() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        val binding = Fx11Signals.bindings.single { it.signal == VehicleSignal.GEAR }
        val source = Fx11VehicleSource(setOf(VehicleSignal.GEAR))
        val vehicle = Vehicle()
        ReflectionHelpers.setField(source, "vehicle", vehicle)
        ReflectionHelpers.setField(source, "valueClass", Value::class.java)
        try {
            assertEquals(listOf(binding), selectTargets(source, emptyList()))
            assertTrue(selectTargets(source, listOf(PropertyConfig(binding.propertyId, access = 2))).isEmpty())
            assertTrue(selectTargets(source, listOf(PropertyConfig(binding.propertyId, areaConfigs = listOf(Area(1))))).isEmpty())
            for ((raw, expected) in listOf(0 to "P", 1 to "R", 2 to "N", 3 to "D", 4 to null, 255 to null)) {
                vehicle.reply = Value().apply {
                    prop = binding.propertyId; timestamp = SystemClock.elapsedRealtimeNanos()
                    value.int32Values.add(raw)
                }
                read(source, binding)
                assertEquals(expected, source.snapshot()[VehicleSignal.GEAR]?.value)
            }
            vehicle.reply.value.int32Values[0] = 0
            read(source, binding); assertEquals("P", source.snapshot()[VehicleSignal.GEAR]?.value)
            vehicle.reply.status = 1
            read(source, binding); assertNull(source.snapshot()[VehicleSignal.GEAR])
            vehicle.reply.status = 0; vehicle.reply.value.int32Values.clear()
            read(source, binding); assertNull(source.snapshot()[VehicleSignal.GEAR])
            vehicle.reply.value.int32Values.add(3)
            read(source, binding); assertEquals("D", source.snapshot()[VehicleSignal.GEAR]?.value)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2501))
            assertNull(source.snapshot()[VehicleSignal.GEAR])
        } finally { source.close() }
    }

    @Test fun missingConfigBypassDoesNotOverrideAdvertisedAccessOrAreaRestrictions() {
        val fuel = Fx11DashboardSignals.bindings.single { it.signal == VehicleSignal.FUEL_PERCENT }
        val source = Fx11VehicleSource(setOf(fuel.signal), listOf(fuel))
        val unmarked = Fx11VehicleSource(setOf(fuel.signal), listOf(fuel.copy(allowMissingConfig = false)))
        try {
            assertTrue(selectTargets(unmarked, emptyList()).isEmpty())
            assertTrue(unmarked.diagnosticReport().contains("config missing"))
            assertTrue(selectTargets(source, listOf(PropertyConfig(fuel.propertyId, access = 2))).isEmpty())
            assertTrue(source.diagnosticReport().contains("config not readable"))
            assertTrue(selectTargets(source, listOf(PropertyConfig(fuel.propertyId, access = 0))).isEmpty())
            assertTrue(selectTargets(source, listOf(PropertyConfig(fuel.propertyId, areaConfigs = listOf(Area(1))))).isEmpty())
            assertTrue(source.diagnosticReport().contains("global area 0 missing; areas=[1]"))
            assertEquals(listOf(fuel), selectTargets(source, listOf(PropertyConfig(fuel.propertyId))))
            assertTrue(source.diagnosticReport().contains("source=advertised"))
            assertEquals(listOf(fuel), selectTargets(source,
                listOf(PropertyConfig(fuel.propertyId, access = 3, areaConfigs = listOf(Area(0))))))
        } finally { source.close(); unmarked.close() }
    }

    @Test fun directReadsStillRejectUnavailableMalformedAndNullValuesWithoutInventingZero() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        val fuel = Fx11DashboardSignals.bindings.single { it.signal == VehicleSignal.FUEL_PERCENT }
        val source = Fx11VehicleSource(setOf(fuel.signal), listOf(fuel))
        val vehicle = Vehicle()
        ReflectionHelpers.setField(source, "vehicle", vehicle)
        ReflectionHelpers.setField(source, "valueClass", Value::class.java)
        try {
            assertEquals(listOf(fuel), selectTargets(source, emptyList()))
            val invalidValues = listOf<Value.() -> Unit>(
                { value.bytes[0] = 2 },
                { value.bytes.clear() },
                { status = 1 },
                { areaId = 1 },
                { prop++ },
                { timestamp = 0 },
                { timestamp = SystemClock.elapsedRealtimeNanos() + 1_000_000_000L },
            )
            for (invalidate in invalidValues) {
                vehicle.reply = paValue(fuel, 17)
                read(source, fuel)
                assertEquals(17.0, source.snapshot()[fuel.signal]?.value)
                vehicle.reply.invalidate()
                read(source, fuel)
                assertFalse(source.snapshot().containsKey(fuel.signal))
            }
            vehicle.reply = paValue(fuel, 17)
            read(source, fuel)
            ReflectionHelpers.setField(source, "vehicle", NullVehicle())
            read(source, fuel)
            assertFalse(source.snapshot().containsKey(fuel.signal))
            assertTrue(source.diagnosticReport().contains("no value"))
            assertTrue(source.diagnosticReport().contains("source=allowlisted direct read"))
        } finally { source.close() }
    }

    @Test fun unconfirmedAccAndLanePropertiesRemainUnselectedWithoutConfigs() {
        val signals = setOf(VehicleSignal.ACC_SET_SPEED_KPH, VehicleSignal.ACC_SET_SPEED_RAW,
            VehicleSignal.LATERAL_INDICATOR_RAW, VehicleSignal.LANE_CENTERING_ACTIVE)
        val source = Fx11VehicleSource(signals, Fx11DashboardSignals.bindings)
        try {
            assertTrue(selectTargets(source, emptyList()).isEmpty())
            assertTrue(source.snapshot().isEmpty())
        } finally { source.close() }
    }

    @Test fun actualReadAdapterConvertsTargetSpeedAndClearsFailureOrUnavailableStatus() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        val binding=Fx11DashboardSignals.bindings.single { it.signal==VehicleSignal.ACC_SET_SPEED_KPH }
        val vehicle=Vehicle()
        val source=Fx11VehicleSource(extraBindings=Fx11DashboardSignals.bindings)
        ReflectionHelpers.setField(source,"vehicle",vehicle)
        ReflectionHelpers.setField(source,"valueClass",Value::class.java)
        fun valid() {
            vehicle.code=0
            vehicle.reply=Value().apply {
                prop=binding.propertyId; timestamp=SystemClock.elapsedRealtimeNanos()
                value.floatValues.add(20f)
            }
            read(source,binding)
            assertEquals(72.0,source.snapshot()[binding.signal]?.value)
        }
        try {
            for (status in listOf(1,2,3)) {
                valid(); vehicle.reply.status=status; read(source,binding)
                assertFalse(source.snapshot().containsKey(binding.signal))
            }
            valid(); vehicle.code=1; read(source,binding)
            assertFalse(source.snapshot().containsKey(binding.signal))
            valid(); vehicle.reply.areaId=1; read(source,binding)
            assertFalse(source.snapshot().containsKey(binding.signal))
            valid(); vehicle.reply.prop=557849448; read(source,binding)
            assertFalse(source.snapshot().containsKey(binding.signal))
            valid(); vehicle.reply.timestamp=0; read(source,binding)
            assertFalse(source.snapshot().containsKey(binding.signal))
            valid(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2501))
            assertFalse(source.snapshot().containsKey(binding.signal))
        } finally { source.close() }
    }
    @Test fun oemRangePaAndFloatDiagnosticAreReadWithoutPromotingRawAccToKph() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        val vehicle=Vehicle()
        val source=Fx11VehicleSource(extraBindings=Fx11DashboardSignals.bindings)
        ReflectionHelpers.setField(source,"vehicle",vehicle)
        ReflectionHelpers.setField(source,"valueClass",Value::class.java)
        val range=Fx11DashboardSignals.bindings.single { it.signal==VehicleSignal.INDICATED_RANGE_KM }
        val raw=Fx11DashboardSignals.bindings.single { it.signal==VehicleSignal.ACC_SET_SPEED_RAW }
        try {
            vehicle.reply=Value().apply {
                prop=range.propertyId; timestamp=SystemClock.elapsedRealtimeNanos()
                value.bytes.addAll(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(326).putInt(0).putInt(0).array().toList())
            }
            read(source,range)
            assertEquals(326.0,source.snapshot()[range.signal]?.value)
            vehicle.reply.value.bytes[0]=2
            read(source,range)
            assertFalse(source.snapshot().containsKey(range.signal))
            vehicle.reply=Value().apply {
                prop=raw.propertyId; timestamp=SystemClock.elapsedRealtimeNanos(); value.floatValues.add(22.5f)
            }
            read(source,raw)
            assertEquals(22.5,source.snapshot()[raw.signal]?.value)
            assertFalse(source.snapshot().containsKey(VehicleSignal.ACC_SET_SPEED_KPH))
        } finally { source.close() }
    }
    @Test fun unchangedOnChangeFuelAndLightsStayFreshOnlyWhileAvailableReadsSucceed() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(90))
        val vehicle = Vehicle()
        val source = Fx11VehicleSource(extraBindings = Fx11DashboardSignals.bindings)
        ReflectionHelpers.setField(source, "vehicle", vehicle)
        ReflectionHelpers.setField(source, "valueClass", Value::class.java)
        val fuel = Fx11DashboardSignals.bindings.single { it.signal == VehicleSignal.FUEL_PERCENT }
        val lamp = Fx11DashboardSignals.bindings.single { it.signal == VehicleSignal.LOW_BEAM }
        try {
            for (binding in listOf(fuel, lamp)) {
                vehicle.reply = Value().apply {
                    prop = binding.propertyId
                    timestamp = 1_000_000_000L // The physical value changed long before these get() calls.
                    if (binding.paInteger) value.bytes.addAll(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(1).putInt(39).putInt(0).putInt(0).array().toList())
                    else value.floatValues.add(1f) // Bosch→RX retained float under a vendor INT32 ID.
                }
                read(source, binding)
                assertNotNull(source.snapshot()[binding.signal])
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2400))
                read(source, binding)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2400))
                assertNotNull(source.snapshot()[binding.signal])
                assertTrue(source.diagnosticReport().contains("accepted; result=0 status=0"))
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(101))
                assertFalse(source.snapshot().containsKey(binding.signal))
            }
        } finally { source.close() }
    }
    @Test fun floatLampPayloadStillRejectsFractionalEnumsAndRecordsConcreteFailureReason() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        val vehicle = Vehicle()
        val source = Fx11VehicleSource(extraBindings = Fx11DashboardSignals.bindings)
        ReflectionHelpers.setField(source, "vehicle", vehicle)
        ReflectionHelpers.setField(source, "valueClass", Value::class.java)
        val lamp = Fx11DashboardSignals.bindings.single { it.signal == VehicleSignal.LOW_BEAM }
        try {
            vehicle.reply = Value().apply {
                prop = lamp.propertyId; timestamp = SystemClock.elapsedRealtimeNanos(); value.floatValues.add(1f)
            }
            read(source, lamp)
            assertEquals(true, source.snapshot()[lamp.signal]?.value)
            vehicle.reply.value.floatValues[0] = 1.5f
            read(source, lamp)
            assertFalse(source.snapshot().containsKey(lamp.signal))
            assertTrue(source.diagnosticReport().contains("decode rejected"))
            assertTrue(source.diagnosticReport().contains("float=[1.5]"))
            vehicle.reply.status = 1
            read(source, lamp)
            assertTrue(source.diagnosticReport().contains("value status=1"))
            vehicle.reply.status = 0; vehicle.reply.timestamp = 0
            read(source, lamp)
            assertTrue(source.diagnosticReport().contains("timestamp rejected"))
        } finally { source.close() }
    }
    @Test fun paDiagnosticsDistinguishUnavailableEnvelopeFromMissingPropertyOrEmptyTank() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        val vehicle = Vehicle()
        val source = Fx11VehicleSource(extraBindings = Fx11DashboardSignals.bindings)
        ReflectionHelpers.setField(source, "vehicle", vehicle)
        ReflectionHelpers.setField(source, "valueClass", Value::class.java)
        val fuel = Fx11DashboardSignals.bindings.single { it.signal == VehicleSignal.FUEL_PERCENT }
        try {
            vehicle.reply = Value().apply {
                prop = fuel.propertyId; timestamp = SystemClock.elapsedRealtimeNanos()
                value.bytes.addAll(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(2).putInt(39).putInt(0).putInt(0).array().toList())
            }
            read(source, fuel)
            assertFalse(source.snapshot().containsKey(fuel.signal))
            assertTrue(source.diagnosticReport().contains("PA=[availability=2,data=39,format=0,status=0]"))
            assertTrue(source.diagnosticReport().contains("payload unavailable or unsupported"))
        } finally { source.close() }
    }
    @Test fun progressWatchdogAllowsLongRoundsButStillRejectsAHungReader() {
        val progress = AtomicLong()
        var calls = 0
        val progressing = object : Future<Unit> {
            override fun cancel(interrupt: Boolean) = false
            override fun isCancelled() = false
            override fun isDone() = calls > 1
            override fun get() = Unit
            override fun get(timeout: Long, unit: TimeUnit) {
                if (calls++ == 0) { progress.incrementAndGet(); throw TimeoutException() }
            }
        }
        Fx11VehicleSource.awaitProgress(progressing, progress, 1)
        assertEquals(2, calls)
        val stuck = FutureTask<Unit> { Unit } // Never started, so it cannot make progress.
        assertThrows(TimeoutException::class.java) { Fx11VehicleSource.awaitProgress(stuck, AtomicLong(), 1) }
    }

}
