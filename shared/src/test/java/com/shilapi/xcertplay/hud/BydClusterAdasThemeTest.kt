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

    /** Stand-ins for the car's scene files, in the order of [BydClusterAdasQml.SCENE_FILES]; the real ones are BYD's. */
    private val scenes = listOf(
        "import QtQuick 2.15\nItem { Image { source: \"car.png\" } }\n",
        "import QtQuick 2.0\nItem { Image { source: \"door.png\" } }\n",
        "import QtQuick 2.15\nItem { Image { source: \"lanes.png\" } }\n",
    )
    private val sampled = setOf(BydClusterAdasQml.scenesDigest(scenes.map { it.toByteArray() }))
    private val englishQm = ByteArray(300) { ((it % 7) + 65).toByte() }
    private val arabicQm = ByteArray(64) { ((it * 197 + 13) % 251).toByte() }

    /** A cluster that can be told to lose a reply, to refuse the car's own resource, or to stop answering. */
    private class FakeChannel(var active: BydClusterThemeChannel.Active?) : BydClusterThemeChannel {
        val calls = mutableListOf<String>()
        var confirm = true
        var followsSelection = true

        /** A selection takes effect but its reply is lost, so [select] answers false. */
        var replyLost = false

        /** The cluster does not take the car's own resource back. */
        var refusesDefault = false

        /** The cluster stops answering questions once it was asked to select something. */
        var silentAfterSelect = false
        private var asked = false

        override fun active() = if (silentAfterSelect && asked) null else active

        override fun select(name: String?): Boolean {
            calls += "select ${name ?: "default"}"
            asked = true
            if (name == null && refusesDefault) return false
            if (replyLost) {
                apply(name)
                return false
            }
            if (!confirm) return false
            if (followsSelection) apply(name)
            return true
        }

        private fun apply(name: String?) {
            active = BydClusterThemeChannel.Active(2, if (name == null) DEFAULT else "$TRANSLATE/$name")
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

    private fun theme(
        cluster: String,
        center: String,
        extra: Boolean = true,
        sceneFiles: List<String> = scenes,
    ): ByteArray = QtResource.write(
        listOf(
            QtResource.item("/qml/cluster.qml", cluster.toByteArray(), 1L),
            QtResource.item("/qml/Center.qml", center.toByteArray(), 2L),
        ) + BydClusterAdasQml.SCENE_FILES.zip(sceneFiles).map { (path, text) -> QtResource.item(path, text.toByteArray(), 6L) } +
            if (extra) listOf(QtResource.item("/images/night_bg.png", ByteArray(500) { (it % 5).toByte() })) else emptyList(),
        version = 3,
    )

    private fun languages(): ByteArray = QtResource.write(
        listOf(
            QtResource.item("/common/language/lang.en_US.qm", englishQm, 3L),
            QtResource.item("/common/language/lang.ar.qm", arabicQm, 4L),
            QtResource.item("/common/language/lang.en_US.ts", "<TS/>\n".repeat(50).toByteArray(), 5L),
        ),
    )

    private fun tool(channel: BydClusterThemeChannel, sampledScenes: Set<String> = sampled) =
        BydClusterAdasTheme(themeFile, languageFile, translate, channel, { 1_700_000_000_000L }, { pauses += it }, sampledScenes)

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
        // A selected file that is not in the folder is not "in use", whatever its name says.
        assertEquals("STALE", tool(car("$TRANSLATE/$expected")).probe()["state"])
        translate.mkdirs()
        File(translate, expected).writeBytes(ByteArray(10))
        assertEquals("CURRENT", tool(car("$TRANSLATE/$expected")).probe()["state"])
        val older = BydClusterAdasTheme.packName("0123456789abcdef")
        assertEquals("STALE", tool(car("$TRANSLATE/$older")).probe()["state"])
        assertEquals("OTHER", tool(car("$TRANSLATE/$OTHER_PACK")).probe()["state"])
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

    @Test fun aProbeRefusesAFileThatAlreadyMovesTheScenes() {
        themeFile.writeBytes(theme(clusterQml, centerQml.replace("}\n", "    transform: []\n}\n")))
        assertEquals("transform-used", tool(car()).probe()["code"])
    }

    @Test fun scenesNobodyHasSampledAreRefusedWithACodeOfTheirOwn() {
        val unsampled = tool(car(), sampledScenes = setOf("0".repeat(64)))
        val probe = unsampled.probe()
        assertEquals(Status.UNSUPPORTED, probe.status)
        assertEquals("scenes-unchecked", probe["code"])
        assertEquals("NONE", probe["state"])
        val install = unsampled.install(30)
        assertEquals(Status.UNSUPPORTED, install.status)
        assertEquals("scenes-unchecked", install["code"])
        assertFalse(translate.exists())
    }

    @Test fun everyOneOfTheSceneFilesCountsAndTheOrderDoesNot() {
        for (index in scenes.indices) {
            val changed = scenes.toMutableList().also { it[index] += "// changed\n" }
            themeFile.writeBytes(theme(clusterQml, centerQml, sceneFiles = changed))
            assertEquals(BydClusterAdasQml.SCENE_FILES[index], "scenes-unchecked", tool(car()).probe()["code"])
        }
        // The files are looked up by name, so the order they sit in the resource does not matter.
        themeFile.writeBytes(QtResource.write(
            BydClusterAdasQml.SCENE_FILES.zip(scenes).reversed().map { (path, text) -> QtResource.item(path, text.toByteArray()) } +
                listOf(QtResource.item("/qml/Center.qml", centerQml.toByteArray()), QtResource.item("/qml/cluster.qml", clusterQml.toByteArray())),
        ))
        assertEquals(Status.READY, tool(car()).probe().status)
    }

    @Test fun aMissingSceneFileIsRefused() {
        for (path in BydClusterAdasQml.SCENE_FILES) {
            themeFile.writeBytes(QtResource.write(
                listOf(QtResource.item("/qml/cluster.qml", clusterQml.toByteArray()), QtResource.item("/qml/Center.qml", centerQml.toByteArray())) +
                    BydClusterAdasQml.SCENE_FILES.zip(scenes).filter { it.first != path }.map { (p, text) -> QtResource.item(p, text.toByteArray()) },
            ))
            assertEquals(path, "no-scenes", tool(car()).probe()["code"])
        }
    }

    @Test fun aSceneThatSetsTransformIsRefusedWhateverIsSampled() {
        for (index in scenes.indices) {
            val changed = scenes.toMutableList().also { it[index] += "Item { transform: [] }\n" }
            themeFile.writeBytes(theme(clusterQml, centerQml, sceneFiles = changed))
            val sampledWithIt = setOf(BydClusterAdasQml.scenesDigest(changed.map { it.toByteArray() }))
            assertEquals("transform-used", tool(car(), sampledWithIt).probe()["code"])
            assertEquals("transform-used", tool(car(), sampledWithIt).install(30)["code"])
        }
    }

    @Test fun aSceneUpdateMakesAnInstalledFileStaleAndStopsAnyNewInstall() {
        val channel = car()
        val name = tool(channel).install(30)["pack"]!!
        themeFile.writeBytes(theme(clusterQml, centerQml, sceneFiles = scenes.map { "$it// new\n" }))
        val probe = tool(car("$TRANSLATE/$name")).probe()
        assertEquals(Status.UNSUPPORTED, probe.status)
        assertEquals("scenes-unchecked", probe["code"])
        assertEquals("STALE", probe["state"])
        assertEquals(Status.UNSUPPORTED, tool(channel).install(30).status)
    }

    @Test fun aSelectedFileThatIsNoLongerInTheFolderIsStale() {
        val channel = car()
        val name = tool(channel).install(30)["pack"]!!
        assertEquals("CURRENT", tool(channel).probe()["state"])
        File(translate, name).delete()
        assertEquals("STALE", tool(channel).probe()["state"])
        // Putting it back is an install, which writes the missing file again instead of calling it installed already.
        channel.calls.clear()
        assertEquals(Status.INSTALLED, tool(channel).install(30).status)
        assertTrue(File(translate, name).isFile)
        assertEquals("CURRENT", tool(channel).probe()["state"])
    }

    @Test fun removingASelectedFileThatIsMissingStillGivesTheCarsOwnBack() {
        val channel = car()
        val name = tool(channel).install(30)["pack"]!!
        File(translate, name).delete()
        channel.calls.clear()
        val result = tool(channel).remove(30)
        assertEquals(Status.REMOVED, result.status)
        assertEquals(listOf("select default", "refresh 30"), channel.calls)
        assertEquals(DEFAULT, channel.active!!.path)
    }

    @Test fun aNameWithMoreThanOneRevisionDigitIsStillOneOfDiPlays() {
        val prefix = BydClusterAdasTheme.PACK_PREFIX
        for (name in listOf("DiPlay1a1b2c3d4", "DiPlay3381b36ef", "DiPlay10a1b2c3d4", "DiPlay123a1b2c3d4")) {
            assertTrue(name, BydClusterAdasTheme.OWN_PACK.matches("$prefix$name.rcc"))
        }
        for (name in listOf("DiPlaya1b2c3d4", "DiPlay1a1b2c3d", "DiPlay19p10", "Other1a1b2c3d4", "DiPlay1a1b2c3d4x")) {
            assertFalse(name, BydClusterAdasTheme.OWN_PACK.matches("$prefix$name.rcc"))
        }
        assertTrue(BydClusterAdasTheme.OWN_PACK.matches(BydClusterAdasTheme.packName("0123456789abcdef")))
    }

    @Test fun theNameAProbeReportsIsTheOneAnInstallWrites() {
        val probed = tool(car()).probe()["expected"]!!
        assertEquals(probed, tool(car()).install(30)["pack"])
    }

    @Test fun aFileOfDiPlaysInUseStaysStaleEvenWhenTheCarsFilesNoLongerFitIt() {
        val name = tool(car()).install(30)["pack"]!!
        themeFile.writeBytes(theme(clusterQml.replace("adas2dNaviWindowBg", "other"), centerQml))
        val stale = tool(car("$TRANSLATE/$name")).probe()
        assertEquals(Status.UNSUPPORTED, stale.status)
        assertEquals("missing-adas2dNaviWindowBg", stale["code"])
        assertEquals("STALE", stale["state"])
        assertEquals("NONE", tool(car()).probe()["state"])
        assertEquals("OTHER", tool(car("$TRANSLATE/$OTHER_PACK")).probe()["state"])
        assertEquals("UNKNOWN", tool(car(null)).probe()["state"])
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
        val other = "$TRANSLATE/$OTHER_PACK"
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

    @Test fun aSelectionWhoseReplyWasLostIsUndoneAndTheFileGoesOnceTheCarsOwnIsBack() {
        val channel = car().apply { replyLost = true }
        val result = tool(channel).install(30)
        assertEquals(Status.FAILED, result.status)
        assertEquals("select", result["code"])
        assertEquals(DEFAULT, channel.active!!.path)
        assertEquals(emptyList<String>(), translate.list()!!.toList())
        assertEquals(2, channel.calls.size)
        assertEquals("select default", channel.calls.last())
    }

    @Test fun aFileTheClusterMayStillSelectIsNeverDeleted() {
        // The first selection took effect, only its reply was lost, and the cluster will not take the car's own back.
        val channel = car().apply { replyLost = true; refusesDefault = true }
        val result = tool(channel).install(30)
        assertEquals(Status.FAILED, result.status)
        assertEquals("rollback", result["code"])
        val name = installedName(channel)
        assertEquals("$TRANSLATE/$name", channel.active!!.path)
        assertTrue(File(translate, name).isFile)
        assertTrue(channel.calls.none { it.startsWith("refresh") })
    }

    @Test fun aClusterThatStopsAnsweringAfterTheSelectionKeepsTheFile() {
        val channel = car().apply { replyLost = true; silentAfterSelect = true }
        val result = tool(channel).install(30)
        assertEquals(Status.FAILED, result.status)
        assertEquals("rollback", result["code"])
        assertTrue(File(translate, installedName(channel)).isFile)
    }

    @Test fun aFailedInstallNeverLeavesTheClusterSelectingAFileThatIsGone() {
        for (confirm in listOf(true, false)) for (follows in listOf(true, false)) for (lost in listOf(true, false)) {
            for (refuses in listOf(true, false)) for (silent in listOf(true, false)) {
                val channel = car().apply {
                    this.confirm = confirm
                    followsSelection = follows
                    replyLost = lost
                    refusesDefault = refuses
                    silentAfterSelect = silent
                }
                translate.deleteRecursively()
                tool(channel).install(30)
                val selected = channel.active?.path?.substringAfterLast('/')?.takeIf { BydClusterAdasTheme.OWN_PACK.matches(it) }
                val label = "confirm=$confirm follows=$follows lost=$lost refuses=$refuses silent=$silent"
                if (selected != null) assertTrue(label, File(translate, selected).isFile)
            }
        }
    }

    @Test fun aReplacedOlderFileStaysWhenTheNewSelectionFails() {
        translate.mkdirs()
        val older = BydClusterAdasTheme.packName("0123456789abcdef")
        File(translate, older).writeBytes(ByteArray(10))
        val channel = car("$TRANSLATE/$older").apply { replyLost = true }
        val result = tool(channel).install(30)
        assertEquals("select", result["code"])
        assertTrue(File(translate, older).isFile)
        assertEquals(setOf(older), translate.list()!!.toSet())
    }

    @Test fun anOlderResourceOfDiPlayIsReplacedAndOtherFilesStay() {
        translate.mkdirs()
        val older = BydClusterAdasTheme.packName("0123456789abcdef")
        File(translate, older).writeBytes(ByteArray(10))
        val foreign = File(translate, OTHER_PACK).apply { writeBytes(ByteArray(10)) }
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
        val foreign = File(translate, OTHER_PACK).apply { writeBytes(ByteArray(10)) }
        File(translate, "diplay-adas.png").writeBytes(ByteArray(1))
        File(translate, "diplay-adas-p.png").writeBytes(ByteArray(1))
        File(translate, "other-modo.png").writeBytes(ByteArray(1))
        channel.calls.clear()
        pauses.clear()
        val result = tool(channel).remove(30)
        assertEquals(Status.REMOVED, result.status)
        assertEquals("true", result["reloaded"])
        assertEquals(listOf("select default", "refresh 30"), channel.calls)
        assertEquals(setOf(foreign.name, "other-modo.png"), translate.list()!!.toSet())
        assertFalse(File(translate, name).exists())
    }

    @Test fun removingWithoutAReloadSelectsTheCarsResourceAndLeavesTheClusterRunning() {
        val channel = car()
        val name = tool(channel).install(30)["pack"]!!
        File(translate, "diplay-adas.png").writeBytes(ByteArray(1))
        channel.calls.clear()
        pauses.clear()
        val result = tool(channel).remove(30, reload = false)
        assertEquals(Status.REMOVED, result.status)
        assertEquals("false", result["reloaded"])
        assertEquals(listOf("select default"), channel.calls)
        assertEquals(emptyList<Long>(), pauses)
        assertFalse(File(translate, name).exists())
        assertFalse(File(translate, "diplay-adas.png").exists())
    }

    @Test fun aFileIsNotDeletedWhileTheClusterStillSelectsIt() {
        val channel = car()
        val name = tool(channel).install(30)["pack"]!!
        channel.confirm = false
        val result = tool(channel).remove(30, reload = false)
        assertEquals(Status.FAILED, result.status)
        assertTrue(File(translate, name).isFile)
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
        val channel = car("$TRANSLATE/$OTHER_PACK")
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
        assertTrue(BydClusterAdasThemeMain.command("/x/base.apk", BydClusterAdasThemeMain.RETIRE).endsWith(" retire 30"))
    }

    private companion object {
        const val DEFAULT = "/system/lib64/commonTranslate.rcc"
        const val TRANSLATE = "/data/cells/cell2-rw/data/media/0/.cluster/translate"

        /** Another app's resource file in the translate folder, named the way the cluster accepts. */
        const val OTHER_PACK = BydClusterAdasTheme.PACK_PREFIX + "Other12p10.rcc"
    }
}
