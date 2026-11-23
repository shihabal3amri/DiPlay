package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.io.Closeable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Restarts DiPlay through the car's adb, for a wheel key service that Android does not start again.
 *
 * On a DiLink 3.0 (Android 10) the head unit ends DiPlay's process when the car is switched off, without a
 * reboot. The service then stays allowed and unbound, and removing it from the allowed list and adding it back
 * did not start it in 14 tries with adb working; closing DiPlay in the car's recent apps (a force-stop) did
 * (#1355, #606). Android takes a force-stopped app's service out of all its lists, so the new process's
 * [WheelKeyService.restoreIfNeeded] adds it as a new one.
 *
 * The driver starts this from a button. DiPlay does it by itself only once after a start with the car, before
 * CarPlay, and only on a car where the last restart brought the service back (see [automaticAllowed]).
 *
 * Connecting or opening CarPlay cancels a restart at any time before its command ([cancelPendingRestart]): the
 * [Pending.claim] right before the command is the only step that a cancel cannot undo. DiPlay ends about a second
 * after the command.
 */
internal object WheelKeyServiceReset {
    private const val PREFS = "wheel_key_reset"
    private const val TAG = WheelKeyService.TAG

    /** A head unit that just booted starts the service itself; only a "boot" without a reboot leaves it stuck. */
    internal const val MIN_UPTIME_MILLIS = 5 * 60_000L
    internal const val MIN_GAP_MILLIS = 10 * 60_000L
    /** A service that starts later than this after a restart was not brought back by it. */
    internal const val PROOF_MILLIS = 2 * 60_000L
    internal const val BOOT_LAUNCH_MILLIS = 60_000L
    private const val START_GRACE_MILLIS = 2_000L
    /** How long the connection at launch waits for [restartAfterBoot] at most. */
    internal const val HOLD_MILLIS = 8_000L
    /** A process that still runs this long after the command was not ended by it. */
    internal const val END_MILLIS = 10_000L
    private const val MAIN_WAIT_MILLIS = 2_000L

    /**
     * [PENDING] from the command until the service starts; [LATE] when it started too late to count, [FAILED]
     * when the command did not end DiPlay's process.
     */
    enum class Outcome { NONE, PENDING, BOUND, LATE, FAILED }

    /** The last restart. Times since boot decide, because a head unit's clock can jump when it starts. */
    internal data class Record(
        val atElapsed: Long = 0L,
        val atMillis: Long = 0L,
        val automatic: Boolean = false,
        val outcome: Outcome = Outcome.NONE,
    )

    /** A restart that has not been sent. Exactly one of these ends it: the command, a cancel, the hold's end. */
    internal class Pending {
        private enum class State { WAITING, CANCELLED, LIFTED, CLAIMED }

        private val state = AtomicReference(State.WAITING)

        /** The driver or CarPlay was first, or came after the command: the connection is theirs either way. */
        @Volatile var cancelled = false
            private set

        val waiting: Boolean get() = state.get() == State.WAITING

        fun cancel() {
            cancelled = true
            state.compareAndSet(State.WAITING, State.CANCELLED)
        }

        /** The time for it is over. */
        fun lift(): Boolean = state.compareAndSet(State.WAITING, State.LIFTED)

        /** The command follows at once. */
        fun claim(): Boolean = state.compareAndSet(State.WAITING, State.CLAIMED)
    }

    /** The car's adb, as far as a restart needs it. */
    internal interface Adb : Closeable {
        fun connect(mayAsk: Boolean): LocalAdb.Access
        fun shell(command: String): String?
    }

    private class CarAdb(context: Context) : Adb {
        private val adb = LocalAdb(AdbKeys.load(context))
        override fun connect(mayAsk: Boolean) = adb.connect(mayAsk)
        override fun shell(command: String) = adb.shell(command)
        override fun close() = adb.close()
    }

    @Volatile private var bootLaunchAt: Long? = null
    @Volatile private var sent = false
    @Volatile private var pending: Pending? = null
    private val buttonRunning = AtomicBoolean(false)
    @Volatile internal var openAdb: (Context) -> Adb = ::CarAdb
    @Volatile internal var startGraceMillis = START_GRACE_MILLIS

    /**
     * The command adbd runs. Its child leaves adbd's process group first (setsid), or it would end with this
     * stream or with DiPlay, and waits a second so the reply gets out. Force-stopping an app also takes its other
     * accessibility services off the allowed list, so USB auto-confirm goes back when it was on
     * ([keepUsbConfirm]), after Android has handled the force-stop.
     */
    internal fun command(packageName: String, keepUsbConfirm: Boolean): String {
        require(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+").matches(packageName))
        val script = listOfNotNull(
            "sleep 1",
            "am force-stop $packageName",
            "sleep 2",
            "( ${UsbPermissionSetup.command(UsbPermissionSetup.Permission.ACCESSIBILITY, packageName)} )"
                .takeIf { keepUsbConfirm },
            "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
                "-n $packageName/${DiPlayActivity::class.java.name}",
        ).joinToString("; ")
        return "{ setsid nohup sh -c ${quote(script)} </dev/null >/dev/null 2>&1 & sleep 1; }"
    }

    private const val TOOLS = "command -v setsid >/dev/null && command -v nohup >/dev/null"

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    /** The driver's button was confirmed, and DiPlay has neither ended nor given up yet. */
    fun restarting(): Boolean = buttonRunning.get()

    /**
     * The driver's button; blocking, off the main thread. [endSession] ends a running CarPlay session, which the
     * driver agreed to, and only when the restart can follow. READY means the command was sent; null that another
     * restart is under way, or that the driver connected or opened CarPlay meanwhile and so took it back.
     */
    fun restart(context: Context, endSession: () -> Unit): LocalAdb.Access? {
        if (!buttonRunning.compareAndSet(false, true)) return null
        val app = context.applicationContext
        var commanded = false
        try {
            val restart = Pending()
            // This one replaces a restart that waits after a start with the car.
            cancelPendingRestart()
            pending = restart
            val access = openAdb(app).use { adb ->
                adb.connect(mayAsk = true).also {
                    if (it == LocalAdb.Access.READY) commanded = restart(app, adb::shell, restart, endSession)
                }
            }
            return when {
                commanded || access != LocalAdb.Access.READY -> access
                restart.cancelled -> null
                else -> LocalAdb.Access.UNREACHABLE
            }
        } finally {
            if (!commanded) buttonRunning.set(false)
        }
    }

    internal fun restart(
        app: Context,
        shell: (String) -> String?,
        restart: Pending = Pending(),
        endSession: () -> Unit,
    ): Boolean {
        if (WheelKeyService.checkedShell(TOOLS, shell) == null || !restart.waiting) return false
        endSession()
        return send(app, automatic = false, shell, restart::claim)
    }

    /** The boot broadcast is about to open DiPlay. */
    fun bootLaunch(nowElapsed: Long = SystemClock.elapsedRealtime()) {
        bootLaunchAt = nowElapsed
    }

    /** One launch per boot broadcast. */
    internal fun consumeBootLaunch(nowElapsed: Long = SystemClock.elapsedRealtime()): Boolean {
        val at = bootLaunchAt ?: return false
        bootLaunchAt = null
        return nowElapsed - at in 0..BOOT_LAUNCH_MILLIS
    }

    /**
     * One automatic restart per start with the car, never with a CarPlay session, and only on proof: the last
     * restart opened DiPlay again and its service started. A restart that did not work leaves [record] without
     * that proof until the driver's button works, and [MIN_GAP_MILLIS] stops a loop whatever the car does.
     */
    internal fun automaticAllowed(
        bootLaunch: Boolean,
        wanted: Boolean,
        allowedInSettings: Boolean,
        connected: Boolean,
        hasSession: Boolean,
        record: Record,
        nowElapsed: Long,
    ): Boolean {
        // Negative after a reboot, which ends what the gap is for.
        val sinceLast = nowElapsed - record.atElapsed
        return bootLaunch && wanted && allowedInSettings && !connected && !hasSession &&
            nowElapsed >= MIN_UPTIME_MILLIS && record.outcome == Outcome.BOUND &&
            (sinceLast < 0 || sinceLast >= MIN_GAP_MILLIS)
    }

    /**
     * At the first launch of DiPlay's screen, on the main thread. False when there is nothing to do. Otherwise
     * [otherwise] runs on the main thread when DiPlay is not restarted, so the connection at launch can follow.
     * [inFront]: DiPlay's home page is what the driver sees. Without that nothing is restarted, because the
     * restart opens DiPlay over whatever is in front. It is asked on the main thread.
     */
    fun restartAfterBoot(context: Context, inFront: () -> Boolean, otherwise: () -> Unit): Boolean {
        val app = context.applicationContext
        val bootLaunch = consumeBootLaunch()
        // As it was: the last check comes after this restart's own record.
        val record = load(app)
        fun allowed() = automaticAllowed(
            bootLaunch, WheelKeyService.wanted(app), WheelKeyService.enabledInSettings(app),
            WheelKeyService.connected(),
            // A CarPlay screen that is opening has no session yet.
            CarPlayBackgroundSession.hasSession() || CarPlayScreen.current() != null, record,
            SystemClock.elapsedRealtime(),
        )
        if (!allowed()) return false
        val hold = Pending()
        pending = hold
        val main = Handler(Looper.getMainLooper())
        main.postDelayed({ if (hold.lift()) otherwise() }, HOLD_MILLIS)
        Thread({
            try {
                Thread.sleep(startGraceMillis)
                openAdb(app).use { adb ->
                    restartIfStuck(
                        app, { adb.connect(mayAsk = false) == LocalAdb.Access.READY }, adb::shell,
                        // On the main thread, where the driver's presses and the screens' changes are handled too.
                        allowed = { hold.waiting && onMain { allowed() && inFront() } },
                        claim = hold::claim,
                        stillRunning = { if (!hold.cancelled) otherwise() },
                    )
                }
            } catch (error: Exception) {
                Log.w(TAG, "automatic restart failed", error)
            }
            // Not after a claim: even a command whose answer was lost may have reached the car.
            main.post { if (hold.lift()) otherwise() }
        }, "diplay-wheel-keys-auto-restart").start()
        return true
    }

    /** False when the main thread does not answer in time. */
    private fun onMain(check: () -> Boolean): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return check()
        val task = FutureTask(check)
        Handler(Looper.getMainLooper()).post(task)
        return try {
            task.get(MAIN_WAIT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            task.cancel(false)
            false
        }
    }

    /** The driver connects or CarPlay opens, so a restart that has not been sent is off. */
    fun cancelPendingRestart() {
        pending?.cancel()
    }

    /**
     * The service's state is read and acted on under the lock that a rewrite of the allowed list holds. Waiting for
     * that lock takes seconds, so nothing that was true before it counts.
     */
    internal fun restartIfStuck(
        app: Context,
        connect: () -> Boolean,
        shell: (String) -> String?,
        allowed: () -> Boolean,
        stillRunning: () -> Unit = {},
        claim: () -> Boolean,
    ): Boolean = allowed() && connect() && synchronized(UsbPermissionSetup.accessibilityLock) {
        allowed() && WheelKeyServiceState.read(app, shell).stuck &&
            WheelKeyService.checkedShell(TOOLS, shell) != null && allowed() &&
            send(app, automatic = true, shell, { allowed() && claim() }, stillRunning)
    }

    /**
     * The lock keeps DiPlay from being stopped between the two writes of the allowed list. [claim] is the last check,
     * right before the command. [stillRunning] runs on the main thread if DiPlay did not end after it.
     */
    private fun send(
        app: Context,
        automatic: Boolean,
        shell: (String) -> String?,
        claim: () -> Boolean,
        stillRunning: () -> Unit = {},
    ): Boolean =
        synchronized(UsbPermissionSetup.accessibilityLock) {
            if (sent) return@synchronized false
            val command = command(app.packageName, UsbAutoConfirmService.isEnabled(app))
            val previous = load(app)
            val now = SystemClock.elapsedRealtime()
            // Stored first, because DiPlay is gone right after. Without the record a restart could repeat.
            if (!save(app, Record(now, System.currentTimeMillis(), automatic, Outcome.PENDING))) return@synchronized false
            if (!claim()) {
                save(app, previous)
                return@synchronized false
            }
            sent = true
            Handler(Looper.getMainLooper()).postDelayed({
                notEnded(app, now)
                stillRunning()
            }, END_MILLIS)
            val accepted = WheelKeyService.checkedShell(command, shell) != null
            if (!accepted) {
                sent = false
                // The gap stays: an answer that was lost does not mean that the command was.
                save(app, previous.copy(atElapsed = now))
            }
            Log.i(TAG, "wheel key service restart sent=$accepted automatic=$automatic")
            WheelKeyLog.note("wheel key service restart sent=$accepted automatic=$automatic")
            accepted
        }

    // The child was refused, or the head unit did not stop DiPlay. A service that starts now is no proof.
    private fun notEnded(app: Context, sentAt: Long) {
        sent = false
        buttonRunning.set(false)
        val record = load(app)
        if (record.outcome == Outcome.PENDING && record.atElapsed == sentAt) save(app, record.copy(outcome = Outcome.FAILED))
        Log.w(TAG, "DiPlay still runs after the wheel key service restart")
        WheelKeyLog.note("DiPlay still runs after the wheel key service restart")
    }

    /** The service started: the proof that the restart before it worked. */
    fun serviceBound(context: Context, nowElapsed: Long = SystemClock.elapsedRealtime()) {
        if (sent) return
        val record = load(context)
        if (record.outcome != Outcome.PENDING) return
        save(context, record.copy(outcome = if (nowElapsed - record.atElapsed in 0..PROOF_MILLIS) Outcome.BOUND else Outcome.LATE))
    }

    internal fun load(context: Context): Record = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).run {
        Record(
            getLong("atElapsed", 0L), getLong("at", 0L), getBoolean("automatic", false),
            Outcome.entries.firstOrNull { it.name == getString("outcome", null) } ?: Outcome.NONE,
        )
    }

    private fun save(context: Context, record: Record): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("atElapsed", record.atElapsed)
            .putLong("at", record.atMillis)
            .putBoolean("automatic", record.automatic)
            .putString("outcome", record.outcome.name)
            .commit()

    /** Part of the report's wheel key service line. */
    fun report(context: Context, nowMillis: Long = System.currentTimeMillis()): String {
        val record = load(context)
        if (record.outcome == Outcome.NONE) return "none"
        return "${if (record.automatic) "automatic" else "button"} outcome=${record.outcome} " +
            "ageMs=${nowMillis - record.atMillis}"
    }

    internal fun resetForTest() {
        bootLaunchAt = null
        sent = false
        pending = null
        buttonRunning.set(false)
        openAdb = ::CarAdb
        startGraceMillis = START_GRACE_MILLIS
    }
}
