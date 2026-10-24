package com.shilapi.xcertplay

import android.content.Context
import android.os.SystemClock
import com.shilapi.xcertplay.ultra.UltraVehicleSource
import com.shilapi.xcertplay.ultra.VehicleReading
import com.shilapi.xcertplay.ultra.VehicleSignal
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Telltale event 1 is a list, not a VHAL property or a fixed byte offset. */
internal class Fx11AutoHoldState(private val clock: () -> Long, private val timeoutMs: Long = 25_000) {
    private var value: Boolean? = null
    private var observedAt = 0L
    private var contactAt: Long? = null
    var frames = 0L; private set
    var status = "waiting"; private set

    @Synchronized fun reset(reason: String = "offline") {
        value = null; contactAt = null; status = reason
    }
    @Synchronized fun accept(line: String) {
        if (expired()) reset("stale")
        if (line == "ALIVE") { contactAt = clock(); return }
        if (line == "ONLINE" || line == "OFFLINE" || line == "INVALID" || line.startsWith("STATUS -")) {
            reset(line.lowercase()); return
        }
        if (!line.startsWith("FRAME ")) return
        val fields = line.split(' ')
        if (fields.size != 3 || fields[1] !in listOf("0", "1") || !fields[2].matches(Regex("[0-9a-fA-F]{426}"))) {
            reset("invalid-frame"); return
        }
        val data = ByteArray(213)
        for (i in data.indices) {
            val byte = fields[2].substring(i * 2, i * 2 + 2).toIntOrNull(16)
            if (byte == null) { reset("invalid-hex"); return }
            data[i] = byte.toByte()
        }
        val count = data[0].toInt() and 255
        if (count > 106) { reset("invalid-count"); return }
        val seen = HashSet<Int>(); var found: Boolean? = null
        for (i in 0 until count) {
            val id = data[1 + i * 2].toInt() and 255
            val state = data[2 + i * 2].toInt() and 255
            if (!seen.add(id)) { reset("duplicate-id"); return }
            if (id == 32) {
                if (state !in 0..1) { reset("invalid-state"); return }
                found = state == 1
            }
        }
        if ((1 + count * 2 until data.size).any { data[it] != 0.toByte() }) {
            reset("invalid-padding"); return
        }
        // FX11's first broadcast is not marked as a snapshot. Absence of 32 stays unknown.
        if (fields[1] == "1") value = null
        if (found != null) { value = found; observedAt = clock() }
        frames++; contactAt = clock(); status = if (value == null) "waiting-for-32" else "subscribed"
    }
    @Synchronized fun expired(): Boolean = contactAt?.let { clock() - it !in 0..timeoutMs } ?: false
    @Synchronized fun snapshot(): Map<VehicleSignal, VehicleReading> {
        if (expired()) reset("stale")
        return value?.let { mapOf(VehicleSignal.AUTO_HOLD_ACTIVE to VehicleReading(it, observedAt, 32)) }.orEmpty()
    }
    @Synchronized fun report(): String {
        val reading = snapshot()[VehicleSignal.AUTO_HOLD_ACTIVE]?.value ?: "unknown"
        return "AutoHold FDBus TelltaleModule/event1/lamp32 status=$status value=$reading frames=$frames"
    }
}

/** Ordinary APK UID, read-only discovery/subscription. Owns and tears down its child processes. */
internal class Fx11AutoHoldSource(context: Context) : UltraVehicleSource {
    private val executable = File(context.applicationInfo.nativeLibraryDir, "libfx11_telltale.so")
    private val state = Fx11AutoHoldState(SystemClock::elapsedRealtime)
    @Volatile private var closed = false
    @Volatile private var child: Process? = null
    private var worker: Thread? = null
    private var watchdog: java.util.concurrent.ScheduledExecutorService? = null
    @Synchronized override fun start() {
        if (worker != null || closed) return
        if (android.os.Build.VERSION.SDK_INT < 30 || !executable.canExecute() || !File("/system/bin/lssvc").canExecute()) {
            state.reset("unavailable-reader-or-discovery"); return
        }
        watchdog = Executors.newSingleThreadScheduledExecutor { Thread(it, "fx11-hold-watchdog").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({
                if (state.expired()) { state.reset("stale"); child?.destroy() }
            }, 5, 5, TimeUnit.SECONDS)
        }
        worker = Thread({
            while (!closed) {
                try {
                    state.reset("discovering")
                    val url = discover()
                    if (closed) break
                    val process = ProcessBuilder(executable.absolutePath, url).redirectErrorStream(true).start()
                    child = process
                    if (closed) { process.destroy(); break }
                    process.inputStream.bufferedReader().use { reader ->
                        while (!closed) {
                            val line = reader.readLine() ?: break
                            synchronized(this@Fx11AutoHoldSource) { if (!closed) state.accept(line) }
                        }
                    }
                } catch (_: Exception) {
                    if (!closed) state.reset("reader-unavailable")
                } finally { child?.destroy(); child = null; state.reset() }
                try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
            }
        }, "fx11-autohold").apply { isDaemon = true; start() }
    }
    private fun discover(): String {
        if (android.os.Build.VERSION.SDK_INT < 26) error("FX11 native reader requires Android 11")
        val process = ProcessBuilder("/system/bin/lssvc", "-v").redirectErrorStream(true).start()
        child = process
        val bytes = ByteArrayOutputStream()
        val reader = Thread({
            runCatching { process.inputStream.use { input ->
                val buffer = ByteArray(2048)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    if (bytes.size() + n > 131072) { process.destroy(); break }
                    bytes.write(buffer, 0, n)
                }
            } }
        }, "fx11-service-list").apply { isDaemon = true; start() }
        try {
            check(process.waitFor(5, TimeUnit.SECONDS)) { "discovery timeout" }
            reader.join(1000)
            check(!reader.isAlive && process.exitValue() == 0) { "discovery failed" }
            return endpoint(bytes.toString("UTF-8")) ?: error("TelltaleModule unavailable")
        } finally { process.destroy(); child = null }
    }
    override fun snapshot() = state.snapshot()
    fun diagnosticReport() = state.report()
    @Synchronized override fun close() {
        closed = true; watchdog?.shutdownNow(); watchdog = null
        child?.destroy(); worker?.interrupt(); state.reset("closed")
    }
    companion object {
        /** Only the verified FX11 QNX peer; TCP port is discovered after every reconnect. */
        internal fun endpoint(inventory: String): String? {
            var ip: String? = null; var target = false
            for (line in inventory.lineSequence()) {
                if (line.startsWith("[")) {
                    ip = Regex("IP: (\\d+\\.\\d+\\.\\d+\\.\\d+)").find(line)?.groupValues?.get(1)
                    target = false
                }
                if (line.trimStart().startsWith("[")) target = line.trim() == "[TelltaleModule]"
                if (target && ip == "192.168.118.2") {
                    val match = Regex("tcp://([0-9.]+):(\\d+)").find(line) ?: continue
                    val host = if (match.groupValues[1] == "0") ip else match.groupValues[1]
                    val port = match.groupValues[2].toIntOrNull() ?: return null
                    if (host == ip && port in 1..65535) return "tcp://$host:$port"
                }
            }
            return null
        }
    }
}
