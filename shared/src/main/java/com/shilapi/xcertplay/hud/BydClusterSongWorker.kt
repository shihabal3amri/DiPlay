package com.shilapi.xcertplay.hud

import android.content.Context
import android.util.Log
import java.io.InputStream
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Reuses an exclusive authorized ADB shell stream; no auxiliary socket or network service. */
internal class ClusterSongWorkerClient {
    private var token: String? = null
    private var adb: com.shilapi.xcertplay.adb.LocalAdb? = null
    private var stream: com.shilapi.xcertplay.adb.LocalAdb.InteractiveShell? = null
    private var retryAtNanos = 0L
    private var ready = false

    fun ensure(context: Context): Boolean {
        if (ready) return true
        if (System.nanoTime() < retryAtNanos) return false
        val startupAt = System.nanoTime()
        closeTransport()
        val client = com.shilapi.xcertplay.adb.LocalAdb(com.shilapi.xcertplay.adb.AdbKeys.load(context))
        adb = client
        if (client.connect(mayAsk = false) != com.shilapi.xcertplay.adb.LocalAdb.Access.READY) return unavailable("launch")
        val next = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        token = next
        val apk = "'" + context.applicationInfo.sourceDir.replace("'", "'\\''") + "'"
        stream = client.openShell("CLASSPATH=$apk app_process /system/bin ${BydClusterSongWorker::class.java.name} --stdin $next")
            ?: return unavailable("launch")
        val pingAt = System.nanoTime()
        if (exchange("ping", 10000) != "ready") return unavailable("not-ready")
        ready = true
        val now = System.nanoTime()
        Log.i("DiPlay-BYD-Song", "worker ready startupMs=${(now - startupAt) / 1_000_000L} pingMs=${(now - pingAt) / 1_000_000L}")
        return true
    }

    fun write(args: String): String? = request("write $args")
    fun keepAlive(): Boolean = request("ping") == "ready"
    fun stop(clearCard: Boolean = true): String? {
        val result = exchange(if (clearCard) "stop" else "close")
        closeTransport()
        retryAtNanos = 0L
        return result
    }

    private fun request(command: String): String? {
        if (!ready) return null
        return exchange(command).also { if (it == null) unavailable("stream") }
    }
    private fun exchange(command: String, timeout: Int = 5000): String? =
        token?.let { stream?.exchangeBounded("$it $command", timeout) }

    private fun unavailable(reason: String): Boolean {
        Log.w("DiPlay-BYD-Song", "worker unavailable reason=$reason")
        closeTransport()
        retryAtNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        return false
    }
    private fun closeTransport() {
        stream?.close(); stream = null
        adb?.close(); adb = null
        token = null
        ready = false
    }
}

/** The only accepted operations target the known instrument media fields, never arbitrary shell. */
internal object ClusterSongWorkerProtocol {
    const val LEASE_MILLIS = 15_000L
    private const val MAX_LINE_BYTES = 1024
    private val tokenPattern = Regex("[0-9a-f]{32}")
    data class Request(val operation: String, val args: Array<String> = emptyArray())

    fun validToken(token: String): Boolean = tokenPattern.matches(token)

    fun parse(line: String, token: String): Request? {
        if (!validToken(token) || line.length > MAX_LINE_BYTES) return null
        val fields = line.split(' ')
        if (fields.getOrNull(0) != token) return null
        if (fields.size == 2 && fields[1] in setOf("ping", "stop", "close")) return Request(fields[1])
        if (fields.size != 5 || fields[1] != "write") return null
        if ((fields[2] != "-" && fields[2].toIntOrNull() !in 0..15) || fields[3] !in setOf("-", "1", "2", "3")) return null
        if (fields[4] != "-") {
            val text = runCatching { String(com.shilapi.xcertplay.compat.Base64Compat.decode(fields[4]), Charsets.UTF_8) }.getOrNull() ?: return null
            if (text.toByteArray(Charsets.UTF_16LE).size > ClusterSongState.MAX_TEXT_BYTES) return null
        }
        return Request("write", fields.drop(2).toTypedArray())
    }

    fun readLine(input: InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        repeat(MAX_LINE_BYTES + 1) {
            val next = input.read()
            if (next < 0) return null
            if (next == 10) return bytes.toString("UTF-8")
            bytes.write(next)
        }
        return null
    }
}

/** Shell entry point: one SDK initialization serves successive lyric updates for a leased session. */
object BydClusterSongWorker {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 2 || args[0] != "--stdin") return
        val token = args[1].takeIf { ClusterSongWorkerProtocol.validToken(it) } ?: return
        val lease = java.util.concurrent.atomic.AtomicLong(System.nanoTime())
        val watchdog = Executors.newSingleThreadScheduledExecutor()
        watchdog.scheduleAtFixedRate({
            if (System.nanoTime() - lease.get() > TimeUnit.MILLISECONDS.toNanos(ClusterSongWorkerProtocol.LEASE_MILLIS)) System.exit(0)
        }, 1, 1, TimeUnit.SECONDS)
        try {
            val writer = ClusterSongDeviceWriter()
            while (true) {
                val line = ClusterSongWorkerProtocol.readLine(System.`in`) ?: break
                val request = ClusterSongWorkerProtocol.parse(line, token) ?: break
                lease.set(System.nanoTime())
                var stop = false
                val response = when (request.operation) {
                    "ping" -> "ready"
                    "stop" -> { stop = true; writer.write(arrayOf("-", "3", "-")) }
                    "close" -> { stop = true; "closed" }
                    else -> writer.write(request.args)
                }
                println(response)
                println("done")
                System.out.flush()
                if (stop) break
            }
        } catch (_: Throwable) {
            // EOF/errors close this authorized shell stream. Never expose another control endpoint.
        } finally {
            watchdog.shutdownNow()
            System.exit(0)
        }
    }
}
