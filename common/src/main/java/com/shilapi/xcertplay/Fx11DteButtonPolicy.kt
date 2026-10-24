package com.shilapi.xcertplay

import com.shilapi.xcertplay.ultra.Fx11DashboardSignals
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 10059 is a demo profile alias, NOT a VHAL property or a OneOS key code. */
internal data class Fx11DteButtonSample(
    val property: Int, val area: Int, val status: Int, val timestampNs: Long,
    val bytes: ByteArray, val initial: Boolean = false,
)

/**
 * Experimental candidate gesture, not a claim that padtetypebutton is RESET.
 * Until a live capture establishes the enum, accept only a complete fresh 0 -> 1 -> 0
 * short pulse. Latched DTE modes, initial snapshots, held values and other enums only log.
 */
internal class Fx11DteButtonPolicy(private val startedNs: Long) {
    data class Decision(val page: Boolean = false, val reason: String, val raw: Int? = null)
    private var lastValue: Int? = null
    private var lastTimestamp = 0L
    private var pressedAt: Long? = null
    private var lastClick: Long? = null

    fun reset() { lastValue = null; lastTimestamp = 0; pressedAt = null; lastClick = null }

    fun accept(sample: Fx11DteButtonSample, nowNs: Long): Decision {
        if (sample.property != PROPERTY || sample.area != 0) return Decision(reason = "wrong property/area")
        if (sample.status != 0) { reset(); return Decision(reason = "unavailable status=${sample.status}") }
        val raw = Fx11DashboardSignals.paInteger(sample.bytes)
        if (raw == null) { reset(); return Decision(reason = "invalid/inactive PA_IntBase") }
        if (raw !in 0..1) { reset(); return Decision(reason = "unmapped enum; observe only", raw = raw) }
        if (sample.timestampNs <= 0 || sample.timestampNs > nowNs) {
            reset(); return Decision(reason = "invalid/future timestamp", raw = raw)
        }
        if (sample.initial) {
            lastValue = raw; lastTimestamp = sample.timestampNs; pressedAt = null
            return Decision(reason = "getter baseline; never a gesture", raw = raw)
        }
        if (sample.timestampNs <= lastTimestamp) return Decision(reason = "duplicate/out-of-order timestamp", raw = raw)
        if (sample.timestampNs < startedNs || nowNs - sample.timestampNs > MAX_EVENT_AGE_NS) {
            lastValue = raw; lastTimestamp = sample.timestampNs; pressedAt = null
            return Decision(reason = "cached/stale event; baseline only", raw = raw)
        }
        val previous = lastValue
        lastValue = raw; lastTimestamp = sample.timestampNs
        if (previous == null) { pressedAt = null; return Decision(reason = "first event baseline", raw = raw) }
        if (previous == raw) return Decision(reason = "unchanged value", raw = raw)
        if (raw == 1) {
            pressedAt = sample.timestampNs
            return Decision(reason = "candidate down; awaiting short release", raw = raw)
        }
        val down = pressedAt
        pressedAt = null
        if (down == null) return Decision(reason = "release without down", raw = raw)
        val duration = sample.timestampNs - down
        if (duration !in MIN_PRESS_NS..MAX_PRESS_NS) return Decision(reason = "bounce/held/latched durationMs=${duration / 1_000_000}", raw = raw)
        if (lastClick?.let { sample.timestampNs - it < DEBOUNCE_NS } == true)
            return Decision(reason = "debounced pulse", raw = raw)
        lastClick = sample.timestampNs
        return Decision(page = true, reason = "candidate short pulse durationMs=${duration / 1_000_000}; physical RESET unconfirmed", raw = raw)
    }

    companion object {
        const val PROPERTY = 561024641 // FX11 demo readSignalName=padtetypebutton
        const val MIN_PRESS_NS = 40_000_000L
        const val MAX_PRESS_NS = 1_200_000_000L
        const val MAX_EVENT_AGE_NS = 2_000_000_000L
        const val DEBOUNCE_NS = 350_000_000L
        fun rawDescription(sample: Fx11DteButtonSample): String {
            if (sample.bytes.size != 16) return "byteCount=${sample.bytes.size}"
            val b = ByteBuffer.wrap(sample.bytes).order(ByteOrder.LITTLE_ENDIAN)
            return "PA=[availability=${b.int},data=${b.int},format=${b.int},status=${b.int}]"
        }
    }
}
