package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.hud.BydClusterAdasPlacement.Outcome
import com.shilapi.xcertplay.hud.BydClusterAdasPlacement.State
import com.shilapi.xcertplay.hud.BydClusterAdasTheme.Status
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BydClusterAdasPlacementTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val commands = mutableListOf<String>()
    private var helper: (String) -> String? = { null }
    private var now = 1_000_000L

    private fun line(status: Status, vararg fields: Pair<String, String>) =
        "${BydClusterAdasThemeMain.HEADER}|" + BydClusterAdasTheme.Result(status, mapOf(*fields)).line()

    private fun probeReply(state: String) = line(Status.READY, "state" to state, "expected" to "e", "installed" to "-", "digest" to "d")

    private fun helperCommands(action: String) = commands.filter { "BydClusterAdasThemeMain $action " in it }

    @Before fun setUp() {
        context.applicationInfo.sourceDir = "/data/app/com.test-1/base.apk"
        BydClusterAdasPlacement.resetForTest()
        BydClusterAdasPlacement.submit = { it() }
        BydClusterAdasPlacement.run = { _, command ->
            commands += command
            if ("BydClusterAdasThemeMain" in command) helper(command) else "ok"
        }
        BydClusterAdasPlacement.access = { LocalAdb.Access.READY }
        BydClusterAdasPlacement.parked = { true }
        BydClusterAdasPlacement.mapShown = { true }
        BydClusterAdasPlacement.nowMillis = { now }
        BydOutputSettings.setAdasSceneBesideMap(context, false)
        BydOutputSettings.setAdasPackInstalled(context, false)
        BydOutputSettings.setAdasPackRetired(context, false)
    }

    @After fun tearDown() {
        BydOutputSettings.setAdasSceneBesideMap(context, false)
        BydOutputSettings.setAdasPackInstalled(context, false)
        BydOutputSettings.setAdasPackRetired(context, false)
        BydClusterAdasPlacement.resetForTest()
    }

    @Test fun theSettingIsOffByDefaultAndSaved() {
        assertFalse(BydOutputSettings.adasSceneBesideMap(context))
        BydOutputSettings.setAdasSceneBesideMap(context, true)
        assertTrue(BydOutputSettings.adasSceneBesideMap(context))
    }

    @Test fun aProbeRunsTheHelperFromTheAppsOwnApkAndReadsWhatIsInstalled() {
        helper = { probeReply("CURRENT") }
        BydClusterAdasPlacement.refresh(context)
        assertEquals(State.CURRENT, BydClusterAdasPlacement.status.state)
        val command = helperCommands("probe").single()
        assertTrue(command, command.startsWith("CLASSPATH='") && command.endsWith(" probe 30"))
    }

    @Test fun everyStateTheHelperReportsIsMapped() {
        val states = mapOf("NONE" to State.NOT_INSTALLED, "CURRENT" to State.CURRENT, "STALE" to State.STALE, "OTHER" to State.OTHER, "UNKNOWN" to State.UNKNOWN)
        for ((reported, expected) in states) {
            helper = { probeReply(reported) }
            BydClusterAdasPlacement.refresh(context)
            assertEquals(reported, expected, BydClusterAdasPlacement.status.state)
        }
        helper = { line(Status.UNSUPPORTED, "code" to "missing-adasUi") }
        BydClusterAdasPlacement.refresh(context)
        assertEquals(State.UNSUPPORTED, BydClusterAdasPlacement.status.state)
        assertEquals("missing-adasUi", BydClusterAdasPlacement.status.code)
        helper = { line(Status.NO_CLUSTER) }
        BydClusterAdasPlacement.refresh(context)
        assertEquals(State.NO_CLUSTER, BydClusterAdasPlacement.status.state)
    }

    @Test fun noAnswerIsBlamedOnAdbOnlyWhenTheLinkIsDown() {
        helper = { null }
        BydClusterAdasPlacement.access = { LocalAdb.Access.NOT_APPROVED }
        BydClusterAdasPlacement.refresh(context)
        assertEquals(State.NO_ADB, BydClusterAdasPlacement.status.state)
        BydClusterAdasPlacement.access = { LocalAdb.Access.READY }
        BydClusterAdasPlacement.refresh(context)
        assertEquals(State.UNKNOWN, BydClusterAdasPlacement.status.state)
    }

    @Test fun installingWaitsForAParkedCarAndNeverRunsTheHelperOtherwise() {
        var outcome: Outcome? = null
        BydClusterAdasPlacement.parked = { false }
        BydClusterAdasPlacement.install(context) { outcome = it }
        assertEquals(Outcome.NOT_PARKED, outcome)
        BydClusterAdasPlacement.parked = { null }
        BydClusterAdasPlacement.install(context) { outcome = it }
        assertEquals(Outcome.GEAR_UNKNOWN, outcome)
        BydClusterAdasPlacement.remove(context) { outcome = it }
        assertEquals(Outcome.GEAR_UNKNOWN, outcome)
        assertEquals(emptyList<String>(), commands)
    }

    @Test fun aSuccessfulInstallIsReportedAndTheStateFollows() {
        helper = { line(Status.INSTALLED, "pack" to "p") }
        var outcome: Outcome? = null
        val changes = mutableListOf<Boolean>()
        BydClusterAdasPlacement.onChange = { changes += BydClusterAdasPlacement.busy }
        BydClusterAdasPlacement.install(context) { outcome = it }
        assertEquals(Outcome.INSTALLED, outcome)
        assertEquals(State.CURRENT, BydClusterAdasPlacement.status.state)
        assertTrue(helperCommands("install").single().endsWith(" install 30"))
        assertEquals(listOf(true, true, false), changes)
        assertFalse(BydClusterAdasPlacement.busy)
    }

    @Test fun anInstallThatIsAlreadyThereChangesNothing() {
        helper = { line(Status.ALREADY, "pack" to "p") }
        var outcome: Outcome? = null
        BydClusterAdasPlacement.install(context) { outcome = it }
        assertEquals(Outcome.ALREADY, outcome)
        assertEquals(State.CURRENT, BydClusterAdasPlacement.status.state)
    }

    @Test fun anotherAppsThemeAndUnfitFilesAreReportedWithTheirReason() {
        var outcome: Outcome? = null
        helper = { line(Status.OTHER_ACTIVE, "path" to "/x/Other.rcc") }
        BydClusterAdasPlacement.install(context) { outcome = it }
        assertEquals(Outcome.OTHER_ACTIVE, outcome)
        assertEquals(State.OTHER, BydClusterAdasPlacement.status.state)
        helper = { line(Status.UNSUPPORTED, "code" to "no-cluster-qml") }
        BydClusterAdasPlacement.install(context) { outcome = it }
        assertEquals(Outcome.UNSUPPORTED, outcome)
        assertEquals("no-cluster-qml", BydClusterAdasPlacement.status.code)
    }

    @Test fun aFailedInstallLooksAgainAtWhatTheCarHas() {
        helper = { command -> if (" install " in command) line(Status.FAILED, "code" to "select") else probeReply("NONE") }
        var outcome: Outcome? = null
        BydClusterAdasPlacement.install(context) { outcome = it }
        assertEquals(Outcome.FAILED, outcome)
        assertEquals(State.NOT_INSTALLED, BydClusterAdasPlacement.status.state)
        assertEquals(1, helperCommands("probe").size)
    }

    @Test fun aHelperThatDoesNotAnswerIsAFailureOrAnAdbProblem() {
        helper = { null }
        var outcome: Outcome? = null
        BydClusterAdasPlacement.install(context) { outcome = it }
        assertEquals(Outcome.FAILED, outcome)
        BydClusterAdasPlacement.access = { LocalAdb.Access.UNREACHABLE }
        BydClusterAdasPlacement.install(context) { outcome = it }
        assertEquals(Outcome.NO_ADB, outcome)
    }

    @Test fun aSecondChangeWhileOneIsRunningIsRefused() {
        var inner: Outcome? = null
        helper = {
            BydClusterAdasPlacement.remove(context) { inner = it }
            line(Status.INSTALLED, "pack" to "p")
        }
        BydClusterAdasPlacement.install(context) {}
        assertEquals(Outcome.BUSY, inner)
        assertEquals(1, helperCommands("install").size)
        assertEquals(0, helperCommands("remove").size)
    }

    @Test fun removingStopsTheHeartbeatFirstAndThenReloadsTheCluster() {
        BydOutputSettings.setAdasSceneBesideMap(context, true)
        helper = { command -> if (" probe " in command) probeReply("CURRENT") else line(Status.REMOVED, "reloaded" to "true") }
        BydClusterAdasPlacement.refresh(context)
        BydClusterAdasPlacement.tick(context)
        assertTrue(commands.any { it.startsWith("[ -f /data/local/tmp/diplay-adas-beat-1.sh ]") })
        commands.clear()
        var outcome: Outcome? = null
        BydClusterAdasPlacement.remove(context) { outcome = it }
        assertEquals(Outcome.REMOVED, outcome)
        assertEquals(State.NOT_INSTALLED, BydClusterAdasPlacement.status.state)
        assertEquals(BydClusterAdasBeat.stop(), commands.first())
        assertTrue(helperCommands("remove").single().endsWith(" remove 30"))
    }

    @Test fun theHeartbeatRunsOnlyWithTheSettingOnTheMapOnTheClusterAndTheThemeInstalled() {
        helper = { probeReply("CURRENT") }
        BydClusterAdasPlacement.tick(context)
        assertEquals(emptyList<String>(), commands)

        BydOutputSettings.setAdasSceneBesideMap(context, true)
        BydClusterAdasPlacement.mapShown = { false }
        BydClusterAdasPlacement.tick(context)
        assertEquals(emptyList<String>(), commands)

        BydClusterAdasPlacement.mapShown = { true }
        BydClusterAdasPlacement.tick(context)
        assertEquals(1, helperCommands("probe").size)
        assertEquals(BydClusterAdasBeat.keepAlive(), commands.last())

        commands.clear()
        BydClusterAdasPlacement.tick(context)
        assertEquals(listOf(BydClusterAdasBeat.keepAlive()), commands)

        commands.clear()
        BydClusterAdasPlacement.mapShown = { false }
        BydClusterAdasPlacement.tick(context)
        BydClusterAdasPlacement.tick(context)
        assertEquals(listOf(BydClusterAdasBeat.stop()), commands)
    }

    @Test fun nothingIsBeatenForAThemeThatIsNotInstalledOrOutOfDate() {
        BydOutputSettings.setAdasSceneBesideMap(context, true)
        for (state in listOf("NONE", "STALE", "OTHER")) {
            BydClusterAdasPlacement.resetForTest()
            BydClusterAdasPlacement.submit = { it() }
            BydClusterAdasPlacement.run = { _, command -> commands += command; probeReply(state) }
            BydClusterAdasPlacement.mapShown = { true }
            commands.clear()
            BydClusterAdasPlacement.tick(context)
            assertTrue(state, commands.none { "diplay-adas-beat" in it })
        }
    }

    @Test fun anUnansweredProbeIsNotRepeatedEverySecond() {
        BydOutputSettings.setAdasSceneBesideMap(context, true)
        helper = { null }
        BydClusterAdasPlacement.tick(context)
        BydClusterAdasPlacement.tick(context)
        assertEquals(1, helperCommands("probe").size)
        now += 61_000L
        BydClusterAdasPlacement.tick(context)
        assertEquals(2, helperCommands("probe").size)
        assertNull(commands.firstOrNull { "diplay-adas-beat" in it })
    }

    @Test fun switchingTheSettingOffStopsTheHeartbeatWithoutWaitingForTheTick() {
        BydOutputSettings.setAdasSceneBesideMap(context, true)
        helper = { probeReply("CURRENT") }
        BydClusterAdasPlacement.tick(context)
        commands.clear()
        BydOutputSettings.setAdasSceneBesideMap(context, false)
        BydClusterAdasPlacement.settingChanged(context)
        assertEquals(listOf(BydClusterAdasBeat.stop()), commands)
    }

    @Test fun aProbeRemembersWhetherDiPlaysFileIsInTheCluster() {
        helper = { probeReply("CURRENT") }
        BydClusterAdasPlacement.refresh(context)
        assertTrue(BydOutputSettings.adasPackInstalled(context))
        helper = { probeReply("NONE") }
        BydClusterAdasPlacement.refresh(context)
        assertFalse(BydOutputSettings.adasPackInstalled(context))
        helper = { probeReply("STALE") }
        BydClusterAdasPlacement.refresh(context)
        assertTrue(BydOutputSettings.adasPackInstalled(context))
        helper = { probeReply("OTHER") }
        BydClusterAdasPlacement.refresh(context)
        assertTrue(BydOutputSettings.adasPackInstalled(context))
    }

    @Test fun installingAndRemovingKeepTheRecordAndClearTheNotice() {
        BydOutputSettings.setAdasPackRetired(context, true)
        helper = { line(Status.INSTALLED, "pack" to "p") }
        BydClusterAdasPlacement.install(context) {}
        assertTrue(BydOutputSettings.adasPackInstalled(context))
        assertFalse(BydOutputSettings.adasPackRetired(context))
        BydOutputSettings.setAdasPackRetired(context, true)
        helper = { line(Status.REMOVED, "reloaded" to "true") }
        BydClusterAdasPlacement.remove(context) {}
        assertFalse(BydOutputSettings.adasPackInstalled(context))
        assertFalse(BydOutputSettings.adasPackRetired(context))
    }

    @Test fun nothingIsAskedAtStartUnlessDiPlayInstalledAFile() {
        helper = { probeReply("STALE") }
        BydClusterAdasPlacement.tick(context)
        assertEquals(emptyList<String>(), commands)
    }

    @Test fun aStaleFileIsGivenUpAtStartWhateverTheSwitchSaysAndTheClusterIsReloadedInP() {
        BydOutputSettings.setAdasPackInstalled(context, true)
        assertFalse(BydOutputSettings.adasSceneBesideMap(context))
        BydClusterAdasPlacement.mapShown = { false }
        helper = { command -> if (" probe " in command) probeReply("STALE") else line(Status.REMOVED, "reloaded" to "true") }
        BydClusterAdasPlacement.tick(context)
        assertEquals(1, helperCommands("probe").size)
        assertTrue(helperCommands("remove").single().endsWith(" remove 30"))
        assertEquals(0, helperCommands("retire").size)
        assertEquals(State.NOT_INSTALLED, BydClusterAdasPlacement.status.state)
        assertFalse(BydOutputSettings.adasPackInstalled(context))
        assertFalse(BydOutputSettings.adasSceneBesideMap(context))
        assertTrue(BydOutputSettings.adasPackRetired(context))
    }

    @Test fun aStaleFileIsRetiredWithoutAReloadWhileTheCarIsNotInPOrTheGearIsUnknown() {
        for (gear in listOf(false, null)) {
            BydClusterAdasPlacement.resetForTest()
            BydClusterAdasPlacement.submit = { it() }
            BydClusterAdasPlacement.access = { LocalAdb.Access.READY }
            BydClusterAdasPlacement.mapShown = { true }
            BydClusterAdasPlacement.nowMillis = { now }
            BydClusterAdasPlacement.run = { _, command ->
                commands += command
                if ("BydClusterAdasThemeMain" in command) helper(command) else "ok"
            }
            BydClusterAdasPlacement.parked = { gear }
            BydOutputSettings.setAdasPackInstalled(context, true)
            BydOutputSettings.setAdasSceneBesideMap(context, true)
            BydOutputSettings.setAdasPackRetired(context, false)
            commands.clear()
            helper = { command -> if (" probe " in command) probeReply("STALE") else line(Status.REMOVED, "reloaded" to "false") }
            BydClusterAdasPlacement.tick(context)
            assertTrue("gear=$gear", helperCommands("retire").single().endsWith(" retire 30"))
            assertEquals("gear=$gear", 0, helperCommands("remove").size)
            assertFalse(BydOutputSettings.adasPackInstalled(context))
            assertFalse(BydOutputSettings.adasSceneBesideMap(context))
            assertTrue(BydOutputSettings.adasPackRetired(context))
        }
    }

    @Test fun aStaleFileWhoseCarFilesNoLongerFitIsStillGivenUp() {
        BydOutputSettings.setAdasPackInstalled(context, true)
        helper = { command ->
            if (" probe " in command) line(Status.UNSUPPORTED, "code" to "missing-adasUi", "state" to "STALE")
            else line(Status.REMOVED, "reloaded" to "true")
        }
        BydClusterAdasPlacement.tick(context)
        assertEquals(1, helperCommands("remove").size)
        assertTrue(BydOutputSettings.adasPackRetired(context))
    }

    @Test fun anUnsupportedClusterIsStaleOnlyWhileAFileOfDiPlaysIsInUse() {
        helper = { line(Status.UNSUPPORTED, "code" to "missing-adasUi", "state" to "NONE") }
        BydClusterAdasPlacement.refresh(context)
        assertEquals(State.UNSUPPORTED, BydClusterAdasPlacement.status.state)
        helper = { line(Status.UNSUPPORTED, "code" to "missing-adasUi", "state" to "STALE") }
        BydClusterAdasPlacement.refresh(context)
        assertEquals(State.STALE, BydClusterAdasPlacement.status.state)
        assertEquals("missing-adasUi", BydClusterAdasPlacement.status.code)
    }

    @Test fun aFileThatStillFitsIsLeftAloneAndCheckedOnlyOnce() {
        BydOutputSettings.setAdasPackInstalled(context, true)
        helper = { probeReply("CURRENT") }
        BydClusterAdasPlacement.tick(context)
        BydClusterAdasPlacement.tick(context)
        now += 61_000L
        BydClusterAdasPlacement.tick(context)
        assertEquals(1, helperCommands("probe").size)
        assertEquals(0, helperCommands("remove").size + helperCommands("retire").size)
        assertTrue(BydOutputSettings.adasPackInstalled(context))
        assertFalse(BydOutputSettings.adasPackRetired(context))
    }

    @Test fun aCheckThatGetsNoAnswerIsRepeatedAfterAMinuteAndThenStops() {
        BydOutputSettings.setAdasPackInstalled(context, true)
        helper = { null }
        BydClusterAdasPlacement.tick(context)
        BydClusterAdasPlacement.tick(context)
        assertEquals(1, helperCommands("probe").size)
        now += 61_000L
        helper = { probeReply("CURRENT") }
        BydClusterAdasPlacement.tick(context)
        assertEquals(2, helperCommands("probe").size)
        now += 61_000L
        BydClusterAdasPlacement.tick(context)
        assertEquals(2, helperCommands("probe").size)
    }

    @Test fun aFileThatCouldNotBeGivenUpIsNotTriedAgainInTheSameRun() {
        BydOutputSettings.setAdasPackInstalled(context, true)
        helper = { command -> if (" probe " in command) probeReply("STALE") else line(Status.FAILED, "code" to "select") }
        BydClusterAdasPlacement.tick(context)
        now += 61_000L
        BydClusterAdasPlacement.tick(context)
        assertEquals(1, helperCommands("remove").size)
        assertTrue(BydOutputSettings.adasPackInstalled(context))
        assertFalse(BydOutputSettings.adasPackRetired(context))
    }
}
