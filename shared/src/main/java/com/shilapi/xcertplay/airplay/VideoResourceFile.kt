package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** A private, bounded resource owned by the pending load or its open player data source. */
class VideoResourceFile internal constructor(val file: File, val length: Long, private val release: () -> Unit) : Closeable {
    private val closed = AtomicBoolean()
    override fun close() { if (closed.compareAndSet(false, true)) release() }
}

class VideoResourceStore internal constructor(
    cacheDirectory: File,
    private val byteLimit: Long = 128L * 1024 * 1024,
    private val fileLimit: Int = 8,
) {
    private val directory = File(cacheDirectory, "iphone-video-resources").apply {
        if (!isDirectory && !mkdirs()) throw IOException("Cannot create video resource cache")
    }
    private var retainedBytes = 0L
    private var retainedFiles = 0

    init {
        // This store is created once per process. Only its own orphaned cache files are removed.
        directory.listFiles()?.filter { it.name.startsWith("resource-") && it.name.endsWith(".bin") }
            ?.forEach { if (!it.delete() && it.exists()) { retainedBytes += it.length(); retainedFiles++ } }
    }

    fun save(bytes: ByteArray): VideoResourceFile = saveData(bytes, 0, bytes.size)

    internal fun save(data: BplistCodec.DataSlice): VideoResourceFile = saveData(data.source, data.offset, data.size)

    @Synchronized private fun saveData(bytes: ByteArray, offset: Int, length: Int): VideoResourceFile {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create video resource cache")
        if (length > VideoInCar.MAX_RESOURCE_BYTES || retainedFiles >= fileLimit ||
            length.toLong() > byteLimit - retainedBytes) throw IOException("Video cache quota exceeded")
        val file = File.createTempFile("resource-", ".bin", directory)
        try { file.outputStream().use { it.write(bytes, offset, length) } } catch (error: Exception) {
            if (!file.delete() && file.exists()) { retainedBytes += file.length(); retainedFiles++ }
            throw error
        }
        retainedBytes += length
        retainedFiles++
        val size = length.toLong()
        return VideoResourceFile(file, size) {
            synchronized(this) {
                if (file.delete() || !file.exists()) { retainedBytes -= size; retainedFiles-- }
            }
        }
    }

    @Synchronized internal fun retainedBytes(): Long = retainedBytes

    companion object {
        @Volatile private var instance: VideoResourceStore? = null
        @Synchronized fun shared(cacheDirectory: File): VideoResourceStore =
            instance?.takeIf { it.directory.parentFile?.canonicalFile == cacheDirectory.canonicalFile }
                ?: VideoResourceStore(cacheDirectory).also { instance = it }
    }
}

/** Converts only FCUP resource bytes; queued UI callbacks never retain a large video ByteArray. */
internal fun spoolVideoResource(message: Map<String, Any?>, getStore: () -> VideoResourceStore): Map<String, Any?> {
    if (message["type"] != "unhandledURL") return message
    val response = message["response"] as? Map<*, *> ?: return mapOf("type" to "unhandledURL")
    val bytes = response["FCUP_Response_Data"] as? ByteArray
    val slice = response["FCUP_Response_Data"] as? BplistCodec.DataSlice
    val headers = response["FCUP_Response_Headers"] as? Map<*, *>
    val safeHeaders = headers?.entries?.mapNotNull { (key, value) ->
        if (key is String && value is String && key.length + value.length <= 16 * 1024) key to value else null
    }?.take(64)?.toMap().orEmpty()
    val next = linkedMapOf<String, Any?>(
        "FCUP_Response_RequestID" to (response["FCUP_Response_RequestID"] as? Number)?.toLong(),
        "FCUP_Response_StatusCode" to (response["FCUP_Response_StatusCode"] as? Number)?.toLong(),
        "FCUP_Response_Headers" to safeHeaders,
        "FCUP_Response_Data" to bytes,
    )
    try {
        if (safeHeaders.entries.sumOf { it.key.length + it.value.length } > 16 * 1024) throw IOException("Video headers too large")
        if (bytes != null && bytes.size > 256 * 1024) next["FCUP_Response_Data"] = getStore().save(bytes)
        if (slice != null) next["FCUP_Response_Data"] =
            if (slice.size > 256 * 1024) getStore().save(slice) else slice.copySmallBytes()
    } catch (_: IOException) {
        next["FCUP_Response_Data"] = ByteArray(0)
        next["FCUP_Response_StatusCode"] = 507L
    }
    return mapOf("type" to "unhandledURL", "kind" to "response", "response" to next)
}
