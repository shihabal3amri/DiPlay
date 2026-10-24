package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import kotlin.math.roundToInt
import kotlin.math.min
import kotlin.math.roundToLong

/** One reading of an electric vehicle's range and charge, as CarPlay's VehicleStatusUpdate carries it. */
data class VehicleStatusSnapshot(
    val rangeKm: Int? = null,
    val rangeWarning: Boolean? = null,
    val batteryPercent: Double? = null,
    val currentChargeWh: Long? = null,
    val maxChargeWh: Long? = null,
    val maxRangeKm: Int? = null,
    val charging: Boolean? = null,
    val outsideTemperatureCelsius: Double? = null,
)

/** Supplies the vehicle's status for CarPlay; null while no reading is available. */
fun interface VehicleStatusProvider : AutoCloseable {
    fun snapshot(): VehicleStatusSnapshot?
    fun awaitSnapshot(timeoutMillis: Long): VehicleStatusSnapshot? = snapshot()
    fun onSubscriptionChanged(active: Boolean) = Unit
    override fun close() = Unit
}

/** The charging inlets the car declares, as iAP2 SupportedChargingConnectors values. */
enum class EvChargingConnectors(internal vararg val wireValues: Int) {
    CCS2_TYPE2(1, 4), // CCS2 (DC) and Mennekes / Type 2 (AC), as on European BYD models
    GB_T(5, 6), // GB/T DC and AC, as on Chinese-market models
    CCS1_J1772(0, 2), // CCS1 (DC) and J1772 (AC)
}

/**
 * The identification to send: an electric vehicle is declared only while [provider] has a battery
 * reading, so the iPhone is never told about an EV that DiPlay cannot report (adb off or not
 * approved, or a car without these properties). Reads the provider's cached value; never blocks.
 */
fun Iap2IdentificationConfig.withVehicleStatusFrom(provider: VehicleStatusProvider?): Iap2IdentificationConfig {
    if (!vehicleStatusEnabled && !vehicleDataEnabled) return this
    val sample = provider?.awaitSnapshot(2_000)
    val fields = Iap2VehicleStatus.capabilities(sample)
    return copy(vehicleStatusEnabled = vehicleStatusEnabled && 21 in fields,
        vehicleStatusCapabilities = fields)
}

/**
 * iAP2 vehicle plane for an electric car: identification declares the vehicle (engine type,
 * charging connectors) and which status fields it reports; the phone then subscribes with
 * StartVehicleStatusUpdates (0xA100) and the accessory answers with VehicleStatusUpdate (0xA101).
 * Parameter ids follow Apple's iAP2 message catalog.
 */
object Iap2VehicleStatus {
    const val START_VEHICLE_STATUS_UPDATES = 0xA100
    const val VEHICLE_STATUS_UPDATE = 0xA101
    const val STOP_VEHICLE_STATUS_UPDATES = 0xA102
    const val UPDATE_INTERVAL_MILLIS = 30_000L

    private const val ENGINE_TYPE_ELECTRIC = 2

    /** IdentificationInformation params 20 (VehicleInformation) and 21 (VehicleStatus). */
    fun Iap2BodyBuilder.electricVehicleComponents(name: String, connectors: EvChargingConnectors) {
        group(20) {
            u16(0, 3) // Identifier
            string(1, name) // Name
            u8(2, ENGINE_TYPE_ELECTRIC) // EngineType
            string(6, name) // DisplayName
            string(8, name) // MapsDisplayName
            for (connector in connectors.wireValues) u8(11, connector) // SupportedChargingConnectors
        }
        group(21) {
            u16(0, 4) // Identifier
            string(1, name) // Name
            void(3) // Range
            void(6) // RangeWarning
            void(11) // RangeElectric
            void(15) // RangeWarningElectric
            void(21) // ElectricChargeInfo
            void(30) // MaxRangeInfo
        }
    }

    /** Only capabilities backed by valid readings are advertised. */
    fun capabilities(status: VehicleStatusSnapshot?): Set<Int> = buildSet {
        if (status == null) return@buildSet
        if (status.rangeKm?.let { it in 0..65535 } == true) { add(3); add(11) }
        if (status.rangeWarning != null) { add(6); add(15) }
        if (status.batteryPercent?.let { it.isFinite() && it in 0.0..100.0 } == true) add(21)
        if (status.maxRangeKm?.let { it in 0..65535 } == true) add(30)
        if (status.outsideTemperatureCelsius?.let { it.isFinite() && it in -100.0..100.0 } == true) add(4)
    }

