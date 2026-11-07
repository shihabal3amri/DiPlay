package com.shilapi.xcertplay.hud

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Reads and writes Qt resource files (`.rcc`) in the binary form that `rcc --binary` writes and that Qt's
 * `QResource::registerResource` loads: a header, the data blobs, the names, then the tree (format versions 1 to 3).
 *
 * BYD's instrument cluster is a Qt application whose theme and language resources are such files, and it accepts a
 * replacement language file that overrides single files of its theme. DiPlay builds that file on the head unit from
 * the car's own resources, so only the entries it needs are handled here: stored and zlib entries, no zstd.
 */
internal object QtResource {
    const val STORED = 0x00
    const val ZLIB = 0x01
    const val DIRECTORY = 0x02
    const val ZSTD = 0x04

    private const val MAGIC = 0x71726573 // "qres"
    private const val MAX_ENTRIES = 200_000
    private const val MAX_DEPTH = 32
    private const val MAX_BLOB_BYTES = 64 * 1024 * 1024
    private const val MAX_TABLE_BYTES = 32 * 1024 * 1024

    /** A readable byte store; a file on the head unit, or bytes in a test. */
    interface Source : Closeable {
        val length: Long

        @Throws(IOException::class)
        fun read(offset: Long, size: Int): ByteArray
    }

    class FileSource(file: File) : Source {
        private val handle = RandomAccessFile(file, "r")
        override val length: Long = handle.length()

        override fun read(offset: Long, size: Int): ByteArray {
            if (offset < 0 || size < 0 || offset + size > length) throw IOException("read outside the file")
            val bytes = ByteArray(size)
            handle.seek(offset)
            handle.readFully(bytes)
            return bytes
        }

        override fun close() = handle.close()
    }

    class BytesSource(private val bytes: ByteArray) : Source {
        override val length: Long = bytes.size.toLong()

        override fun read(offset: Long, size: Int): ByteArray {
            if (offset < 0 || size < 0 || offset + size > length) throw IOException("read outside the data")
            return bytes.copyOfRange(offset.toInt(), offset.toInt() + size)
        }

        override fun close() = Unit
    }

    /** One file of a resource: [path] starts with `/`, [dataOffset] is relative to the data section. */
    class Entry(
        val path: String,
        val flags: Int,
        val country: Int,
        val language: Int,
        val dataOffset: Long,
        val lastModified: Long,
    )

    /** A parsed resource: the tree and names are in memory, blobs are read on demand. */
    class Archive internal constructor(
        val version: Int,
        val entries: List<Entry>,
        private val source: Source,
        private val dataStart: Long,
        private val dataEnd: Long,
    ) : Closeable {
        private val byPath = entries.associateBy { it.path }

        fun find(path: String): Entry? = byPath[path]

        /** The blob as stored: zlib entries keep their 4-byte inflated length in front of the stream. */
        @Throws(IOException::class)
        fun stored(entry: Entry): ByteArray {
            val at = dataStart + entry.dataOffset
            if (entry.dataOffset < 0 || at + 4 > dataEnd) throw IOException("data offset outside the data section")
            val length = u32(source.read(at, 4), 0)
            if (length < 0 || length > MAX_BLOB_BYTES || at + 4 + length > dataEnd) throw IOException("bad blob length")
            return source.read(at + 4, length.toInt())
        }

        /** The file's own bytes. */
        @Throws(IOException::class)
        fun content(entry: Entry): ByteArray {
            val raw = stored(entry)
            return when {
                entry.flags and ZSTD != 0 -> throw IOException("zstd entries are not supported")
                entry.flags and ZLIB != 0 -> inflate(raw)
                else -> raw
            }
        }

        override fun close() = source.close()
    }

