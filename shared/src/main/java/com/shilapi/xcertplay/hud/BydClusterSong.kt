package com.shilapi.xcertplay.hud

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.util.Log
import com.shilapi.xcertplay.compat.Base64Compat
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Dashboard song; [line] preserves title-only HUD text, [source] selects a note's music icon. */
internal data class ClusterSong(val text: String, val playing: Boolean, val line: String = text, val source: Int? = null)

/**
 * The CarPlay song for the dashboard, from iAP2 NowPlayingUpdate (0x5001): title (1) and artist (12)
 * in MediaItemAttributes, playback status in PlaybackAttributes. Updates carry only what changed;
 * omitted fields retain their previous values, while an explicitly cleared title forgets the item.
 */
internal class ClusterSongState {
    private var title: String? = null
    private var artist: String? = null
    private var playing = false
    private var last: ClusterSong? = null

    /** Updates the cached card; null can mean unchanged or cleared, so consumers compare [current]. */
    fun accept(frame: Iap2Frame): ClusterSong? {
        if (frame.messageId != NOW_PLAYING_UPDATE) return null
        val body = runCatching { Iap2BodyReader.of(frame) }.getOrNull() ?: return null
        runCatching { body.optionalGroup(ITEM) }.getOrNull()?.let { item ->
            val nextTitle = runCatching { item.optionalString(TITLE) }.getOrNull()
            if (nextTitle != null) {
                title = nextTitle
                if (nextTitle.isBlank()) artist = null
            }
            if (nextTitle?.isBlank() != true) {
                runCatching { item.optionalString(ARTIST) }.getOrNull()?.let { artist = it }
            }
        }
        runCatching { body.optionalGroup(PLAYBACK)?.optionalU8(STATUS) }.getOrNull()?.let { status ->
            playing = status == STATUS_PLAYING || status == STATUS_SEEK_FORWARD || status == STATUS_SEEK_BACKWARD
        }
        val next = text(title, artist)?.let { ClusterSong(it, playing, title!!.trim()) }
        if (next == last) return null
        last = next
        return next
    }

    /** The card for the song known so far, if any. */
    fun current(showArtist: Boolean = true): ClusterSong? =
        if (showArtist) last else text(title, null)?.let { last?.copy(text = it) }

    /** The session ended: forget the song. */
    fun clear() {
        title = null
        artist = null
        playing = false
        last = null
    }

    companion object {
        const val NOW_PLAYING_UPDATE = 0x5001
        private const val ITEM = 0
        private const val TITLE = 1
        private const val ARTIST = 12
        private const val PLAYBACK = 1
        private const val STATUS = 0
        private const val STATUS_PLAYING = 1
        private const val STATUS_SEEK_FORWARD = 3
        private const val STATUS_SEEK_BACKWARD = 4

        /** The dashboard takes at most 255 bytes of UTF-16LE. */
        const val MAX_TEXT_BYTES = 255

        /** "Title — Artist", shortened to what the dashboard takes; null without a title. */
        fun text(title: String?, artist: String?): String? {
            val name = title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val full = artist?.trim()?.takeIf { it.isNotEmpty() }?.let { "$name — $it" } ?: name
            var end = full.length
            while (full.substring(0, end).toByteArray(Charsets.UTF_16LE).size > MAX_TEXT_BYTES) {
                end--
                if (end > 0 && Character.isLowSurrogate(full[end])) end--
            }
            return full.substring(0, end)
        }
    }
}

/**
 * Optional, needs ADB over network: shows the CarPlay song in the dashboard's music card. Apps cannot
 * write it (BYDAutoInstrumentDevice checks a BYD permission), but autoservice accepts the adb shell
 * user, so DiPlay runs [BydClusterSongTool] from its own APK under the head unit's adb shell. The card
 * appears as BYD's "other" music source; CarPlay's own source value is not drawn on this dashboard.
 */
internal object BydClusterSong {
    private const val TAG = "DiPlay-BYD-Song"
    private const val SOURCE_OTHERS = 11
    private const val STATE_PLAYING = 1
    private const val STATE_PAUSED = 2
    private const val STATE_STOPPED = 3

    private val worker = ClusterSongWorkerClient()
    private val controls = ClusterSongAcknowledgements()
    private const val NOTE_MILLIS = 3_000L
    private const val ON_CHANGE_MILLIS = 5_000L

    // An empty, stopped card: how the song leaves the dashboard between new songs.
    private val EMPTY = ClusterSong(" ", false)

