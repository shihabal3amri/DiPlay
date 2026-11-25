package com.shilapi.xcertplay.airplay

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class VideoResourceViewMemoryTest {
    @Test fun repeatedLargeNestedRepliesSpoolWithoutCopyingTheirWirePayload() {
        val directory = Files.createTempDirectory("video-view-test").toFile()
        try {
            val size = 21_558_983
            val inner = File(directory, "inner.plist")
            plist(inner, listOf(
                byteArrayOf(0xd3.toByte(), 1, 3, 5, 2, 4, 6), ascii("type"), ascii("unhandledURL"),
                ascii("kind"), ascii("response"), ascii("response"),
                byteArrayOf(0xd3.toByte(), 7, 9, 11, 8, 10, 12), ascii("FCUP_Response_RequestID"),
                byteArrayOf(0x10, 1), ascii("FCUP_Response_StatusCode"), byteArrayOf(0x10, 200.toByte()),
                ascii("FCUP_Response_Data")), size) { output ->
                val chunk = ByteArray(16 * 1024)
                var remaining = size
                var first = true
                while (remaining > 0) {
                    val count = minOf(remaining, chunk.size)
                    chunk.fill(0)
                    if (first) chunk[0] = 0xa5.toByte()
                    if (remaining == count) chunk[count - 1] = 0x5a
                    output.write(chunk, 0, count)
                    first = false
                    remaining -= count
                }
            }
            val outer = File(directory, "outer.plist")
            plist(outer, listOf(byteArrayOf(0xd1.toByte(), 1, 2), ascii("params"),
                byteArrayOf(0xd1.toByte(), 3, 4), ascii("data")), inner.length().toInt()) { output ->
                inner.inputStream().use { it.copyTo(output, 16 * 1024) }
            }
            val store = VideoResourceStore(directory)
            // Retaining closed handles must not retain the original 21 MB wire arrays.
            val closed = mutableListOf<VideoResourceFile>()
            repeat(6) {
                val wire = outer.readBytes()
                val root = BplistCodec.decodeVideoResourceView(wire) as Map<*, *>
                val nested = (root["params"] as Map<*, *>)["data"] as BplistCodec.DataSlice
                val message = BplistCodec.decodeVideoResourceView(nested) as Map<*, *>
                val data = (message["response"] as Map<*, *>)["FCUP_Response_Data"] as BplistCodec.DataSlice
                assertSame(wire, nested.source)
                assertSame(wire, data.source)
                assertEquals(size, data.size)
                assertThrows(IllegalArgumentException::class.java) { data.copySmallBytes() }
                @Suppress("UNCHECKED_CAST")
                val spooled = spoolVideoResource(message as Map<String, Any?>) { store }
                val file = (spooled["response"] as Map<*, *>)["FCUP_Response_Data"] as VideoResourceFile
                assertEquals(size.toLong(), file.length)
                RandomAccessFile(file.file, "r").use { reader ->
                    assertEquals(0xa5, reader.read())
                    reader.seek(size.toLong() - 1)
                    assertEquals(0x5a, reader.read())
                }
                file.close()
                closed.add(file)
                assertFalse(file.file.exists())
                assertEquals(0L, store.retainedBytes())
            }
            assertEquals(6, closed.size)
        } finally { directory.deleteRecursively() }
    }

    @Test fun nestedViewsShareTheirMetadataAllocationLimit() {
        val inner = BplistCodec.encode(mapOf("text" to "x".repeat(40_000)))
        val outer = BplistCodec.encode(mapOf("text" to "y".repeat(40_000), "data" to inner))
        val root = BplistCodec.decodeVideoResourceView(outer) as Map<*, *>
        assertThrows(IllegalArgumentException::class.java) {
            BplistCodec.decodeVideoResourceView(root["data"] as BplistCodec.DataSlice)
        }
        assertEquals(40_000, (BplistCodec.decodeVideoResourceView(inner) as Map<*, *>)["text"].toString().length)
    }

    private fun ascii(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        return (if (bytes.size < 15) byteArrayOf((0x50 + bytes.size).toByte())
            else byteArrayOf(0x5f, 0x10, bytes.size.toByte())) + bytes
    }

    /** Stream a single trailing data object so the test fixture needs no large intermediate arrays. */
    private fun plist(file: File, objects: List<ByteArray>, length: Int, data: (OutputStream) -> Unit) {
        file.outputStream().buffered().use { output ->
            output.write("bplist00".toByteArray())
            val offsets = mutableListOf<Int>()
            var position = 8
            objects.forEach { offsets.add(position); output.write(it); position += it.size }
            offsets.add(position)
            output.write(byteArrayOf(0x4f, 0x12))
            output.write(ByteBuffer.allocate(4).putInt(length).array())
            data(output)
            position += 6 + length
            offsets.forEach { output.write(ByteBuffer.allocate(4).putInt(it).array()) }
            output.write(ByteBuffer.allocate(32).put(ByteArray(6)).put(4.toByte()).put(1.toByte())
                .putLong(offsets.size.toLong()).putLong(0).putLong(position.toLong()).array())
        }
    }
}
