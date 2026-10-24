package com.shilapi.xcertplay

import android.content.Context
import android.hardware.automotive.vehicle.V2_0.IVehicle
import android.hardware.automotive.vehicle.V2_0.VehiclePropValue
import com.shilapi.xcertplay.ultra.Fx11DashboardSignals

/** Only the FX11 DIM show/hide channel. Never sends mode 3 to the ECARX animation switch. */
internal class Fx11OemOverlayBridge(
    private val provider: () -> IVehicle = { IVehicle.getService("default", false) },
) : Fx11ThreeScreenBridge {
    private var vehicle: IVehicle? = null
    private var verified = false

    override fun connect(context: Context) { vehicle = provider() }

    override fun validate(mode: Fx11ProjectionMode) {
        verified = false
        check(mode == Fx11ProjectionMode.OEM_OVERLAY) { "Wrong projection bridge" }
        val configs = checkNotNull(vehicle).allPropConfigs
        var omitted = false
        for ((id, access) in listOf(READ_PROPERTY to 1, WRITE_PROPERTY to 2)) {
            val matching = configs.filter { it.prop == id }
            check(matching.size <= 1) { "Ambiguous DIM property $id" }
            val config = matching.singleOrNull()
            if (config == null) {
                omitted = true
                continue
            }
            check(config.access and access != 0) { "DIM property $id access=${config.access}" }
            check(config.areaConfigs.isEmpty() || config.areaConfigs.any { it.areaId == 0 }) {
                "DIM property $id has no global area"
            }
        }
        // configArray is vendor metadata, not proof of an enum. Mode 3 remains experimental
        // until a single explicit request is accepted and the separate feedback reaches 3.
        // Live FX11 omits this exact pair from its directory, yet returns valid PA feedback.
        // Only this fixed pair can use direct access; declared restrictions still win above.
        if (omitted) {
            val feedback = readFeedback()
            check(feedback == 0 || feedback == 3) { "Unadvertised DIM channel returned unknown mode $feedback; no write" }
        }
        verified = true
    }

    override fun read(): Int {
        check(verified)
        return readFeedback()
    }

    private fun readFeedback(): Int {
        var result: Int? = null
        var failure = "DIM getter did not return a value"
        checkNotNull(vehicle).get(VehiclePropValue().apply { prop = READ_PROPERTY; areaId = 0 }) { status, value ->
            failure = "DIM read result=$status prop=${value?.prop} area=${value?.areaId} status=${value?.status}"
            if (status == 0 && value?.prop == READ_PROPERTY && value.areaId == 0 && value.status == 0) {
                val raw = value.value
                if (raw != null && raw.floatValues.isEmpty() && raw.int64Values.isEmpty() && raw.stringValue.isNullOrEmpty()) {
                    result = decode(raw.int32Values, raw.bytes.toByteArray())
                }
            }
        }
        return result ?: error("$failure; missing/invalid DIM mode; no assumed zero")
    }

    override fun write(value: Int): Boolean {
        check(verified)
        require(value == 0 || value == 3) { "Only OEM restore and overlay are writable" }
        val request = VehiclePropValue().apply {
            prop = WRITE_PROPERTY; areaId = 0
            this.value.int32Values.add(value)
        }
        return checkNotNull(vehicle).set(request) == 0
    }

    override fun close() { verified = false; vehicle = null }

    companion object {
        const val READ_PROPERTY = 561023759 // panotifdimshowhide, PA_IntBase
        const val WRITE_PROPERTY = 557876790 // cbnotifdimshowhide, scalar INT32

        /** Preserve unknown OEM states for controller rejection; an empty payload is never 0. */
        internal fun decode(ints: List<Int>, bytes: ByteArray): Int? = when {
            ints.size == 1 && bytes.isEmpty() -> ints.single()
            ints.isEmpty() && bytes.size == 16 -> Fx11DashboardSignals.paInteger(bytes)
            else -> null
        }
    }
}
