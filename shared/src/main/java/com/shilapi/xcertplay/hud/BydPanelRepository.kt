package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.EnumMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Reads the side panel's BYD signals over the same adb shell (autoservice binder) the tyres and battery use,
 * but independently: a field the car does not answer is simply dropped, never a failure. Everything a tier
 * needs is read in one shell round-trip (a marker echoes before each `service call`), fields are polled only
 * while a tile shows them, and on their own tier. Addresses follow the firmware catalog when the head unit
 * reports one, so the DiLink 5 constants line up with this car's numbers.
 */
object BydPanelRepository {
    private const val TAG = "DiPlay-BYD-Panel"
    private const val FAST_MILLIS = 1_000L
    private const val NORMAL_MILLIS = 5_000L
    private const val SLOW_MILLIS = 30_000L
    private const val STALE_MILLIS = 90_000L
    /** A reading past [STALE_MILLIS] is kept this long and shown dimmed, so a quiet field does not go blank. */
    private const val STALE_RETAIN_MILLIS = 600_000L
    private const val BATCH_READ_TIMEOUT = 20_000
    /** Empty replies this many times in a row before the panel concludes the autoservice is gone. */
    private const val NO_SERVICE_LIMIT = 6
    private const val NO_SERVICE_RETRY_MILLIS = 30_000L