    private val writer = Executors.newSingleThreadScheduledExecutor { Thread(it, "diplay-cluster-song").apply { isDaemon = true } }
    private val state = ClusterSongState() // guards wanted and note too
    @Volatile private var context: Context? = null
    private var wanted: ClusterSong? = null
    private var note: Any? = null // the note on the card now, if any
    private var onChange: Any? = null // the new song's few seconds on the card, while they run
    private var announced: String? = null // the last song shown in the "only when it changes" mode
    private var shown: ClusterSong? = null // writer thread
    private var firstLogged = false // writer thread

    init {
        writer.scheduleWithFixedDelay({
            runCatching {
                val app = context ?: return@runCatching
                val song = synchronized(state) { wanted } ?: return@runCatching
                if (!worker.keepAlive()) { shown = null; controls.reset() }
                if (song != shown || controls.needsRefresh(System.nanoTime())) write(app, song)
            }.onFailure { Log.w(TAG, "song worker renewal failed", it) }
        }, 5, 5, TimeUnit.SECONDS)
    }

    fun attach(appContext: Context) {
        context = appContext.applicationContext
    }

    /** NowPlayingUpdate frames; the song is followed even while the setting is off, so it can show at once. */
    fun onFrame(frame: Iap2Frame) {
        val app = context ?: return
        synchronized(state) {
            val previous = state.current()
            state.accept(frame)
            val song = state.current(BydOutputSettings.clusterSongArtist(app))
            if (state.current() == previous || !BydOutputSettings.clusterSong(app)) return
            if (song == null) {
                stop(app)
                return
            }
            if (!BydOutputSettings.clusterSongOnChange(app)) {
                show(app, song)
                return
            }
            // Only a new song shows, for a few seconds; play and pause alone do not bring it back.
            if (announced != song.text) {
                announced = song.text
                showForChange(app, song)
            } else if (onChange != null) show(app, song)
        }
    }

    private fun showForChange(app: Context, song: ClusterSong) {
        val token = Any()
        synchronized(state) {
            onChange = token
            show(app, song)
        }
        writer.schedule({ endChange(app, token) }, ON_CHANGE_MILLIS, TimeUnit.MILLISECONDS)
    }

    // Writer thread.
    private fun endChange(app: Context, token: Any) {
        synchronized(state) {
            if (onChange !== token) return
            onChange = null
            // Preferences are saved before their callback; an expiry in that gap must also be safe.
            if (!BydOutputSettings.clusterSong(app) || !BydOutputSettings.clusterSongOnChange(app)) return
            if (note != null) return
            wanted = EMPTY
        }
        write(app, EMPTY)
    }

    /** The "only when it changes" setting changed; applies at once. */
    fun onChangeSettingChanged() {
        val app = context ?: return
        settingChanged(BydOutputSettings.clusterSong(app))
    }

    /** The setting changed: show the current song now, or stop the card DiPlay set. */
    fun settingChanged(enabled: Boolean) {
        val app = context ?: return
        synchronized(state) {
            onChange = null
            announced = null
            val song = state.current(BydOutputSettings.clusterSongArtist(app)).takeIf { enabled }
            when {
                song == null -> stop(app)
                BydOutputSettings.clusterSongOnChange(app) -> {
                    announced = song.text
                    showForChange(app, song)
                }
                else -> show(app, song)
            }
        }
    }

    /** The session ended: forget the song and stop the card DiPlay set. */
    fun end() {
        val app = context ?: return
        synchronized(state) {
            state.clear()
            stop(app)
        }
    }

    /**
     * A short note in the dashboard's music card (for example the wheel zoom mode) for a few seconds,
     * optionally with another source icon, then the song again or no card, as before. Uses the same adb
     * shell as the song, so it shows only where that works.
     */
    fun note(text: String, source: Int? = null) {
        val app = context ?: return
        val token = Any()
        val card = synchronized(state) {
            note = token
            ClusterSong(text, state.current()?.playing ?: true, source = source).also { wanted = it }
        }
        writer.execute { write(app, card) }
        writer.schedule({ endNote(app, token) }, NOTE_MILLIS, TimeUnit.MILLISECONDS)
    }

    // Writer thread.
    private fun endNote(app: Context, token: Any) {
        val song = synchronized(state) {
            if (note !== token) return
            note = null
            val current = state.current(BydOutputSettings.clusterSongArtist(app)).takeIf { BydOutputSettings.clusterSong(app) }
            // In the "only when it changes" mode the card stays empty unless the new song's seconds still run.
            (if (current != null && BydOutputSettings.clusterSongOnChange(app) && onChange == null) EMPTY else current)
                .also { wanted = it }
        }
        if (song == null) clear(app) else write(app, song)
    }
    fun current(): ClusterSong? = synchronized(state) { state.current() }

