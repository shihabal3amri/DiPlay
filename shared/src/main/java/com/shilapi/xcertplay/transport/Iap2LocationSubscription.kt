package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** Selector and per-sentence minimum intervals belong to the receiving control link. */
class Iap2LocationSubscription(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    var components: Set<Int> = emptySet()
        private set
    private var intervals = emptyMap<Int, Long>()
    private val lastSent = mutableMapOf<Int, Long>()
    fun request(frame: Iap2Frame, supported: Set<Int> = setOf(1, 2, 4)): Boolean {
        stop()
        return try {
            val fields = Iap2BodyReader.of(frame).list()
            require(fields.none { it.id in 1..7 && it.payload.isNotEmpty() })
            require(fields.none { it.id in 0x8000..0x8007 && it.payload.size != 4 })
            components = fields.filter { it.id in 1..7 }.map { it.id }.toSet().intersect(supported)
            intervals = fields.filter { it.id in 0x8001..0x8007 }.groupBy { it.id - 0x8000 }
                .mapValues { (_, fields) -> fields.maxOf { field ->
                    field.payload.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xff) }
                } }
            components.isNotEmpty()
        } catch (_: Exception) { stop(); false }
    }
    fun filter(nmea: String): String? {
        val timestamp = now()
        val selected = nmea.split("\r\n").filter { sentence ->
            val id = when {
                sentence.startsWith("\$GPGGA,") -> 1
                sentence.startsWith("\$GPRMC,") -> 2
                sentence.startsWith("\$PASCD,") -> 4
                else -> return@filter false
            }
            if (id !in components) return@filter false
            val last = lastSent[id]
            if (last != null && timestamp - last < maxOf(1_000, intervals[id] ?: 0)) return@filter false
            lastSent[id] = timestamp
            true
        }
        return selected.takeIf { it.isNotEmpty() }?.joinToString("\r\n", postfix = "\r\n")
    }
    fun stop() { components = emptySet(); intervals = emptyMap(); lastSent.clear() }
}
