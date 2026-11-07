package com.shilapi.xcertplay.hud

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BydClusterAdasBeatTest {
    @get:Rule val temp = TemporaryFolder()

    private fun shellAvailable(): Boolean = try {
        ProcessBuilder("sh", "-c", "stat -c %Y . >/dev/null && date +%s >/dev/null && echo aGk= | base64 -d >/dev/null")
            .redirectErrorStream(true).start().let { it.waitFor() == 0 }
    } catch (_: IOException) {
        false
    }

    private fun posix(file: File) = file.path.replace('\\', '/')

    private fun dimensions(file: File): Pair<Int, Int>? = try {
        val bytes = file.readBytes()
        if (bytes.size < 24) null else {
            fun int(at: Int) = ((bytes[at].toInt() and 0xff) shl 24) or ((bytes[at + 1].toInt() and 0xff) shl 16) or
                ((bytes[at + 2].toInt() and 0xff) shl 8) or (bytes[at + 3].toInt() and 0xff)
            int(16) to int(20)
        }
    } catch (_: IOException) {
        null
    }

    @Test fun theScriptHasNoSingleQuoteSoItCanTravelInOneShellCommand() {
        assertTrue(BydClusterAdasBeat.script().none { '\'' in it })
        val command = BydClusterAdasBeat.keepAlive()
        assertFalse('\n' in command)
        assertTrue("touch /data/local/tmp/diplay-adas.lease" in command)
        assertTrue("setsid sh /data/local/tmp/diplay-adas-beat-1.sh" in command)
        assertTrue(command.startsWith("[ -f /data/local/tmp/diplay-adas-beat-1.sh ] || "))
    }

    @Test fun theLeaseIsRemovedToStopAndPathsAreChecked() {
        assertEquals("rm -f /data/local/tmp/diplay-adas.lease", BydClusterAdasBeat.stop())
        assertThrows(IllegalArgumentException::class.java) { BydClusterAdasBeat.script(dir = "/a b") }
        assertThrows(IllegalArgumentException::class.java) { BydClusterAdasBeat.script(work = "/a'b") }
    }

    @Test fun theLoopWritesTwoImagesInTurnAndStopsWhenTheLeaseGoes() {
        assumeTrue("needs a POSIX shell with stat, date and base64", shellAvailable())
        val dir = temp.newFolder("translate")
        val work = temp.newFolder("work")
        val script = File(work, "beat.sh").apply {
            writeText(BydClusterAdasBeat.script(posix(dir), posix(work)).joinToString("\n") + "\n")
        }
        val lease = File(work, "diplay-adas.lease").apply { writeText("") }
        val process = ProcessBuilder("sh", posix(script)).redirectErrorStream(true).start()
        try {
            val seen = mutableSetOf<Pair<Int, Int>>()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (System.nanoTime() < deadline && seen.size < 2) {
                dimensions(File(dir, "diplay-adas.png"))?.let(seen::add)
                Thread.sleep(50)
            }
            assertEquals(setOf(2 to 2, 2 to 3), seen)
            assertTrue(File(work, "diplay-adas.pid").isFile)
            assertTrue(lease.delete())
            assertTrue(process.waitFor(6, TimeUnit.SECONDS))
            assertFalse(File(dir, "diplay-adas.png").exists())
            assertFalse(File(work, "diplay-adas.pid").exists())
        } finally {
            process.destroyForcibly()
        }
    }

    @Test fun aLeaseThatIsNotRefreshedEndsTheLoopByItself() {
        assumeTrue("needs a POSIX shell with stat, date and base64", shellAvailable())
        val dir = temp.newFolder("translate")
        val work = temp.newFolder("work")
        val script = File(work, "beat.sh").apply {
            writeText(BydClusterAdasBeat.script(posix(dir), posix(work)).joinToString("\n") + "\n")
        }
        File(work, "diplay-adas.lease").apply {
            writeText("")
            assertTrue(setLastModified(System.currentTimeMillis() - 60_000))
        }
        val process = ProcessBuilder("sh", posix(script)).redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(6, TimeUnit.SECONDS))
            assertNull(dimensions(File(dir, "diplay-adas.png")))
            assertFalse(File(work, "diplay-adas.pid").exists())
        } finally {
            process.destroyForcibly()
        }
    }
}
