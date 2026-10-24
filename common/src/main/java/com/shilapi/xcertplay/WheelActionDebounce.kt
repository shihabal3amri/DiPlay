package com.shilapi.xcertplay

/** Uses completed OEM gestures, never guesses press duration from undocumented raw event values. */
internal class WheelActionDebounce {
    private val held = mutableMapOf<Int, Boolean>()
    private val last = mutableMapOf<Pair<Int, Boolean>, Long>()
    private val longAt = mutableMapOf<Int, Long>()
    fun holding(key: Int, started: Boolean, now: Long) {
        if (started) held[key] = false
        else if (held.remove(key) == true) longAt[key] = now
    }
    fun suppressesShort(key: Int, now: Long) = held[key] == true || longAt[key]?.let { now - it in 0 until 350 } == true
    fun accept(key: Int, longPress: Boolean, now: Long): Boolean {
        if (longPress && held[key] == true) return false
        if (!longPress && suppressesShort(key, now)) return false
        val gesture = key to longPress
        if (last[gesture]?.let { now - it in 0 until 350 } == true) return false
        last[gesture] = now
        if (longPress) { longAt[key] = now; if (held.containsKey(key)) held[key] = true }
        return true
    }
    fun reset() { held.clear(); last.clear(); longAt.clear() }
}
