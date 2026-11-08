package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.hud.BydClusterAdasTheme.Status
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BydClusterAdasThemeTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var themeFile: File
    private lateinit var languageFile: File
    private lateinit var translate: File
    private val pauses = mutableListOf<Long>()

    private val clusterQml = "\uFEFFimport QtQuick 2.12\r\nItem {\r\n    Image {\r\n        id: adas2dNaviWindowBg\r\n    }\r\n" +
        "    Center {\r\n        id:centerContainerId\r\n    }\r\n    //\u52a8\u6548 end\r\n}\r\n"
    private val centerQml = "Item {\n    property CenterChild adasUi: null\n    property CenterChild adas3r1vUi: null\n}\n"
    private val englishQm = ByteArray(300) { ((it % 7) + 65).toByte() }
    private val arabicQm = ByteArray(64) { ((it * 197 + 13) % 251).toByte() }

    private class FakeChannel(var active: BydClusterThemeChannel.Active?) : BydClusterThemeChannel {
        val calls = mutableListOf<String>()
        var confirm = true
        var followsSelection = true

        override fun active() = active

        override fun select(name: String?): Boolean {
            calls += "select ${name ?: "default"}"
            if (!confirm) return false
            if (followsSelection) {
                active = BydClusterThemeChannel.Active(2, if (name == null) DEFAULT else "$TRANSLATE/$name")
            }
            return true
        }

        override fun refresh(code: Int): Boolean {
            calls += "refresh $code"
            return true
        }
    }

    @Before fun files() {
        themeFile = temp.newFile("cluster_theme1.rcc").also { it.writeBytes(theme(clusterQml, centerQml)) }
        languageFile = temp.newFile("commonTranslate.rcc").also { it.writeBytes(languages()) }
        translate = File(temp.root, "translate")
    }

    private fun theme(cluster: String, center: String, extra: Boolean = true): ByteArray = QtResource.write(
        listOf(
            QtResource.item("/qml/cluster.qml", cluster.toByteArray(), 1L),
            QtResource.item("/qml/Center.qml", center.toByteArray(), 2L),
        ) + if (extra) listOf(QtResource.item("/images/night_bg.png", ByteArray(500) { (it % 5).toByte() })) else emptyList(),
        version = 3,
    )

    private fun languages(): ByteArray = QtResource.write(
        listOf(
            QtResource.item("/common/language/lang.en_US.qm", englishQm, 3L),
            QtResource.item("/common/language/lang.ar.qm", arabicQm, 4L),
            QtResource.item("/common/language/lang.en_US.ts", "<TS/>\n".repeat(50).toByteArray(), 5L),
        ),
    )

    private fun tool(channel: BydClusterThemeChannel) =
        BydClusterAdasTheme(themeFile, languageFile, translate, channel, { 1_700_000_000_000L }, { pauses += it })

    private fun car(path: String? = DEFAULT) = FakeChannel(path?.let { BydClusterThemeChannel.Active(0, it) })

    private fun installedName(channel: FakeChannel) = channel.calls.first { it.startsWith("select ") }.removePrefix("select ")

    @Test fun aProbeChangesNothingAndNamesTheResourceItWouldInstall() {
        val channel = car()
        val result = tool(channel).probe()
        assertEquals(Status.READY, result.status)
        assertEquals("NONE", result["state"])
        assertEquals("-", result["installed"])
        assertTrue(BydClusterAdasTheme.OWN_PACK.matches(result["expected"]!!))
        assertEquals(emptyList<String>(), channel.calls)
        assertFalse(translate.exists())
    }

    @Test fun theNameFollowsTheCarsOwnFilesAndIsOneTheClusterAccepts() {
        val first = tool(car()).probe()["expected"]!!
        assertEquals(first, tool(car()).probe()["expected"])
        assertTrue(Regex("^Di3F_DynastyOceanTranslate_V0001_Translate_[A-Za-z0-9]{1,48}\\.rcc$").matches(first))
        themeFile.writeBytes(theme(clusterQml.replace("end", "fin"), centerQml))
        assertNotEquals(first, tool(car()).probe()["expected"])
        themeFile.writeBytes(theme(clusterQml, centerQml.replace("adasUi", "adasUi /* */")))
        assertNotEquals(first, tool(car()).probe()["expected"])
    }

    @Test fun aProbeTellsWhichResourceIsInUse() {
        val expected = tool(car()).probe()["expected"]!!
        assertEquals("CURRENT", tool(car("$TRANSLATE/$expected")).probe()["state"])
        val older = BydClusterAdasTheme.packName("0123456789abcdef")
        assertEquals("STALE", tool(car("$TRANSLATE/$older")).probe()["state"])
        assertEquals("OTHER", tool(car("$TRANSLATE/${BydClusterAdasTheme.PACK_PREFIX}OtherApp12p10.rcc")).probe()["state"])
        assertEquals("UNKNOWN", tool(car(null)).probe()["state"])
    }

    @Test fun aProbeReportsWhyTheCarsFilesDoNotFit() {
        themeFile.writeBytes(theme(clusterQml.replace("adas2dNaviWindowBg", "other"), centerQml))
        assertEquals("missing-adas2dNaviWindowBg", tool(car()).probe()["code"])
        themeFile.writeBytes(theme(clusterQml, "Item {}"))
        assertEquals("missing-adasUi", tool(car()).probe()["code"])
        themeFile.writeBytes(QtResource.write(listOf(QtResource.item("/qml/Center.qml", centerQml.toByteArray()))))
        assertEquals("no-cluster-qml", tool(car()).probe()["code"])
        themeFile.writeBytes(QtResource.write(listOf(QtResource.item("/qml/cluster.qml", clusterQml.toByteArray()))))
        assertEquals("no-center-qml", tool(car()).probe()["code"])
        themeFile.writeBytes(theme(clusterQml, centerQml))
        languageFile.writeBytes(QtResource.write(listOf(QtResource.item("/common/language/readme.txt", ByteArray(10)))))
        assertEquals("no-languages", tool(car()).probe()["code"])
        languageFile.writeBytes(QtResource.write(listOf(
            QtResource.item("/common/language/lang.en_US.qm", englishQm),
            QtResource.item("/qml/cluster.qml", ByteArray(10)),
        )))
        assertEquals("language-has-qml", tool(car()).probe()["code"])
        languageFile.delete()
        assertEquals(Status.UNSUPPORTED, tool(car()).probe().status)
        assertEquals("files", tool(car()).probe()["code"])
    }

    @Test fun installingBuildsAFileWithTheCarsLanguagesAndThePatchedClusterQml() {
        val channel = car()
        val result = tool(channel).install(30)
        assertEquals(result.line(), Status.INSTALLED, result.status)
        val name = result["pack"]!!
        assertEquals(listOf("select $name", "refresh 30"), channel.calls)
        assertEquals(listOf(1_100L), pauses)
        assertEquals(listOf(name), translate.list()!!.toList())
        QtResource.open(QtResource.FileSource(File(translate, name))).use { pack ->
            assertEquals(2, pack.version)
            assertEquals(4, pack.entries.size)
            val qml = String(pack.content(pack.find("/qml/cluster.qml")!!), Charsets.UTF_8)
            assertTrue(qml.startsWith("\uFEFFimport QtQuick") && BydClusterAdasQml.MARKER in qml)
            assertTrue("//\u52a8\u6548 end" in qml)
            assertEquals(1_700_000_000_000L, pack.find("/qml/cluster.qml")!!.lastModified)
            QtResource.open(QtResource.FileSource(languageFile)).use { source ->
                for (entry in source.entries) {
                    val copy = pack.find(entry.path)!!
                    assertEquals(entry.flags, copy.flags)
                    assertEquals(entry.lastModified, copy.lastModified)
                    assertArrayEquals(source.stored(entry), pack.stored(copy))
                }
            }
        }
    }

    @Test fun anInstalledResourceIsLeftAloneTheSecondTime() {
        val channel = car()
        val name = tool(channel).install(30)["pack"]!!
        val before = File(translate, name).lastModified()
        channel.calls.clear()
        val again = tool(channel).install(30)
        assertEquals(Status.ALREADY, again.status)
        assertEquals(emptyList<String>(), channel.calls)
        assertEquals(before, File(translate, name).lastModified())
    }

    @Test fun anotherAppsResourceInUseIsNeverReplaced() {
        val other = "$TRANSLATE/${BydClusterAdasTheme.PACK_PREFIX}OtherApp12p10.rcc"
        val channel = car(other)
        val result = tool(channel).install(30)
        assertEquals(Status.OTHER_ACTIVE, result.status)
        assertEquals(other, result["path"])
        assertEquals(emptyList<String>(), channel.calls)
        assertFalse(translate.exists())
    }

    @Test fun aClusterThatDoesNotAnswerIsLeftAlone() {
        assertEquals(Status.NO_CLUSTER, tool(car(null)).install(30).status)
        assertEquals(Status.NO_CLUSTER, tool(car(null)).remove(30).status)
        assertFalse(translate.exists())
    }

    @Test fun aSelectionTheClusterDoesNotConfirmIsUndone() {
        val channel = car().apply { confirm = false }
        val result = tool(channel).install(30)
        assertEquals(Status.FAILED, result.status)
        assertEquals("select", result["code"])
        assertEquals(emptyList<String>(), translate.list()!!.toList())
        assertTrue("select default" in channel.calls && channel.calls.none { it.startsWith("refresh") })
    }

    @Test fun aSelectionThatDoesNotBecomeActiveIsUndone() {
        val channel = car().apply { followsSelection = false }
        val result = tool(channel).install(30)
        assertEquals(Status.FAILED, result.status)
        assertEquals(emptyList<String>(), translate.list()!!.toList())
        assertTrue(channel.calls.none { it.startsWith("refresh") })
    }

    @Test fun anOlderResourceOfDiPlayIsReplacedAndOtherFilesStay() {
        translate.mkdirs()
        val older = BydClusterAdasTheme.packName("0123456789abcdef")
        File(translate, older).writeBytes(ByteArray(10))
        val foreign = File(translate, "${BydClusterAdasTheme.PACK_PREFIX}OtherApp12p10.rcc").apply { writeBytes(ByteArray(10)) }
        val channel = car("$TRANSLATE/$older")
        val result = tool(channel).install(30)
        assertEquals(Status.INSTALLED, result.status)
        assertEquals(setOf(result["pack"], foreign.name), translate.list()!!.toSet())
    }

    @Test fun onlyTheScreenReloadCodeForTheTwelvePointThreeInchClusterIsAccepted() {
        for (code in listOf(0, 29, 31, 16, 18)) {
            val result = tool(car()).install(code)
            assertEquals("refresh-code", result["code"])
            assertEquals("refresh-code", tool(car()).remove(code)["code"])
        }
        assertFalse(translate.exists())
    }

    @Test fun removingGivesTheCarsResourceBackAndDeletesOnlyDiPlaysFiles() {
        val channel = car()
        val name = tool(channel).install(30)["pack"]!!
        val foreign = File(translate, "${BydClusterAdasTheme.PACK_PREFIX}OtherApp12p10.rcc").apply { writeBytes(ByteArray(10)) }
        File(translate, "diplay-adas.png").writeBytes(ByteArray(1))
        File(translate, "diplay-adas-p.png").writeBytes(ByteArray(1))
        File(translate, "other-app-mode.png").writeBytes(ByteArray(1))
        channel.calls.clear()
        pauses.clear()
        val result = tool(channel).remove(30)
        assertEquals(Status.REMOVED, result.status)
        assertEquals("true", result["reloaded"])
        assertEquals(listOf("select default", "refresh 30"), channel.calls)
        assertEquals(setOf(foreign.name, "other-app-mode.png"), translate.list()!!.toSet())
        assertFalse(File(translate, name).exists())
    }

    @Test fun removingAlsoClearsTheCarColorAndItsLeftovers() {
        val channel = car()
        tool(channel).install(30)
        assertTrue(BydClusterAdasColorFiles.write(translate, AdasCarColor(215, 80, 100)))
        File(translate, ".diplay-adas-cv.png.tmp").writeBytes(ByteArray(1))
        File(translate, "other-app-mode.png").writeBytes(ByteArray(1))
        tool(channel).remove(30)
        assertEquals(setOf("other-app-mode.png"), translate.list()!!.toSet())
        assertEquals(null, BydClusterAdasColorFiles.read(translate))
    }

    @Test fun installingLeavesTheCarColorAlone() {
        translate.mkdirs()
        assertTrue(BydClusterAdasColorFiles.write(translate, AdasCarColor(24, 88, 100)))
        assertEquals(Status.INSTALLED, tool(car()).install(30).status)
        assertEquals(AdasCarColor(24, 88, 100), BydClusterAdasColorFiles.read(translate))
    }

    @Test fun removingWhenTheCarsOwnResourceIsInUseOnlyTidiesUp() {
        translate.mkdirs()
        File(translate, "diplay-adas.png").writeBytes(ByteArray(1))
        val channel = car()
        val result = tool(channel).remove(30)
        assertEquals(Status.REMOVED, result.status)
        assertEquals("false", result["reloaded"])
        assertEquals(emptyList<String>(), channel.calls)
        assertEquals(emptyList<String>(), translate.list()!!.toList())
    }

    @Test fun removingNeverSwitchesAwayFromAnotherAppsResource() {
        translate.mkdirs()
        val own = BydClusterAdasTheme.packName("0123456789abcdef")
        File(translate, own).writeBytes(ByteArray(10))
        val channel = car("$TRANSLATE/${BydClusterAdasTheme.PACK_PREFIX}OtherApp12p10.rcc")
        val result = tool(channel).remove(30)
        assertEquals("false", result["reloaded"])
        assertEquals(emptyList<String>(), channel.calls)
        assertFalse(File(translate, own).exists())
    }

    @Test fun aFailedSwitchBackKeepsTheFilesAndSaysSo() {
        val channel = car()
        tool(channel).install(30)
        channel.confirm = false
        val result = tool(channel).remove(30)
        assertEquals(Status.FAILED, result.status)
        assertEquals(1, translate.list()!!.size)
    }

    @Test fun theResultIsOneLineOfKeysAndValues() {
        assertEquals("READY|state=NONE|pack=a_b", BydClusterAdasTheme.Result(Status.READY, mapOf("state" to "NONE", "pack" to "a|b")).line())
        assertEquals("NO_CLUSTER", BydClusterAdasTheme.Result(Status.NO_CLUSTER).line())
    }

    @Test fun theHelperIsStartedFromTheAppsOwnApkWithTheScreenCode() {
        assertEquals(
            "CLASSPATH='/data/app/com.x-1/base.apk' app_process /system/bin com.shilapi.xcertplay.hud.BydClusterAdasThemeMain install 30",
            BydClusterAdasThemeMain.command("/data/app/com.x-1/base.apk", "install"),
        )
        assertTrue(BydClusterAdasThemeMain.command("/a'b/base.apk", "probe").startsWith("CLASSPATH='/a'\"'\"'b/base.apk' "))
    }

    private companion object {
        const val DEFAULT = "/system/lib64/commonTranslate.rcc"
        const val TRANSLATE = "/data/cells/cell2-rw/data/media/0/.cluster/translate"
    }
}