    fun Iap2BodyBuilder.partialVehicleComponents(name: String, capabilities: Set<Int>) {
        group(20) { u16(0, 3); string(1, name); string(6, name) }
        group(21) { u16(0, 4); string(1, name); capabilities.sorted().forEach { void(it) } }
    }

    /** Convenience encoder for existing EV callers. The reporter always passes negotiated fields. */
    fun update(status: VehicleStatusSnapshot): Iap2Frame =
        requireNotNull(update(status, capabilities(status), capabilities(status)))

    fun update(status: VehicleStatusSnapshot, advertised: Set<Int>, requested: Set<Int>): Iap2Frame? {
        val valid = capabilities(status).intersect(advertised)
        val selected = valid.intersect(requested) + valid.intersect(setOf(21, 30))
        // A100 has no charge/max-range selectors; these belong to the subscribed status plane.
        if (selected.isEmpty()) return null
        return Iap2Messages.buildRaw(VEHICLE_STATUS_UPDATE) {
            if (3 in selected) u16(3, requireNotNull(status.rangeKm))
            if (4 in selected) i16(4, requireNotNull(status.outsideTemperatureCelsius).roundToInt())
            if (6 in selected) bool(6, requireNotNull(status.rangeWarning))
            if (11 in selected) u16(11, requireNotNull(status.rangeKm))
            if (15 in selected) bool(15, requireNotNull(status.rangeWarning))
            if (21 in selected) {
                val current = status.currentChargeWh?.takeIf { it in 0..0xffff_ffffL }
                val maximum = status.maxChargeWh?.takeIf { it in 0..0xffff_ffffL }
                if (current != null || maximum != null) u32(21, 0L)
                current?.let { u32(22, it) }
                maximum?.let { u32(23, it) }
                u32(24, (requireNotNull(status.batteryPercent) * 1000).roundToLong())
                status.charging?.let { bool(25, it) }
            }
            if (30 in selected) u16(30, requireNotNull(status.maxRangeKm))
        }
    }
}

/** Each wired or tunneled link owns its own negotiated fields and subscription. */
internal class Iap2VehicleStatusReporter(
    private val provider: VehicleStatusProvider?,
    private val onProgress: (String) -> Unit,
    private val advertised: Set<Int> = emptySet(),
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var active = false
    private var requested = emptySet<Int>()
    private var nextSendNanos = 0L

    fun handle(frame: Iap2Frame, send: (Iap2Frame) -> Unit): Boolean {
        when (frame.messageId) {
            Iap2VehicleStatus.START_VEHICLE_STATUS_UPDATES -> {
                val fields = runCatching {
                    Iap2BodyReader.of(frame).list().also { fields ->
                        require(fields.none { it.id in setOf(3,4,5,6,9,10,11,12,13,14,15,16,17,18,19,20) && it.payload.isNotEmpty() })
                    }.filter { it.payload.isEmpty() }.map { it.id }.toSet()
                }.getOrElse { stop(); return true }
                requested = fields
                val enabled = provider != null && advertised.isNotEmpty()
                if (enabled && !active) provider?.onSubscriptionChanged(true)
                active = enabled
                nextSendNanos = nanoTime()
                onProgress("iap2 rx=0xa100 vehicle-status requested=$requested advertised=$advertised")
                tick(send)
            }
            Iap2VehicleStatus.STOP_VEHICLE_STATUS_UPDATES -> stop()
            else -> return false
        }
        return true
    }

    fun tick(send: (Iap2Frame) -> Unit) {
        if (!active || nanoTime() < nextSendNanos) return
        // Back off unavailable samples too; never spin on a disconnected vehicle.
        nextSendNanos = nanoTime() + Iap2VehicleStatus.UPDATE_INTERVAL_MILLIS * 1_000_000
        val sample = provider?.snapshot() ?: return
        Iap2VehicleStatus.update(sample, advertised, requested)?.let(send)
    }

    fun pollTimeout(remainingMillis: Long): Long = if (active)
        min(remainingMillis, ((nextSendNanos - nanoTime()) / 1_000_000).coerceAtLeast(1)) else remainingMillis

    fun stop() {
        if (active) provider?.onSubscriptionChanged(false)
        active = false
        requested = emptySet()
        nextSendNanos = 0
    }
}
