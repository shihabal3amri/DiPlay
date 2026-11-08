package com.shilapi.xcertplay.hud

import java.io.File
import org.junit.Assert.assertArrayEquals
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

    @Test fun theBlockUsesTheChannelFilesAndOnlyBydsOwnItems() {
        val block = BydClusterAdasQml.BLOCK
        for (name in listOf("diplay-adas.png", "diplay-adas-p.png", "diplay-adas-s.png") + BydClusterAdasColorFiles.ALL) {
            assertTrue(name, name in block)
        }
        assertTrue("adas2dNaviWindowBg" in block && "centerContainerId.adasUi" in block)
        assertTrue(block.endsWith("}\n"))
        assertFalse('\r' in block)
        assertFalse('$' in block)
    }

    @Test fun theBlockReadsTheColorTheWayTheHelperWritesIt() {
        val block = BydClusterAdasQml.BLOCK
        // hue and saturation carry one more than the value, so a value of 0 is still a readable image
        assertTrue("diplayHueImgId.implicitWidth - 1" in block && "Math.min(359, diplayHueImgId" in block)
        assertTrue("diplaySatImgId.implicitWidth - 1" in block && "Math.min(100, diplaySatImgId" in block)
        assertTrue(
            "Math.max(${AdasCarColor.BRIGHTNESS_MIN}, Math.min(${AdasCarColor.BRIGHTNESS_MAX}, diplayBriImgId.implicitWidth))" in block,
        )
        for ((name, id) in mapOf(
            BydClusterAdasColorFiles.HUE to "diplayHueImgId",
            BydClusterAdasColorFiles.SATURATION to "diplaySatImgId",
            BydClusterAdasColorFiles.BRIGHTNESS to "diplayBriImgId",
        )) {
            val image = block.substringAfter("id: $id").substringBefore("Image {").substringBefore("}")
            assertTrue("$id reads $name", "/.cluster/translate/$name?t=" in image)
        }
    }

    @Test fun theColorOnlyAppliesWhileTheShaderWorksAndEveryFileIsThere() {
        val block = BydClusterAdasQml.BLOCK
        assertTrue("try { tinted[i].layer.enabled = colored && !tintBroken } catch (e) {}" in block)
        assertTrue("onStatusChanged: if (status === ShaderEffect.Error) diplayAdasId.tintBroken = true" in block)
        assertTrue("diplayAdasId.colored = okHue && okSat && okBri" in block)
        // the layer is switched off by the timer or a scene change and never from a handler that runs inside the effect
        assertFalse("onTintBrokenChanged" in block || "onColoredChanged" in block || "onTintOnChanged" in block)
        // the color looks at its files less often while there is none, to keep the cluster's log quiet
        assertTrue("diplayAdasId.tick % (diplayAdasId.colored ? 4 : 8) === 0" in block)
    }

    @Test fun theColorFindsTheCarsOfBothScenesAndFollowsTheirComingAndGoing() {
        val block = BydClusterAdasQml.BLOCK
        assertTrue("indexOf(\"self_car\")" in block)
        assertTrue("onAdasUiChanged: { apply(); tintCars() }" in block)
        assertTrue("onAdas3r1vUiChanged: { apply(); tintCars() }" in block)
        assertTrue("if (diplayAdasId.colored && (!wasColored || diplayAdasId.tick % 4 === 0)) diplayAdasId.tintCars()" in block)
        assertTrue("else diplayAdasId.setTint()" in block)
    }

    @Test fun theShaderStatesAPrecisionForEverythingGlslEs100Needs() {
        val shader = shader()
        val declaration = Regex("""^\s*(float|int|vec[234]|ivec[234]|mat[234]|sampler2D)\b""")
        for (line in shader.lines().filter { it.isNotBlank() }) {
            if (line.trimStart().startsWith("uniform") || line.trimStart().startsWith("varying")) {
                assertTrue(line, Regex("""\b(lowp|mediump|highp)\b""").containsMatchIn(line))
            }
            assertFalse("no default precision in fragment shaders: $line", declaration.containsMatchIn(line))
        }
        assertTrue("gl_FragColor" in shader && "texture2D" in shader && "qt_Opacity" in shader)
        assertFalse("#version" in shader)
    }

    @Test fun theShaderIsTheOneThatWasCheckedOnTheGpuPathAndWithAGlslCompiler() {
        // Edit the shader only together with a new run of the offline checks (see docs/BYD_NAVIGATION.md), then update this.
        assertEquals("dadb14c4dde3f32359905caa1f299fcfccbcecd14ea25f73df0e5d49e89e4df6", BydClusterAdasQml.digest(shader().toByteArray()))
    }

    @Test fun anOptionalDumpOfThePatchedFileForTheOfflineQtChecks() {
        // DIPLAY_QML_STOCK is a car's own cluster.qml and DIPLAY_QML_PATCHED_OUT is where it is written with the block
        // added, so the offline Qt checks can load the result. CI leaves both unset: the car's file is BYD's and is not
        // kept in this repository.
        val stock = System.getenv("DIPLAY_QML_STOCK") ?: return
        val out = System.getenv("DIPLAY_QML_PATCHED_OUT") ?: return
        val result = BydClusterAdasQml.patch(File(stock).readBytes(), center.toByteArray()) as BydClusterAdasQml.Result.Patched
        File(out).writeBytes(result.qml)
    }

    /** The GLSL ES 1.00 source the block hands to the shader effect, rebuilt from its string literals. */
    private fun shader(): String {
        val start = BydClusterAdasQml.BLOCK.indexOf("property string tintShader:")
        val literals = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(
            BydClusterAdasQml.BLOCK.substring(start).lineSequence().drop(1).takeWhile { it.trimStart().startsWith("\"") }.joinToString("\n"),
        )
        return literals.joinToString("") { it.groupValues[1].replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\") }
    }

    private companion object {
        val IDS = listOf("adas2dNaviWindowBg", "centerContainerId")
    }
}
