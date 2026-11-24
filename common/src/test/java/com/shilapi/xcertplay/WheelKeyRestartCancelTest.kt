package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import com.shilapi.xcertplay.WheelKeyServiceReset.Outcome
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.lang.ref.WeakReference
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers

/**
 * Restarting DiPlay force-stops it, so nothing that the driver or CarPlay starts before the command may be lost to it.
 * The whole path runs here, with its own thread; only the car's adb is a stand-in, which answers when the test says so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, shadows = [HostConnectionSettingsRefreshTest.Bootstrap::class])
@LooperMode(LooperMode.Mode.PAUSED)
class WheelKeyRestartCancelTest {
    private val app: Context = RuntimeEnvironment.getApplication()
    private val main get() = shadowOf(Looper.getMainLooper())
    private val commands = Collections.synchronizedList(mutableListOf<String>())
    private val otherwise = AtomicInteger()
    private val sessionsEnded = AtomicInteger()
    private val connecting = CountDownLatch(1)
    private val connect = CountDownLatch(1)
    private var service: WheelKeyService? = null
    private lateinit var proof: WheelKeyServiceReset.Record

    @Before fun setUp() {
        WheelKeyServiceReset.resetForTest()
        WheelKeyLog.reset()
        for (name in listOf("diplay", "xcertplay_airplay")) app.getSharedPreferences(name, 0).edit().clear().commit()
        CarPlayBackgroundSession.clear()
        WheelZoomSettings.setMediaPlayPause(app, true)
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ComponentName(app, WheelKeyService::class.java).flattenToString())
        ShadowSystemClock.advanceBy(Duration.ofMinutes(30))
        app.getSharedPreferences("wheel_key_reset", Context.MODE_PRIVATE).edit().clear()
            .putLong("atElapsed", 1).putLong("at", 5).putString("outcome", "BOUND").commit()
        proof = WheelKeyServiceReset.load(app)
        WheelKeyServiceReset.startGraceMillis = 0
        WheelKeyServiceReset.openAdb = {
            object : WheelKeyServiceReset.Adb {
                override fun connect(mayAsk: Boolean): LocalAdb.Access {
                    connecting.countDown()
                    assertTrue(connect.await(10, TimeUnit.SECONDS))
                    return LocalAdb.Access.READY
                }
                override fun shell(command: String) = this@WheelKeyRestartCancelTest.shell(command)
                override fun close() = Unit
            }
        }
        // The screens' own restore would look for a real adb.
        restoring(true)
        WheelKeyServiceReset.bootLaunch()
    }

    private fun restoring(running: Boolean) =
        ReflectionHelpers.getStaticField<AtomicBoolean>(WheelKeyService::class.java, "restoring").set(running)

    @After fun tearDown() {
        connect.countDown()
        workers().forEach { awaitEnd(it) }
        service?.onDestroy()
        restoring(false)
        CarPlayBackgroundSession.clear()
        WheelZoomSettings.setMediaPlayPause(app, false)
        WheelKeyServiceReset.resetForTest()
        WheelKeyLog.reset()
        AppAppearanceRuntime.resetForTest()
    }

    private fun shell(command: String): String {
        commands += command
        if ("dumpsys accessibility" !in command) return "\nDIPLAY_WHEEL_EXIT:0"
        val ours = ComponentName(app, WheelKeyService::class.java).flattenToString()
        return "User state[attributes:{id=0, currentUser=true}\n     Bound services:{}\n     Enabled services:{{$ours}}\n" +
            "     Binding services:{{$ours}}\n     Crashed services:{}\n\nDIPLAY_WHEEL_EXIT:0"
    }

    private fun sent() = commands.toList().count { "force-stop" in it }

    private fun workers() = Thread.getAllStackTraces().keys.filter { it.name in WORKERS && it.isAlive }
    private fun worker() = workers().single { it.name == "diplay-wheel-keys-auto-restart" }

    private fun await(done: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!done()) {
            assertTrue("timed out", System.nanoTime() < end)
            main.idle()
            Thread.sleep(5)
        }
    }

    private fun awaitEnd(thread: Thread) = await { !thread.isAlive }.also { main.idle() }

    /** The car's adb answers the waiting restart. */
    private fun answer(worker: Thread = worker()) {
        connect.countDown()
        awaitEnd(worker)
    }

    private fun hold(inFront: () -> Boolean = { true }): Thread {
        assertTrue(WheelKeyServiceReset.restartAfterBoot(app, inFront) { otherwise.incrementAndGet() })
        await { connecting.count == 0L }
        return worker()
    }

    /** Lets adb answer, with the list's lock taken by a rewrite that ends after [meanwhile]. */
    private fun whileTheRestartWaitsForTheLock(worker: Thread, meanwhile: () -> Unit) {
        val taken = CountDownLatch(1)
        val release = CountDownLatch(1)
        val rewrite = thread {
            synchronized(UsbPermissionSetup.accessibilityLock) {
                taken.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        assertTrue(taken.await(5, TimeUnit.SECONDS))
        connect.countDown()
        await { worker.state == Thread.State.BLOCKED }
        meanwhile()
        release.countDown()
        rewrite.join()
        awaitEnd(worker)
    }

    private fun assertNothingSent() {
        assertEquals(0, sent())
        assertEquals("a restart that was not sent leaves no trace", proof, WheelKeyServiceReset.load(app))
        main.idleFor(Duration.ofSeconds(60))
        assertEquals(0, sent())
    }

    private fun session(running: Boolean) {
        val stop: ((() -> Unit) -> Unit)? = if (running) { done -> sessionsEnded.incrementAndGet(); done() } else null
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)
    }

    private fun bind() {
        service = Robolectric.buildService(WheelKeyService::class.java).create().get().also {
            it.javaClass.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(it)
        }
    }

    @Test fun aStuckServiceIsRestartedAfterTheWait() {
        whileTheRestartWaitsForTheLock(hold()) {}
        assertEquals(1, sent())
        assertTrue(WheelKeyServiceReset.load(app).automatic)
        assertEquals(Outcome.PENDING, WheelKeyServiceReset.load(app).outcome)
        // DiPlay is about to end: neither the hold's end nor the worker's connects CarPlay.
        main.idleFor(Duration.ofMillis(WheelKeyServiceReset.HOLD_MILLIS))
        assertEquals(0, otherwise.get())
    }

    @Test fun aCancelThatArrivesWhileTheRestartWaitsForTheLockStopsIt() {
        whileTheRestartWaitsForTheLock(hold()) { WheelKeyServiceReset.cancelPendingRestart() }
        assertNothingSent()
        assertEquals("the driver's own connection is under way", 0, otherwise.get())
    }

    @Test fun aSessionThatOpensWhileTheRestartWaitsForTheLockStopsIt() {
        whileTheRestartWaitsForTheLock(hold()) { session(true) }
        assertNothingSent()
    }

    @Test fun aServiceThatStartsWhileTheRestartWaitsForTheLockStopsIt() {
        whileTheRestartWaitsForTheLock(hold()) { bind() }
        assertNothingSent()
        assertEquals(1, otherwise.get())
    }

    @Test fun theServicesStateIsReadOnlyOnceARewriteOfTheListIsDone() {
        whileTheRestartWaitsForTheLock(hold()) { assertTrue(commands.toString(), commands.isEmpty()) }
        assertEquals(1, sent())
    }

    @Test fun aCancelRightBeforeTheCommandStopsIt() {
        var checks = 0
        val restart = WheelKeyServiceReset.Pending()
        val results = mutableListOf<Boolean>()
        // The last check passes, and the driver presses Connect after it.
        assertFalse(WheelKeyServiceReset.restartIfStuck(app, { true }, ::shell, { if (++checks == 4) restart.cancel(); true }) {
            restart.claim().also { results += it }
        })
        assertEquals(listOf(false), results)
        assertNothingSent()
    }

    @Test fun aCancelAfterTheClaimIsTooLateAndAClaimAfterACancelFails() {
        val cancelled = WheelKeyServiceReset.Pending().apply { cancel() }
        assertFalse(cancelled.claim())
        assertFalse(cancelled.lift())
        val claimed = WheelKeyServiceReset.Pending()
        assertTrue(claimed.claim())
        claimed.cancel()
        assertTrue(claimed.cancelled)
        assertFalse(claimed.lift())
    }

    @Test fun theEndOfTheHoldConnectsCarPlayAndStopsTheRestart() {
        val worker = hold()
        main.idleFor(Duration.ofMillis(WheelKeyServiceReset.HOLD_MILLIS))
        assertEquals(1, otherwise.get())
        answer(worker)
        assertNothingSent()
        assertEquals(1, otherwise.get())
    }

    @Test fun pressingConnectStopsTheRestart() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java, Intent()).create().get()
        val worker = hold()
        // The press alone, without the session or the screen that it opens.
        ReflectionHelpers.setField(activity, "setupError", "not set up")
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "connect",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
        answer(worker)
        assertNothingSent()
        assertEquals(0, otherwise.get())
        activity.finish()
    }

    @Test fun aCarPlayScreenThatWasInFrontStopsTheRestart() {
        val worker = hold()
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).setup()
        // Closed again before adb answers: that it was there is enough.
        host.get().finish()
        host.pause().stop().destroy()
        session(false)
        answer(worker)
        assertNothingSent()
        assertEquals(0, otherwise.get())
    }

    @Test fun aCarPlayScreenWithoutASessionYetStopsTheRestart() {
        val worker = hold()
        // As its onCreate leaves it, before onResume and before a session.
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        ReflectionHelpers.setField(CarPlayScreen, "open", WeakReference(host))
        answer(worker)
        CarPlayScreen.destroyed(host)
        assertNothingSent()
    }

    @Test fun nothingIsRestartedOverAnotherPageOrApp() {
        var inFront = true
        whileTheRestartWaitsForTheLock(hold { inFront }) { inFront = false }
        assertNothingSent()
        // The launch's own handler decides what that means for the connection.
        assertEquals(1, otherwise.get())
    }

    @Test fun whatDiPlayShowsIsAskedOnTheMainThread() {
        val threads = Collections.synchronizedSet(mutableSetOf<Thread>())
        whileTheRestartWaitsForTheLock(hold { threads += Thread.currentThread(); true }) {}
        assertEquals(1, sent())
        assertEquals(setOf(Looper.getMainLooper().thread), threads.toSet())
    }

    @Test fun aProcessThatOutlivesTheCommandConnectsAndTheServicesStartIsNoProof() {
        whileTheRestartWaitsForTheLock(hold()) {}
        assertEquals(1, sent())
        main.idleFor(Duration.ofMillis(WheelKeyServiceReset.END_MILLIS))
        assertEquals(1, otherwise.get())
        assertEquals(Outcome.FAILED, WheelKeyServiceReset.load(app).outcome)
        WheelKeyServiceReset.serviceBound(app)
        assertEquals(Outcome.FAILED, WheelKeyServiceReset.load(app).outcome)
        assertTrue(WheelKeyServiceReset.report(app).startsWith("automatic outcome=FAILED "))
        // The next restart is not mistaken for that one.
        assertTrue(WheelKeyServiceReset.restart(app, ::shell) {})
        assertEquals(2, sent())
        WheelKeyServiceReset.resetForTest()
        WheelKeyServiceReset.serviceBound(app)
        assertEquals(Outcome.BOUND, WheelKeyServiceReset.load(app).outcome)
    }

    @Test fun theDriversOwnConnectionAfterTheCommandIsNotRepeated() {
        whileTheRestartWaitsForTheLock(hold()) {}
        WheelKeyServiceReset.cancelPendingRestart()
        main.idleFor(Duration.ofMillis(WheelKeyServiceReset.END_MILLIS))
        assertEquals(0, otherwise.get())
    }

    @Test fun aCommandWhoseAnswerWasLostConnectsOnlyOnceItCanNoLongerStopDiPlay() {
        WheelKeyServiceReset.openAdb = {
            object : WheelKeyServiceReset.Adb {
                override fun connect(mayAsk: Boolean) = LocalAdb.Access.READY.also { connecting.countDown() }
                override fun shell(command: String) = this@WheelKeyRestartCancelTest.shell(command).takeUnless { "force-stop" in command }
                override fun close() = Unit
            }
        }
        assertTrue(WheelKeyServiceReset.restartAfterBoot(app, { true }) { otherwise.incrementAndGet() })
        await { workers().none { it.name == "diplay-wheel-keys-auto-restart" } }
        assertEquals(1, sent())
        main.idleFor(Duration.ofMillis(WheelKeyServiceReset.END_MILLIS - 100))
        assertEquals(0, otherwise.get())
        main.idleFor(Duration.ofMillis(100))
        assertEquals(1, otherwise.get())
        assertEquals("the proof stays", Outcome.BOUND, WheelKeyServiceReset.load(app).outcome)
    }

    /** The confirmed button, on its own thread as in the settings. */
    private fun button(result: (LocalAdb.Access?) -> Unit): Thread = thread(name = "diplay-wheel-keys-restart") {
        result(WheelKeyServiceReset.restart(app) { sessionsEnded.incrementAndGet() })
    }

    @Test fun connectingTakesBackAConfirmedRestartThatWasNotSent() {
        var access: LocalAdb.Access? = LocalAdb.Access.READY
        val worker = button { access = it }
        await { connecting.count == 0L }
        assertTrue(WheelKeyServiceReset.restarting())
        WheelKeyServiceReset.cancelPendingRestart()
        answer(worker)
        assertNull(access)
        assertEquals("the new session goes on", 0, sessionsEnded.get())
        assertFalse(WheelKeyServiceReset.restarting())
        assertNothingSent()
    }

    @Test fun aSessionOpenedAfterTheOldOneEndedTakesBackAConfirmedRestart() {
        val restart = WheelKeyServiceReset.Pending()
        assertFalse(WheelKeyServiceReset.restart(app, ::shell, restart) { restart.cancel() })
        assertNothingSent()
    }

    @Test fun aConfirmedRestartIsSentOnceAndReplacesTheOneAfterBoot() {
        val automatic = hold()
        val results = Collections.synchronizedList(mutableListOf<LocalAdb.Access?>())
        val first = button { results += it }
        await { WheelKeyServiceReset.restarting() }
        val second = button { results += it }
        awaitEnd(second)
        assertEquals(listOf(null), results.toList())
        connect.countDown()
        awaitEnd(first)
        awaitEnd(automatic)
        assertEquals(listOf(null, LocalAdb.Access.READY), results.toList())
        assertEquals(1, sent())
        assertFalse(WheelKeyServiceReset.load(app).automatic)
        assertEquals(1, sessionsEnded.get())
        assertTrue("until DiPlay ends", WheelKeyServiceReset.restarting())
        main.idleFor(Duration.ofMillis(WheelKeyServiceReset.END_MILLIS))
        assertFalse(WheelKeyServiceReset.restarting())
    }

    /** Opened by the boot broadcast with "connect on open", on a car that proved to need the restart. */
    private fun heldLaunch(): ActivityController<DiPlayActivity> {
        DiPlayPreferences.savePhone(app, "00:11:22:33:44:55", "Test iPhone")
        DiPlayPreferences.saveAutoConnect(app, true)
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.WIFI_P2P)
        app.getSharedPreferences("diplay", 0).edit().putBoolean("notification_asked", true).commit()
        val home = Robolectric.buildActivity(DiPlayActivity::class.java, Intent()).create()
        ReflectionHelpers.setField(home.get(), "setupError", null)
        home.start().resume()
        main.idle()
        await { connecting.count == 0L }
        assertNull(shadowOf(home.get()).nextStartedActivity)
        return home
    }

    private fun opened(home: ActivityController<DiPlayActivity>): String? {
        main.idleFor(Duration.ofMillis(WheelKeyServiceReset.HOLD_MILLIS + 100))
        return shadowOf(home.get()).nextStartedActivity?.component?.className
    }

    @Test fun theHeldLaunchConnectsOnTheHomePage() {
        val home = heldLaunch()
        assertEquals(CarPlayHostActivity::class.java.name, opened(home))
        answer()
        assertNothingSent()
        home.pause().stop().destroy()
    }

    @Test fun theHeldLaunchRestartsFromTheHomePage() {
        val home = heldLaunch()
        answer()
        assertEquals(1, sent())
        home.pause().stop().destroy()
    }

    @Test fun theHeldLaunchLeavesAPageTheDriverOpenedAlone() {
        val home = heldLaunch()
        ReflectionHelpers.setField(home.get(), "page", "settings")
        ReflectionHelpers.callInstanceMethod<Unit>(home.get(), "render")
        answer()
        assertNull(opened(home))
        assertNothingSent()
        home.pause().stop().destroy()
    }

    @Test fun theHeldLaunchLeavesAnotherAppAlone() {
        val home = heldLaunch()
        home.pause()
        answer()
        assertNull(opened(home))
        assertNothingSent()
        home.stop().destroy()
    }

    @Test fun theEndOfTheHoldLeavesAnotherAppAlone() {
        val home = heldLaunch()
        home.pause()
        assertNull(opened(home))
        home.stop().destroy()
    }

    @Test fun aLaunchOnACarWithoutProofConnectsAtOnce() {
        app.getSharedPreferences("wheel_key_reset", Context.MODE_PRIVATE).edit().putString("outcome", "LATE").commit()
        assertFalse(WheelKeyServiceReset.restartAfterBoot(app, { true }) { fail("held") })
    }

    private companion object {
        val WORKERS = setOf("diplay-wheel-keys-auto-restart", "diplay-wheel-keys-restart")
    }
}
