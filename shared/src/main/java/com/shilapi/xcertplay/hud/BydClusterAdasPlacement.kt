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
 * Installing and removing the file reload the cluster's interface, so they run only while the car is parked. All work
 * runs on one thread, through the same one-shot adb shell the other BYD features use; nothing is done without the
 * driver's approval of that link, and it is never asked for here.
 */
object BydClusterAdasPlacement : BydClusterAdasPlacementControl {
    private const val TAG = "DiPlay-AdasPlacement"
    const val TICK_SECONDS = 5L
    private const val HELPER_TIMEOUT_MILLIS = 30_000
    private const val PROBE_RETRY_MILLIS = 60_000L
    private const val STALE_MILLIS = 30_000L

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

    /** Starts the heartbeat tick once; it does nothing while the setting is off or the map is not on the cluster. */
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
        result.status == BydClusterAdasTheme.Status.UNSUPPORTED -> Status(State.UNSUPPORTED, result["code"])
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
    }

    private fun change(app: Context, action: String): Outcome {
        if (busy) return Outcome.BUSY
        val gear = parked(app) ?: return Outcome.GEAR_UNKNOWN
        if (!gear) return Outcome.NOT_PARKED
        busy = true
        onChange?.invoke()
        try {
            if (action == BydClusterAdasThemeMain.REMOVE) stopBeat(app)
            val result = execute(app, action)
            val outcome = outcomeOf(result)
            Log.i(TAG, "$action: ${result?.status ?: "no answer"} -> $outcome")
            when (outcome) {
                Outcome.INSTALLED, Outcome.ALREADY -> publish(Status(State.CURRENT))
                Outcome.REMOVED -> publish(Status(State.NOT_INSTALLED))
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
