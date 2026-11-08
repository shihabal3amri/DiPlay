package com.shilapi.xcertplay.hud

import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BydClusterAdasColorFilesTest {
    @get:Rule val temp = TemporaryFolder()

    private fun dir() = File(temp.root, "translate")

    private fun widthOf(file: File): Int {
        val image = ImageIO.read(file)
        assertEquals("one row", 1, image.height)
        return image.width
    }

    @Test fun theImageIsAValidPngOfTheWantedWidthAndOneRow() {
        for (width in listOf(1, 2, 31, 101, 150, 360)) {
            val image = ImageIO.read(ByteArrayInputStream(BydClusterAdasColorFiles.png(width)))
            assertEquals(width, image.width)
            assertEquals(1, image.height)
        }
    }

    @Test fun aWidthTheClusterCouldNotUseIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { BydClusterAdasColorFiles.png(0) }
        assertThrows(IllegalArgumentException::class.java) { BydClusterAdasColorFiles.png(361) }
    }

    @Test fun theBytesDoNotDependOnAnythingButTheWidth() {
        assertArrayEquals(BydClusterAdasColorFiles.png(150), BydClusterAdasColorFiles.png(150))
    }

    @Test fun theWidthsAreWhatTheBlockReads() {
        assertTrue(BydClusterAdasColorFiles.write(dir(), AdasCarColor(215, 80, 100)))
        assertEquals(216, widthOf(File(dir(), "diplay-adas-ch.png")))
        assertEquals(81, widthOf(File(dir(), "diplay-adas-cs.png")))
        assertEquals(100, widthOf(File(dir(), "diplay-adas-cv.png")))
    }

    @Test fun aColorWrittenReadsBackAndLeavesNoTemporaries() {
        assertTrue(BydClusterAdasColorFiles.write(dir(), AdasCarColor(24, 88, 105)))
        assertEquals(AdasCarColor(24, 88, 105), BydClusterAdasColorFiles.read(dir()))
        assertEquals(BydClusterAdasColorFiles.ALL.toSet(), dir().list()!!.toSet())
    }

    @Test fun everyPresetAndTheLimitsRoundTrip() {
        for (color in AdasCarColor.PRESETS.map { it.color } + listOf(
            AdasCarColor(0, 0, AdasCarColor.BRIGHTNESS_MIN),
            AdasCarColor(359, 100, AdasCarColor.BRIGHTNESS_MAX),
        )) {
            assertTrue(BydClusterAdasColorFiles.write(dir(), color))
            assertEquals(color, BydClusterAdasColorFiles.read(dir()))
        }
    }

    @Test fun writingReplacesAnEarlierColor() {
        assertTrue(BydClusterAdasColorFiles.write(dir(), AdasCarColor(10, 20, 100)))
        assertTrue(BydClusterAdasColorFiles.write(dir(), AdasCarColor(300, 40, 60)))
        assertEquals(AdasCarColor(300, 40, 60), BydClusterAdasColorFiles.read(dir()))
    }

    @Test fun clearingGivesBydsOwnCarBackAndLeavesOtherFiles() {
        assertTrue(BydClusterAdasColorFiles.write(dir(), AdasCarColor(10, 20, 100)))
        File(dir(), "diplay-adas.png").writeBytes(ByteArray(1))
        BydClusterAdasColorFiles.clear(dir())
        assertNull(BydClusterAdasColorFiles.read(dir()))
        assertEquals(setOf("diplay-adas.png"), dir().list()!!.toSet())
    }

    @Test fun aColorThatIsNotAllThereReadsAsNone() {
        for (missing in BydClusterAdasColorFiles.ALL) {
            assertTrue(BydClusterAdasColorFiles.write(dir(), AdasCarColor(10, 20, 100)))
            File(dir(), missing).delete()
            assertNull(missing, BydClusterAdasColorFiles.read(dir()))
        }
    }

    @Test fun aFileThatIsNotAPngOrIsCutShortReadsAsNone() {
        assertTrue(BydClusterAdasColorFiles.write(dir(), AdasCarColor(10, 20, 100)))
        File(dir(), "diplay-adas-cs.png").writeBytes(ByteArray(40) { 7 })
        assertNull(BydClusterAdasColorFiles.read(dir()))
        assertTrue(BydClusterAdasColorFiles.write(dir(), AdasCarColor(10, 20, 100)))
        File(dir(), "diplay-adas-ch.png").writeBytes(BydClusterAdasColorFiles.png(50).copyOf(12))
        assertNull(BydClusterAdasColorFiles.read(dir()))
    }

    @Test fun aWidthOutsideTheRangesReadsAsNone() {
        assertTrue(BydClusterAdasColorFiles.write(dir(), AdasCarColor(10, 20, 100)))
        // brightness 20 % is below the 30 % the block accepts
        File(dir(), "diplay-adas-cv.png").writeBytes(BydClusterAdasColorFiles.png(20))
        assertNull(BydClusterAdasColorFiles.read(dir()))
    }

    @Test fun aFolderThatCannotBeMadeIsAFailureThatLeavesNothingBehind() {
        val blocker = temp.newFile("blocker")
        assertFalse(BydClusterAdasColorFiles.write(File(blocker, "translate"), AdasCarColor(10, 20, 100)))
        assertEquals(listOf("blocker"), temp.root.list()!!.toList())
    }

    @Test fun otherFilesInTheFolderAreNeverTouched() {
        dir().mkdirs()
        val other = File(dir(), "other-app-mode.png").apply { writeBytes(ByteArray(3)) }
        assertTrue(BydClusterAdasColorFiles.write(dir(), AdasCarColor(10, 20, 100)))
        BydClusterAdasColorFiles.clear(dir())
        assertTrue(other.isFile)
        assertEquals(3L, other.length())
    }
}
