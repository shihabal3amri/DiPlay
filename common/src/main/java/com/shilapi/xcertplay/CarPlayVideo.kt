package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.airplay.VideoRequestAdmission
import com.shilapi.xcertplay.airplay.VideoInCar
import com.shilapi.xcertplay.airplay.VideoResourceFile
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayVideoListener
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicLong
import java.security.MessageDigest

/**
 * iOS 27 video in car (see [VideoInCar]). The iPhone hands the car a media URL (insertPlayQueueItem)
 * and drives it (setRate, seek, stop); the car plays it in [CarPlayVideoActivity], which opens as soon
 * as the iPhone starts the item (or sends requestUI "videoplayback:") and only while the shared
 * video gate allows it (normally P, or the explicit force-video override).
 */
internal object CarPlayVideo : CarPlayVideoListener {
    private const val TAG = "DiPlay-Video"
    private const val LEGACY_URL_TIMEOUT_SECONDS = 10L
    const val SKIP_MILLIS = 10_000

    private val main = Handler(Looper.getMainLooper())
    private val sender = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue<Runnable>()) {
        Thread(it, "diplay-video-reply").apply { isDaemon = true }
    }
    @Volatile private var appContext: Context? = null
    @Volatile private var controller: CarPlayController? = null

    // Main-thread state; CarPlayVideoActivity applies it. The stream and item are also read by loader threads.
    @Volatile private var streamId: Long? = null
    @Volatile private var itemUuid: Any? = null
    var url: String? = null
        private set
    /** The iPhone called the item streaming (HLS) rather than a file. */
    var streaming = false
        private set
    var startMillis = 0
        private set
    var playing = false
        private set
    var pendingSeekMillis: Int? = null
    // Separate user intent from iPhone playback messages and an Activity start not yet delivered.
    @Volatile private var playbackEnded = false
    // Only bounded identity fingerprints survive return; never retain media bytes, files or URLs.
    private val retiredItems = LinkedHashSet<String>()
    @Volatile internal var playbackGeneration = 0L
        private set
    @Volatile private var commandGeneration = 0L
    internal const val EXTRA_GENERATION = "video_generation"
    internal const val EXTRA_LAUNCH = "video_launch"
    private var nextLaunch = 0L
    private var launchId: Long? = null
    private var playerOpening = false
    private data class PendingOpen(val stream: Long?, val expiresAt: Long, val play: Boolean?, val generation: Long,
        val attempts: Int = 0)
    private var pendingOpen: PendingOpen? = null
    private var deferredPlay: PendingOpen? = null
    private var openingTimeout: Runnable? = null
    var activity: CarPlayVideoActivity? = null
        set(value) {
            field = value
            if (value != null) playerOpening = false
        }

    fun attach(context: Context, next: CarPlayController) {
        if (controller !== next) {
            endPlayback("controller_changed", notify = false)
            retiredItems.clear()
            playbackEnded = false
            streamId = null
        }
        appContext = context.applicationContext
        controller = next
        next.videoListener = this
    }

    /** A queued Activity start may arrive after Back, stop, or a session/gear change. */
    fun attachPlayer(next: CarPlayVideoActivity): Boolean {
        val intent = next.intent
        val generation = intent?.getLongExtra(EXTRA_GENERATION, -1L)
        val ticket = intent?.getLongExtra(EXTRA_LAUNCH, -1L)
        val reason = when {
            generation != playbackGeneration || ticket != launchId -> "stale_launch"
            activity != null && activity !== next -> "already_attached"
            playbackEnded || url == null -> "playback_ended"
            !VideoInCar.allowed -> "not_allowed"
            else -> null
        }
        if (reason != null) {
            diagnostic("event=player_attach_rejected generation=$generation launch=$ticket reason=$reason")
            return false
        }
        openingTimeout?.let(main::removeCallbacks)
        openingTimeout = null
        playerOpening = false
        pendingOpen = null
        activity = next
        diagnostic("event=player_attached generation=$generation launch=$ticket")
        return true
    }

    /** Called only after the old player's resources have been released. */
    fun detachPlayer(previous: CarPlayVideoActivity, changingConfigurations: Boolean, positionMillis: Int? = null) {
        if (activity !== previous) return
        activity = null
        diagnostic("event=player_detached generation=$playbackGeneration configuration=$changingConfigurations")
        if (changingConfigurations) return
        if (pendingOpen != null && !playbackEnded) show()
        else if (!playbackEnded && url != null &&
            previous.intent?.getLongExtra(EXTRA_GENERATION, -1L) == playbackGeneration) {
            pausePlayback("player_destroyed", positionMillis)
        }
    }

    override fun readParked(): Boolean? = appContext?.let(BydNavigationOutputs::parked)

    override fun usesIphoneNetworkForVideo(): Boolean =
        appContext?.let(AirPlayPersistence::loadVideoUseIphoneNetwork) == true

    fun diagnostic(message: String) {
        try { controller?.recordVideoDiagnostic(message) } catch (_: Exception) { }
    }

    override fun claimVideoResourceResponse(streamId: Long, requestId: Long): Boolean = synchronized(urlRequestLock) {
        streamId == this.streamId && pendingUrls[requestId]?.isDone == false && claimedReplies.add(requestId)
    }

    override fun onVideoResourceRejected(streamId: Long, reason: String, bodyBytes: Int) {
        val item = synchronized(urlRequestLock) {
            if (streamId != this.streamId) return
            // The discarded body has no readable FCUP request ID. Fail this stream's bounded
            // outstanding loads immediately; unrelated streams/items remain untouched.
            val failed = pendingUrls.entries.count { (id, answer) ->
                answer.completeExceptionally(IphoneVideoResourceRejectedException(reason, id))
            }
            diagnostic("event=resource_rejected stream=$streamId reason=$reason bytes=$bodyBytes failedLoads=$failed")
            itemUuid
        }
        main.post {
            if (this.streamId == streamId && itemUuid == item) activity?.onResourcePressure(reason)
        }
    }

    override fun onVideoAllowedChanged(allowed: Boolean) {
        if (!allowed) main.post { pausePlayback("left_park", activity?.resumePositionMillis()) }
    }

    override fun onVideoSessionEnded() {
        val generation = commandGeneration
        main.post {
            if (generation != commandGeneration) return@post
            endPlayback("session_ended", notify = false)
            streamId = null
        }
    }

    override fun onVideoUiRequested() {
        val generation = commandGeneration
        main.post {
            if (generation != commandGeneration) return@post
            diagnostic("event=open_requested generation=$playbackGeneration itemReady=${url != null} ended=$playbackEnded opening=$playerOpening attached=${activity != null}")
            if (playerOpening) return@post
            playbackEnded = false // An explicit new UI request starts the next playback, including the same channel.
            deferOpen(null, null)
            if (url != null) show()
        }
    }

    override fun onVideoMessage(streamId: Long, message: Map<String, Any?>) {
        // Resource futures are thread-safe; do not queue their payloads behind Android UI work.
        if (message["type"] == "unhandledURL") {
            onUrlLoaded(streamId, message)
            return
        }
        val generation = commandGeneration
        if (!main.post {
            if (generation == commandGeneration) handle(streamId, message)
        }) closeResponse(message["response"] as? Map<*, *>)
    }

    /**
     * A steering-wheel media key (CarPlayMediaButton index) while the player is on screen: play and pause
     * toggle it, next and previous skip 10 s. The iPhone is not asked: a CarPlay play/pause makes it end
     * the video session. Returns false when no player is open. Main thread.
     */
    fun onMediaKey(index: Int): Boolean {
        val player = activity?.takeUnless { it.playbackReleased || it.isFinishing } ?: return false
        when (index) {
            CarPlayMediaButton.NEXT -> player.skip(SKIP_MILLIS)
            CarPlayMediaButton.PREVIOUS -> player.skip(-SKIP_MILLIS)
            else -> setPlaying(!playing)
        }
        return true
    }

    /** Play or pause from the car (wheel or on-screen button); tells the iPhone at once. Main thread. */
    fun setPlaying(next: Boolean) {
        if (next && playbackEnded) return
        playing = next
        activity?.applyRate()
        streamId?.let { reply(it, VideoInCar.playbackStateNotification(next, itemUuid)) }
    }

    /**
     * The car's player cannot play the item, for example protected video: tell the iPhone as Apple's
     * receiver does, say so on the car screen and go back to CarPlay instead of showing black. Main thread.
     */
    fun onPlayerFailed(code: Int) {
        Log.w(TAG, "item cannot play here code=$code")
        streamId?.let { reply(it, VideoInCar.errorNotification(itemUuid, code)) }
        appContext?.let { Toast.makeText(it, R.string.video_cannot_play, Toast.LENGTH_LONG).show() }
        stop()
    }

    /** Back pauses the current item while releasing its player and pending resource loads. */
    fun dismissPlayer(positionMillis: Int?) {
        commandGeneration++
        pausePlayback("returned", positionMillis)
    }

    /** Natural completion clears the finished item, allowing a fresh queue item to play again. */
    fun onPlaybackCompleted() {
        endPlayback("completed", notify = true)
    }

    private fun handle(streamId: Long, message: Map<String, Any?>) {
        val request = message["kind"] == "request"
        val type = message["type"] as? String
        if (type == "setRate" && ((message["rate"] as? Number)?.toDouble() ?: 0.0) <= 0 &&
            deferredPlay?.stream == streamId) deferredPlay = null
        if (playbackEnded && type == "setRate") {
            if (((message["rate"] as? Number)?.toDouble() ?: 0.0) > 0 && VideoInCar.allowed) {
                // A play-first handoff may precede both the item and requestUI. Never open anything
                // until a fresh item identity (or an explicit UI request) validates the next playback.
                deferredPlay = PendingOpen(streamId, SystemClock.uptimeMillis() + 30_000, true, playbackGeneration)
                diagnostic("event=play_deferred generation=$playbackGeneration stream=$streamId reason=awaiting_fresh_item")
            }
            return
        }
        if (playbackEnded && type in setOf("setRate", "seek", "setProperty")) {
            diagnostic("event=command_dropped generation=$playbackGeneration command=$type reason=playback_ended")
            return
        }
        when (type) {
            "insertPlayQueueItem" -> {
                // An app's own scheme is loaded through the iPhone (IphoneResolvingDataSource).
                val item = VideoInCar.parseItem(message, iphoneLoadsAppSchemes = true)
                if (item == null) {
                    Log.w(TAG, "queue item this player cannot play")
                    return
                }
                val key = itemKey(streamId, item.uuid)
                var waiting = pendingOpen
                val requested = waiting != null && waiting.generation == playbackGeneration &&
                    waiting.expiresAt >= SystemClock.uptimeMillis() && (waiting.stream == null || waiting.stream == streamId)
                val repeatedCurrent = url != null && this.streamId == streamId && sameItemId(itemUuid, item.uuid)
                val retired = key != null && key in retiredItems
                // Fresh media can arrive before requestUI, or a phone handoff can use item + setRate only.
                // The old item's identity remains rejected unless a new explicit opening request allows reuse.
                if ((retired && !requested && !repeatedCurrent) ||
                    (playbackEnded && !requested && key == null && this.streamId == streamId)) {
                    diagnostic("event=command_dropped generation=$playbackGeneration command=$type stream=$streamId reason=retired_item")
                    return
                }
                if (playbackEnded) {
                    playbackEnded = false
                    diagnostic("event=fresh_item generation=$playbackGeneration stream=$streamId identified=${key != null}")
                }
                val earlyPlay = deferredPlay
                if (earlyPlay != null && earlyPlay.generation == playbackGeneration &&
                    earlyPlay.expiresAt >= SystemClock.uptimeMillis() && earlyPlay.stream == streamId) {
                    playing = true
                    if (waiting == null) {
                        waiting = earlyPlay
                        pendingOpen = earlyPlay
                    }
                    deferredPlay = null
                } else if (earlyPlay != null && earlyPlay.expiresAt < SystemClock.uptimeMillis()) deferredPlay = null
                Log.i(TAG, "queue item app=${(message["item"] as? Map<*, *>)?.get("clientBundleID")}")
                synchronized(urlRequestLock) {
                    if (this.streamId != streamId || url != item.url || itemUuid != item.uuid) cancelPendingUrls()
                    this.streamId = streamId
                    url = item.url
                    itemUuid = item.uuid
                }
                val queued = message["item"] as? Map<*, *>
                streaming = queued?.get("mediaType") == "streaming"
                val app = (queued?.get("clientBundleID") as? String)
                    ?.takeIf { it.length <= 160 && it.matches(Regex("[A-Za-z0-9_.-]+")) } ?: "unknown"
                diagnostic("event=item generation=$playbackGeneration stream=$streamId app=$app streaming=$streaming")
                Log.i(TAG, "queue item host=${android.net.Uri.parse(item.url).host} mediaType=${queued?.get("mediaType")}")
                startMillis = item.startMillis
                activity?.takeUnless { it.playbackReleased || it.isFinishing }?.load()
                if (waiting != null && waiting.expiresAt < SystemClock.uptimeMillis()) pendingOpen = null
                else if (waiting != null && (waiting.stream == null || waiting.stream == streamId)) {
                    waiting.play?.let { playing = it }
                    activity?.takeUnless { it.playbackReleased || it.isFinishing }?.applyRate()
                    diagnostic("event=open_resumed stream=$streamId")
                    show()
                }
            }
            "setRate" -> {
                val nextPlaying = ((message["rate"] as? Number)?.toDouble() ?: 0.0) > 0.0
                if (this.streamId != streamId || url == null) {
                    if (nextPlaying) deferOpen(streamId, true)
                    else if (pendingOpen?.stream == streamId) pendingOpen = null
                    return
                }
                if (nextPlaying && !VideoInCar.allowed) return
                playing = nextPlaying
                if (!playing && (pendingOpen?.stream == null || pendingOpen?.stream == streamId)) pendingOpen = null
                // The iPhone starts the item at once, as Apple's receiver plays it straight
                // away, so open the player now instead of waiting for Now Playing's video button.
                if (playing) show()
                activity?.applyRate()
            }
            "seek" -> {
                VideoInCar.seekMillis(message)?.let {
                    pendingSeekMillis = it
                    activity?.applySeek()
                }
                if (request) reply(streamId, VideoInCar.seekResponse(message["messageID"]))
            }
            "playbackInfo" -> if (request) {
                reply(streamId, VideoInCar.playbackInfoResponse(message["messageID"], itemUuid, playerState()))
            }
            "property" -> if (request) {
                reply(streamId, VideoInCar.propertyResponse(message["messageID"], message["property"], playerState()))
            }
            "unhandledURL" -> onUrlLoaded(streamId, message)
            "stop", "removePlayQueueItem" -> {
                if (type == "stop" && deferredPlay?.stream == streamId) deferredPlay = null
                val target = (message["item"] as? Map<*, *>)?.get("uuid") ?: message["uuid"]
                val reason = when {
                    playbackEnded -> "playback_ended"
                    this.streamId != null && this.streamId != streamId -> "other_stream"
                    target != null && !sameItemId(target, itemUuid) -> "other_item"
                    type == "removePlayQueueItem" && target == null -> "missing_item"
                    else -> null
                }
                diagnostic("event=stop_command generation=$playbackGeneration command=$type targeted=${target != null} applies=${reason == null} opening=$playerOpening attached=${activity != null} reason=${reason ?: "current_playback"}")
                if (reason == null) stop()
            }
            "setProperty" -> Unit
            else -> Log.i(TAG, "video message $type keys=${message.keys}")
        }
    }

    /** What the iPhone answered to [resolveOnIphone]. */
    class LoadedUrl(val status: Int?, val data: ByteArray?, val location: String?,
        val headers: Map<String, List<String>> = emptyMap(), val file: VideoResourceFile? = null)

    private val pendingUrls = ConcurrentHashMap<Long, CompletableFuture<Map<*, *>>>()
    private val claimedReplies = HashSet<Long>() // guarded by urlRequestLock; a subset of pendingUrls
    private val nextUrlRequest = AtomicLong(1)
    private val urlRequestLock = Any()
    private val urlAdmission = VideoRequestAdmission()
    private var urlItemEpoch = 0L // guarded by urlRequestLock, including same-stream replacements
    private val urlLoadStart = ThreadLocal<(() -> Unit)?>()
    private val urlLoadRequest = ThreadLocal<((Long) -> Unit)?>()

    internal fun <T> withUrlLoadStart(start: () -> Unit, onRequest: (Long) -> Unit = {}, load: () -> T): T {
        val previous = urlLoadStart.get()
        val previousRequest = urlLoadRequest.get()
        urlLoadStart.set(start)
        urlLoadRequest.set(onRequest)
        return try { load() } finally {
            if (previous == null) urlLoadStart.remove() else urlLoadStart.set(previous)
            if (previousRequest == null) urlLoadRequest.remove() else urlLoadRequest.set(previousRequest)
        }
    }

    internal fun notifyUrlLoadStarted() { urlLoadStart.get()?.invoke() }


    /**
     * Asks the iPhone to load a URL the car cannot (an app's own scheme), as Apple's receiver
     * does: unhandledURL request with FCUP_Response_URL / _IsContentKeyRequest / _RequestID, answered
     * with FCUP_Response_Data, _StatusCode and _Headers (Location). Blocking; a loader thread.
     */
    fun resolveOnIphone(url: String): LoadedUrl? = resolveOnIphone(url, playbackGeneration)

    internal fun resolveOnIphone(url: String, generation: Long, relay: Boolean = false): LoadedUrl? {
        val id = nextUrlRequest.getAndIncrement()
        urlLoadRequest.get()?.invoke(id)
        val requestedAt = SystemClock.elapsedRealtime()
        if (VideoLoadDiagnostics.TRACE_REQUESTS) diagnostic("event=load_enter request=$id generation=$generation urlChars=${url.length}")
        if (Thread.currentThread().isInterrupted) {
            diagnostic("event=load_cancelled request=$id reason=interrupted")
            return null
        }
        val (itemEpoch, admissionEpoch) = synchronized(urlRequestLock) {
            if (generation != playbackGeneration || playbackEnded) {
                diagnostic("event=load_cancelled request=$id reason=playback_ended")
                return null
            }
            urlItemEpoch to if (relay) urlAdmission.epochToken() else null
        }
        val ticket = admissionEpoch?.let { epoch ->
            var admissionReason: String? = null
            try {
                urlAdmission.acquire(url.length, allowWaiting = true, expectedEpoch = epoch) { event ->
                    if (event.stage == "rejected") admissionReason = event.reason
                    if (VideoLoadDiagnostics.TRACE_REQUESTS || event.stage == "rejected")
                        diagnostic("event=load_${event.stage} request=$id generation=$generation reason=${event.reason} " +
                            "active=${event.active} waiting=${event.waiting} waitedMs=${event.waitedMs} urlChars=${url.length} " +
                            "reservedBytes=${event.reservedBytes} limitBytes=${event.limitBytes} " +
                            "availableHeapBytes=${event.availableHeapBytes} headroomBytes=${event.headroomBytes}")
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            } ?: run {
                if (synchronized(urlRequestLock) { itemEpoch != urlItemEpoch }) return null
                throw IphoneVideoResourceRejectedException("request_admission", id, admissionReason)
            }
        }
        val answer = CompletableFuture<Map<*, *>>()
        val (stream, item) = synchronized(urlRequestLock) {
            if (generation != playbackGeneration || playbackEnded || itemEpoch != urlItemEpoch || ticket?.isCurrent() == false) {
                diagnostic("event=load_cancelled request=$id reason=item_changed_before_send")
                ticket?.close()
                return null
            }
            val stream = streamId
            val item = itemUuid
            if (stream == null || item == null) { ticket?.close(); return null }
            pendingUrls[id] = answer
            stream to item
        }
        val legacyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LEGACY_URL_TIMEOUT_SECONDS)
        fun remainingNanos(): Long = ticket?.remainingNanos() ?: (legacyDeadline - System.nanoTime()).coerceAtLeast(0)
        var pendingSend: Runnable? = null
        var outcome = "failed"
        val response = try {
            notifyUrlLoadStarted()
            pendingSend = reply(stream, linkedMapOf(
                "type" to "unhandledURL",
                "kind" to "request",
                "request" to linkedMapOf(
                    "FCUP_Response_URL" to url,
                    "FCUP_Response_IsContentKeyRequest" to false,
                    "FCUP_Response_RequestID" to id,
                ),
                "item" to linkedMapOf("uuid" to item),
            ), shouldSend = { !answer.isDone && ticket?.isCurrent() != false && remainingNanos() > 0 },
                onSending = { if (VideoLoadDiagnostics.TRACE_REQUESTS) diagnostic("event=load_sending request=$id") },
                onSent = { if (VideoLoadDiagnostics.TRACE_REQUESTS || !it) diagnostic("event=load_sent request=$id success=$it") })
            Log.i(TAG, "asked the iPhone to load a ${android.net.Uri.parse(url).scheme} URL request=$id")
            answer.get(remainingNanos(), TimeUnit.NANOSECONDS).also { outcome = "response" }
        } catch (_: InterruptedException) {
            outcome = "interrupted"
            Thread.currentThread().interrupt()
            null
        } catch (error: ExecutionException) {
            diagnostic("event=load_failed request=$id causes=${VideoLoadDiagnostics.causes(error)}")
            val cause = error.cause
            if (cause is IphoneVideoResourceRejectedException) throw cause
            Log.w(TAG, "iPhone load failed request=$id")
            null
        } catch (_: java.util.concurrent.TimeoutException) {
            outcome = "reply_timeout"
            null
        } catch (_: java.util.concurrent.CancellationException) {
            outcome = "cancelled"
            null
        } catch (error: Exception) {
            diagnostic("event=load_failed request=$id causes=${VideoLoadDiagnostics.causes(error)}")
            Log.w(TAG, "no iPhone answer for request=$id")
            null
        } finally {
            synchronized(urlRequestLock) {
                pendingUrls.remove(id)
                claimedReplies.remove(id)
                pendingSend?.let { sender.remove(it) }
                ticket?.close()
            }
            if (VideoLoadDiagnostics.TRACE_REQUESTS || outcome != "response" && outcome != "cancelled" && outcome != "interrupted")
                diagnostic("event=load_finished request=$id outcome=$outcome elapsedMs=${SystemClock.elapsedRealtime() - requestedAt}")
        }
        if (response == null) {
            closeResponse(runCatching { answer.getNow(null) }.getOrNull())
            return null
        }
        if (generation != playbackGeneration || streamId != stream || itemUuid != item ||
            synchronized(urlRequestLock) { itemEpoch != urlItemEpoch }) {
            diagnostic("event=load_discarded request=$id reason=item_changed_after_response")
            closeResponse(response); return null
        }
        val headers = response["FCUP_Response_Headers"] as? Map<*, *>
        return LoadedUrl(
            status = (response["FCUP_Response_StatusCode"] as? Number)?.toInt(),
            data = response["FCUP_Response_Data"] as? ByteArray,
            location = (headers?.get("Location") ?: headers?.get("location")) as? String,
            headers = headers?.entries?.mapNotNull { (key, value) ->
                val name = key as? String ?: return@mapNotNull null
                val text = value as? String ?: return@mapNotNull null
                name to listOf(text)
            }?.toMap() ?: emptyMap(),
            file = response["FCUP_Response_Data"] as? VideoResourceFile,
        )
    }

    private fun cancelPendingUrls() {
        synchronized(urlRequestLock) {
            urlItemEpoch++
            urlAdmission.cancelAll()
            pendingUrls.values.forEach {
                if (!it.cancel(false)) closeResponse(runCatching { it.getNow(null) }.getOrNull())
            }
            pendingUrls.clear()
            claimedReplies.clear()
        }
    }

    private fun closeResponse(response: Map<*, *>?) {
        (response?.get("FCUP_Response_Data") as? VideoResourceFile)?.close()
    }

    private fun onUrlLoaded(stream: Long, message: Map<String, Any?>) {
        val response = message["response"] as? Map<*, *>
        val id = (response?.get("FCUP_Response_RequestID") as? Number)?.toLong()
        Log.i(TAG, "iPhone unhandledURL answer request=$id keys=${message.keys} responseKeys=${response?.keys}")
        val accepted = synchronized(urlRequestLock) {
            stream == streamId && id != null && response != null && pendingUrls[id]?.complete(response) == true
        }
        val body = response?.get("FCUP_Response_Data")
        val bytes = (body as? ByteArray)?.size?.toLong() ?: (body as? VideoResourceFile)?.length ?: 0L
        val status = (response?.get("FCUP_Response_StatusCode") as? Number)?.toInt() ?: -1
        if (VideoLoadDiagnostics.TRACE_REQUESTS || !accepted || status !in 200..299 && status != 0)
            diagnostic("event=load_response request=${id ?: -1} stream=$stream accepted=$accepted status=$status bytes=$bytes")
        if (!accepted) closeResponse(response)
    }

    // A retired player must not report its old state for the next playback waiting to open.
    private fun playerState(): VideoInCar.PlayerState? =
        url?.let {
            activity?.takeUnless { it.playbackReleased || it.isFinishing }?.state()
                ?: VideoInCar.PlayerState(false, false, startMillis / 1000.0, 0.0, 0.0)
        }

    private fun sameItemId(left: Any?, right: Any?): Boolean =
        if (left is ByteArray && right is ByteArray) left.contentEquals(right) else left == right

    private fun itemKey(stream: Long, uuid: Any?): String? {
        val (type, bytes) = when (uuid) {
            is String -> "string" to uuid.toByteArray(Charsets.UTF_8)
            is ByteArray -> "data" to uuid
            is Number -> "number" to uuid.toString().toByteArray(Charsets.US_ASCII)
            else -> return null
        }
        val digest = MessageDigest.getInstance("SHA-256").apply { update(type.toByteArray(Charsets.US_ASCII)) }
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        return "$stream:$digest"
    }

    private fun reply(streamId: Long, message: Map<String, Any?>, shouldSend: () -> Boolean = { true },
        onSending: () -> Unit = {}, onSent: (Boolean) -> Unit = {},
    ): Runnable? {
        val target = controller ?: return null
        val work = Runnable {
            if (!shouldSend()) return@Runnable
            try { onSending() } catch (_: Exception) { }
            // A reply that cannot be sent must never take CarPlay down with it.
            val sent = runCatching { target.sendVideoMessage(streamId, message) }
                .onFailure { Log.w(TAG, "reply ${message["type"]} failed: ${it.javaClass.simpleName}") }
                .getOrDefault(false)
            try { onSent(sent) } catch (_: Exception) { }
            if (!sent) Log.w(TAG, "reply ${message["type"]} not sent")
        }
        sender.execute(work)
        return work
    }

    private fun show() {
        val context = appContext ?: return
        if (playbackEnded || url == null || !VideoInCar.allowed) return
        val waiting = pendingOpen
        if (waiting != null && (waiting.generation != playbackGeneration || waiting.expiresAt < SystemClock.uptimeMillis())) {
            pendingOpen = null
            return
        }
        val attached = activity
        if (attached != null) {
            // Keep the new request until the old Activity has released all player resources.
            if (!attached.playbackReleased && !attached.isFinishing) pendingOpen = null
            else if (pendingOpen == null) deferOpen(streamId, playing)
            return
        }
        if (playerOpening) return
        if (pendingOpen == null) deferOpen(streamId, playing)
        val request = pendingOpen ?: return
        if (request.attempts >= 2) {
            pendingOpen = null
            diagnostic("event=activity_start_exhausted generation=$playbackGeneration")
            return
        }
        pendingOpen = request.copy(attempts = request.attempts + 1)
        playerOpening = true
        val ticket = ++nextLaunch
        val generation = playbackGeneration
        launchId = ticket
        diagnostic("event=opening generation=$generation launch=$ticket stream=${streamId ?: -1}")
        val intent = Intent(context, CarPlayVideoActivity::class.java)
            .putExtra(EXTRA_GENERATION, generation).putExtra(EXTRA_LAUNCH, ticket)
        val host = CarPlayScreen.current()
        val started = if (host != null) OwnScreenStart.start(host, intent) {
            diagnostic("event=activity_start_refused generation=$generation launch=$ticket detail=$it")
        } else try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (error: RuntimeException) {
            diagnostic("event=activity_start_failed generation=$generation launch=$ticket reason=${error.javaClass.simpleName}")
            false
        }
        if (!started) {
            playerOpening = false
            launchId = null
            return
        }
        openingTimeout?.let(main::removeCallbacks)
        openingTimeout = Runnable {
            if (generation == playbackGeneration && launchId == ticket && playerOpening && activity == null) {
                playerOpening = false
                launchId = null
                diagnostic("event=activity_start_timeout generation=$generation launch=$ticket")
                // Invalidate the first ticket before one bounded retry. A late old Activity cannot attach.
                show()
            }
        }.also { main.postDelayed(it, 5_000) }
    }

    private fun pausePlayback(reason: String, positionMillis: Int?) {
        positionMillis?.let { startMillis = it }
        if (playing && url != null) {
            streamId?.let { reply(it, VideoInCar.playbackStateNotification(false, itemUuid)) }
        }
        playing = false
        clearPendingOpen()
        synchronized(urlRequestLock) {
            playbackGeneration++
            cancelPendingUrls()
        }
        diagnostic("event=playback_paused generation=$playbackGeneration reason=$reason pendingLoads=${pendingUrls.size}")
        activity?.closePlayback()
    }

    private fun stop() = endPlayback("remote_stop", notify = false, requireFreshUi = false)

    private fun endPlayback(reason: String, notify: Boolean, requireFreshUi: Boolean = true) {
        val oldStream = streamId
        val oldItem = itemUuid
        val hadItem = url != null
        if (requireFreshUi && hadItem && oldStream != null) {
            itemKey(oldStream, oldItem)?.let { retiredItems.add(it) }
            while (retiredItems.size > 32) retiredItems.remove(retiredItems.first())
        }
        if (notify && hadItem && oldStream != null) {
            reply(oldStream, VideoInCar.playbackStateNotification(false, oldItem))
        }
        clearPendingOpen()
        synchronized(urlRequestLock) {
            playbackGeneration++
            // Local return/session boundaries invalidate callbacks already queued on the main thread.
            // A remote stop may be followed by the next item in the same received command batch.
            if (requireFreshUi) commandGeneration++
            playbackEnded = requireFreshUi
            cancelPendingUrls()
            url = null
            itemUuid = null
        }
        playing = false
        streaming = false
        startMillis = 0
        pendingSeekMillis = null
        diagnostic("event=playback_retired generation=$playbackGeneration reason=$reason pendingLoads=${pendingUrls.size}")
        activity?.closePlayback()
    }

    private fun clearPendingOpen() {
        pendingOpen = null
        deferredPlay = null
        openingTimeout?.let(main::removeCallbacks)
        openingTimeout = null
        playerOpening = false
        launchId = null
    }

    private fun deferOpen(stream: Long?, play: Boolean?) {
        if (!VideoInCar.allowed || playbackEnded) return
        pendingOpen = PendingOpen(stream, SystemClock.uptimeMillis() + 30_000, play, playbackGeneration)
        diagnostic("event=open_deferred generation=$playbackGeneration stream=${stream ?: -1} reason=item_not_ready")
    }
}
