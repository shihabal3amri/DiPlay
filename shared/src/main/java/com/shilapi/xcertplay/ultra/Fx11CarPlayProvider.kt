package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.transport.*

/**
 * Read-only bridge from the FX11 source to ordinary CarPlay. Speed and status have independent
 * subscriptions. The bounded preflight runs on the control worker, never on the Android UI thread.
 * No fuel range is relabelled as electric range, and undocumented vendor BYTES are never sent.
 */
class Fx11CarPlayProvider(
    private val sourceFactory: () -> UltraVehicleSource,
    private val clock: () -> Long,
) : VehicleSpeedSource, VehicleStatusProvider {
    private var source: UltraVehicleSource? = null
    private var speedActive = false
    private var statusActive = false
    private var preflights = 0
    private var closed = false
    private var lastSpeedTimestamp: Long? = null
    private var speedAvailable = false
    @Volatile var diagnostic: String = "FX11 waiting for connection preflight"
        private set

    @Synchronized override fun available(): Boolean = !closed && speedAvailable
    @Synchronized override fun start() {
        if (closed) return
        speedActive = true
        ensureSource()
    }
    @Synchronized override fun stop() {
        speedActive = false
        lastSpeedTimestamp = null
        releaseIfIdle()
    }
    @Synchronized override fun onSubscriptionChanged(active: Boolean) {
        if (closed) return
        statusActive = active
        if (active) ensureSource() else releaseIfIdle()
    }
    private fun ensureSource() {
        if (source == null) source = sourceFactory().also { it.start() }
    }
    private fun releaseIfIdle() {
        if (!speedActive && !statusActive && preflights == 0) {
            source?.close()
            source = null
        }
    }
    @Synchronized override fun snapshot(): VehicleStatusSnapshot? {
        if (closed) return null
        return status(source?.snapshot().orEmpty())
    }
    private fun status(values: Map<VehicleSignal, VehicleReading>): VehicleStatusSnapshot? {
        val reading = values[VehicleSignal.AMBIENT_C] ?: return null
        val event = reading.sourceElapsedMillis ?: return null
        if (clock() - reading.receivedMillis !in 0..2500 || clock() - event !in 0..30_000) return null
        val temperature = (reading.value as? Number)?.toDouble()
            ?.takeIf { it.isFinite() && it in -60.0..85.0 } ?: return null
        return VehicleStatusSnapshot(outsideTemperatureCelsius = temperature)
    }
    private fun speed(values: Map<VehicleSignal, VehicleReading>): VehicleSpeedReading? {
        val reading = values[VehicleSignal.SPEED_KPH] ?: return null
        val event = reading.sourceElapsedMillis ?: return null
        val now = clock()
        if (now - event !in 0..2000 || now - reading.receivedMillis !in 0..2000) return null
        val gearReading = values[VehicleSignal.GEAR] ?: return null
        if (now - gearReading.receivedMillis !in 0..2500) return null
        val gear = VehicleGear.entries.firstOrNull { it.letter.toString() == gearReading.value } ?: return null
        val metersPerSecond = (reading.value as? Number)?.toDouble()?.div(3.6)
            ?.takeIf { it.isFinite() && it in 0.0..(350.0 / 3.6) } ?: return null
        return VehicleSpeedReading(gear, listOf(VehicleSpeedSample(event, metersPerSecond)))
    }
    @Synchronized override fun drain(): VehicleSpeedReading? {
        if (!speedActive || closed) return null
        val reading = speed(source?.snapshot().orEmpty()) ?: return null
        val timestamp = reading.samples.single().elapsedMillis
        if (lastSpeedTimestamp?.let { timestamp <= it } == true) return null
        lastSpeedTimestamp = timestamp
        return reading
    }
    override fun awaitSnapshot(timeoutMillis: Long): VehicleStatusSnapshot? {
        synchronized(this) {
            if (closed) return null
            preflights++
            try { ensureSource() } catch (error: Exception) { preflights--; throw error }
        }
        val deadline = System.nanoTime() + timeoutMillis.coerceIn(0, 2000) * 1_000_000
        try {
            while (true) {
                val result = synchronized(this) {
                    if (closed) return null
                    val values = source?.snapshot().orEmpty()
                    speedAvailable = speed(values) != null
                    status(values).also {
                        diagnostic = "FX11 speed/gear=$speedAvailable outsideTemperature=${it != null}; " +
                            "SOC/electric range unavailable; speed subscription=$speedActive status subscription=$statusActive"
                    }
                }
                if ((result != null && available()) || System.nanoTime() >= deadline) return result
                Thread.sleep(25)
            }
        } finally {
            synchronized(this) { preflights--; releaseIfIdle() }
        }
    }
    @Synchronized fun diagnosticReport(): String {
        val values = source?.snapshot().orEmpty()
        return "$diagnostic\nLive FX11 speed/gear=${speed(values) != null} temperature=${status(values) != null}; " +
            "sampling=${source != null} speedSubscription=$speedActive statusSubscription=$statusActive"
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        speedActive = false
        statusActive = false
        source?.close()
        source = null
        speedAvailable = false
        diagnostic = "FX11 CarPlay data stopped"
    }
}
