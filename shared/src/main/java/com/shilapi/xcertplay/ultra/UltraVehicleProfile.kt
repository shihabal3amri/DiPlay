package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.rcs.caf.*

/**
 * A real CAF configuration and explicit unit conversions, supplied with provenance.
 * No guessed characteristic type IDs: xcertplay only publishes the accessory-level catalog.
 * The empty default can exercise negotiation, but cannot make native gauges consume FX11 data.
 */
class UltraVehicleProfile private constructor(
    val pluginId: Long,
    val pluginName: String,
    val config: Map<String, Any?>,
    val bindings: Map<Long, Binding>,
    val unavailableError: Long,
    val unsupportedError: Long,
) {
    data class Binding(val signal: VehicleSignal, val format: CafCharacteristicFormat,
        val scale: Double, val offset: Double, val enumValues: Map<String, Any?>,
        val minimum: Double? = null, val maximum: Double? = null) {
        fun value(reading: VehicleReading?): Any? {
            val raw = reading?.value ?: return null
            val result = if (enumValues.isNotEmpty()) enumValues[raw.toString()] else when (raw) {
                is Number -> {
                    val n = raw.toDouble() * scale + offset
                    if (!n.isFinite()) return null
                    when (format) {
                        CafCharacteristicFormat.FLOAT -> n
                        CafCharacteristicFormat.STRING -> n.toString()
                        else -> if (n % 1.0 == 0.0 && n >= Long.MIN_VALUE.toDouble() && n < Long.MAX_VALUE.toDouble()) n.toLong() else return null
                    }
                }
                else -> raw
            }
            if (result is Number) {
                val n = result.toDouble()
                if (!n.isFinite() || minimum != null && n < minimum || maximum != null && n > maximum) return null
            }
            return result?.takeIf { runCatching { CafFormatValue.decode(it, format, "FX11 binding") }.isSuccess }
        }
    }

    companion object {
        fun negotiationOnly() = UltraVehicleProfile(1, "DiPlay.FX11", mapOf("accessories" to emptyList<Any>()), emptyMap(), -1, -1)
        fun decode(bytes: ByteArray): UltraVehicleProfile {
            require(bytes.size <= 512 * 1024) { "CAF profile exceeds 512 KiB" }
            val root = BplistCodec.decode(bytes) as? Map<*, *> ?: error("CAF profile must be a dictionary")
            require((root["evidence"] as? String)?.isNotBlank() == true) { "CAF profile needs schema provenance" }
            @Suppress("UNCHECKED_CAST")
            val config = root["config"] as? Map<String, Any?> ?: error("CAF config missing")
            fun rejectInitialValues(value: Any?) {
                when (value) {
                    is Map<*, *> -> { require(!value.containsKey("initialValue")) { "Static initial values are forbidden for live vehicle data" }; value.values.forEach(::rejectInitialValues) }
                    is List<*> -> value.forEach(::rejectInitialValues)
                }
            }
            rejectInitialValues(config)
            val tree = CafConfigTree.decode(config)
            val nodes = tree.accessories.flatMap { it.services }.flatMap { it.characteristics }
            val allIds = tree.accessories.map { it.iid.value } + tree.accessories.flatMap { it.services }.map { it.iid.value } + nodes.map { it.iid.value }
            require(allIds.distinct().size == allIds.size) { "Duplicate CAF IID" }
            require(tree.accessories.flatMap { it.services }.all { it.controls.isEmpty() }) { "Vehicle controls are not supported" }
            require(nodes.none { it.writable == true || it.mutable == true || it.oemWritable == true }) { "Only read-only CAF profiles are supported" }
            val byIid = nodes.associateBy { it.iid.value }
            val bindings = (root["bindings"] as? List<*>)?.associate { entry ->
                val e = entry as? Map<*, *> ?: error("Bad CAF binding")
                val iid = CafNumbers.toLong(e["iid"], "Binding IID")
                val node = byIid[iid] ?: error("Binding IID is not a characteristic")
                require(node.supportsInvalid == true) { "Live characteristics must support invalid values" }
                val signal = VehicleSignal.valueOf(e["signal"] as? String ?: error("Signal missing"))
                require(e["sourceUnit"] == signal.unit) { "Source unit must match ${signal.unit}" }
                require(e["targetUnit"] != null && node.units == e["targetUnit"]) { "CAF unit missing or mismatched" }
                val scale = (e["scale"] as? Number)?.toDouble() ?: 1.0
                val offset = (e["offset"] as? Number)?.toDouble() ?: 0.0
                require(scale.isFinite() && offset.isFinite())
                @Suppress("UNCHECKED_CAST")
                fun numeric(v: CafFormatValue?): Double? = when (v) {
                    is CafFormatValue.Floating -> v.value
                    is CafFormatValue.Signed -> v.value.toDouble()
                    is CafFormatValue.Unsigned -> v.value.toDouble()
                    else -> null
                }
                iid to Binding(signal, node.format, scale, offset, (e["enumValues"] as? Map<String, Any?>).orEmpty(),
                    numeric(node.minimumValue), numeric(node.maximumValue))
            } ?: error("CAF bindings missing")
            require(bindings.size == (root["bindings"] as List<*>).size && bindings.keys == byIid.keys) { "Every characteristic needs exactly one live binding" }
            val id = CafNumbers.toLong(root["pluginId"], "pluginId")
            val name = root["pluginName"] as? String ?: error("pluginName missing")
            require(id >= 0 && name.isNotBlank())
            fun errorCode(key: String) = CafNumbers.toLong(root[key], key).also { require(it != 0L) }
            return UltraVehicleProfile(id, name, config, bindings, errorCode("unavailableError"), errorCode("unsupportedError"))
        }
    }
}
