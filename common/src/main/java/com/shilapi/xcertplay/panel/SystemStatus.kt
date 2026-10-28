package com.shilapi.xcertplay.panel

import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** A snapshot of the head unit's own load, read from local files the app can always access. */
data class SystemReading(
    val cpuPercent: Double,
    val coreFreqMhz: List<Int>,
    val memUsedGb: Double,
    val memTotalGb: Double,
    val tempC: Double?,
)

/** Whole-CPU jiffies (total, idle+wait) at one instant; the delta between two gives the busy share. */
data class CpuTimes(val total: Long, val idle: Long)

/**
 * The reading part of a head unit's own load: it is the car's Android system, not a BYD signal, so these
 * come from local files a normal app can always read (no adb, no permission), not from the autoservice
 * binder. Parsing lives here as pure functions so samples of the real /proc and sysfs formats are
 * unit-testable. GPU utilisation is deliberately absent: no public API exposes it on Android 10.
 */
object SystemProbe {
    val CPU_STAT = File("/proc/stat")
    val MEM_INFO = File("/proc/meminfo")
    val CPU_ROOT = File("/sys/devices/system/cpu")
    val THERMAL_ROOT = File("/sys/class/thermal")

    /** First line "cpu user nice system idle iowait irq softirq steal …"; idle is idle + iowait. */
    fun parseCpuTimes(statFirstLine: String): CpuTimes? {
        val parts = statFirstLine.trim().removePrefix("cpu").trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
        if (parts.size < 5) return null
        return CpuTimes(parts.sum(), parts[3] + parts[4])
    }

    /** Busy share between two samples, in percent; null on the first sample or when the counters went backwards. */
    fun cpuPercent(previous: CpuTimes?, now: CpuTimes): Double? {
        if (previous == null) return null
        val totalDelta = now.total - previous.total
        if (totalDelta <= 0) return null
        val busy = totalDelta - (now.idle - previous.idle)
        if (busy < 0) return null
        return (busy.toDouble() / totalDelta * 100.0).coerceIn(0.0, 100.0)
    }

    fun parseCpuTimes(file: File): CpuTimes? =
        runCatching { file.readLines().firstOrNull { it.startsWith("cpu ") }?.let(::parseCpuTimes) }.getOrNull()

    /** kB pair (total, available) from the two meminfo lines the tile needs; null when either is absent. */
    fun parseMemoryKb(meminfo: String): Pair<Long, Long>? {
        var total: Long? = null
        var available: Long? = null
        for (line in meminfo.lineSequence()) {
            val value = line.substringAfter(':').trim().removeSuffix(" kB").toLongOrNull() ?: continue
            when {
                line.startsWith("MemTotal:") -> total = value
                line.startsWith("MemAvailable:") -> available = value
            }
        }
        return if (total != null && available != null) total to available else null
    }

    /** Per-core current frequency in MHz; a core that reports nothing (offline, blocked) is skipped. */
    fun readCoreFreqMhz(cpuRoot: File): List<Int> {
        val cores = runCatching {
            cpuRoot.listFiles { file -> file.name.matches(Regex("cpu\\d+")) }?.toList()
        }.getOrNull() ?: return emptyList()
        return cores.mapNotNull { core ->
            val khz = runCatching {
                File(core, "cpufreq/scaling_cur_freq").readText().trim().toLongOrNull()
            }.getOrNull()
            khz?.takeIf { it > 0 }?.let { (it / 1000).toInt() }
        }
    }

    /** Hottest readable thermal zone in °C; zones the kernel hides or reports absurdly simply yield nothing. */
    fun readMaxTempC(thermalRoot: File): Double? {
        val zones = runCatching {
            thermalRoot.listFiles { file -> file.name.startsWith("thermal_zone") }?.toList()
        }.getOrNull() ?: return null
        val temps = zones.mapNotNull { zone ->
            runCatching { File(zone, "temp").readText().trim().toDoubleOrNull() }.getOrNull()
                ?.let { if (it > 1000.0) it / 1000.0 else it }
                ?.takeIf { it in -20.0..130.0 }
        }
        return temps.maxOrNull()
    }