    private fun show(app: Context, song: ClusterSong) {
        synchronized(state) {
            // Song updates still advance the cache and timer while a short wheel note has priority.
            if (note != null) return
            wanted = song
        }
        writer.execute { write(app, song) }
    }

    private fun stop(app: Context) {
        synchronized(state) {
            onChange = null
            announced = null
            note = null
            wanted = null
        }
        writer.execute { clear(app) }
    }

    private fun write(app: Context, song: ClusterSong) {
        // Only the newest song matters; older queued ones are skipped.
        if (synchronized(state) { wanted } != song || (song == shown && !controls.needsRefresh(System.nanoTime()))) return
        val text = Base64.encodeToString(song.text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val playing = when {
            song == EMPTY -> STATE_STOPPED
            song.playing -> STATE_PLAYING
            else -> STATE_PAUSED
        }
        if (!worker.ensure(app)) return
        if (synchronized(state) { wanted } != song) return
        val command = controls.command(song.source ?: SOURCE_OTHERS, playing, text, System.nanoTime())
        val output = worker.write(command.args)
        val previousSourceConfirmation = controls.sourceConfirmed
        controls.accept(output, command, System.nanoTime())
        if (previousSourceConfirmation != controls.sourceConfirmed) {
            Log.i(TAG, "dashboard sourceConfirmed=${controls.sourceConfirmed}")
        }
        if (ClusterSongWriteResult.acceptedFields(output, command.fields)) {
            shown = song
            if (!firstLogged) { firstLogged = true; Log.i(TAG, "dashboard write fields=${command.fields} sourceConfirmed=${controls.sourceConfirmed}") }
        }

    }

    private fun clear(app: Context) {
        if (synchronized(state) { wanted } != null) return
        worker.stop()
        controls.reset()
        shown = null
    }

}

/** Validate the complete app_process write response, rather than treating an empty response as success. */
internal object ClusterSongWriteResult {
    // The PR reporter observed this result on a DiLink 4 Seal while the music card updated.
    // Accept it only for the instrument writes below; it is not a general shell success code.
    private const val OBSERVED_VENDOR_RESULT = -2147482648

    fun accepted(output: String, clearing: Boolean = false): Boolean {
        val expected = if (clearing) setOf("state") else setOf("source", "state", "text")
        return acceptedFields(output, expected)
    }

    fun acceptedFields(output: String?, expected: Set<String>): Boolean {
        if (output == null) return false
        val received = mutableSetOf<String>()
        for (line in output.lineSequence().map { it.trim() }.filter { '=' in it }) {
            val name = line.substringBefore('=').trim()
            val result = line.substringAfter('=').trim().toIntOrNull() ?: return false
            if (name !in expected || !received.add(name) ||
                (result != 0 && result != OBSERVED_VENDOR_RESULT)) return false
        }
        return received == expected
    }
}

/**
 * Runs under the head unit's adb shell through app_process, not in DiPlay: writes the dashboard's music
 * source, play state and song text to the instrument (device 1007), as BYD's media controller does
 * (source feature resolved from the vehicle SDK, state 0x43E0000A, text 0x43FB1008 in UTF-16LE). Arguments: source, state and
 * base64 UTF-8 text, "-" to skip one. Prints "name=result" per write; 0 is success.
 */
object BydClusterSongTool {
    @JvmStatic fun main(args: Array<String>) {
        try { println(ClusterSongDeviceWriter().write(args)) }
        catch (error: Throwable) { println("write=ERR ${clusterSongError(error)}") }
        finally { System.exit(0) }
    }
}

/** Initializes only the previously approved instrument device and methods. */
@SuppressLint("PrivateApi")
internal class ClusterSongDeviceWriter(
    suppliedDevice: Any? = null,
    private val sourceFeatureClass: () -> Class<*> = { Class.forName("android.hardware.bydauto.BYDAutoFeatureIds\$Instrument") },
) {
    private val sourceFeature = lazy { runCatching { resolveSourceFeature(sourceFeatureClass()) } }
    private val started = System.nanoTime()
    private val device: Any
    private val setState: java.lang.reflect.Method
    private val setInfo: java.lang.reflect.Method
    private val contextMs: Long
    private val deviceMs: Long

    init {
        runCatching { android.os.Looper.prepareMainLooper() }
        val contextReady: Long
        val deviceClass: Class<*>
        if (suppliedDevice != null) {
            device = suppliedDevice
            deviceClass = device.javaClass
            contextReady = started
        } else {
            val thread = Class.forName("android.app.ActivityThread")
            val main = thread.getMethod("systemMain").invoke(null)
            val context = thread.getMethod("getSystemContext").invoke(main)
            contextReady = System.nanoTime()
            deviceClass = Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice")
            device = try {
                deviceClass.getMethod("getInstance", Context::class.java).invoke(null, context)
            } catch (_: InvocationTargetException) {
                deviceClass.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)
            }
        }
        setState = deviceClass.getMethod("setMediaState", Int::class.java, Int::class.java, Int::class.java)
        setInfo = deviceClass.getMethod("setMediaInfo", Int::class.java, Int::class.java, ByteArray::class.java)
        contextMs = (contextReady - started) / 1_000_000L
        deviceMs = (System.nanoTime() - contextReady) / 1_000_000L
    }

