package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.ultra.Fx11ExteriorLightSource
import com.shilapi.xcertplay.ultra.Fx11CoolantSource
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.hud.GeelyDimNavigation
import com.shilapi.xcertplay.ultra.Fx11VehicleSource
import com.shilapi.xcertplay.ultra.Fx11DashboardSignals
import com.shilapi.xcertplay.ultra.VehicleReading
import com.shilapi.xcertplay.ultra.VehicleSignal
import com.shilapi.xcertplay.ultra.UltraVehicleSource

internal data class DashboardSnapshot(
    val vehicle: Map<VehicleSignal, VehicleReading> = emptyMap(),
    val music: ClusterMusicState.Snapshot = ClusterMusicState.Snapshot(),
    val navigation: ClusterTurnGuidance? = null,
) {
    fun number(signal: VehicleSignal): Double? = (vehicle[signal]?.value as? Number)?.toDouble()?.takeIf { it.isFinite() }
    // The cluster's indicated speed is distinct from Android's standard physical-speed value.
    // A missing vendor reading must not fall back to the standard zero reported on this FX11.
    val speedKph: Double? get() = number(VehicleSignal.INDICATED_SPEED_KPH)?.takeIf { it in 0.0..350.0 }
    val followGap: Int? get() = number(VehicleSignal.FOLLOW_GAP)?.let { value ->
        value.toInt().takeIf { it in 1..3 && it.toDouble() == value }
    }
    // Live FX11: OEM 111 km / 23.3°C, standard 50 km / 25°C. The standard values
    // are not equivalent fallbacks on this car; missing OEM feedback must stay unknown.
    val rangeKm: Double? get() = number(VehicleSignal.INDICATED_RANGE_KM)?.takeIf { it in 0.0..3000.0 }
    val ambientC: Double? get() = number(VehicleSignal.INDICATED_AMBIENT_C)?.takeIf { it in -60.0..85.0 }
    val accSetSpeedKph: Double? get() = number(VehicleSignal.ACC_SET_SPEED_KPH)?.takeIf { it in 0.0..350.0 }
    val coolantC: Double? get() = number(VehicleSignal.ENGINE_COOLANT_C)?.takeIf { it in -40.0..215.0 }
    val frontCompartmentC: Double? get() = number(VehicleSignal.FRONT_COMPARTMENT_C)?.takeIf { it in -40.0..125.0 }
    val gear: String get() = (vehicle[VehicleSignal.GEAR]?.value as? String)?.takeIf { it in listOf("P", "R", "N", "D") } ?: "—"
    val assistance: DashboardAssistance get() = DashboardAssistance.from(this)
    // A green steering wheel claims lateral control as well as longitudinal assistance.
    // GPilot selection plus ACC_STATE alone does not establish that claim.
    val assistanceIconActive: Boolean get() = assistance.active &&
        (assistance.mode != DashboardAssistMode.GPILOT || vehicle[VehicleSignal.LANE_CENTERING_ACTIVE]?.value == true)
}

internal enum class DashboardAssistMode { OFF, CRUISE, ACC, GPILOT, UNKNOWN }
internal enum class DashboardAssistState { OFF, STANDBY, ACTIVE, OVERRIDE, STOPPED, WAITING, FAULT, UNKNOWN }

/** An active mode name alone never turns an assistance icon green. */
internal data class DashboardAssistance(val mode: DashboardAssistMode, val state: DashboardAssistState) {
    val active get() = state == DashboardAssistState.ACTIVE
    val showGap get() = mode != DashboardAssistMode.CRUISE && mode != DashboardAssistMode.OFF
    val showTarget get() = mode != DashboardAssistMode.OFF
    companion object {
        fun from(snapshot: DashboardSnapshot): DashboardAssistance {
            val mode = snapshot.vehicle[VehicleSignal.ASSIST_MODE]?.value
            val cruise = snapshot.vehicle[VehicleSignal.CRUISE_STATE]?.value
            // CrsCtrlrSts is not a substitute for the adaptive-mode selector. Require explicit
            // inactive ACC/GPilot feedback before displaying the separate ordinary-cruise state.
            if (mode == "OFF") return when (cruise) {
                "ACTIVE" -> DashboardAssistance(DashboardAssistMode.CRUISE, DashboardAssistState.ACTIVE)
                "STANDBY" -> DashboardAssistance(DashboardAssistMode.CRUISE, DashboardAssistState.STANDBY)
                "OFF" -> DashboardAssistance(DashboardAssistMode.OFF, DashboardAssistState.OFF)
                else -> DashboardAssistance(DashboardAssistMode.UNKNOWN, DashboardAssistState.UNKNOWN)
            }
            val kind = when (mode) {
                "ACC" -> DashboardAssistMode.ACC
                "GPILOT" -> DashboardAssistMode.GPILOT
                else -> DashboardAssistMode.UNKNOWN
            }
            val state = if (kind == DashboardAssistMode.UNKNOWN) DashboardAssistState.UNKNOWN else when (snapshot.number(VehicleSignal.ACC_STATE)) {
                1.0 -> DashboardAssistState.STANDBY; 2.0 -> DashboardAssistState.ACTIVE
                4.0 -> DashboardAssistState.OVERRIDE; 5.0 -> DashboardAssistState.STOPPED
                6.0 -> DashboardAssistState.WAITING; 7.0, 8.0 -> DashboardAssistState.FAULT
                else -> DashboardAssistState.UNKNOWN
            }
            return DashboardAssistance(kind, state)
        }
    }
}

