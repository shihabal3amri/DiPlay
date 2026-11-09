package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BydClusterAdasThemeMainTest {
    private val header = BydClusterAdasThemeMain.HEADER

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
}