    private fun resolveSourceFeature(featureClass: Class<*>): Int {
        val field = featureClass.getField("INSTRUMENT_MUSIC_SOURCE_SET")
        require(java.lang.reflect.Modifier.isStatic(field.modifiers) && field.type == Int::class.javaPrimitiveType) {
            "INSTRUMENT_MUSIC_SOURCE_SET must be a static int"
        }
        val id = field.getInt(null)
        require(id == 0x43E00030 || id == 0x38F00030) { "unsupported INSTRUMENT_MUSIC_SOURCE_SET 0x${id.toUInt().toString(16)}" }
        return id
    }

    fun write(args: Array<String>): String {
        val response = mutableListOf<String>()
        val start = System.nanoTime()
        fun result(name: String, action: () -> Any?) {
            response += "$name=${runCatching { action().toString() }.getOrElse { "ERR ${clusterSongError(it)}" }}"
        }
        args.getOrNull(0)?.takeIf { it != "-" }?.let { result("source") { setState.invoke(device, 1007, sourceFeature.value.getOrThrow(), it.toInt()) } }
        val sourceDone = System.nanoTime()
        args.getOrNull(1)?.takeIf { it != "-" }?.let { result("state") { setState.invoke(device, 1007, 0x43E0000A, it.toInt()) } }
        val stateDone = System.nanoTime()
        args.getOrNull(2)?.takeIf { it != "-" }?.let { encoded ->
            result("text") {
                val text = String(Base64Compat.decode(encoded), Charsets.UTF_8).toByteArray(Charsets.UTF_16LE)
                if (text.size > ClusterSongState.MAX_TEXT_BYTES) "ERR too long" else setInfo.invoke(device, 1007, 0x43FB1008, text)
            }
        }
        val textDone = System.nanoTime()
        response += "timing contextMs:$contextMs deviceMs:$deviceMs sourceMs:${(sourceDone - start) / 1_000_000L} stateMs:${(stateDone - sourceDone) / 1_000_000L} textMs:${(textDone - stateDone) / 1_000_000L}"
        return response.joinToString("\n")
    }
}

private fun clusterSongError(error: Throwable): String {
    val cause = error.cause ?: error
    return (cause.javaClass.name + (cause.message?.let { ": $it" } ?: "")).replace('\n', ' ').replace('\r', ' ').take(160)
}


/** 分别确认来源和播放状态；来源失败时歌词仍可更新，30 秒后再试来源。 */
internal class ClusterSongAcknowledgements {
    data class Command(val args: String, val source: Int?, val state: Int?, val fields: Set<String>)
    private var source: Int? = null
    private var state: Int? = null
    val sourceConfirmed: Boolean get() = source != null
    private var retrySourceAt = 0L
    private var retryStateAt = 0L
    fun reset() { source = null; state = null; retrySourceAt = 0L; retryStateAt = 0L }
    fun needsRefresh(now: Long) = (source == null && now >= retrySourceAt) || (state == null && now >= retryStateAt)
    fun command(nextSource: Int, nextState: Int, text: String, now: Long): Command {
        val sourceValue = nextSource.takeIf { source != it && now >= retrySourceAt }
        val stateValue = nextState.takeIf { state != it && now >= retryStateAt }
        return Command("${sourceValue ?: "-"} ${stateValue ?: "-"} $text", sourceValue, stateValue,
            buildSet { add("text"); if (sourceValue != null) add("source"); if (stateValue != null) add("state") })
    }
    fun accept(output: String?, command: Command, now: Long) {
        if (output == null) { reset(); return }
        fun accepted(name: String) = ClusterSongWriteResult.acceptedFields(
            output.lineSequence().filter { it.substringBefore('=') == name }.joinToString("\n"), setOf(name))
        command.source?.let { if (accepted("source")) { source = it; retrySourceAt = 0L }
            else { source = null; retrySourceAt = now + TimeUnit.SECONDS.toNanos(30) } }
        command.state?.let { if (accepted("state")) { state = it; retryStateAt = 0L }
            else { state = null; retryStateAt = now + TimeUnit.SECONDS.toNanos(5) } }
    }
}