    @Throws(IOException::class)
    fun open(source: Source): Archive {
        if (source.length < 20) throw IOException("not a Qt resource file")
        val header = ByteBuffer.wrap(source.read(0, minOf(24L, source.length).toInt()))
        if (header.getInt(0) != MAGIC) throw IOException("not a Qt resource file")
        val version = header.getInt(4)
        if (version !in 1..3) throw IOException("unsupported Qt resource version $version")
        val headerSize = if (version >= 3) 24 else 20
        val treeOffset = u32(header.array(), 8)
        val dataOffset = u32(header.array(), 12)
        val namesOffset = u32(header.array(), 16)
        val bounds = listOf(dataOffset, namesOffset, treeOffset, source.length).sorted()
        fun end(start: Long) = bounds.first { it > start }
        if (minOf(dataOffset, namesOffset, treeOffset) < headerSize || maxOf(dataOffset, namesOffset, treeOffset) >= source.length) {
            throw IOException("section offsets outside the file")
        }
        val treeSize = end(treeOffset) - treeOffset
        val namesSize = end(namesOffset) - namesOffset
        if (treeSize > MAX_TABLE_BYTES || namesSize > MAX_TABLE_BYTES) throw IOException("resource tables too large")
        val tree = source.read(treeOffset, treeSize.toInt())
        val names = source.read(namesOffset, namesSize.toInt())
        val size = if (version == 1) 14 else 22
        val count = tree.size / size
        if (count < 1 || count > MAX_ENTRIES) throw IOException("bad entry count")

        fun nameAt(offset: Long): String {
            if (offset < 0 || offset + 6 > names.size) throw IOException("name outside the names section")
            val chars = u16(names, offset.toInt())
            val last = offset + 6 + chars * 2L
            if (last > names.size) throw IOException("name outside the names section")
            return String(names, offset.toInt() + 6, chars * 2, Charsets.UTF_16BE)
        }

        val files = ArrayList<Entry>()
        var visited = 0

        fun visit(index: Long, parent: String, depth: Int) {
            if (depth > MAX_DEPTH || index < 0 || index >= count || ++visited > count) throw IOException("bad resource tree")
            val at = index.toInt() * size
            val flags = u16(tree, at + 4)
            val path = if (index == 0L) "" else parent + "/" + nameAt(u32(tree, at))
            if (flags and DIRECTORY != 0) {
                val children = u32(tree, at + 6)
                val first = u32(tree, at + 10)
                if (children < 0 || first < 0 || first + children > count) throw IOException("bad child range")
                for (child in 0 until children) visit(first + child, path, depth + 1)
            } else {
                val modified = if (version >= 2) i64(tree, at + 14) else 0L
                files += Entry(path, flags, u16(tree, at + 6), u16(tree, at + 8), u32(tree, at + 10), modified)
            }
        }
        visit(0, "", 0)
        return Archive(version, files, source, dataOffset, end(dataOffset))
    }

    /** A file to put into a resource: [stored] is the blob as [Archive.stored] returns it. */
    class Item(
        val path: String,
        val flags: Int,
        val stored: ByteArray,
        val lastModified: Long = 0L,
        val country: Int = 0,
        val language: Int = 1,
    )

    /** An item holding [content], zlib compressed when that makes it smaller. */
    fun item(path: String, content: ByteArray, lastModified: Long = 0L): Item {
        val packed = deflate(content)
        return if (packed != null) Item(path, ZLIB, packed, lastModified) else Item(path, STORED, content, lastModified)
    }

    /** Qt's resource name hash, as the tree is sorted by it for binary lookup. */
    fun hash(name: String): Int {
        var h = 0
        for (c in name) {
            h = (h shl 4) + c.code
            val g = h and 0xf0000000.toInt()
            if (g != 0) h = h xor (g ushr 23)
            h = h and g.inv()
        }
        return h
    }

    private class Node(val name: String) {
        val children = HashMap<String, Node>()
        var item: Item? = null
        var nameOffset = 0
        var childOffset = 0
        var dataOffset = 0L
        val directory get() = item == null
    }

