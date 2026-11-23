package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.util.concurrent.atomic.AtomicBoolean

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
    private const val HOLD_MILLIS = 8_000L

    /** [PENDING] from the command until the service starts; [LATE] when it started too late to count. */
    enum class Outcome { NONE, PENDING, BOUND, LATE }

    /** The last restart. Times since boot decide, because a head unit's clock can jump when it starts. */
    internal data class Record(
        val atElapsed: Long = 0L,
        val atMillis: Long = 0L,
        val automatic: Boolean = false,
        val outcome: Outcome = Outcome.NONE,
    )

    @Volatile private var bootLaunchAt: Long? = null
    @Volatile private var sent = false
    @Volatile private var held: AtomicBoolean? = null

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

    /**
     * The driver's button; blocking, off the main thread. [endSession] ends a running CarPlay session, which the
     * driver agreed to, and only when the restart can follow. READY means the command was sent.
     */
    fun restart(context: Context, endSession: () -> Unit): LocalAdb.Access {
        val app = context.applicationContext
        return LocalAdb(AdbKeys.load(app)).use { adb ->
            val access = adb.connect(mayAsk = true)
            if (access != LocalAdb.Access.READY) access
            else if (restart(app, adb::shell, endSession)) access
            else LocalAdb.Access.UNREACHABLE
        }
    }

    internal fun restart(app: Context, shell: (String) -> String?, endSession: () -> Unit): Boolean {
        if (WheelKeyService.checkedShell(TOOLS, shell) == null) return false
        endSession()
        return send(app, automatic = false, shell)
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
     */
    fun restartAfterBoot(context: Context, otherwise: () -> Unit): Boolean {
        val app = context.applicationContext
        val bootLaunch = consumeBootLaunch()
        fun allowed() = automaticAllowed(
            bootLaunch, WheelKeyService.wanted(app), WheelKeyService.enabledInSettings(app),
            WheelKeyService.connected(), CarPlayBackgroundSession.hasSession(), load(app),
            SystemClock.elapsedRealtime(),
        )
        if (!allowed()) return false
        val decided = AtomicBoolean(false)
        held = decided
        val main = Handler(Looper.getMainLooper())
        main.postDelayed({ if (decided.compareAndSet(false, true)) otherwise() }, HOLD_MILLIS)
        Thread({
            var claimed = false
            val restarting = try {
                Thread.sleep(START_GRACE_MILLIS)
                LocalAdb(AdbKeys.load(app)).use { adb ->
                    restartIfStuck(app, { adb.connect(mayAsk = false) == LocalAdb.Access.READY }, adb::shell, ::allowed) {
                        decided.compareAndSet(false, true).also { claimed = it }
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "automatic restart failed", error)
                false
            }
            if (!restarting) main.post { if (claimed || decided.compareAndSet(false, true)) otherwise() }
        }, "diplay-wheel-keys-auto-restart").start()
        return true
    }

    /** CarPlay is opening, so an automatic restart that has not been sent is off. */
    fun cancelRestartAfterBoot() {
        held?.set(true)
    }

    /** [allowed] and [claim] come last: a session that began while adb answered, or a timeout, stops it. */
    internal fun restartIfStuck(
        app: Context,
        connect: () -> Boolean,
        shell: (String) -> String?,
        allowed: () -> Boolean,
        claim: () -> Boolean,
    ): Boolean = allowed() && connect() && WheelKeyServiceState.read(app, shell).stuck &&
        WheelKeyService.checkedShell(TOOLS, shell) != null && allowed() && claim() &&
        send(app, automatic = true, shell)

    /** The lock keeps DiPlay from being stopped between the two writes of the allowed list. */
    private fun send(app: Context, automatic: Boolean, shell: (String) -> String?): Boolean =
        synchronized(UsbPermissionSetup.accessibilityLock) {
            val previous = load(app)
            val now = SystemClock.elapsedRealtime()
            // Stored first, because DiPlay is gone right after. Without the record a restart could repeat.
            if (!save(app, Record(now, System.currentTimeMillis(), automatic, Outcome.PENDING))) return@synchronized false
            sent = true
            val accepted = WheelKeyService.checkedShell(
                command(app.packageName, UsbAutoConfirmService.isEnabled(app)), shell) != null
            if (!accepted) {
                sent = false
                // The gap stays: an answer that was lost does not mean that the command was.
                save(app, previous.copy(atElapsed = now))
            }
            Log.i(TAG, "wheel key service restart sent=$accepted automatic=$automatic")
            WheelKeyLog.note("wheel key service restart sent=$accepted automatic=$automatic")
            accepted
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
        held = null
    }
}
