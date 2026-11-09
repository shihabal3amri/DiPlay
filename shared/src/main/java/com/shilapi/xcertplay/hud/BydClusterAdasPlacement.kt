package com.shilapi.xcertplay.hud

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.adb.LocalAdb
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** What the Settings screen needs of [BydClusterAdasPlacement]; its tests replace it. */
interface BydClusterAdasPlacementControl {
    val status: BydClusterAdasPlacement.Status
    val busy: Boolean

    /** Called, on the worker thread, after [status] or [busy] changed. */
    var onChange: (() -> Unit)?

    /** Asks the head unit what is installed, unless that was asked a short while ago. */
    fun refreshIfStale(app: Context)

    fun install(app: Context, done: (BydClusterAdasPlacement.Outcome) -> Unit)

    fun remove(app: Context, done: (BydClusterAdasPlacement.Outcome) -> Unit)

    /** Applies the setting now rather than at the next tick: a switch turned off stops the heartbeat at once. */
    fun settingChanged(app: Context)
}

/**
 * Moves the cluster's large ADAS scene beside DiPlay's CarPlay map instead of over it.
 *
 * On a BYD Han the car sometimes opens a large ADAS scene in the middle of the cluster, on top of the map, while BYD's
 * own "navigation window" box on the right stays empty. This feature installs DiPlay's own file in the cluster's
 * theme (see [BydClusterAdasTheme]) and, while DiPlay's map is on the cluster, keeps a heartbeat the file's block
 * waits for ([BydClusterAdasBeat]); the block then moves the scene into the box. Nothing is hidden, closed or sent to
 * the car: no frame, no signal, no setting. Without the heartbeat the cluster shows BYD's own layout.
 *
 * Installing and removing the file reload the cluster's interface, so they run only while the car is parked. While a
 * file of DiPlay's is installed, DiPlay checks once after each start that it still fits the car's software, and gives
 * it up when it does not. All work runs on one thread, through the same one-shot adb shell the other BYD features use;
 * nothing is done without the driver's approval of that link, and it is never asked for here.
 */
object BydClusterAdasPlacement : BydClusterAdasPlacementControl {
    private const val TAG = "DiPlay-AdasPlacement"
    const val TICK_SECONDS = 5L
    private const val HELPER_TIMEOUT_MILLIS = 30_000
    private const val PROBE_RETRY_MILLIS = 60_000L
    private const val STALE_MILLIS = 30_000L

    /** The states that say nothing about what the cluster uses: the head unit or the cluster did not answer. */
    private val UNANSWERED = setOf(State.UNKNOWN, State.NO_ADB, State.NO_CLUSTER)

    enum class State { UNKNOWN, NOT_INSTALLED, CURRENT, STALE, OTHER, UNSUPPORTED, NO_ADB, NO_CLUSTER }

    /** [code] says why a cluster's files do not fit, for [State.UNSUPPORTED]. */
    class Status(val state: State, val code: String? = null)

    enum class Outcome {
        INSTALLED, ALREADY, REMOVED, NOT_PARKED, GEAR_UNKNOWN, OTHER_ACTIVE, UNSUPPORTED, NO_ADB, NO_CLUSTER, FAILED, BUSY,
    }

    @Volatile override var status: Status = Status(State.UNKNOWN)
        private set

    /** True while the helper is changing the cluster. */
    @Volatile override var busy: Boolean = false
        private set

    @Volatile override var onChange: (() -> Unit)? = null

