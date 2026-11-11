package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.net.ProtocolException

/** Incremental framing: a fixed header and at most one pre-admitted, exactly sized body. */
internal class ControlMessageReader(
    private val videoStreams: () -> Set<Long> = { emptySet() },
    private val budget: ControlBodyBudget = ControlBodyBudget.shared,
    private val log: (String) -> Unit = {},
    private val allowsLargeVideoResources: () -> Boolean = { true },
) : Closeable {
    private val headerBytes = ByteArray(HEADER_LIMIT + 4)
    private var headerSize = 0
    private var header: RtspMessage.Header? = null
    private var received = 0
    private var body: ByteArray? = null
    private var permit: Closeable? = null
    private var video = false
    private var rejection: String? = null

    fun feed(bytes: ByteArray): List<RtspMessage.Request> {
        val result = ArrayList<RtspMessage.Request>()
        var offset = 0
        try {
            while (offset < bytes.size) {
                if (header == null) {
                    if (headerSize == headerBytes.size) throw ProtocolException("RTSP header over $HEADER_LIMIT bytes")
                    headerBytes[headerSize++] = bytes[offset++]
                    val end = headerSize >= 4 && headerBytes[headerSize - 4] == 13.toByte() &&
                        headerBytes[headerSize - 3] == 10.toByte() && headerBytes[headerSize - 2] == 13.toByte() &&
                        headerBytes[headerSize - 1] == 10.toByte()
                    if (!end) continue
                    if (headerSize - 4 > HEADER_LIMIT) throw ProtocolException("RTSP header over $HEADER_LIMIT bytes")
                    begin(RtspMessage.parseHeader(String(headerBytes, 0, headerSize - 4, Charsets.US_ASCII)))
                }
                val current = header ?: continue
                val count = minOf(bytes.size - offset, current.length - received)
                body?.let { bytes.copyInto(it, received, offset, offset + count) }
                received += count
                offset += count
                if (received == current.length) {
                    result += RtspMessage.Request(current.method, current.path, current.protocol, current.headers,
                        body ?: ByteArray(0), video, if (rejection != null) current.length else 0, rejection)
                        .also { it.bodyLease = permit }
                    permit = null
                    reset()
                }
            }
            return result
        } catch (error: Throwable) {
            result.forEach { it.close() }
            close()
            throw error
        }
    }

    private fun begin(next: RtspMessage.Header) {
        val stream = next.headers["x-apple-streamid"]?.toLongOrNull()
        val allowedStreams = videoStreams()
        val negotiatedVideo = next.method == "POST" && RtspMessage.isCommandPath(next.path) && stream in allowedStreams
        val allowsLargeBody = negotiatedVideo && allowsLargeVideoResources()
        video = allowsLargeBody
        if (next.length > ORDINARY_BODY_LIMIT) {
            if (!negotiatedVideo) throw ProtocolException(
                "RTSP body of ${next.length} bytes over $ORDINARY_BODY_LIMIT stream=${stream ?: -1} allowed=$allowedStreams")
            video = true
            rejection = when {
                !allowsLargeBody -> "relay_disabled"
                next.length > VideoInCar.MAX_RESOURCE_COMMAND_BYTES -> "resource_size_limit"
                else -> {
                    permit = budget.acquire(next.length)
                    if (permit == null) "memory_budget" else null
                }
            }
        }
        if (rejection == null) {
            try { body = ByteArray(next.length) } catch (error: OutOfMemoryError) {
                if (!video) throw error
                permit?.close()
                permit = null
                rejection = "heap_pressure"
            }
        }
        header = next
        if (video && next.length > ORDINARY_BODY_LIMIT) log(
            "video resource framing stream=$stream bytes=${next.length} action=${if (rejection == null) "receive" else "drain"} reason=${rejection ?: "negotiated_video_stream"} reservedBytes=${budget.reservedBytes()} maxHeapBytes=${Runtime.getRuntime().maxMemory()}")
    }

    private fun reset() {
        headerSize = 0
        header = null
        received = 0
        body = null
        video = false
        rejection = null
    }

    override fun close() {
        permit?.close()
        permit = null
        reset()
    }

    companion object {
        private const val HEADER_LIMIT = 16 * 1024
        private const val ORDINARY_BODY_LIMIT = 1024 * 1024
    }
}