    /**
     * Writes [items] as a resource of [version] 2 or 3 in the layout `rcc` uses: header, data, names, tree, with the
     * children of every directory sorted by name hash and the first directory visited last-in-first-out, as it does.
     */
    fun write(items: List<Item>, version: Int = 2): ByteArray {
        require(version == 2 || version == 3) { "version $version" }
        val root = Node("")
        for (item in items) {
            val parts = item.path.trimStart('/').split('/')
            require(parts.all { it.isNotEmpty() }) { "bad path ${item.path}" }
            var node = root
            for ((index, part) in parts.withIndex()) {
                val last = index == parts.lastIndex
                val child = node.children.getOrPut(part) { Node(part) }
                if (last) {
                    require(child.directory && child.children.isEmpty()) { "duplicate or directory path ${item.path}" }
                    child.item = item
                } else {
                    require(child.directory) { "${item.path} is below a file" }
                }
                node = child
            }
        }
        val headerSize = if (version >= 3) 24 else 20
        val data = ByteArrayOutputStream()
        val nameTable = ByteArrayOutputStream()
        val tree = ByteArrayOutputStream()

        fun ordered(node: Node) = node.children.values.sortedBy { it.name }
        fun byHash(node: Node) = node.children.values.sortedWith(compareBy<Node>({ hash(it.name).toLong() and 0xffffffffL }, { it.name }))

        val blobs = ArrayDeque<Node>().apply { addLast(root) }
        while (blobs.isNotEmpty()) {
            val node = blobs.removeLast()
            for (child in ordered(node)) {
                if (child.directory) {
                    blobs.addLast(child)
                } else {
                    val item = child.item!!
                    child.dataOffset = data.size().toLong()
                    data.write(be32(item.stored.size))
                    data.write(item.stored)
                }
            }
        }

        val known = HashMap<String, Int>()
        val names = ArrayDeque<Node>().apply { addLast(root) }
        while (names.isNotEmpty()) {
            val node = names.removeLast()
            for (child in ordered(node)) {
                if (child.directory) names.addLast(child)
                val offset = known[child.name]
                if (offset != null) {
                    child.nameOffset = offset
                } else {
                    child.nameOffset = nameTable.size()
                    known[child.name] = child.nameOffset
                    nameTable.write(be16(child.name.length))
                    nameTable.write(be32(hash(child.name)))
                    for (c in child.name) nameTable.write(be16(c.code))
                }
            }
        }

        var next = 1
        val layout = ArrayDeque<Node>().apply { addLast(root) }
        while (layout.isNotEmpty()) {
            val node = layout.removeLast()
            node.childOffset = next
            for (child in byHash(node)) {
                next++
                if (child.directory) layout.addLast(child)
            }
        }

        fun entry(node: Node) {
            val item = node.item
            tree.write(be32(node.nameOffset))
            if (item == null) {
                tree.write(be16(DIRECTORY))
                tree.write(be32(node.children.size))
                tree.write(be32(node.childOffset))
                tree.write(be64(0L))
            } else {
                tree.write(be16(item.flags))
                tree.write(be16(item.country))
                tree.write(be16(item.language))
                tree.write(be32(node.dataOffset.toInt()))
                tree.write(be64(item.lastModified))
            }
        }
        entry(root)
        val write = ArrayDeque<Node>().apply { addLast(root) }
        while (write.isNotEmpty()) {
            val node = write.removeLast()
            for (child in byHash(node)) {
                entry(child)
                if (child.directory) write.addLast(child)
            }
        }

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf('q'.code.toByte(), 'r'.code.toByte(), 'e'.code.toByte(), 's'.code.toByte()))
        out.write(be32(version))
        val dataAt = headerSize
        val namesAt = dataAt + data.size()
        val treeAt = namesAt + nameTable.size()
        out.write(be32(treeAt))
        out.write(be32(dataAt))
        out.write(be32(namesAt))
        if (version >= 3) out.write(be32(items.fold(0) { flags, item -> flags or (item.flags and (ZLIB or ZSTD)) }))
        out.write(data.toByteArray())
        out.write(nameTable.toByteArray())
        out.write(tree.toByteArray())
        return out.toByteArray()
    }

    /** `qCompress` form: the inflated length, big endian, then the zlib stream; null when it is not smaller. */
    fun deflate(content: ByteArray, level: Int = 9): ByteArray? {
        if (content.isEmpty()) return null
        val deflater = Deflater(level)
        try {
            deflater.setInput(content)
            deflater.finish()
            val out = ByteArrayOutputStream(content.size / 2 + 16)
            out.write(be32(content.size))
            val buffer = ByteArray(8192)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            return out.toByteArray().takeIf { it.size < content.size }
        } finally {
            deflater.end()
        }
    }

    @Throws(IOException::class)
    fun inflate(stored: ByteArray): ByteArray {
        if (stored.size < 4) throw IOException("zlib entry too short")
        val expected = u32(stored, 0)
        if (expected < 0 || expected > MAX_BLOB_BYTES) throw IOException("bad inflated length")
        val inflater = Inflater()
        try {
            inflater.setInput(stored, 4, stored.size - 4)
            val out = ByteArray(expected.toInt())
            var done = 0
            while (done < out.size) {
                val read = inflater.inflate(out, done, out.size - done)
                if (read == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
                done += read
            }
            if (done != out.size) throw IOException("inflated length differs")
            return out
        } catch (error: DataFormatException) {
            throw IOException("bad zlib data", error)
        } finally {
            inflater.end()
        }
    }

    private fun u16(bytes: ByteArray, at: Int): Int {
        if (at < 0 || at + 2 > bytes.size) throw IOException("read outside the table")
        return ((bytes[at].toInt() and 0xff) shl 8) or (bytes[at + 1].toInt() and 0xff)
    }

    internal fun u32(bytes: ByteArray, at: Int): Long {
        if (at < 0 || at + 4 > bytes.size) throw IOException("read outside the table")
        var value = 0L
        for (i in 0 until 4) value = (value shl 8) or (bytes[at + i].toLong() and 0xff)
        return value
    }

    private fun i64(bytes: ByteArray, at: Int): Long {
        if (at < 0 || at + 8 > bytes.size) throw IOException("read outside the table")
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (bytes[at + i].toLong() and 0xff)
        return value
    }

    private fun be16(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())

    private fun be32(value: Int) =
        byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

    private fun be64(value: Long) = ByteArray(8) { (value shr (56 - 8 * it)).toByte() }
}
