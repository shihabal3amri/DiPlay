package com.shilapi.xcertplay.hud

import java.io.File
import java.io.IOException
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QtResourceTest {
    private val clusterQml = "Item {\n    id: root\n" +
        "    Image { id: adas2dNaviWindowBg; x: 1256; y: 225; width: 563; height: 353 }\n" +
        "    Item { id:centerContainerId }\n    //\u52a8\u6548 end\n}\n"
    private val compressible = ByteArray(300) { ((it % 7) + 65).toByte() }
    private val incompressible = ByteArray(64) { ((it * 197 + 13) % 251).toByte() }
    private val translation =
        "<TS language=\"en\"><message><source>Speed</source><translation>Speed</translation></message></TS>\n".repeat(4)

    /** Built by Qt's own `rcc --binary --format-version 2 --compress-algo zlib` from the four files above. */
    private val qtVersion2 = listOf(
        "cXJlcwAAAAIAAAISAAAAFAAAAWgAAAAXAAABLHicc3RydnF1c3ccpQhTANgiT64AAABADdKcZjD1v4lTHeKsdkAKz5ljLfK8hlAa",
        "36lzPQfMlmAq77mDTRfcpnA6BMmTXSfstoBKFNmjbTcBxpBaJOmzfQAAAFUAAAGEeJyzCQlWyEnMSy9NTE+1VUrNU7KzyU0tLgby",
        "7GyK80uLklPtggtSU1Ns9KE8m5KixLzinMSSzPw8mBSykI0+XL9+SLAdl82oBYQsAADX14shAAAAmEl0ZW0gewogICAgaWQ6IHJv",
        "b3QKICAgIEltYWdlIHsgaWQ6IGFkYXMyZE5hdmlXaW5kb3dCZzsgeDogMTI1NjsgeTogMjI1OyB3aWR0aDogNTYzOyBoZWlnaHQ6",
        "IDM1MyB9CiAgICBJdGVtIHsgaWQ6Y2VudGVyQ29udGFpbmVySWQgfQogICAgLy/liqjmlYggZW5kCn0KAAMAAHg8AHEAbQBsAAYG",
        "pkReAGMAbwBtAG0AbwBuAAgITrmVAGwAYQBuAGcAdQBhAGcAZQANCUMtvQBsAGEAbgBnAC4AZQBuAF8AVQBTAC4AcQBtAAoKRgX9",
        "AGwAYQBuAGcALgBhAHIALgBxAG0ADQlDLXMAbABhAG4AZwAuAGUAbgBfAFUAUwAuAHQAcwALChExfABjAGwAdQBzAHQAZQByAC4A",
        "cQBtAGwAAAAAAAIAAAACAAAAAQAAAAAAAAAAAAAAAAACAAAAAQAAAAcAAAAAAAAAAAAAAAwAAgAAAAEAAAADAAAAAAAAAAAAAAAe",
        "AAIAAAADAAAABAAAAAAAAAAAAAAAbgABAAAAAQAAAF8AAAGht8IlewAAADQAAQAAAAEAAAAAAAABobfCJXoAAABUAAAAAAABAAAA",
        "GwAAAaG3wiV7AAAAjgAAAAAAAQAAALgAAAGht8IleQ==",
    ).joinToString("")

    /** The same files, from `--format-version 3`: a 24-byte header that adds the overall flags. */
    private val qtVersion3 = listOf(
        "cXJlcwAAAAMAAAIWAAAAGAAAAWwAAAABAAAAFwAAASx4nHN0cnZxdXN3HKUIUwDYIk+uAAAAQA3SnGYw9b+JUx3irHZACs+ZYy3y",
        "vIZQGt+pcz0HzJZgKu+5g00X3KZwOgTJk10n7LaAShTZo203AcaQWiTps30AAABVAAABhHicswkJVshJzEsvTUxPtVVKzVOys8lN",
        "LS4G8uxsivNLi5JT7YILUlNTbPShPJuSosS84pzEksz8PJgUspCNPly/fkiwHZfNqAWELAAA19eLIQAAAJhJdGVtIHsKICAgIGlk",
        "OiByb290CiAgICBJbWFnZSB7IGlkOiBhZGFzMmROYXZpV2luZG93Qmc7IHg6IDEyNTY7IHk6IDIyNTsgd2lkdGg6IDU2MzsgaGVp",
        "Z2h0OiAzNTMgfQogICAgSXRlbSB7IGlkOmNlbnRlckNvbnRhaW5lcklkIH0KICAgIC8v5Yqo5pWIIGVuZAp9CgADAAB4PABxAG0A",
        "bAAGBqZEXgBjAG8AbQBtAG8AbgAICE65lQBsAGEAbgBnAHUAYQBnAGUADQlDLb0AbABhAG4AZwAuAGUAbgBfAFUAUwAuAHEAbQAK",
        "CkYF/QBsAGEAbgBnAC4AYQByAC4AcQBtAA0JQy1zAGwAYQBuAGcALgBlAG4AXwBVAFMALgB0AHMACwoRMXwAYwBsAHUAcwB0AGUA",
        "cgAuAHEAbQBsAAAAAAACAAAAAgAAAAEAAAAAAAAAAAAAAAAAAgAAAAEAAAAHAAAAAAAAAAAAAAAMAAIAAAABAAAAAwAAAAAAAAAA",
        "AAAAHgACAAAAAwAAAAQAAAAAAAAAAAAAAG4AAQAAAAEAAABfAAABobfCJc0AAAA0AAEAAAABAAAAAAAAAaG3wiXMAAAAVAAAAAAA",
        "AQAAABsAAAGht8IlzAAAAI4AAAAAAAEAAAC4AAABobfCJcs=",
    ).joinToString("")

    private fun open(base64: String) = QtResource.open(QtResource.BytesSource(Base64.getDecoder().decode(base64)))

    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)

    private fun assertQtFixture(archive: QtResource.Archive, version: Int) {
        assertEquals(version, archive.version)
        assertEquals(
            setOf("/qml/cluster.qml", "/common/language/lang.en_US.qm", "/common/language/lang.ar.qm", "/common/language/lang.en_US.ts"),
            archive.entries.map { it.path }.toSet(),
        )
        val expected = mapOf(
            "/qml/cluster.qml" to Pair(bytes(clusterQml), QtResource.STORED),
            "/common/language/lang.en_US.qm" to Pair(compressible, QtResource.ZLIB),
            "/common/language/lang.ar.qm" to Pair(incompressible, QtResource.STORED),
            "/common/language/lang.en_US.ts" to Pair(bytes(translation), QtResource.ZLIB),
        )
        for ((path, want) in expected) {
            val entry = archive.find(path)!!
            assertArrayEquals(path, want.first, archive.content(entry))
            assertEquals(path, want.second, entry.flags)
            assertEquals(0, entry.country)
            assertEquals(1, entry.language)
        }
    }

    @Test fun readsAResourceBuiltByQtRccVersion2() = assertQtFixture(open(qtVersion2), 2)

    @Test fun readsAResourceBuiltByQtRccVersion3() = assertQtFixture(open(qtVersion3), 3)

    @Test fun aZlibEntryIsStoredWithItsInflatedLengthInFront() {
        val archive = open(qtVersion2)
        val stored = archive.stored(archive.find("/common/language/lang.en_US.qm")!!)
        assertEquals(300L, QtResource.u32(stored, 0))
        assertTrue(stored.size < 300)
        assertNull(archive.find("/missing"))
    }

    @Test fun theNameHashMatchesTheOnesQtWroteForBydAndForTheFixture() {
        assertEquals(30780, QtResource.hash("qml"))
        assertEquals(117669315, QtResource.hash("images"))
    }

    private fun sample() = listOf(
        QtResource.item("/qml/cluster.qml", bytes(clusterQml), lastModified = 1_765_388_679_000L),
        QtResource.item("/common/language/lang.en_US.qm", compressible, lastModified = 7L),
        QtResource.item("/common/language/lang.ar.qm", incompressible),
        QtResource.item("/common/language/lang.en_US.ts", bytes(translation)),
    )

    @Test fun aWrittenResourceReadsBackWithTheSameFiles() {
        for (version in 2..3) {
            val archive = QtResource.open(QtResource.BytesSource(QtResource.write(sample(), version)))
            assertEquals(version, archive.version)
            assertEquals(4, archive.entries.size)
            val qml = archive.find("/qml/cluster.qml")!!
            assertArrayEquals(bytes(clusterQml), archive.content(qml))
            assertEquals(1_765_388_679_000L, qml.lastModified)
            assertArrayEquals(compressible, archive.content(archive.find("/common/language/lang.en_US.qm")!!))
            assertArrayEquals(incompressible, archive.content(archive.find("/common/language/lang.ar.qm")!!))
            assertArrayEquals(bytes(translation), archive.content(archive.find("/common/language/lang.en_US.ts")!!))
            assertTrue(archive.entries.all { it.country == 0 && it.language == 1 })
        }
    }

    @Test fun aBlobCopiedFromAnotherResourceIsKeptByteForByte() {
        val source = open(qtVersion2)
        val entry = source.find("/common/language/lang.en_US.qm")!!
        val copy = QtResource.Item(entry.path, entry.flags, source.stored(entry), entry.lastModified)
        val archive = QtResource.open(QtResource.BytesSource(QtResource.write(listOf(copy))))
        val back = archive.find(entry.path)!!
        assertEquals(entry.flags, back.flags)
        assertArrayEquals(source.stored(entry), archive.stored(back))
        assertArrayEquals(compressible, archive.content(back))
    }

    @Test fun theHeaderOffsetsPointAtDataThenNamesThenTree() {
        for (version in 2..3) {
            val bytes = QtResource.write(sample(), version)
            val header = if (version == 3) 24 else 20
            assertEquals("qres", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals(version.toLong(), QtResource.u32(bytes, 4))
            val tree = QtResource.u32(bytes, 8)
            val data = QtResource.u32(bytes, 12)
            val names = QtResource.u32(bytes, 16)
            assertEquals(header.toLong(), data)
            assertTrue(data < names && names < tree && tree < bytes.size)
            if (version == 3) assertEquals(QtResource.ZLIB.toLong(), QtResource.u32(bytes, 20))
            // 4 files + qml, common and language directories + the root, 22 bytes each, ends the file.
            assertEquals(bytes.size - tree, 8L * 22)
        }
    }

    @Test fun theTreeOrdersTheChildrenOfADirectoryByNameHash() {
        val bytes = QtResource.write(sample())
        val tree = QtResource.u32(bytes, 8).toInt()
        // Root: name offset, flags, child count, first child. Then its two directories.
        assertEquals(2L, QtResource.u32(bytes, tree + 6))
        assertEquals(1L, QtResource.u32(bytes, tree + 10))
        val names = QtResource.u32(bytes, 16).toInt()
        fun nameHashAt(entry: Int): Long = QtResource.u32(bytes, names + QtResource.u32(bytes, tree + entry * 22).toInt() + 2)
        val hashes = listOf(nameHashAt(1), nameHashAt(2))
        assertEquals(hashes.sorted(), hashes)
        assertEquals(setOf(QtResource.hash("qml").toLong(), QtResource.hash("common").toLong()), hashes.toSet())
    }

    @Test fun aNameUsedInTwoDirectoriesIsWrittenOnce() {
        val shared = QtResource.write(listOf(QtResource.item("/a/x.qm", compressible), QtResource.item("/b/x.qm", compressible)))
        val distinct = QtResource.write(listOf(QtResource.item("/a/x.qm", compressible), QtResource.item("/b/y.qm", compressible)))
        // The second file name costs its length, hash and four UTF-16 characters.
        assertEquals(6 + 2 * 4, distinct.size - shared.size)
    }

    @Test fun anItemIsCompressedOnlyWhenThatMakesItSmaller() {
        val packed = QtResource.item("/a.qm", compressible)
        assertEquals(QtResource.ZLIB, packed.flags)
        assertEquals(300L, QtResource.u32(packed.stored, 0))
        assertEquals(QtResource.STORED, QtResource.item("/b.qm", incompressible).flags)
        assertEquals(QtResource.STORED, QtResource.item("/c.qm", ByteArray(0)).flags)
    }

    @Test fun readsFromAFileOnDisk() {
        val file = File.createTempFile("qtresource", ".rcc").apply { deleteOnExit() }
        file.writeBytes(QtResource.write(sample()))
        QtResource.open(QtResource.FileSource(file)).use { archive ->
            assertArrayEquals(compressible, archive.content(archive.find("/common/language/lang.en_US.qm")!!))
        }
    }

    @Test fun refusesWhatIsNotAResource() {
        assertThrows(IOException::class.java) { QtResource.open(QtResource.BytesSource(ByteArray(8))) }
        val bad = Base64.getDecoder().decode(qtVersion2).also { it[0] = 'x'.code.toByte() }
        assertThrows(IOException::class.java) { QtResource.open(QtResource.BytesSource(bad)) }
        val future = Base64.getDecoder().decode(qtVersion2).also { it[7] = 9 }
        assertThrows(IOException::class.java) { QtResource.open(QtResource.BytesSource(future)) }
    }

    @Test fun refusesATruncatedResource() {
        val bytes = Base64.getDecoder().decode(qtVersion2)
        assertThrows(IOException::class.java) { QtResource.open(QtResource.BytesSource(bytes.copyOf(bytes.size - 40))) }
    }

    @Test fun refusesATreeThatLoopsBackOnItself() {
        val bytes = QtResource.write(sample())
        val tree = QtResource.u32(bytes, 8).toInt()
        // The root's first-child index set to the root itself.
        for (i in 0 until 4) bytes[tree + 10 + i] = 0
        assertThrows(IOException::class.java) { QtResource.open(QtResource.BytesSource(bytes)) }
    }

    @Test fun refusesABlobThatLeavesTheDataSection() {
        val bytes = QtResource.write(sample())
        val data = QtResource.u32(bytes, 12).toInt()
        bytes[data] = 0x7f
        val archive = QtResource.open(QtResource.BytesSource(bytes))
        val broken = archive.entries.first { it.dataOffset == 0L }
        assertThrows(IOException::class.java) { archive.stored(broken) }
    }

    @Test fun zstdEntriesAreReportedNotGuessedAt() {
        val item = QtResource.Item("/z.qm", QtResource.ZSTD, byteArrayOf(1, 2, 3))
        val archive = QtResource.open(QtResource.BytesSource(QtResource.write(listOf(item))))
        assertNotNull(archive.find("/z.qm"))
        assertThrows(IOException::class.java) { archive.content(archive.find("/z.qm")!!) }
    }

    @Test fun aZlibEntryWithTheWrongInflatedLengthIsRefused() {
        val item = QtResource.item("/a.qm", compressible)
        val broken = item.stored.copyOf().also { it[3] = (it[3] + 1).toByte() }
        assertThrows(IOException::class.java) { QtResource.inflate(broken) }
    }

    @Test fun conflictingPathsAreRejectedWhenWriting() {
        assertThrows(IllegalArgumentException::class.java) {
            QtResource.write(listOf(QtResource.item("/a", compressible), QtResource.item("/a/b", compressible)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            QtResource.write(listOf(QtResource.item("/a", compressible), QtResource.item("/a", compressible)))
        }
        assertThrows(IllegalArgumentException::class.java) { QtResource.write(listOf(QtResource.item("/a//b", compressible))) }
    }

    @Test fun anOptionalDumpLetsQtLoadTheWrittenFile() {
        // Set DIPLAY_QTRESOURCE_DUMP to a path to load the result in Qt's QResource by hand; CI leaves it unset.
        val path = System.getenv("DIPLAY_QTRESOURCE_DUMP") ?: return
        File(path).writeBytes(QtResource.write(sample()))
    }
}