/** One read-only FX11 source shared by the physical panel and on-screen preview. Main thread only. */
internal object NativeDashboardRuntime {
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<(DashboardSnapshot) -> Unit>()
    private var source: UltraVehicleSource? = null
    private var exteriorLights: UltraVehicleSource? = null
    private var coolant: UltraVehicleSource? = null
    private var autoHold: UltraVehicleSource? = null
    internal var autoHoldFactory: (Context) -> UltraVehicleSource = { Fx11AutoHoldSource(it) }
    internal var coolantFactory: (Context) -> UltraVehicleSource = { Fx11CoolantSource(it) }
    internal var exteriorLightFactory: (Context) -> UltraVehicleSource = { Fx11ExteriorLightSource(it) }
    internal var sourceFactory: () -> UltraVehicleSource = {
        Fx11VehicleSource(setOf(VehicleSignal.SPEED_KPH, VehicleSignal.ENGINE_RPM,
            VehicleSignal.GEAR, VehicleSignal.AMBIENT_C, VehicleSignal.RANGE_KM) +
            Fx11DashboardSignals.bindings.map { it.signal }, extraBindings = Fx11DashboardSignals.bindings)
    }
    private var music = ClusterMusicState.Snapshot()
    private var removeMusic: (() -> Unit)? = null
    var state = DashboardSnapshot()
        private set
    val status: String get() = ((source as? Fx11VehicleSource)?.diagnosticReport() ?: "FX11 reader idle") +
        "; " + ((exteriorLights as? Fx11ExteriorLightSource)?.diagnosticReport() ?: "OEM exterior-light reader idle") +
        "; " + ((coolant as? Fx11CoolantSource)?.diagnosticReport() ?: "OEM coolant reader idle") +
        "; " + ((autoHold as? Fx11AutoHoldSource)?.diagnosticReport() ?: "AutoHold reader idle") +
        "; " + ClusterMusicState.diagnostic() +
        "; readings=" + state.vehicle.entries.joinToString { (signal, reading) ->
            "${signal.name}=${reading.value} ${signal.unit}@${reading.propertyId}"
        } + "; followGap=OEM 1..3; laneCentering=unmapped (lateral RAW/TIMEOUT are diagnostics only)" +
        "; speed=OEM SIVehSpdIndcd integer km/h, no standard fallback; moving comparison pending" +
        "; ACC set=standard m/s converted to km/h; FX11 display RAW units unverified; range=OEM PA DTE only, unavailable stays unknown" +
        "; temperature=OEM Celsius only, unavailable stays unknown; tirePressure=FX11 PA data / 10 bar" +
        "; coolant=standard Celsius preferred, active ECARX sensor 1052416 fallback (vehicle comparison pending); parkingBrake=FX11 EPBLampReq 557850632 raw 0=on/1=off, no standard fallback; physical transition unverified; AutoHold=verified QNX Telltale lamp32 1=holding/0=not holding; missing/disconnected=unknown; HDC=unmapped; classic brake icons gray unless confirmed: AutoHold green, parking brake red; HDC hidden until active" +
        "; cruise=CrsCtrlrSts1 gated by inactive adaptive mode (vehicle validation pending); autoLamp=OEM exterior-light selector only; unavailable stays unknown" + "\n" + DashboardPerformanceLog.report()
    private var expectedTickAt = 0L
    private var maxTickDelay = 0L
    private var lastPerformanceAt = 0L
    private val tick = object : Runnable {
        override fun run() {
            val now = android.os.SystemClock.uptimeMillis()
            if (expectedTickAt > 0) maxTickDelay = maxOf(maxTickDelay, (now - expectedTickAt).coerceAtLeast(0))
            state = DashboardSnapshot(coolant?.snapshot().orEmpty() + source?.snapshot().orEmpty() + exteriorLights?.snapshot().orEmpty() + autoHold?.snapshot().orEmpty(), music, GeelyDimNavigation.snapshot())
            listeners.toList().forEach { it(state) }
            val finished = android.os.SystemClock.uptimeMillis()
            if (now - lastPerformanceAt >= 5000) {
                DashboardPerformanceLog.record("Dashboard: tick maxDelayMs=$maxTickDelay workMs=${finished-now} observers=${listeners.size}")
                lastPerformanceAt = now; maxTickDelay = 0
            }
            expectedTickAt = finished + 250
            if (listeners.isNotEmpty()) handler.postDelayed(this, 250)
        }
    }
    fun observe(context: Context? = null, listener: (DashboardSnapshot) -> Unit): () -> Unit {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (listeners.isEmpty()) {
            source = sourceFactory().also { it.start() }
            removeMusic = ClusterMusicState.observe { music = it }
        }
        if (exteriorLights == null && context != null) {
            exteriorLights = exteriorLightFactory(context.applicationContext).also { it.start() }
        }
        if (coolant == null && context != null) {
            coolant = coolantFactory(context.applicationContext).also { it.start() }
        }
        if (autoHold == null && context != null) {
            autoHold = autoHoldFactory(context.applicationContext).also { it.start() }
        }
        listeners.add(listener)
        listener(state)
        handler.removeCallbacks(tick)
        expectedTickAt = 0L
        handler.post(tick)
        return {
            listeners.remove(listener)
            if (listeners.isEmpty()) {
                handler.removeCallbacks(tick)
                removeMusic?.invoke(); removeMusic = null
                source?.close(); source = null
                exteriorLights?.close(); exteriorLights = null
                coolant?.close(); coolant = null
                autoHold?.close(); autoHold = null
                music = ClusterMusicState.Snapshot(); state = DashboardSnapshot()
            }
        }
    }
}
