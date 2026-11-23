package com.shilapi.xcertplay.network

import android.content.Context
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Optional out-of-band debug channel for firmware that drops all inbound traffic (the porting
 * testbed head unit drops ICMP/TCP inbound on wlan0 and reportedly on VpnService tuns). Connects
 * out to a listener on the PC — outbound is always allowed — and bridges it to the local adbd
 * port, giving the PC an "adb connect" path that needs neither the USB port nor a VpnService
 * tunnel, so it can run while the iPhone occupies the only USB port.
 *
 * Enabled only while the app's `diplay_debugbridge.txt` external file exists and contains the PC
 * endpoint as "host:port" (for example "192.168.137.1:9999"). Requires "adb tcpip 5555" to have
 * been run once since boot. The file's presence is rechecked on every reconnect, so removing it
 * (plus up to one reconnect interval) turns the bridge off.
 */
object DebugBridge {
    private const val MARKER_NAME = "diplay_debugbridge.txt"
    private const val LOG_NAME = "bridge_debug.log"
    private const val ADBD_HOST = "127.0.0.1"
    private const val ADBD_PORT = 5555
    private const val CONNECT_TIMEOUT_MILLIS = 4_000L
    private const val RECONNECT_DELAY_MILLIS = 5_000L

    @Volatile
    private var started = false

    @Volatile
    private var markerFile: File? = null

    @Volatile
    private var logFile: File? = null

    fun ensureStarted(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            val dir = context.getExternalFilesDir(null) ?: return
            markerFile = File(dir, MARKER_NAME)
            logFile = File(dir, LOG_NAME)
            started = true
            Thread(::runLoop, "debug-bridge").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun runLoop() {
        while (true) {
            val target = readTarget()
            if (target == null) {
                Thread.sleep(RECONNECT_DELAY_MILLIS)
                continue
            }
            try {
                serve(target.first, target.second)
                log("debug bridge session ended; will reconnect")
            } catch (_: Exception) {
                // PC unreachable or adbd not in tcpip mode yet; keep retrying quietly.
            }
            Thread.sleep(RECONNECT_DELAY_MILLIS)
        }
    }

    private fun serve(host: String, port: Int) {
        val upstream = Socket()
        try {
            upstream.tcpNoDelay = true
            upstream.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS.toInt())
            val adbd = Socket()
            try {
                adbd.tcpNoDelay = true
                adbd.connect(InetSocketAddress(ADBD_HOST, ADBD_PORT), CONNECT_TIMEOUT_MILLIS.toInt())
                log("debug bridge session open pc=$host:$port")
                val pumps = listOf(
                    Thread { pump(upstream, adbd) }.apply { isDaemon = true; start() },
                    Thread { pump(adbd, upstream) }.apply { isDaemon = true; start() },
                )
                pumps.forEach { it.join() }
            } finally {
                runCatching { adbd.close() }
            }
        } finally {
            runCatching { upstream.close() }
        }
    }

    private fun pump(from: Socket, to: Socket) {
        try {
            val buffer = ByteArray(8_192)
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val length = input.read(buffer)
                if (length < 0) break
                output.write(buffer, 0, length)
                output.flush()
            }
        } catch (_: Exception) {
        } finally {
            runCatching { to.close() }
            runCatching { from.close() }
        }
    }

    private fun readTarget(): Pair<String, Int>? {
        val file = markerFile ?: return null
        return try {
            val text = file.readText().trim()
            if (text.isEmpty()) return null
            val host = text.substringBeforeLast(':')
            val port = text.substringAfterLast(':').toIntOrNull() ?: return null
            if (host.isBlank() || host.contains(' ') || port !in 1..65_535) null else host to port
        } catch (_: Exception) {
            null
        }
    }

    private fun log(line: String) {
        val file = logFile ?: return
        try {
            file.parentFile?.mkdirs()
            java.io.FileWriter(file, true).use { writer ->
                writer.append(System.currentTimeMillis().toString()).append(' ').append(line).append('\n')
            }
        } catch (_: Exception) {
        }
    }
}