    private val shell = BydAdbShell(TAG)
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "diplay-panel").apply { isDaemon = true }
    }

    @Volatile private var appContext: Context? = null
    @Volatile private var noService = false
    @Volatile private var noServiceSince = 0L
    @Volatile private var catalogResolved = false
    private var emptyReplies = 0
    private val values = LinkedHashMap<String, Double>()
    private val valueMillis = HashMap<String, Long>()
    private val requested = LinkedHashMap<String, BydTier>()
    private val tasks = EnumMap<BydTier, ScheduledFuture<*>>(BydTier::class.java)
    private val lock = Any()

    private var addresses: Map<BydPanelField, Pair<Int, Int>> =
        BydPanelFields.all.associateWith { it.defaultDevice to it.defaultFid }

    /** The latest plausible reading for a field id, or null when absent or older than [STALE_MILLIS]. */
    fun latest(id: String): Double? = synchronized(lock) {
        values[id]?.takeIf { SystemClock.elapsedRealtime() - (valueMillis[id] ?: 0L) <= STALE_MILLIS }
    }

    /**
     * The kept reading for a field id whose last successful read is past [STALE_MILLIS] but within
     * [STALE_RETAIN_MILLIS]; the panel shows it dimmed instead of a dash while reads are down.
     */
    fun stale(id: String): Double? = synchronized(lock) {
        values[id]?.takeIf {
            val age = SystemClock.elapsedRealtime() - (valueMillis[id] ?: 0L)
            age > STALE_MILLIS && age <= STALE_RETAIN_MILLIS
        }
    }

    /** True when the head unit has no BYD autoservice, so the whole panel should hide its vehicle tiles. */
    fun unavailable(): Boolean = noService

    /** Declare the fields on screen and their tiers; starts the pollers for tiers not already running. */
    fun subscribe(context: Context, needed: Map<String, BydTier>) {
        appContext = context.applicationContext
        val start: MutableList<BydTier>
        synchronized(lock) {
            // A marked-out service is retried after a cooldown, so a transient shell break (an ADB
            // permission change, a busy head unit) does not keep the vehicle tiles dead all session.
            if (noService && SystemClock.elapsedRealtime() - noServiceSince < NO_SERVICE_RETRY_MILLIS) return
            if (noService) {
                noService = false
                emptyReplies = 0
                Log.i(TAG, "re-probing BYD autoservice after cooldown")
            }
            requested.clear()
            requested.putAll(needed)
            start = BydTier.entries
                .filter { tier -> needed.any { it.value == tier } && !tasks.containsKey(tier) }
                .toMutableList()
        }
        if (!catalogResolved) executor.execute(::resolveCatalog)
        start.forEach(::startTier)
    }

    /** Stop a tier's poller when nothing on screen needs it any more (a tile was removed or the panel closed). */
    fun unsubscribeUnused() {
        synchronized(lock) {
            if (noService) return
            val inUse = requested.values.toSet()
            BydTier.entries.filter { it !in inUse }.forEach { tier -> tasks.remove(tier)?.cancel(false) }
        }
    }

    @Synchronized
    fun stopAll() {
        synchronized(lock) {
            tasks.values.forEach { it.cancel(false) }
            tasks.clear()
            requested.clear()
            noService = false
            emptyReplies = 0
        }
        catalogResolved = false
        executor.execute(shell::close)
    }

    private fun startTier(tier: BydTier) {
        synchronized(lock) {
            if (noService || tasks.containsKey(tier)) return
            val millis = when (tier) {
                BydTier.FAST -> FAST_MILLIS
                BydTier.NORMAL -> NORMAL_MILLIS
                BydTier.SLOW -> SLOW_MILLIS
            }
            tasks[tier] = executor.scheduleWithFixedDelay({ poll(tier) }, 0, millis, TimeUnit.MILLISECONDS)
        }
    }

    private fun resolveCatalog() {
        val app = appContext ?: return
        val command = "CLASSPATH=" + shellQuote(app.packageCodePath) +
            " app_process /system/bin ${Byd13CatalogProbeMain::class.java.name}"
        val catalog = BydFirmwareCatalog.parse(shell.run(app, command, BATCH_READ_TIMEOUT))
        if (catalog != null) {
            addresses = BydPanelFields.all.associateWith { field ->
                // A catalog number equal to the compiled constant says this firmware already uses it;
                // taking the catalog's device then would move a working read to another ECU.
                val fid = field.symbol?.let(catalog.symbols::get)?.takeIf { it != field.defaultFid }
                if (fid != null) (catalog.devices[field.deviceName] ?: field.defaultDevice) to fid
                else field.defaultDevice to field.defaultFid
            }
            Log.i(TAG, "panel catalog resolved for ${catalog.symbols.size} symbols")
        }
        catalogResolved = true
    }

    private fun poll(tier: BydTier) {
        val app = appContext ?: return
        val fields = synchronized(lock) { BydPanelFields.all.filter { requested[it.id] == tier } }
        if (fields.isEmpty()) return
        val reply = runCatching { readBatch(app, fields) }.getOrNull() ?: return
        if (reply.isEmpty()) {
            // One empty batch is a transient break (a slow shell, an ADB permission change), not proof
            // the autoservice is gone; only a run of them stops the pollers.
            val giveUp = synchronized(lock) {
                emptyReplies++
                emptyReplies >= NO_SERVICE_LIMIT
            }
            if (giveUp) markNoService()
            return
        }
        val decoded = HashMap<String, Double>()
        for (field in fields) {
            BydPanelCodec.decode(field, reply[field.id])?.let { decoded[field.id] = it }
        }
        synchronized(lock) {
            emptyReplies = 0
            val now = SystemClock.elapsedRealtime()
            // Every successful read refreshes the timestamp, even when the value did not move: a field
            // that stays constant (ignition on, pedals at rest) is live data, not a stale reading.
            decoded.forEach { (id, value) ->
                values[id] = value
                valueMillis[id] = now
            }
        }
    }

    /**
     * One shell round-trip for the tier's fields. The output is split on the markers echoed before each
     * `service call`, and each field's word parsed from its own segment. An empty result means the shell
     * itself failed (a genuine transport break, not one field not answering); a segment with no `Parcel(`
     * leaves that field's word null, which the decoder drops while its neighbours still stand.
     */
    private fun readBatch(context: Context, fields: List<BydPanelField>): Map<String, Int?> {
        val markers = fields.map { marker(it.id) }
        val joined = fields.indices.joinToString("; ") { index ->
            val field = fields[index]
            val (device, fid) = addresses[field] ?: field.defaultDevice to field.defaultFid
            "echo '${markers[index]}'; ${field.command(device, fid)}"
        }
        val output = shell.run(context, joined, BATCH_READ_TIMEOUT) ?: return emptyMap()
        val starts = markers.map { output.indexOf(it) }
        if (starts.none { it >= 0 }) return emptyMap()
        return fields.indices.mapNotNull { index ->
            if (starts[index] < 0) return@mapNotNull null
            val start = starts[index] + markers[index].length
            val end = starts.drop(index + 1).firstOrNull { it >= 0 } ?: output.length
            val segment = output.substring(start, end)
            fields[index].id to BydParcel.value(segment.takeIf { "Parcel(" in segment })
        }.toMap()
    }

    private fun marker(id: String) = "###$id###"
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    private fun markNoService() {
        Log.i(TAG, "no BYD autoservice; panel fields are not read")
        noService = true
        noServiceSince = SystemClock.elapsedRealtime()
        synchronized(lock) {
            emptyReplies = 0
            tasks.values.forEach { it.cancel(false) }
            tasks.clear()
        }
    }
}