    private val worker = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "diplay-adas-placement").apply { isDaemon = true }
    }
    private val shell = BydAdbShell(TAG)
    private val started = AtomicBoolean(false)
    private var leaseHeld = false
    private var nextProbeAtMillis = 0L
    private var nextCheckAtMillis = 0L
    private var installedChecked = false
    private var probedAtMillis = 0L
    @Volatile private var context: Context? = null

    @Volatile internal var submit: (() -> Unit) -> Unit = ::submitToWorker
    @Volatile internal var run: (Context, String) -> String? = ::runInShell
    @Volatile internal var access: () -> LocalAdb.Access? = { shell.lastAccess }
    @Volatile internal var parked: (Context) -> Boolean? = BydNavigationOutputs::parked
    @Volatile internal var mapShown: () -> Boolean = { BydClusterMapPause.clusterMapShown }
    @Volatile internal var nowMillis: () -> Long = { System.currentTimeMillis() }

    private fun submitToWorker(task: () -> Unit) = worker.execute(task)

    private fun runInShell(app: Context, command: String): String? = shell.run(app, command, HELPER_TIMEOUT_MILLIS)

    /** Starts the tick once: it checks an installed file at start and keeps the heartbeat while the setting is on and the map is on the cluster. */
    fun initialize(appContext: Context) {
        context = appContext.applicationContext
        if (started.compareAndSet(false, true)) {
            worker.scheduleWithFixedDelay({ context?.let(::tick) }, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS)
        }
    }

    /** Asks the head unit what is installed; the answer arrives as [status]. */
    fun refresh(app: Context) = submit { probe(app.applicationContext) }

    override fun refreshIfStale(app: Context) = submit {
        if (status.state == State.UNKNOWN || nowMillis() - probedAtMillis >= STALE_MILLIS) probe(app.applicationContext)
    }

    /** Installs the file and reloads the cluster. Only while the car is known to be parked. */
    override fun install(app: Context, done: (Outcome) -> Unit) = submit {
        val appContext = app.applicationContext
        done(change(appContext, BydClusterAdasThemeMain.INSTALL))
    }

    /** Gives the cluster its own theme back, reloads it and deletes DiPlay's files. Only while parked. */
    override fun remove(app: Context, done: (Outcome) -> Unit) = submit {
        val appContext = app.applicationContext
        done(change(appContext, BydClusterAdasThemeMain.REMOVE))
    }

    override fun settingChanged(app: Context) {
        val appContext = app.applicationContext
        context = appContext
        submit { tick(appContext) }
    }

    internal fun statusOf(result: BydClusterAdasTheme.Result?, access: LocalAdb.Access?): Status = when {
        result == null ->
            if (access != null && access != LocalAdb.Access.READY) Status(State.NO_ADB) else Status(State.UNKNOWN)
        // A file of DiPlay's that is in use stays stale even when the car's files no longer fit it.
        result.status == BydClusterAdasTheme.Status.UNSUPPORTED ->
            Status(if (result["state"] == "STALE") State.STALE else State.UNSUPPORTED, result["code"])
        result.status == BydClusterAdasTheme.Status.NO_CLUSTER -> Status(State.NO_CLUSTER)
        result.status == BydClusterAdasTheme.Status.READY -> Status(
            when (result["state"]) {
                "NONE" -> State.NOT_INSTALLED
                "CURRENT" -> State.CURRENT
                "STALE" -> State.STALE
                "OTHER" -> State.OTHER
                else -> State.UNKNOWN
            },
        )
        else -> Status(State.UNKNOWN)
    }

    private fun execute(app: Context, action: String): BydClusterAdasTheme.Result? {
        val apk: String? = app.applicationInfo?.sourceDir
        if (apk == null) return null
        return BydClusterAdasThemeMain.parse(run(app, BydClusterAdasThemeMain.command(apk, action)))
    }

    private fun probe(app: Context) {
        val result = execute(app, BydClusterAdasThemeMain.PROBE)
        probedAtMillis = nowMillis()
        publish(statusOf(result, access()))
        // Remember what the cluster showed, so a file DiPlay installed is found again at the next start.
        when (status.state) {
            State.CURRENT, State.STALE -> BydOutputSettings.setAdasPackInstalled(app, true)
            State.NOT_INSTALLED -> BydOutputSettings.setAdasPackInstalled(app, false)
            else -> Unit
        }
    }

    private fun change(app: Context, action: String): Outcome {
        if (busy) return Outcome.BUSY
        // Retiring never reloads the cluster, so it does not wait for P.
        if (action != BydClusterAdasThemeMain.RETIRE) {
            val gear = parked(app) ?: return Outcome.GEAR_UNKNOWN
            if (!gear) return Outcome.NOT_PARKED
        }
        busy = true
        onChange?.invoke()
        try {
            if (action != BydClusterAdasThemeMain.INSTALL) stopBeat(app)
            val result = execute(app, action)
            val outcome = outcomeOf(result)
            Log.i(TAG, "$action: ${result?.status ?: "no answer"} -> $outcome")
            when (outcome) {
                Outcome.INSTALLED, Outcome.ALREADY -> {
                    remember(app, installed = true)
                    publish(Status(State.CURRENT))
                }
                Outcome.REMOVED -> {
                    remember(app, installed = false)
                    publish(Status(State.NOT_INSTALLED))
                }
                Outcome.OTHER_ACTIVE -> publish(Status(State.OTHER))
                Outcome.UNSUPPORTED -> publish(Status(State.UNSUPPORTED, result?.get("code")))
                else -> probe(app)
            }
            return outcome
        } finally {
            busy = false
            onChange?.invoke()
        }
    }

    private fun remember(app: Context, installed: Boolean) {
        installedChecked = true
        BydOutputSettings.setAdasPackInstalled(app, installed)
        BydOutputSettings.setAdasPackRetired(app, false)
    }

    /**
     * While DiPlay's file is installed, whatever the switch says, asks once after each start of DiPlay whether it still
     * fits the car's software. A file that does not is given up, and Settings says so: the cluster would otherwise keep
     * loading an old copy of BYD's `cluster.qml` in place of the new one. In P the cluster is reloaded; otherwise it
     * uses its own file the next time it starts.
     */
    private fun checkInstalled(app: Context) {
        if (installedChecked || busy || nowMillis() < nextCheckAtMillis) return
        if (!BydOutputSettings.adasPackInstalled(app)) {
            // Nothing of DiPlay's was installed when DiPlay started; what a probe finds later is left to the driver.
            installedChecked = true
            return
        }
        nextCheckAtMillis = nowMillis() + PROBE_RETRY_MILLIS
        val answered = status.state !in UNANSWERED && nowMillis() - probedAtMillis < STALE_MILLIS
        if (!answered) probe(app)
        when (status.state) {
            in UNANSWERED -> return
            State.STALE -> retire(app)
            else -> Unit
        }
        installedChecked = true
    }

    private fun retire(app: Context) {
        var outcome = if (parked(app) == true) change(app, BydClusterAdasThemeMain.REMOVE) else Outcome.NOT_PARKED
        if (outcome == Outcome.NOT_PARKED || outcome == Outcome.GEAR_UNKNOWN) outcome = change(app, BydClusterAdasThemeMain.RETIRE)
        Log.w(TAG, "the installed file does not fit the car's software: retired -> $outcome")
        if (outcome == Outcome.REMOVED) {
            BydOutputSettings.setAdasSceneBesideMap(app, false)
            BydOutputSettings.setAdasPackRetired(app, true)
        }
    }

    private fun outcomeOf(result: BydClusterAdasTheme.Result?): Outcome = when (result?.status) {
        null -> if (access().let { it != null && it != LocalAdb.Access.READY }) Outcome.NO_ADB else Outcome.FAILED
        BydClusterAdasTheme.Status.INSTALLED -> Outcome.INSTALLED
        BydClusterAdasTheme.Status.ALREADY -> Outcome.ALREADY
        BydClusterAdasTheme.Status.REMOVED -> Outcome.REMOVED
        BydClusterAdasTheme.Status.OTHER_ACTIVE -> Outcome.OTHER_ACTIVE
        BydClusterAdasTheme.Status.UNSUPPORTED -> Outcome.UNSUPPORTED
        BydClusterAdasTheme.Status.NO_CLUSTER -> Outcome.NO_CLUSTER
        else -> Outcome.FAILED
    }

    private fun publish(next: Status) {
        status = next
        onChange?.invoke()
    }

    internal fun tick(app: Context) {
        checkInstalled(app)
        val wanted = BydOutputSettings.adasSceneBesideMap(app) && mapShown()
        if (!wanted) {
            stopBeat(app)
            if (!leaseHeld) shell.close()
            return
        }
        if (busy) return
        if (status.state == State.UNKNOWN && nowMillis() >= nextProbeAtMillis) {
            nextProbeAtMillis = nowMillis() + PROBE_RETRY_MILLIS
            probe(app)
        }
        if (status.state != State.CURRENT) {
            stopBeat(app)
            return
        }
        if (run(app, BydClusterAdasBeat.keepAlive()) != null) leaseHeld = true
    }

    private fun stopBeat(app: Context) {
        if (!leaseHeld) return
        run(app, BydClusterAdasBeat.stop())
        leaseHeld = false
    }

    internal fun resetForTest() {
        status = Status(State.UNKNOWN)
        busy = false
        leaseHeld = false
        nextProbeAtMillis = 0L
        nextCheckAtMillis = 0L
        installedChecked = false
        probedAtMillis = 0L
        onChange = null
        submit = ::submitToWorker
        run = ::runInShell
        access = { shell.lastAccess }
        parked = BydNavigationOutputs::parked
        mapShown = { BydClusterMapPause.clusterMapShown }
        nowMillis = { System.currentTimeMillis() }
    }
}