    /** One sample against the previous one; null until two /proc/stat reads have happened. */
    fun read(): SystemReading? {
        val now = parseCpuTimes(CPU_STAT) ?: return null
        // The running baseline is the only shared state, so the lock covers the delta alone; the file reads
        // below need no lock and must not block a concurrent reader behind slow disk IO.
        val percent = synchronized(this) {
            val delta = cpuPercent(lastCpu, now)
            lastCpu = now
            delta
        } ?: return null
        val memory = runCatching { parseMemoryKb(MEM_INFO.readText()) }.getOrNull() ?: return null
        return SystemReading(
            cpuPercent = percent,
            coreFreqMhz = readCoreFreqMhz(CPU_ROOT),
            memUsedGb = (memory.first - memory.second) / 1048576.0,
            memTotalGb = memory.first / 1048576.0,
            tempC = readMaxTempC(THERMAL_ROOT),
        )
    }

    /** Drops the CPU baseline so the next reading is a fresh delta, not one measured before the panel closed. */
    fun reset() {
        synchronized(this) { lastCpu = null }
    }

    private var lastCpu: CpuTimes? = null
}

/** Samples the head unit's own load while the panel shows the tile, the same lifecycle the tyre status uses. */
object SystemStatus {
    private const val TAG = "DiPlay-System"
    private const val READ_MILLIS = 2_000L
    private const val STALE_MILLIS = 15_000L
    // A plain app may not be allowed the thermal sysfs at all; two samples are only the /proc/stat
    // warm-up, so the tile is announced as unreadable after four silent polls instead of after two.
    private const val UNREADABLE_LIMIT = 4

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "diplay-system").apply { isDaemon = true }
    }
    private var task: ScheduledFuture<*>? = null
    @Volatile private var latest: SystemReading? = null
    @Volatile private var latestMillis = 0L
    private var firstReadingLogged = false
    private var silentReads = 0
    private var unreadableLogged = false

    @Synchronized
    fun start() {
        if (task != null) return
        firstReadingLogged = false
        silentReads = 0
        unreadableLogged = false
        // A reopened panel must show the load it just measured, not the average since the panel closed:
        // drop the running CPU baseline and the previous session's snapshot so the tile starts blank.
        SystemProbe.reset()
        latest = null
        latestMillis = 0L
        task = executor.scheduleWithFixedDelay(::poll, 0, READ_MILLIS, TimeUnit.MILLISECONDS)
        Log.i(TAG, "system status started")
    }

    @Synchronized
    fun stop() {
        task?.cancel(false)
        task = null
        Log.i(TAG, "system status stopped")
    }

    /** The latest reading, or null when nothing has been sampled or it is older than 15 seconds. */
    fun latest(): SystemReading? = latest?.takeIf { SystemClock.elapsedRealtime() - latestMillis <= STALE_MILLIS }

    private fun poll() {
        val reading = runCatching { SystemProbe.read() }.getOrNull()
        if (reading == null) {
            // The first poll is always empty (the CPU delta needs two /proc/stat samples); only a run of
            // them means the head unit really hides the files from a plain app.
            if (++silentReads >= UNREADABLE_LIMIT && !unreadableLogged) {
                unreadableLogged = true
                Log.i(TAG, "system files not readable here; the tile stays blank")
            }
            return
        }
        silentReads = 0
        unreadableLogged = false
        latest = reading
        latestMillis = SystemClock.elapsedRealtime()
        if (!firstReadingLogged) {
            firstReadingLogged = true
            Log.i(
                TAG,
                "system cpu %d%% ram %.1f/%.1fGB cores %d%s%s".format(
                    reading.cpuPercent.roundToInt(),
                    reading.memUsedGb, reading.memTotalGb, reading.coreFreqMhz.size,
                    reading.coreFreqMhz.maxOrNull()?.let { " max ${it}MHz" } ?: "",
                    reading.tempC?.let { " temp %.0fC".format(it) } ?: " temp n/a",
                ),
            )
        }
    }
}
