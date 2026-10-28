package com.shilapi.xcertplay.hud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the instrument cluster's ADAS view on its small tile while DiPlay's CarPlay map is on the cluster.
 *
 * On a BYD Han the car sometimes opens a large ADAS scene in the centre of the cluster, which covers the map. The
 * steering wheel's menu key closes it. The car reports its ADAS view as the instrument event `26f00028` (1 is the
 * normal small tile, 6 the large scene), and the frame `26,F0,00,28,01,01` sets it back to 1, as the menu key does.
 * It is sent through BYD's ClusterDebug receiver, an exported receiver without a permission that passes the frame to
 * the car's test channel. Nothing is hidden: the small tile and its warnings stay.
 *
 * DiPlay cannot register for the event (BYD's permission), so while this is on and the map is on the cluster it
 * reads the event's log lines over the network ADB every [POLL_SECONDS] seconds.
 */
object BydClusterAdasTile {
    private const val TAG = "DiPlay-AdasTile"
    const val PACKAGE = "com.byd.clusterdebug"
    const val RECEIVER = "com.byd.clusterdebug.BroadcastReceiverCAN"
    const val ACTION = "com.byd.cluster.spi"
    const val EXTRA_FRAME = "wholeFrame"
    const val SMALL_FRAME = "26,F0,00,28,01,01"
    const val SMALL_REPEATS = 5
    const val POLL_SECONDS = 2L

    internal const val READ_COMMAND = "logcat -d -v threadtime -t 200 -s BYDAutoInstrumentDevice:D"
    internal const val EVENT_ID = "26f00028"
    internal const val BIG_SCENE = 6

    private val started = AtomicBoolean(false)
    private val shell = BydAdbShell(TAG)
    private val watch = BigSceneWatch()
    @Volatile private var context: Context? = null

    /** Reads the log over adb. Blocking, and only called on the poll thread; tests replace it. */
    @Volatile internal var read: (Context) -> String? = { app -> shell.run(app, READ_COMMAND) }
    @Volatile internal var send: (Context) -> Boolean = ::showSmall
    @Volatile internal var receiverAvailable: (Context) -> Boolean = ::available
    @Volatile internal var mapShown: () -> Boolean = { BydClusterMapPause.clusterMapShown }

    /** Whether the head unit has the receiver, enabled and exported; nothing is sent. */
    fun available(context: Context): Boolean = runCatching {
        val info = context.packageManager.getReceiverInfo(ComponentName(PACKAGE, RECEIVER), 0)
        info.enabled && info.exported && info.permission == null
    }.getOrDefault(false)

    fun intent(frame: String): Intent = Intent(ACTION)
        .setComponent(ComponentName(PACKAGE, RECEIVER))
        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        .putExtra(EXTRA_FRAME, frame)

    /** Asks for the small tile; true when every broadcast was handed to Android. This does not confirm the cluster. */
    fun showSmall(context: Context): Boolean = runCatching {
        repeat(SMALL_REPEATS) { context.sendBroadcast(intent(SMALL_FRAME)) }
        Log.i(TAG, "sent $SMALL_REPEATS x $SMALL_FRAME")
        true
    }.getOrElse {
        Log.w(TAG, "could not send $SMALL_FRAME: ${it.javaClass.simpleName}")
        false
    }

    /** Starts the poll once; it does nothing while the setting is off or the map is not on the cluster. */
    fun initialize(appContext: Context) {
        context = appContext.applicationContext
        if (started.compareAndSet(false, true)) {
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "diplay-adas-tile").apply { isDaemon = true }
            }.scheduleWithFixedDelay({ context?.let(::tick) }, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS)
        }
    }

    internal fun tick(app: Context) {
        val active = BydOutputSettings.keepAdasTileSmall(app) && mapShown() && receiverAvailable(app)
        if (!active) {
            watch.reset()
            shell.close()
            return
        }
        if (watch.onRead(read(app))) {
            Log.i(TAG, "the car shows the large ADAS scene; asking for the small tile")
            send(app)
        }
    }

    internal fun resetForTest() = watch.reset()

    /** One adb log line of the car's instrument events, as `logcat -v threadtime` prints it. */
    internal data class Event(val time: String, val value: Int)

    internal fun parse(output: String?): List<Event> = output.orEmpty().lineSequence().mapNotNull { line ->
        EVENT.matchEntire(line.trim())?.let { match ->
            match.groupValues[2].toIntOrNull()?.let { Event(match.groupValues[1], it) }
        }
    }.toList()

    private val EVENT = Regex(
        """(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\s+\d+\s+\d+\s+D\s+BYDAutoInstrumentDevice:\s+postEvent device_type: 1007, """ +
            """event_type =$EVENT_ID, value = (-?\d+)""",
    )

    /**
     * Decides from successive log reads whether the car has newly opened the large scene. The first read only marks
     * what is already in the log, so an old event never triggers a send. At most one send per [MIN_GAP_MILLIS], and
     * [MAX_PER_MINUTE] a minute, so a car that keeps reopening the scene cannot make DiPlay flood the bus.
     */
    internal class BigSceneWatch(private val nowMillis: () -> Long = SystemClock::elapsedRealtime) {
        private var lastSeen: String? = null
        private val sends = ArrayDeque<Long>()

        fun reset() {
            lastSeen = null
            sends.clear()
        }

        fun onRead(output: String?): Boolean {
            val events = parse(output)
            val latest = events.lastOrNull() ?: return false
            val seen = lastSeen
            if (seen == null || latest.time < seen) {
                lastSeen = latest.time
                return false
            }
            val fresh = events.filter { it.time > seen }
            if (fresh.isEmpty()) return false
            lastSeen = fresh.last().time
            if (fresh.last().value != BIG_SCENE) return false
            val now = nowMillis()
            while (sends.isNotEmpty() && now - sends.first() >= MINUTE_MILLIS) sends.removeFirst()
            if (sends.size >= MAX_PER_MINUTE || (sends.isNotEmpty() && now - sends.last() < MIN_GAP_MILLIS)) return false
            sends.addLast(now)
            return true
        }
    }

    private const val MIN_GAP_MILLIS = 3_000L
    private const val MAX_PER_MINUTE = 6
    private const val MINUTE_MILLIS = 60_000L
}
