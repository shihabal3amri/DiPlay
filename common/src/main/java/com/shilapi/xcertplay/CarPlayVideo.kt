package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.VideoRequestAdmission
import com.shilapi.xcertplay.airplay.VideoResourceFile
import android.os.SystemClock
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.airplay.VideoInCar
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayVideoListener
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * iOS 27 video in car (see [VideoInCar]). The iPhone hands the car a media URL (insertPlayQueueItem)
 * and drives it (setRate, seek, stop); the car plays it in [CarPlayVideoActivity], which opens as soon
 * as the iPhone starts the item (or sends requestUI "videoplayback:") and only while the car is in P.
 */
internal object CarPlayVideo : CarPlayVideoListener {
    private const val TAG = "DiPlay-Video"
    const val SKIP_MILLIS = 10_000
    private const val LEGACY_URL_TIMEOUT_SECONDS = 10L

    private val main = Handler(Looper.getMainLooper())
    private val sender = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue<Runnable>()) {
        Thread(it, "diplay-video-reply").apply { isDaemon = true }
    }
    @Volatile private var appContext: Context? = null
    @Volatile private var controller: CarPlayController? = null
    @Volatile private var diagnostic: (String) -> Unit = {}
    private val iphoneLoads = VideoDiagnostics.IphoneLoads()

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
    @Volatile internal var relayItemGeneration = 0L
        private set
    var activity: CarPlayVideoActivity? = null

    /**
     * From the moment DiPlay opens its player until the player closes, or until the item or video session ends
     * while the player has not opened (see [stop]). The player hides the system bars, so
     * the CarPlay screen's window grows behind it; the host keeps its CarPlay canvas meanwhile (#1180).
     */
    @Volatile var playerOnScreen = false
        internal set

    /** [log] takes report lines: hosts, schemes, sizes and codes only, never a URL (#1180). */
    fun attach(context: Context, next: CarPlayController, log: (String) -> Unit = {}) {
        appContext = context.applicationContext
        if (controller !== next) invalidateIphoneItem()
        controller = next
        diagnostic = log
        next.videoListener = this
    }

    /** Logcat and the diagnostic report. Callers pass no URL: they carry tokens. */
    fun note(message: String, warn: Boolean = false, logcat: Boolean = true) {
        if (logcat) {
            if (warn) Log.w(TAG, message) else Log.i(TAG, message)
        }
        runCatching { diagnostic("Video: $message") }
    }

    /** A per-load line of an item loaded through the iPhone: the first few reach the report, then a count. */
    private fun noteIphoneLoad(kind: VideoDiagnostics.IphoneLoads.Kind, message: String, warn: Boolean = false) {
        if (iphoneLoads.take(kind)) {
            note(message, warn)
        } else if (warn) {
            Log.w(TAG, message)
        } else {
            Log.i(TAG, message)
        }
    }

    /** What the iPhone sent for one load ([IphoneResolvingDataSource]). Loader thread. */
    fun noteIphoneLoaded(scheme: String?, status: Int?, bytes: Int?, redirect: Boolean) {
        // Status 0 is a resource loader's data-only answer, not a failure.
        val failed = !redirect && status != null && status != 0 && status !in 200..299
        noteIphoneLoad(if (failed) VideoDiagnostics.IphoneLoads.Kind.FAILED else VideoDiagnostics.IphoneLoads.Kind.LOADED,
            VideoDiagnostics.iphoneLoaded(scheme, status, bytes, redirect), warn = failed)
    }

    override fun readParked(): Boolean? = appContext?.let(BydNavigationOutputs::parked)

    override fun usesIphoneNetworkForVideo(): Boolean =
        appContext?.let(AirPlayPersistence::loadVideoUseIphoneNetwork) == true

    fun noteRelay(message: String) = note(message)

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
            noteRelay("event=resource_rejected stream=$streamId reason=$reason bytes=$bodyBytes failedLoads=$failed")
            itemUuid
        }
        main.post {
            if (this.streamId == streamId && itemUuid == item) activity?.onResourcePressure(reason)
        }
    }

    override fun onVideoAllowedChanged(allowed: Boolean) {
        if (!allowed) main.post { closePlayer("the car left P") }
    }

    override fun onVideoSessionEnded() {
        main.post {
            stop()
            streamId = null
        }
    }

    override fun gearUnreadableReason(): String? = BydNavigationOutputs.gearUnreadable()?.report

    /** An unreadable gear keeps video off; Home and the report say why until the gear reads again (#1180). */
    override fun onGearObserved(parked: Boolean?) {
        val context = appContext ?: return
        if (parked != null) return LastConnectionProblem.clearVideoGear(context)
        val unreadable = BydNavigationOutputs.gearUnreadable()
        LastConnectionProblem.videoGearUnreadable(context, unreadable?.adb, unreadable?.replied == true)
    }

    override fun onVideoUiRequested() {
        main.post { show() }
    }

    override fun onVideoMessage(streamId: Long, message: Map<String, Any?>) {
        main.post { handle(streamId, message) }
    }

    /**
     * A steering-wheel media key (CarPlayMediaButton index) while the player is on screen: play and pause
     * toggle it, next and previous skip 10 s. The iPhone is not asked: a CarPlay play/pause makes it end
     * the video session. Returns false when no player is open. Main thread.
     */
    fun onMediaKey(index: Int): Boolean {
        val player = activity ?: return false
        when (index) {
            CarPlayMediaButton.NEXT -> player.skip(SKIP_MILLIS)
            CarPlayMediaButton.PREVIOUS -> player.skip(-SKIP_MILLIS)
            else -> setPlaying(!playing)
        }
        return true
    }

    /** Play or pause from the car (wheel or on-screen button); tells the iPhone at once. Main thread. */
    fun setPlaying(next: Boolean) {
        val wasPlaying = playing
        playing = next
        if (wasPlaying && !next) cancelPendingUrls()
        activity?.applyRate()
        streamId?.let { reply(it, VideoInCar.playbackStateNotification(next, itemUuid)) }
    }

    /**
     * The car's player cannot play the item (Media3 [playbackErrorCode]): tell the iPhone as Apple's receiver
     * does, say why on the car screen and go back to CarPlay instead of showing black. Main thread.
     */
    fun onPlayerFailed(playbackErrorCode: Int) {
        val failure = failureFor(playbackErrorCode)
        note("item cannot play here error=$playbackErrorCode code=${failure.iphoneCode}", warn = true)
        streamId?.let { reply(it, VideoInCar.errorNotification(itemUuid, failure.iphoneCode)) }
        appContext?.let { Toast.makeText(it, failure.message, Toast.LENGTH_LONG).show() }
        stop()
    }

    /** What the iPhone is told and the car screen says for a failed item. */
    internal data class PlayerFailure(val iphoneCode: Int, @androidx.annotation.StringRes val message: Int)

    /**
     * Only a DRM error names protected video, and only parsing and decoding errors name the format. A lost
     * connection is not a licence problem, and saying so sent drivers looking for the wrong cause (#1180).
     * The load text names no network: an app's own URL loads through the iPhone, and its failures are IO
     * errors too. Other errors (a live stream fallen behind, a timeout, audio output) state no cause.
     */
    internal fun failureFor(playbackErrorCode: Int): PlayerFailure = when (playbackErrorCode) {
        in 2000..2999 -> PlayerFailure(VideoInCar.ERROR_NETWORK, R.string.video_cannot_load) // IO
        in 3000..3999 -> PlayerFailure(VideoInCar.ERROR_INCOMPATIBLE_ASSET, R.string.video_cannot_play_here) // parsing
        in 4000..4999 -> PlayerFailure(VideoInCar.ERROR_DECODER, R.string.video_cannot_play_here) // decoding
        in 6000..6999 -> PlayerFailure(VideoInCar.ERROR_INCOMPATIBLE_ASSET, R.string.video_cannot_play) // DRM
        else -> PlayerFailure(VideoInCar.ERROR_INCOMPATIBLE_ASSET, R.string.video_cannot_play_on_car) // no stated cause
    }

    /** The player activity is going away (Back, the car left P, or the item stopped). */
    fun onPlayerGone() {
        playerOnScreen = false
    }

    /** The player closed on the car (Back, or the car left P): pause, so the iPhone shows it paused. */
    fun onPlayerClosed(positionMillis: Int?) {
        positionMillis?.let { startMillis = it }
        if (playing) setPlaying(false) else cancelPendingUrls()
    }

    private fun handle(streamId: Long, message: Map<String, Any?>) {
        if (message["type"] == "unhandledURL") {
            onUrlLoaded(streamId, message)
            return
        }
        val request = message["kind"] == "request"
        val type = message["type"] as? String
        if (type != "insertPlayQueueItem") this.streamId = streamId
        when (type) {
            "insertPlayQueueItem" -> {
                // An app's own scheme is loaded through the iPhone (IphoneResolvingDataSource).
                val item = VideoInCar.parseItem(message, iphoneLoadsAppSchemes = true)
                val queued = message["item"] as? Map<*, *>
                if (item == null) {
                    note("queue item this player cannot play mediaType=${VideoDiagnostics.word(queued?.get("mediaType"))}",
                        warn = true)
                    return
                }
                finishIphoneLoads() // a new item replaces one the iPhone did not stop first
                synchronized(urlRequestLock) {
                    if (this.streamId != streamId || url != item.url || !sameItemId(itemUuid, item.uuid)) {
                        invalidateIphoneItem()
                    }
                    this.streamId = streamId
                    url = item.url
                    itemUuid = item.uuid
                    streaming = queued?.get("mediaType") == "streaming"
                }
                note(VideoDiagnostics.queueItem(item.url, queued?.get("mediaType")))
                startMillis = item.startMillis
                activity?.load()
            }
            "setRate" -> {
                val wasPlaying = playing
                playing = ((message["rate"] as? Number)?.toDouble() ?: 0.0) > 0.0
                if (wasPlaying && !playing) cancelPendingUrls()
                // The iPhone starts the item at once, as Apple's receiver plays it straight
                // away, so open the player now instead of waiting for Now Playing's video button.
                if (playing && activity == null) show()
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
            "stop", "removePlayQueueItem" -> stop()
            "setProperty" -> Unit
            else -> note("video message ${VideoDiagnostics.word(type)} keys=${VideoDiagnostics.keys(message.keys)}")
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
    fun resolveOnIphone(url: String): LoadedUrl? = resolveOnIphone(url, relayItemGeneration)

    internal fun resolveOnIphone(url: String, generation: Long, relay: Boolean = false): LoadedUrl? {
        val id = nextUrlRequest.getAndIncrement()
        urlLoadRequest.get()?.invoke(id)
        val requestedAt = SystemClock.elapsedRealtime()
        if (VideoLoadDiagnostics.TRACE_REQUESTS) noteRelay("event=load_enter request=$id generation=$generation urlChars=${url.length}")
        if (Thread.currentThread().isInterrupted) {
            noteRelay("event=load_cancelled request=$id reason=interrupted")
            return null
        }
        val (itemEpoch, admissionEpoch) = synchronized(urlRequestLock) {
            if (generation != relayItemGeneration) {
                noteRelay("event=load_cancelled request=$id reason=playback_ended")
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
                        noteRelay("event=load_${event.stage} request=$id generation=$generation reason=${event.reason} " +
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
            if (generation != relayItemGeneration || itemEpoch != urlItemEpoch || ticket?.isCurrent() == false) {
                noteRelay("event=load_cancelled request=$id reason=item_changed_before_send")
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
                onSending = { if (VideoLoadDiagnostics.TRACE_REQUESTS) noteRelay("event=load_sending request=$id") },
                onSent = { if (VideoLoadDiagnostics.TRACE_REQUESTS || !it) noteRelay("event=load_sent request=$id success=$it") })
            noteIphoneLoad(VideoDiagnostics.IphoneLoads.Kind.ASKED,
                "asked the iPhone to load a ${VideoDiagnostics.scheme(url)} URL request=$id")
            answer.get(remainingNanos(), TimeUnit.NANOSECONDS).also { outcome = "response" }
        } catch (_: InterruptedException) {
            outcome = "interrupted"
            Thread.currentThread().interrupt()
            null
        } catch (error: ExecutionException) {
            noteRelay("event=load_failed request=$id causes=${VideoLoadDiagnostics.causes(error)}")
            val cause = error.cause
            if (cause is IphoneVideoResourceRejectedException) throw cause
            Log.w(TAG, "iPhone load failed request=$id")
            null
        } catch (_: java.util.concurrent.TimeoutException) {
            outcome = "reply_timeout"
            noteIphoneLoad(VideoDiagnostics.IphoneLoads.Kind.NO_ANSWER, "no iPhone answer for request=$id", warn = true)
            null
        } catch (_: java.util.concurrent.CancellationException) {
            outcome = "cancelled"
            null
        } catch (error: Exception) {
            noteRelay("event=load_failed request=$id causes=${VideoLoadDiagnostics.causes(error)}")
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
                noteRelay("event=load_finished request=$id outcome=$outcome elapsedMs=${SystemClock.elapsedRealtime() - requestedAt}")
        }
        if (response == null) {
            closeResponse(runCatching { answer.getNow(null) }.getOrNull())
            return null
        }
        if (generation != relayItemGeneration || streamId != stream || !sameItemId(itemUuid, item) ||
            synchronized(urlRequestLock) { itemEpoch != urlItemEpoch }) {
            noteRelay("event=load_discarded request=$id reason=item_changed_after_response")
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

    private fun invalidateIphoneItem() {
        synchronized(urlRequestLock) {
            relayItemGeneration++
            cancelPendingUrls()
        }
    }

    private fun cancelPendingUrls() {
        synchronized(urlRequestLock) {
            urlItemEpoch++
            urlAdmission.cancelAll()
            pendingUrls.values.forEach {
                it.cancel(false)
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
        noteIphoneLoad(VideoDiagnostics.IphoneLoads.Kind.ANSWER,
            "iPhone unhandledURL answer request=$id keys=${VideoDiagnostics.keys(message.keys)} " +
                "responseKeys=${response?.keys?.let(VideoDiagnostics::keys)}")
        val accepted = synchronized(urlRequestLock) {
            stream == streamId && id != null && pendingUrls[id]?.complete(response) == true
        }
        val body = response?.get("FCUP_Response_Data")
        val bytes = (body as? ByteArray)?.size?.toLong() ?: (body as? VideoResourceFile)?.length ?: 0L
        val status = (response?.get("FCUP_Response_StatusCode") as? Number)?.toInt() ?: -1
        if (VideoLoadDiagnostics.TRACE_REQUESTS || status !in 200..299 && status != 0)
            noteRelay("event=load_response request=${id ?: -1} stream=$stream accepted=$accepted status=$status bytes=$bytes")
        if (!accepted) closeResponse(response)
    }

    // While the player is closed the item stays paused where it was, so the iPhone keeps its position.
    private fun playerState(): VideoInCar.PlayerState? =
        activity?.state() ?: url?.let { VideoInCar.PlayerState(false, false, startMillis / 1000.0, 0.0, 0.0) }

    private fun sameItemId(left: Any?, right: Any?): Boolean =
        if (left is ByteArray && right is ByteArray) left.contentEquals(right) else left == right

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
        when {
            url == null -> note("video player requested without a playable item", warn = true)
            !VideoInCar.allowed -> note("video player requested while not parked", warn = true)
            activity != null -> Unit
            else -> {
                // Before the player starts: it hides the bars as it opens, and the host's window grows then.
                playerOnScreen = true
                context.startActivity(
                    Intent(context, CarPlayVideoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    private fun closePlayer(reason: String) {
        val player = activity ?: return
        note("closing the video player: $reason")
        player.finish()
    }

    private fun finishIphoneLoads() {
        iphoneLoads.finish()?.let(::note)
    }

    private fun stop() {
        activity?.releasePlaybackResources()
        synchronized(urlRequestLock) {
            invalidateIphoneItem()
            url = null
            itemUuid = null
        }
        finishIphoneLoads()
        playing = false
        pendingSeekMillis = null
        // A player that was asked for but never opened (Android can refuse a start from the background)
        // must not leave the CarPlay screen holding its size changes; one that opens later sets it again.
        if (activity == null) playerOnScreen = false
        activity?.finish()
    }
}
