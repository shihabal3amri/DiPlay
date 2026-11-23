package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.shilapi.xcertplay.WheelKeyServiceReset.Outcome
import com.shilapi.xcertplay.WheelKeyServiceReset.Record
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Restarting DiPlay for a wheel key service that Android does not start again (#1355). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class WheelKeyServiceResetTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("wheel_key_reset", Context.MODE_PRIVATE)
    private val commands = mutableListOf<String>()
    private var exit = 0
    private var listed = true

    @Before fun setUp() {
        prefs.edit().clear().commit()
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
        WheelKeyServiceReset.resetForTest()
        WheelKeyLog.reset()
    }

    @After fun tearDown() {
        WheelKeyServiceReset.resetForTest()
        WheelKeyLog.reset()
    }

    private fun shell(command: String): String? {
        commands += command
        if ("dumpsys accessibility" in command) {
            val ours = android.content.ComponentName(context, WheelKeyService::class.java).flattenToString()
            return "User state[attributes:{id=0, currentUser=true}\n     Bound services:{}\n     Enabled services:{{$ours}}\n" +
                "     Binding services:{${if (listed) "{$ours}" else ""}}\n     Crashed services:{}\n\nDIPLAY_WHEEL_EXIT:0"
        }
        return "\nDIPLAY_WHEEL_EXIT:$exit"
    }

    private fun sent() = commands.filter { "force-stop" in it }

    private val proven = Record(atElapsed = 1_000L, atMillis = 5L, automatic = false, outcome = Outcome.BOUND)
    private val late = 1_000L + WheelKeyServiceReset.MIN_GAP_MILLIS

    private fun allowed(
        bootLaunch: Boolean = true, wanted: Boolean = true, allowedInSettings: Boolean = true, connected: Boolean = false,
        hasSession: Boolean = false, record: Record = proven, nowElapsed: Long = late,
    ) = WheelKeyServiceReset.automaticAllowed(bootLaunch, wanted, allowedInSettings, connected, hasSession, record, nowElapsed)

    @Test fun theAutomaticRestartNeedsEveryCondition() {
        assertTrue(allowed())
        assertFalse("not started with the car", allowed(bootLaunch = false))
        assertFalse("no feature needs the service", allowed(wanted = false))
        assertFalse("the driver turned it off", allowed(allowedInSettings = false))
        assertFalse("the service runs", allowed(connected = true))
        assertFalse("a CarPlay session", allowed(hasSession = true))
    }

    @Test fun onlyARestartThatBroughtTheServiceBackAllowsAnAutomaticOne() {
        for (outcome in Outcome.entries) assertEquals(outcome.name, outcome == Outcome.BOUND, allowed(record = proven.copy(outcome = outcome)))
        assertFalse(allowed(record = Record()))
    }

    @Test fun aHeadUnitThatJustBootedIsLeftToStartTheService() {
        val old = proven.copy(atElapsed = -WheelKeyServiceReset.MIN_GAP_MILLIS)
        assertFalse(allowed(record = old, nowElapsed = WheelKeyServiceReset.MIN_UPTIME_MILLIS - 1))
        assertTrue(allowed(record = old, nowElapsed = WheelKeyServiceReset.MIN_UPTIME_MILLIS))
    }

    @Test fun anotherAutomaticRestartWaitsForTheGapUnlessTheHeadUnitRebooted() {
        val at = 100 * 60_000L
        val record = proven.copy(atElapsed = at)
        assertFalse(allowed(record = record, nowElapsed = at))
        assertFalse(allowed(record = record, nowElapsed = at + WheelKeyServiceReset.MIN_GAP_MILLIS - 1))
        assertTrue(allowed(record = record, nowElapsed = at + WheelKeyServiceReset.MIN_GAP_MILLIS))
        // Less time since boot than at the last restart: the head unit has rebooted since.
        assertTrue(allowed(record = record, nowElapsed = at - 1))
    }

    @Test fun aBootBroadcastCountsForOneLaunchSoonAfterIt() {
        assertFalse(WheelKeyServiceReset.consumeBootLaunch(0))
        WheelKeyServiceReset.bootLaunch(1_000)
        assertTrue(WheelKeyServiceReset.consumeBootLaunch(1_000 + WheelKeyServiceReset.BOOT_LAUNCH_MILLIS))
        assertFalse(WheelKeyServiceReset.consumeBootLaunch(1_001))
        WheelKeyServiceReset.bootLaunch(1_000)
        assertFalse(WheelKeyServiceReset.consumeBootLaunch(1_001 + WheelKeyServiceReset.BOOT_LAUNCH_MILLIS))
        assertFalse(WheelKeyServiceReset.consumeBootLaunch(1_001))
    }

    @Test fun onlyABootBroadcastThatOpensDiPlayIsABootLaunch() {
        AirPlayPersistence.saveAutoStartOnBoot(context, false)
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertFalse(WheelKeyServiceReset.consumeBootLaunch())
        AirPlayPersistence.saveAutoStartOnBoot(context, true)
        BootReceiver().onReceive(context, Intent("other"))
        assertFalse(WheelKeyServiceReset.consumeBootLaunch())
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(WheelKeyServiceReset.consumeBootLaunch())
        AirPlayPersistence.saveAutoStartOnBoot(context, false)
    }

    @Test fun aLaunchWithoutABootBroadcastConnectsAtOnce() {
        assertFalse(WheelKeyServiceReset.restartAfterBoot(context, { true }) { fail("restartAfterBoot took the connection over") })
    }

    @Test fun theRecordIsStoredBeforeTheCommandAndTheServicesStartIsItsProof() {
        assertTrue(WheelKeyServiceReset.restart(app = context, shell = { command ->
            if ("force-stop" in command) assertEquals(Outcome.PENDING, WheelKeyServiceReset.load(context).outcome)
            shell(command)
        }, endSession = {}))
        assertEquals(1, sent().size)
        val record = WheelKeyServiceReset.load(context)
        assertEquals(Outcome.PENDING, record.outcome)
        assertFalse(record.automatic)

        // A start in the process that is about to be stopped proves nothing.
        WheelKeyServiceReset.serviceBound(context, record.atElapsed)
        assertEquals(Outcome.PENDING, WheelKeyServiceReset.load(context).outcome)
        // The new process.
        WheelKeyServiceReset.resetForTest()
        WheelKeyServiceReset.serviceBound(context, record.atElapsed + WheelKeyServiceReset.PROOF_MILLIS)
        assertEquals(Outcome.BOUND, WheelKeyServiceReset.load(context).outcome)
        assertTrue(WheelKeyServiceReset.report(context), WheelKeyServiceReset.report(context).startsWith("button outcome=BOUND ageMs="))
    }

    @Test fun aStartLongAfterTheRestartOrAfterARebootIsNoProof() {
        for (now in listOf(1_001 + WheelKeyServiceReset.PROOF_MILLIS, 999L)) {
            prefs.edit().putLong("atElapsed", 1_000).putString("outcome", "PENDING").commit()
            WheelKeyServiceReset.serviceBound(context, now)
            assertEquals(Outcome.LATE, WheelKeyServiceReset.load(context).outcome)
            // A later start does not turn it into proof.
            WheelKeyServiceReset.serviceBound(context, 1_000)
            assertEquals(Outcome.LATE, WheelKeyServiceReset.load(context).outcome)
        }
    }

    @Test fun theServicesOwnStartStoresTheProof() {
        val now = android.os.SystemClock.elapsedRealtime()
        prefs.edit().putLong("atElapsed", now).putString("outcome", "PENDING").commit()
        val service = Robolectric.buildService(WheelKeyService::class.java).create().get()
        service.javaClass.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
        try {
            assertEquals(Outcome.BOUND, WheelKeyServiceReset.load(context).outcome)
        } finally { service.onDestroy() }
    }

    @Test fun carPlayEndsOnlyWhenTheRestartCanFollow() {
        val order = mutableListOf<String>()
        exit = 1
        assertFalse(WheelKeyServiceReset.restart(app = context, shell = ::shell) { order += "session ended" })
        assertEquals(emptyList<String>(), order)
        assertEquals(emptyList<String>(), sent())
        assertEquals(Outcome.NONE, WheelKeyServiceReset.load(context).outcome)

        exit = 0
        commands.clear()
        assertTrue(WheelKeyServiceReset.restart(app = context, shell = { command ->
            if ("force-stop" in command) order += "restart"
            shell(command)
        }) { order += "session ended" })
        assertEquals(listOf("session ended", "restart"), order)
    }

    @Test fun aCommandThatFailedKeepsTheProofAndStartsTheGap() {
        prefs.edit().putLong("atElapsed", -1).putLong("at", 5).putString("outcome", "BOUND").commit()
        assertFalse(WheelKeyServiceReset.restart(context, { command -> if ("force-stop" in command) null else shell(command) }) {})
        val record = WheelKeyServiceReset.load(context)
        assertEquals(Outcome.BOUND, record.outcome)
        assertTrue(record.atElapsed >= 0)
        assertFalse(allowed(record = record, nowElapsed = record.atElapsed + 1))
    }

    @Test fun theAutomaticRestartIsSentForAStuckServiceOnly() {
        var claims = 0
        fun run() = WheelKeyServiceReset.restartIfStuck(context, { true }, ::shell, { true }) { claims++; true }
        listed = false
        assertFalse(run())
        assertEquals(0, claims)
        assertEquals(emptyList<String>(), sent())
        listed = true
        assertTrue(run())
        assertEquals(1, claims)
        assertEquals(1, sent().size)
        assertTrue(WheelKeyServiceReset.load(context).automatic)
    }

    @Test fun aSessionThatBeginsWhileAdbAnswersStopsTheAutomaticRestart() {
        var session = false
        assertFalse(WheelKeyServiceReset.restartIfStuck(
            context, { true }, { command -> shell(command).also { session = true } }, { !session },
        ) { fail("claimed with a session"); true })
        assertEquals(emptyList<String>(), sent())
    }

    @Test fun aConnectionThatAlreadyStartedStopsTheAutomaticRestart() {
        prefs.edit().putLong("atElapsed", proven.atElapsed).putLong("at", proven.atMillis).putString("outcome", "BOUND").commit()
        assertFalse(WheelKeyServiceReset.restartIfStuck(context, { true }, ::shell, { true }) { false })
        assertEquals(emptyList<String>(), sent())
        assertEquals("a restart that was not sent leaves no trace", proven, WheelKeyServiceReset.load(context))
        assertFalse(WheelKeyServiceReset.restartIfStuck(context, { false }, { fail("adb is not ready"); null }, { true }) { true })
    }

    @Test fun theCommandStopsDiPlayThenOpensItAndOutlivesItsShell() {
        assertEquals(
            listOf("setsid nohup sh -c", "sleep 1", "am force-stop com.shihab.diplay", "sleep 2", AM_START),
            run(WheelKeyServiceReset.command("com.shihab.diplay", keepUsbConfirm = false)),
        )
    }

    @Test fun usbAutoConfirmGoesBackOnTheListBeforeDiPlayOpens() {
        val command = WheelKeyServiceReset.command("com.shihab.diplay", keepUsbConfirm = true)
        val calls = run(command)
        assertEquals(listOf("setsid nohup sh -c", "sleep 1", "am force-stop com.shihab.diplay", "sleep 2"), calls.take(4))
        assertEquals(listOf(
            "settings get secure enabled_accessibility_services",
            "settings put secure enabled_accessibility_services car.package/.Service:com.shihab.diplay/com.shilapi.xcertplay.UsbAutoConfirmService",
            "settings put secure accessibility_enabled 1",
            AM_START,
        ), calls.drop(4))
    }

    @Test fun usbAutoConfirmIsKeptOnlyWhenItWasOn() {
        assertTrue(WheelKeyServiceReset.restart(context, ::shell) {})
        assertFalse("UsbAutoConfirmService" in sent().single())
        WheelKeyServiceReset.resetForTest()
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            android.content.ComponentName(context, UsbAutoConfirmService::class.java).flattenToString())
        commands.clear()
        assertTrue(WheelKeyServiceReset.restart(context, ::shell) {})
        assertTrue("UsbAutoConfirmService" in sent().single())
    }

    @Test fun onlyAPackageNameGoesIntoTheCommand() {
        for (name in listOf("diplay", "a.b; rm -rf /", "a.b'", "")) {
            assertThrows(IllegalArgumentException::class.java) { WheelKeyServiceReset.command(name, false) }
        }
    }

    /** Runs the command as adbd would, with stand-ins for the car's tools; returns what they were called with. */
    private fun run(command: String): List<String> {
        assumeTrue(File("/bin/sh").exists())
        val log = File(temporary.root, "calls.txt").apply { delete() }
        val bin = File(temporary.root, "bin").apply { mkdirs() }
        fun tool(name: String, body: String) = File(bin, name).apply {
            writeText("#!/bin/sh\n$body\n")
            assertTrue(setExecutable(true))
        }
        val record = "printf '%s\\n' \"\${0##*/} \$*\" >> '${log.path}'"
        tool("setsid", "printf '%s\\n' \"setsid \$1 \$2 \$3\" >> '${log.path}'; exec \"\$@\"")
        tool("nohup", "exec \"\$@\"")
        tool("am", record)
        tool("sleep", record)
        tool("settings", "$record; [ \"\$1\" = get ] && echo car.package/.Service; exit 0")
        // The parent's own sleep only lets the child leave adbd's process group; it is not part of the sequence.
        val process = ProcessBuilder("/bin/sh", "-c", command.replace("& sleep 1; }", "& }") + "; wait")
            .apply { environment()["PATH"] = bin.path + ":" + environment()["PATH"] }
            .redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        assertEquals(output, 0, process.exitValue())
        return log.readLines()
    }

    private companion object {
        const val AM_START = "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
            "-n com.shihab.diplay/com.shilapi.xcertplay.DiPlayActivity"
    }
}
