package com.shilapi.xcertplay.ultra

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Native panel only. Sources, units and unresolved signals are documented in FX11_DASHBOARD_SIGNALS.md. */
object Fx11DashboardSignals {
    private fun bounded(n: Number, scale: Double, max: Double): Double? =
        (n.toDouble() * scale).takeIf { it.isFinite() && it in 0.0..max }
    private fun flag(n: Number): Boolean? = when (n.toDouble()) { 0.0 -> false; 1.0 -> true; else -> null }
    private fun light(signal: VehicleSignal, id: Int, direct: Boolean = false) =
        Fx11Signals.Binding(signal, id, allowMissingConfig = direct, decode = ::flag)
    private fun pressure(signal: VehicleSignal, id: Int) = Fx11Signals.Binding(signal, id,
        paInteger = true, allowMissingConfig = true) {
        // 2026-10-16 FX11 live capture: all four PA data=23, OEM display=2.3 bar.
        // This feedback uses tenths of a bar, unlike the earlier generic SDK kPa assumption.
        bounded(it.toDouble() / 10.0, 1.0, 6.0)
    }
    val bindings = listOf(
        // VHAL demo property 10006: CmptmtTFrntCmptmtTFrnt. No coolant equivalence is established.
        Fx11Signals.Binding(VehicleSignal.FRONT_COMPARTMENT_C, 559970554, allowMissingConfig = true) {
            it.toDouble().takeIf { value -> value.isFinite() && value in -40.0..125.0 }
        },
        // AutoService's FX11 10001 maps SIVehSpdIndcd directly to integer km/h, no scale.
        // Live FX11 returns this INT32 even though getAllPropConfigs omits it. Use only
        // for the native gauges; do not substitute it into the standard iAP2 speed source.
        Fx11Signals.Binding(VehicleSignal.INDICATED_SPEED_KPH, 557874773, allowMissingConfig = true) {
            bounded(it, 1.0, 350.0)?.takeIf { value -> value == kotlin.math.floor(value) }
        },
        // Android VehiclePropertyIds: read-only Celsius when advertised by the firmware.
        Fx11Signals.Binding(VehicleSignal.ENGINE_COOLANT_C, 0x11600301) {
            it.toDouble().takeIf { value -> value.isFinite() && value in -40.0..215.0 }
        },
        // AutoService FX11 property 10040 uses LCM_EPBLAMPREQEPBLAMPREQ (RX feedback).
        // Its explicit mapping is inverted: raw 0 -> lamp on, raw 1 -> lamp off.
        // Advertised READ/ON_CHANGE in the saved FX11 catalog. Do not fall back to the
        // unvalidated standard PARKING_BRAKE_ON value, or infer this from gear P.
        Fx11Signals.Binding(VehicleSignal.PARKING_BRAKE_ON, 557850632) {
            when (it.toDouble()) { 0.0 -> true; 1.0 -> false; else -> null }
        },
        // AutoHold is read separately from QNX Telltale event 1/lamp 32, not a VHAL ID.
        // Hill descent remains unverified.
        // Prefer standard ODOMETER if the firmware advertises it. Both are kilometres.
        Fx11Signals.Binding(VehicleSignal.ODOMETER_KM, 0x11600204) { bounded(it, 1.0, 9_999_999.0) },
        Fx11Signals.Binding(VehicleSignal.ODOMETER_KM, 561024140, paInteger = true) { bounded(it, 1.0, 9_999_999.0) },
        Fx11Signals.Binding(VehicleSignal.FUEL_PERCENT, 561024416, paInteger = true, allowMissingConfig = true) { bounded(it, 1.0, 100.0) },
        // OEM PA_TS_DTEIndicated, integer km. Keep separate from the non-equivalent standard range.
        Fx11Signals.Binding(VehicleSignal.INDICATED_RANGE_KM, 561024488, paInteger = true, allowMissingConfig = true) { bounded(it, 1.0, 3000.0) },
        // Native panel only: live vendor 23.3°C matched OEM 23°C while standard returned 25°C.
        Fx11Signals.Binding(VehicleSignal.INDICATED_AMBIENT_C, 559970552, allowMissingConfig = true) {
            it.toDouble().takeIf { value -> value.isFinite() && value in -60.0..85.0 }
        },
        pressure(VehicleSignal.TIRE_FL_BAR, 561024399), pressure(VehicleSignal.TIRE_FR_BAR, 561024400),
        pressure(VehicleSignal.TIRE_RL_BAR, 561024401), pressure(VehicleSignal.TIRE_RR_BAR, 561024402),
        // VHAL demo RX feedback: readable on FX11 despite missing configs; live left turn toggled 0/1.
        light(VehicleSignal.LEFT_TURN, 557873489, direct = true), light(VehicleSignal.RIGHT_TURN, 557873490, direct = true),
        light(VehicleSignal.LOW_BEAM, 557849510), light(VehicleSignal.HIGH_BEAM, 557849181),
        light(VehicleSignal.POSITION_LIGHT, 557849179), light(VehicleSignal.REAR_FOG, 557855125),
        // AUTO comes from the OEM exterior-light selector, not HEADLIGHTS_SWITCH.
        // The standard property stayed 256 when AUTO was off during live validation.
        // CLUSTER_ADAS_CRSCTRLRSTS / OEM CrsCtrlrSts1. Kept separate from AsyALgtSts.
        Fx11Signals.Binding(VehicleSignal.CRUISE_STATE, 557849289) {
            when (it.toDouble()) { 1.0 -> "OFF"; 2.0 -> "STANDBY"; 3.0 -> "ACTIVE"; else -> null }
        },
        light(VehicleSignal.DRIVER_BELT_FASTENED, 557855239),
        Fx11Signals.Binding(VehicleSignal.ASSIST_MODE, 557849285) {
            when (it.toDouble()) { 0.0 -> "OFF"; 1.0 -> "ACC"; 2.0 -> "GPILOT"; else -> null }
        },
        Fx11Signals.Binding(VehicleSignal.ACC_STATE, 557871508) {
            it.toInt().takeIf { value -> value.toDouble() == it.toDouble() && value in setOf(1, 2, 4, 5, 6, 7, 8) }
        },
        // Standard CRUISE_CONTROL_TARGET_SPEED is m/s. Only polled when advertised readable.
        Fx11Signals.Binding(VehicleSignal.ACC_SET_SPEED_KPH, 0x11601013) { bounded(it, 3.6, 350.0) },
        // FX11 DispSpdSetForLgtCtrl feedback. Units are not established by the captured zero values.
        // Never confuse it with the write-only SETSPDFORCRSCTRLFCT (557849448).
        Fx11Signals.Binding(VehicleSignal.ACC_SET_SPEED_RAW, 557849502, allowFloatPayload = true) {
            // The native Bosch→RX bridge can retain float payloads under INT32 property IDs.
            it.toDouble().takeIf { value -> value.isFinite() && value >= 0 }
        },
        // OEM TiGapSetForLgtCtrl: 0=None, 1/2/3=TimeGap_1/2/3. Never count roller presses.
        Fx11Signals.Binding(VehicleSignal.FOLLOW_GAP, 557849293) {
            it.toInt().takeIf { value -> value in 1..3 && value.toDouble() == it.toDouble() }
        },
        // Diagnostics only: INDCR has no verified link to the similarly named AsyALatSts enum.
        Fx11Signals.Binding(VehicleSignal.LATERAL_INDICATOR_RAW, 557871388) {
            it.toInt().takeIf { value -> value >= 0 && value.toDouble() == it.toDouble() }
        },
        light(VehicleSignal.LATERAL_INDICATOR_TIMEOUT, 557874031),
        Fx11Signals.Binding(VehicleSignal.LANE_LEFT_COLOR, 557871401) { laneColor(it) },
        Fx11Signals.Binding(VehicleSignal.LANE_RIGHT_COLOR, 557871403) { laneColor(it) },
    ).map { binding ->
        // FX11 Bosch→RX keeps the numeric payload slot while renaming the property ID.
        // Native panel INT32 signals can therefore arrive as a single float. Their own strict
        // enum/range decoders still validate the value; PA envelopes and standard IDs are unchanged.
        if (binding.propertyId and 0xffff0000.toInt() == 0x21400000)
            binding.copy(allowFloatPayload = true) else binding
    }
    private fun laneColor(n: Number) = n.toInt().takeIf { it in 0..4 && it.toDouble() == n.toDouble() }

    /** OEM PATypes.PA_IntBase: 16 LE bytes: availability, data, format, status.
     * Decode only explicitly registered IntBase properties; never infer a layout for other BYTES.
     * Metadata is not an extra availability flag: OEM consumers gate on availability == Active.
     */
    fun paInteger(bytes: ByteArray): Int? {
        if (bytes.size != 16) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.int != 1) return null
        return buffer.int
    }
}
