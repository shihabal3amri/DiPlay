package com.shilapi.xcertplay

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/** Bounded metadata only: never copy media into a diagnostic or print exception messages. */
internal object VideoLoadDiagnostics {
    // Keep the detailed resource probe for investigations without running it during normal playback.
    const val TRACE_REQUESTS = false
    private const val SCAN_LIMIT = 1024 * 1024
    private const val LINE_PREFIX_LIMIT = 256
    private val reasons = setOf("request_admission", "memory_budget", "storage_pressure",
        "resource_size_limit", "decode_failed", "unexpected_envelope", "not_pending")

    fun causes(error: Throwable): String = generateSequence(error) { it.cause }.take(6)
        .joinToString("<-") {
            val type = it.javaClass.simpleName.filter { c -> c.isLetterOrDigit() || c == '_' }.take(64)
            if (it is IphoneVideoResourceRejectedException && it.reason in reasons) {
                val request = it.requestId?.let { id -> ";request=$id" } ?: ""
                "$type[${it.reason}$request]"
            } else type
        }

    fun inspect(data: ByteArray?, file: File?, size: Long): String {
        val input = if (data != null) ByteArrayInputStream(data) else file?.inputStream() ?: return "kind=empty"
        return input.use { inspect(it, size) }
    }

    private fun inspect(input: InputStream, size: Long): String {
        val buffer = ByteArray(8192)
        var count = input.read(buffer)
        if (count <= 0) return "kind=empty"
        val offset = if (count >= 3 && buffer[0] == 0xef.toByte() && buffer[1] == 0xbb.toByte() && buffer[2] == 0xbf.toByte()) 3 else 0
        val hls = count >= offset + 7 && String(buffer, offset, 7, Charsets.US_ASCII) == "#EXTM3U"
        if (!hls) {
            val mp4 = count >= 8 && String(buffer, 4, 4, Charsets.US_ASCII) in setOf("ftyp", "styp", "moof")
            return "kind=${if (mp4) "mp4" else if (buffer[0] == 0x47.toByte()) "ts" else "other"}"
        }
        var variants = 0
        var audio = 0
        var subtitles = 0
        var unknownMedia = 0
        var segments = 0
        var scanned = 0
        val line = StringBuilder(LINE_PREFIX_LIMIT)
        fun finishLine() {
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> variants++
                line.startsWith("#EXTINF:") -> segments++
                line.startsWith("#EXT-X-MEDIA:") -> when {
                    line.contains("TYPE=AUDIO") -> audio++
                    line.contains("TYPE=SUBTITLES") -> subtitles++
                    else -> unknownMedia++
                }
            }
            line.setLength(0)
        }
        while (count > 0 && scanned < SCAN_LIMIT) {
            val limit = minOf(count, SCAN_LIMIT - scanned)
            for (i in 0 until limit) {
                val value = buffer[i].toInt() and 0xff
                if (value == 10) finishLine()
                else if (value != 13 && line.length < LINE_PREFIX_LIMIT) line.append(value.toChar())
            }
            scanned += limit
            if (scanned >= SCAN_LIMIT) break
            count = input.read(buffer, 0, minOf(buffer.size, SCAN_LIMIT - scanned))
        }
        if (scanned.toLong() >= size) finishLine()
        return "kind=hls variants=$variants audio=$audio subtitles=$subtitles mediaUnknown=$unknownMedia " +
            "segments=$segments scannedBytes=$scanned truncated=${scanned.toLong() < size}"
    }
}
