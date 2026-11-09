package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BydClusterAdasQmlTest {
    private fun cluster(eol: String = "\r\n", bom: Boolean = true, tail: String = "}", ids: List<String> = IDS) =
        (if (bom) "\uFEFF" else "") + (
            listOf("import QtQuick 2.12", "Item {") +
                ids.flatMap { listOf("    Item {", "        id:$it", "    }") } +
                listOf("    //\u52a8\u6548 end", tail)
            ).joinToString(eol) + eol

    private val center = "import QtQuick 2.12\nItem {\n    property CenterChild adasUi: null\n" +
        "    property CenterChild adas3r1vUi: null\n}\n"

    private fun patch(cluster: String, center: String = this.center) =
        BydClusterAdasQml.patch(cluster.toByteArray(Charsets.UTF_8), center.toByteArray(Charsets.UTF_8))

    private fun patched(cluster: String): String =
        String((patch(cluster) as BydClusterAdasQml.Result.Patched).qml, Charsets.UTF_8)

    private fun refusal(result: BydClusterAdasQml.Result) = (result as BydClusterAdasQml.Result.Refused).code

    @Test fun theBlockIsInsertedLastInTheRootItemAndNothingElseChanges() {
        val original = cluster()
        val result = patched(original)
        assertTrue(BydClusterAdasQml.MARKER in result)
        val block = "\r\n" + BydClusterAdasQml.BLOCK.replace("\n", "\r\n")
        assertEquals(original, result.replace(block, ""))
        assertTrue(result.endsWith("}\r\n"))
        assertTrue(result.indexOf("end\r\n") < result.indexOf(BydClusterAdasQml.MARKER))
        // Only the closing brace of the root item follows the block.
        assertEquals("}", result.substringAfter(BydClusterAdasQml.BLOCK.replace("\n", "\r\n")).trim())
    }

    @Test fun theByteOrderMarkAndLineEndingsAreKept() {
        val result = patched(cluster())
        assertTrue(result.startsWith("\uFEFF"))
        assertFalse(Regex("(?<!\r)\n").containsMatchIn(result))
    }

    @Test fun aFileWithLfLineEndingsGetsLfOnly() {
        val result = patched(cluster(eol = "\n", bom = false))
        assertFalse('\r' in result)
        assertTrue(BydClusterAdasQml.BLOCK in result)
        assertFalse(result.startsWith("\uFEFF"))
    }

    @Test fun aFileWithoutAFinalNewlineKeepsEndingWithTheRootBrace() {
        val result = patched(cluster().trimEnd())
        assertTrue(result.endsWith("    }\r\n}"))
        assertEquals(1, Regex("""\bid\s*:\s*diplayAdasId\b""").findAll(result).count())
    }

    @Test fun aPatchedFileIsNotPatchedAgain() {
        assertEquals("already-patched", refusal(patch(patched(cluster()))))
    }

    @Test fun aFileWithoutTheNavigationBoxOrTheCentreContainerIsRefused() {
        assertEquals("missing-adas2dNaviWindowBg", refusal(patch(cluster(ids = listOf("centerContainerId")))))
        assertEquals("missing-centerContainerId", refusal(patch(cluster(ids = listOf("adas2dNaviWindowBg")))))
    }

    @Test fun aCentreFileWithoutTheAdasScenesIsRefused() {
        assertEquals("missing-adasUi", refusal(patch(cluster(), center = "Item { property int other }")))
        assertEquals("missing-adas3r1vUi", refusal(patch(cluster(), center = "Item { property CenterChild adasUi: null }")))
    }

    @Test fun aFileThatAlreadyUsesTransformIsRefused() {
        assertEquals("transform-used", refusal(patch(cluster().replace("Item {\r\n", "Item {\r\n    transform: []\r\n"))))
        assertEquals("transform-used", refusal(patch(cluster(), center = center.replace("}\n", "    property var transform\n}\n"))))
    }

    @Test fun aFileThatDoesNotEndWithTheRootBraceIsRefused() {
        assertEquals("no-root-item", refusal(patch(cluster(tail = "    // cut short"))))
    }

    @Test fun bytesThatAreNotUtf8TextAreRefused() {
        val bad = byteArrayOf(0x49, 0x74, 0x65, 0x6d, 0x7b, 0xff.toByte(), 0x7d)
        assertEquals("cluster-not-text", refusal(BydClusterAdasQml.patch(bad, center.toByteArray())))
        assertEquals("center-not-text", refusal(BydClusterAdasQml.patch(cluster().toByteArray(), bad)))
    }

    @Test fun theDigestIsALowerCaseSha256() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            BydClusterAdasQml.digest("abc".toByteArray()),
        )
    }

    @Test fun theBlockReadsOneChannelFileAndUsesOnlyBydsOwnItems() {
        val block = BydClusterAdasQml.BLOCK
        assertEquals(1, Regex("""\bImage\s*\{""").findAll(block).count())
        assertEquals(setOf("diplay-adas.png"), Regex("""diplay-adas[\w-]*\.png""").findAll(block).map { it.value }.toSet())
        assertTrue("adas2dNaviWindowBg" in block && "centerContainerId.adasUi" in block)
        assertTrue(block.endsWith("}\n"))
        assertFalse('\r' in block)
    }

    @Test fun theBlockReadsTheFileMoreSlowlyWhileThereIsNoHeartbeat() {
        assertTrue("interval: diplayAdasId.alive ? 250 : 1000" in BydClusterAdasQml.BLOCK)
    }

    private companion object {
        val IDS = listOf("adas2dNaviWindowBg", "centerContainerId")
    }
}
