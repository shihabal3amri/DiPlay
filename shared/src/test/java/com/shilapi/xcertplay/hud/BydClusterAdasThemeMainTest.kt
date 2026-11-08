package com.shilapi.xcertplay.hud

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BydClusterAdasThemeMainTest {
    @get:Rule val temp = TemporaryFolder()

    private val header = BydClusterAdasThemeMain.HEADER
    private fun dir() = File(temp.root, "translate")

    @Test fun theProtocolLineIsReadWhateverElseTheRuntimePrints() {
        val output = "WARNING: linker: something\n$header|READY|state=CURRENT|expected=a.rcc|installed=a.rcc|digest=ab\nbye\n"
        val result = BydClusterAdasThemeMain.parse(output)!!
        assertEquals(BydClusterAdasTheme.Status.READY, result.status)
        assertEquals("CURRENT", result["state"])
        assertEquals("a.rcc", result["expected"])
        assertNull(result["missing"])
    }

    @Test fun theLastProtocolLineWins() {
        val output = "$header|FAILED|code=a\n$header|INSTALLED|pack=p\n"
        assertEquals(BydClusterAdasTheme.Status.INSTALLED, BydClusterAdasThemeMain.parse(output)!!.status)
    }

    @Test fun aLineWithoutFieldsIsAStatusOnly() {
        val result = BydClusterAdasThemeMain.parse("$header|NO_CLUSTER")!!
        assertEquals(BydClusterAdasTheme.Status.NO_CLUSTER, result.status)
        assertEquals(emptyMap<String, String>(), result.fields)
    }

    @Test fun aValueMayContainAnEqualsSign() {
        assertEquals("a=b", BydClusterAdasThemeMain.parse("$header|UNSUPPORTED|code=a=b")!!["code"])
    }

    @Test fun nothingUsableIsNull() {
        assertNull(BydClusterAdasThemeMain.parse(null))
        assertNull(BydClusterAdasThemeMain.parse(""))
        assertNull(BydClusterAdasThemeMain.parse("DIPLAY_OTHER|READY"))
        assertNull(BydClusterAdasThemeMain.parse("$header|SOMETHING_NEW|a=b"))
    }

    @Test fun whatTheToolPrintsIsWhatTheControllerReads() {
        val result = BydClusterAdasTheme.Result(
            BydClusterAdasTheme.Status.OTHER_ACTIVE,
            mapOf("path" to "/data/cells/x.rcc"),
        )
        val back = BydClusterAdasThemeMain.parse("$header|${result.line()}")!!
        assertEquals(result.status, back.status)
        assertEquals("/data/cells/x.rcc", back["path"])
    }

    @Test fun theColorCommandPassesOnlyNumbersOrOff() {
        val apk = "/data/app/com.x-1/base.apk"
        assertEquals(
            "CLASSPATH='$apk' app_process /system/bin com.shilapi.xcertplay.hud.BydClusterAdasThemeMain color 215 80 100",
            BydClusterAdasThemeMain.colorCommand(apk, AdasCarColor(215, 80, 100)),
        )
        assertEquals(
            "CLASSPATH='$apk' app_process /system/bin com.shilapi.xcertplay.hud.BydClusterAdasThemeMain color off",
            BydClusterAdasThemeMain.colorCommand(apk, null),
        )
        assertTrue(BydClusterAdasThemeMain.colorCommand("/a'b/base.apk", null).startsWith("CLASSPATH='/a'\"'\"'b/base.apk' "))
    }

    @Test fun theHelperWritesTheColorFilesAndReportsIt() {
        val result = BydClusterAdasThemeMain.color(listOf("215", "80", "100"), dir())
        assertEquals(BydClusterAdasTheme.Status.COLORED, result.status)
        assertEquals("215,80,100", result["color"])
        assertEquals(AdasCarColor(215, 80, 100), BydClusterAdasColorFiles.read(dir()))
    }

    @Test fun offClearsTheColorFilesAndNothingElse() {
        BydClusterAdasColorFiles.write(dir(), AdasCarColor(215, 80, 100))
        File(dir(), "diplay-adas.png").writeBytes(ByteArray(1))
        val result = BydClusterAdasThemeMain.color(listOf("off"), dir())
        assertEquals(BydClusterAdasTheme.Status.COLORED, result.status)
        assertEquals("off", result["color"])
        assertNull(BydClusterAdasColorFiles.read(dir()))
        assertEquals(setOf("diplay-adas.png"), dir().list()!!.toSet())
    }

    @Test fun badColorArgumentsAreRefusedAndWriteNothing() {
        val refused = mapOf(
            emptyList<String>() to "color-arguments",
            listOf("on") to "color-arguments",
            listOf("1", "2") to "color-arguments",
            listOf("1", "2", "100", "4") to "color-arguments",
            listOf("360", "0", "100") to "color-values",
            listOf("0", "0", "20") to "color-values",
            listOf("a", "b", "c") to "color-values",
        )
        for ((args, code) in refused) {
            val result = BydClusterAdasThemeMain.color(args, dir())
            assertEquals(args.toString(), BydClusterAdasTheme.Status.FAILED, result.status)
            assertEquals(args.toString(), code, result["code"])
        }
        assertFalse(dir().exists())
    }

    @Test fun aColorThatCannotBeWrittenIsReported() {
        val blocker = temp.newFile("blocker")
        val result = BydClusterAdasThemeMain.color(listOf("1", "2", "100"), File(blocker, "translate"))
        assertEquals(BydClusterAdasTheme.Status.FAILED, result.status)
        assertEquals("color-write", result["code"])
    }

    @Test fun aColorLineIsReadBackWithItsValue() {
        val back = BydClusterAdasThemeMain.parse("$header|COLORED|color=215,80,100")!!
        assertEquals(BydClusterAdasTheme.Status.COLORED, back.status)
        assertEquals("215,80,100", back["color"])
    }
}
