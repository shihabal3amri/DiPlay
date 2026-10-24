package com.shilapi.xcertplay.ultra

import java.io.Closeable

/** Canonical units are explicit. Raw vendor speed is intentionally not called km/h. */
enum class VehicleSignal(val unit: String) {
    SPEED_KPH("km/h"), SPEED_INDICATED_RAW("raw"), ENGINE_RPM("rpm"), GEAR("P/R/N/D"),
    AMBIENT_C("degC"), FUEL_PERCENT("percent"), BATTERY_PERCENT("percent"), RANGE_KM("km"),
    ODOMETER_KM("km"), TIRE_FL_BAR("bar"), TIRE_FR_BAR("bar"), TIRE_RL_BAR("bar"), TIRE_RR_BAR("bar"),
    LEFT_TURN("boolean"), RIGHT_TURN("boolean"), LOW_BEAM("boolean"), HIGH_BEAM("boolean"),
    POSITION_LIGHT("boolean"), FRONT_FOG("boolean"), REAR_FOG("boolean"), DRIVER_BELT_FASTENED("boolean"),
    ASSIST_MODE("OFF/ACC/GPILOT"), ACC_STATE("enum"), LANE_LEFT_COLOR("enum"), LANE_RIGHT_COLOR("enum"),
    FOLLOW_GAP("1..3"), LANE_CENTERING_ACTIVE("boolean"),
    LATERAL_INDICATOR_RAW("raw"), LATERAL_INDICATOR_TIMEOUT("boolean"),
    ACC_SET_SPEED_KPH("km/h"), ACC_SET_SPEED_RAW("raw"), INDICATED_RANGE_KM("km"),
    AUTO_HEADLIGHTS("boolean"), CRUISE_STATE("OFF/STANDBY/ACTIVE"),
    INDICATED_AMBIENT_C("degC"),
    ENGINE_COOLANT_C("degC"), PARKING_BRAKE_ON("boolean"),
    AUTO_HOLD_ACTIVE("boolean"), HILL_DESCENT_ACTIVE("boolean"),
    INDICATED_SPEED_KPH("km/h"),
    FRONT_COMPARTMENT_C("degC"),
}
data class VehicleReading(val value: Any, val receivedMillis: Long, val propertyId: Int, val sourceElapsedMillis: Long? = null)
interface UltraVehicleSource : Closeable {
    fun start()
    fun snapshot(): Map<VehicleSignal, VehicleReading>
}

/** Null/failed/expired readings are removed, never represented as a stopped car or empty tank. */
class VehicleTelemetry(private val clock: () -> Long, private val lifetimeMillis: Long = 2500) {
    private val values = linkedMapOf<VehicleSignal, VehicleReading>()
    @Synchronized fun accept(signal: VehicleSignal, value: Any?, propertyId: Int, sourceElapsedMillis: Long? = null) {
        if (value == null || value is Number && !value.toDouble().isFinite()) values.remove(signal)
        else values[signal] = VehicleReading(value, clock(), propertyId, sourceElapsedMillis)
    }
    @Synchronized fun snapshot(): Map<VehicleSignal, VehicleReading> {
        val now = clock()
        values.entries.removeAll {
            now - it.value.receivedMillis !in 0..lifetimeMillis ||
                ((it.key == VehicleSignal.SPEED_KPH || it.key == VehicleSignal.INDICATED_SPEED_KPH) &&
                    it.value.sourceElapsedMillis?.let { event -> now - event !in 0..2000 } == true)
        }
        return LinkedHashMap(values)
    }
    @Synchronized fun clear() { values.clear() }
}

object Fx11Signals {
    data class Binding(val signal: VehicleSignal, val propertyId: Int,
        val paInteger: Boolean = false, val allowFloatPayload: Boolean = false,
        // Explicitly verified FX11 feedback only; never overrides a declared access/area restriction.
        val allowMissingConfig: Boolean = false, val decode: (Number) -> Any?)
    private fun bounded(value: Double, min: Double, max: Double): Double? = value.takeIf { it.isFinite() && it in min..max }
    // Standard Android VHAL speed is m/s, RPM is rev/min, temperature is Celsius.
    // Vendor IDs and /2 RPM mapping are from FX11 vhal_fx11_signal_mapping.json.
    val bindings = listOf(
        Binding(VehicleSignal.SPEED_KPH, 0x11600207) { bounded(kotlin.math.abs(it.toDouble()) * 3.6, 0.0, 350.0) },
        Binding(VehicleSignal.SPEED_INDICATED_RAW, 557874773) { bounded(it.toDouble(), 0.0, 65534.0) },
        Binding(VehicleSignal.ENGINE_RPM, 557850019) { bounded(it.toDouble() / 2.0, 0.0, 12000.0) },
        // FX11 live 2026-10-18: directory omits GearLvrIndcn; direct INT32 0 agrees with
        // standard GEAR_SELECTION=4 (P). Keep the vendor enum separate from Android's bits.
        Binding(VehicleSignal.GEAR, 557874334, allowMissingConfig = true) {
            when (it.toDouble()) { 0.0 -> "P"; 1.0 -> "R"; 2.0 -> "N"; 3.0 -> "D"; else -> null }
        },
        Binding(VehicleSignal.AMBIENT_C, 0x11600703) { bounded(it.toDouble(), -60.0, 85.0) },
        // RANGE_REMAINING, in metres. Vendor patsdteindicated/fuel/SOC are BYTES and NOT decoded here.
        Binding(VehicleSignal.RANGE_KM, 0x11600308) { bounded(it.toDouble() / 1000.0, 0.0, 3000.0) },
    )
    val undecodedVendorProperties = listOf(561024509, 561024416, 561024488, 559970552)
}
