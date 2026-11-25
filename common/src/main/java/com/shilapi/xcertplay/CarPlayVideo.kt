package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.Toast
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.airplay.VideoInCar
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayVideoListener
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.security.MessageDigest

/**
 * iOS 27 video in car (see [VideoInCar]). The iPhone hands the car a media URL (insertPlayQueueItem)
 * and drives it (setRate, seek, stop); the car plays it in [CarPlayVideoActivity], which opens as soon
 * as the iPhone starts the item (or sends requestUI "videoplayback:") and only while the shared
 * video gate allows it.
 */
internal object CarPlayVideo : CarPlayVideoListener {
    private const val TAG = "DiPlay-Video"
    const val SKIP_MILLIS = 10_000
    private const val URL_TIMEOUT_SECONDS = 10L
    private const val BACK_PLAY_GUARD_MILLIS = 2_000L
    private const val LIVE_OPEN_REQUEST_MILLIS = 30_000L
    private const val CARPLAY_TAP_REOPEN_MILLIS = 10_000L
    private const val PLAYER_OPEN_TIMEOUT_MILLIS = 5_000L
    private const val PHONE_STOP_HANDOFF_MILLIS = 300L
    private const val EXTRA_PLAYER_LAUNCH = "video_player_launch"
    private const val EXTRA_PLAYER_GENERATION = "video_player_generation"

    private val main = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor { Thread(it, "diplay-video-reply").apply { isDaemon = true } }
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
    // Separate user intent from iPhone playback messages and an Activity start not yet delivered.
    private var dismissedByUser = false
    private var waitingForLiveItem = false
    private var liveUiRequestUntil = 0L
    private var livePlayRequestUntil = 0L
    private var deferredPlayStream: Long? = null
    private var deferredPlayUntil = 0L
    private var deferredUiUntil = 0L
    private val retiredItems = LinkedHashSet<String>()
    private var closedLiveSource: String? = null
    private val supersededStreams = LinkedHashSet<Long>()
    private val inactiveItems = LinkedHashMap<Long, Any?>()
    private val remoteQueueItems = LinkedHashMap<Long, Any>()
    private var playbackOwnerStream: Long? = null
    private val inactivePlayRequests = LinkedHashMap<Long, Long>()
    private var itemApp: String? = null
    private var dismissedLiveApp: String? = null
    private var awaitingLiveReplacement = false
    private var pendingPhoneStop: Runnable? = null
    private var pendingPhoneStopStream: Long? = null
    private data class RejectedLiveHandoff(val stream: Long, val generation: Long, val until: Long)
    private var rejectedLiveHandoff: RejectedLiveHandoff? = null
    internal var playbackGeneration = 0L
        private set
    @Volatile private var commandGeneration = 0L
    @Volatile private var playbackStateRevision = 0L
    private data class PlayerPlaybackReport(val session: Long, val generation: Long, val stream: Long,
        val item: String, val state: String)
    private var lastPlayerPlaybackReport: PlayerPlaybackReport? = null
    private var tapTracking = false
    private var tapDownX = 0f
    private var tapDownY = 0f
    private var tapRequestAt = 0L
    private var tapRequestUntil = 0L
    private var tapRequestGeneration = -1L
    private data class TappedItem(val stream: Long, val message: Map<String, Any?>, val receivedAt: Long)
    private var pendingTappedItem: TappedItem? = null
    @Volatile private var resumeBlockedUntil = 0L
    private var nextLaunch = 0L
    private var openingLaunch: Long? = null
    private var openingTimeout: Runnable? = null
    private var openAfterClose = false
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
        val changed = controller !== next
        if (changed) {
            controller?.videoListener = null
            commandGeneration++
            resetSession()
        }
        appContext = context.applicationContext
        controller = next
        diagnostic = log
        next.videoListener = this
        if (changed) note("video session reset reason=new_controller")
    }

    /** Logcat and the diagnostic report. Callers pass no URL: they carry tokens. */
    fun note(message: String, warn: Boolean = false, logcat: Boolean = true) {
        if (logcat) {
            if (warn) Log.w(TAG, message) else Log.i(TAG, message)
        }
        runCatching { diagnostic("Video: $message") }
    }

    /** Only a short, single-finger tap actually forwarded to CarPlay can authorize one closed-source handoff. */
    fun onCarPlayTouch(event: MotionEvent, queued: Boolean = false) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clearTapRequest()
                tapTracking = event.pointerCount == 1
                tapDownX = event.x
                tapDownY = event.y
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val slop = appContext?.let { ViewConfiguration.get(it).scaledTouchSlop } ?: 0
                val dx = event.x - tapDownX
                val dy = event.y - tapDownY
                if (event.pointerCount != 1 || dx * dx + dy * dy > slop.toFloat() * slop) tapTracking = false
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    val shortTap = event.eventTime - event.downTime <= ViewConfiguration.getLongPressTimeout()
                    if (tapTracking && shortTap && queued && VideoInCar.allowed && !playing &&
                        activity?.isFinishing != false && (url == null || dismissedByUser || waitingForLiveItem)) {
                        tapRequestAt = SystemClock.uptimeMillis()
                        tapRequestUntil = tapRequestAt + CARPLAY_TAP_REOPEN_MILLIS
                        tapRequestGeneration = commandGeneration
                        note("CarPlay tap armed one source handoff windowMs=$CARPLAY_TAP_REOPEN_MILLIS")
                    }
                    tapTracking = false
                }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                tapTracking = false
                clearTapRequest()
            }
        }
    }

    private fun hasTapRequest(receivedAt: Long): Boolean = tapRequestGeneration == commandGeneration &&
        receivedAt >= tapRequestAt && SystemClock.uptimeMillis() < tapRequestUntil

    private fun clearTapRequest() {
        tapRequestAt = 0L
        tapRequestUntil = 0L
        tapRequestGeneration = -1L
        pendingTappedItem = null
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

    /** A queued Activity start may arrive after Back, stop, or a session/gear change. */
    fun attachPlayer(next: CarPlayVideoActivity): Boolean {
        val launch = next.intent?.getLongExtra(EXTRA_PLAYER_LAUNCH, -1L)
        val generation = next.intent?.getLongExtra(EXTRA_PLAYER_GENERATION, -1L)
        if (launch == null || launch != openingLaunch || generation != playbackGeneration) {
            note("player start refused reason=stale_launch")
            return false
        }
        cancelOpening()
        val reason = when {
            url == null -> "no_item"
            !VideoInCar.allowed -> "not_parked"
            dismissedByUser -> "dismissed"
            activity != null -> "already_attached"
            else -> null
        }
        if (reason != null) {
            note("player start refused reason=$reason")
            return false
        }
        activity = next
        playerOnScreen = true
        return true
    }

    fun detachPlayer(previous: CarPlayVideoActivity, positionMillis: Int?, changingConfigurations: Boolean,
        generation: Long = playbackGeneration) {
        if (activity !== previous) return
        activity = null
        onPlayerGone()
        val currentItem = generation == playbackGeneration
        if (currentItem) positionMillis?.let { startMillis = it }
        if (openAfterClose) {
            openAfterClose = false
            show()
        } else if (changingConfigurations && currentItem) {
            val launch = previous.intent.getLongExtra(EXTRA_PLAYER_LAUNCH, -1L)
            if (url != null && VideoInCar.allowed && !dismissedByUser) {
                openingLaunch = launch
                playerOnScreen = true
                awaitPlayer(launch, retryOnTimeout = true)
            }
        } else if (currentItem) {
            onPlayerClosed(positionMillis)
        }
    }

    override fun readParked(): Boolean? = appContext?.let(BydNavigationOutputs::parked)

    override fun onVideoAllowedChanged(allowed: Boolean) {
        if (!allowed) main.post {
            clearTapRequest()
            closePlayer("the car left P")
        }
    }

    override fun onVideoSessionEnded() {
        val generation = ++commandGeneration
        main.post {
            if (generation != commandGeneration) return@post
            resetSession()
        }
    }

    override fun onVideoStreamEnded(streamId: Long) {
        val generation = commandGeneration
        main.post {
            if (generation != commandGeneration) return@post
            val item = remoteQueueItems.remove(streamId)
            rememberInactiveItem(streamId, item ?: if (this.streamId == streamId) itemUuid else inactiveItems[streamId])
            if (playbackOwnerStream == streamId) playbackOwnerStream = null
            note("remote queue ended reason=teardown stream=$streamId")
        }
    }

    private fun resetSession() {
        stop()
        clearTapRequest()
        tapTracking = false
        retiredItems.clear()
        closedLiveSource = null
        supersededStreams.clear()
        inactiveItems.clear()
        remoteQueueItems.clear()
        inactivePlayRequests.clear()
        itemApp = null
        dismissedLiveApp = null
        streamId = null
        streaming = false
        startMillis = 0
        resumeBlockedUntil = 0L
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
        val generation = commandGeneration
        main.post {
            if (generation != commandGeneration) {
                note("video UI request ignored reason=returned_after_request")
                return@post
            }
            if (dismissedLiveApp != null && !hasTapRequest(SystemClock.uptimeMillis())) {
                note("video UI request ignored reason=closed_live_app_without_tap")
                return@post
            }
            if (VideoInCar.allowed) deferredUiUntil = SystemClock.uptimeMillis() + LIVE_OPEN_REQUEST_MILLIS
            if (url == null || !VideoInCar.allowed) {
                if (waitingForLiveItem) note("live UI request waiting for a fresh item fallback=false")
                show()
                return@post
            }
            if (waitingForLiveItem) {
                liveUiRequestUntil = SystemClock.uptimeMillis() + LIVE_OPEN_REQUEST_MILLIS
                note("live UI request waiting for a fresh item fallback=false")
                return@post
            }
            if (dismissedByUser) setPlaying(true)
            show()
        }
    }

    override fun onVideoMessage(streamId: Long, message: Map<String, Any?>) {
        val receivedAt = SystemClock.uptimeMillis()
        val generation = commandGeneration
        main.post {
            if (generation == commandGeneration) handle(streamId, message, receivedAt)
            else note("video command ignored reason=closed_before_delivery")
        }
    }

    /**
     * A steering-wheel media key (CarPlayMediaButton index) while the player is on screen: play and pause
     * toggle it, next and previous skip 10 s. The iPhone is not asked: a CarPlay play/pause makes it end
     * the video session. Returns false when no player is open. Main thread.
     */
    fun onMediaKey(index: Int): Boolean {
        val player = activity?.takeUnless { it.isFinishing } ?: return false
        when (index) {
            CarPlayMediaButton.NEXT -> player.skip(SKIP_MILLIS)
            CarPlayMediaButton.PREVIOUS -> player.skip(-SKIP_MILLIS)
            else -> setPlaying(!playing)
        }
        return true
    }

    /** Play or pause from the car (wheel or on-screen button); tells the iPhone at once. Main thread. */
    fun setPlaying(next: Boolean) {
        playbackStateRevision++
        if (next) dismissedByUser = false
        playing = next
        activity?.takeUnless { it.isFinishing }?.applyRate()
        streamId?.let {
            note("player state requested stream=$it playing=$next item=${identityFingerprint(itemUuid)}")
            if (!ownsRemoteItem(it)) {
                note("playback state notification skipped reason=no_remote_item stream=$it")
                return@let
            }
            val message = VideoInCar.playbackStateNotification(next, itemUuid, if (next) playerState() else null)
            lastPlayerPlaybackReport = playerPlaybackReport(it, message)
            reply(it, message)
        }
    }

    /** Report actual loading/playing transitions, including when no car button was pressed. */
    internal fun onPlayerPlaybackStateChanged(player: CarPlayVideoActivity, generation: Long) {
        val stream = streamId ?: return
        if (activity !== player || player.isFinishing || generation != playbackGeneration ||
            !player.ownsCurrentItem() || url == null || dismissedByUser || inactiveItems.containsKey(stream) ||
            !ownsRemoteItem(stream)) return
        val state = player.state()
        if (state.prepared && state.playing && player.isLiveItem()) closedLiveSource = null
        val message = VideoInCar.playerPlaybackStateNotification(itemUuid, state)
        val report = playerPlaybackReport(stream, message)
        if (report == lastPlayerPlaybackReport) return
        lastPlayerPlaybackReport = report
        note("player lifecycle state=${report.state} stream=$stream rate=${if (state.playWhenReady) 1 else 0} " +
            "generation=$generation")
        reply(stream, message, playbackStateRevision)
    }

    private fun playerPlaybackReport(stream: Long, message: Map<String, Any?>) =
        PlayerPlaybackReport(commandGeneration, playbackGeneration, stream, identityFingerprint(itemUuid),
            VideoDiagnostics.word(message["name"]))

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
        if (playing) setPlaying(false)
    }

    /** Pause locally and ignore in-flight play requests briefly while the iPhone catches up. */
    fun dismissPlayer(positionMillis: Int?, isLive: Boolean = false) {
        playbackStateRevision++
        clearPhoneStop()
        commandGeneration++
        cancelLiveRemoval()
        clearTapRequest()
        note("player returned live=$isLive generation=$playbackGeneration")
        dismissedByUser = true
        liveUiRequestUntil = 0L
        livePlayRequestUntil = 0L
        resumeBlockedUntil = SystemClock.uptimeMillis() + BACK_PLAY_GUARD_MILLIS
        cancelOpening()
        clearDeferredOpen()
        openAfterClose = false
        if (isLive) finishLivePlayback("back") else onPlayerClosed(positionMillis)
        waitingForLiveItem = isLive && url != null
        if (isLive) {
            dismissedLiveApp = itemApp
            streamId?.let { rememberInactiveItem(it, itemUuid) }
            playbackGeneration++
            url = null
            pendingSeekMillis = null
            startMillis = 0
        }
    }

    fun onPlaybackCompleted() {
        streamId?.let { stream ->
            itemKey(stream, itemUuid)?.let { retiredItems.add(it) }
            while (retiredItems.size > 32) retiredItems.remove(retiredItems.first())
        }
        note("playback completed action=return_to_carplay")
        if (playing) setPlaying(false)
        stop()
    }

    private fun finishLivePlayback(reason: String) {
        clearPhoneStop()
        note("live playback ended reason=$reason stream=$streamId source=${identityFingerprint(url)}")
        rememberClosedLiveSource()
        if (reason == "back") setPlaying(false) else {
            playbackStateRevision++
            playing = false
            activity?.takeUnless { it.isFinishing }?.applyRate()
        }
        cancelLiveRemoval()
        clearTapRequest()
        cancelOpening()
        clearLiveWait()
        clearDeferredOpen()
        openAfterClose = false
        pendingSeekMillis = null
        startMillis = 0
    }

    private fun handle(streamId: Long, message: Map<String, Any?>, receivedAt: Long) {
        val request = message["kind"] == "request"
        val type = message["type"] as? String
        if (type == "insertPlayQueueItem") {
            val item = message["item"] as? Map<*, *>
            note("queue item identity stream=$streamId app=${VideoDiagnostics.word(item?.get("clientBundleID"))} " +
                "source=${identityFingerprint(item?.get("Content-Location"))} uuid=${identityFingerprint(item?.get("uuid"))} " +
                "player=${identityFingerprint(item?.get("playerLoggingID"))} " +
                "playerItem=${identityFingerprint(item?.get("playerItemLoggingID"))} " +
                "currentStream=${this.streamId} currentUuid=${identityFingerprint(itemUuid)} " +
                "tap=${hasTapRequest(receivedAt)} ui=${receivedAt < deferredUiUntil}")
        }
        if (type == "removePlayQueueItem") {
            val target = (message["item"] as? Map<*, *>)?.get("uuid") ?: message["uuid"]
            acknowledgeQueueRemoval(streamId, target)
        }
        if (inactiveItems.containsKey(streamId) && type in setOf("setRate", "seek", "setProperty", "stop", "removePlayQueueItem")) {
            if (type == "setRate") {
                if (((message["rate"] as? Number)?.toDouble() ?: 0.0) > 0.0) inactivePlayRequests[streamId] = receivedAt
                else inactivePlayRequests.remove(streamId)
            }
            note("video command ignored reason=inactive_stream stream=$streamId command=${VideoDiagnostics.word(type)}")
            if (request && type == "seek") reply(streamId, VideoInCar.seekResponse(message["messageID"]))
            return
        }
        if (streamId in supersededStreams && type in setOf("insertPlayQueueItem", "setRate", "seek", "setProperty")) {
            if (type == "insertPlayQueueItem" && (receivedAt < deferredUiUntil || hasTapRequest(receivedAt))) supersededStreams.remove(streamId)
            else {
                note("video command ignored reason=superseded_stream stream=$streamId command=${VideoDiagnostics.word(type)}")
                return
            }
        }
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
                val openLiveItem = waitingForLiveItem && receivedAt < liveUiRequestUntil
                val playLiveItem = waitingForLiveItem && receivedAt < livePlayRequestUntil
                val openDeferredItem = receivedAt < deferredUiUntil
                val playDeferredItem = streamId == deferredPlayStream && receivedAt < deferredPlayUntil ||
                    inactivePlayRequests[streamId]?.let { receivedAt - it in 0..LIVE_OPEN_REQUEST_MILLIS } == true
                val sourceKey = itemKey(0, item.url)
                val nextApp = (queued?.get("clientBundleID") as? String)?.takeIf { it.isNotBlank() }
                val tappedReopen = hasTapRequest(receivedAt)
                if (sourceKey != null && sourceKey == closedLiveSource && !tappedReopen) {
                    val currentStream = this.streamId
                    if (currentStream != null && currentStream != streamId && url != null && playing &&
                        activity?.takeUnless { it.isFinishing }?.let { it.ownsCurrentItem() && !it.isLiveItem() } == true) {
                        rejectedLiveHandoff = RejectedLiveHandoff(currentStream, playbackGeneration,
                            receivedAt + PHONE_STOP_HANDOFF_MILLIS)
                        if (pendingPhoneStopStream == currentStream) {
                            cancelPhoneStop()
                            note("phone stop cancelled reason=blocked_live_handoff stream=$currentStream")
                            reportRetainedPlaybackState("stop_cancelled")
                        }
                    }
                    if (this.streamId != streamId || url == null) rememberInactiveItem(streamId, item.uuid)
                    note("queue item ignored reason=closed_live_source stream=$streamId")
                    return
                }
                val key = itemKey(streamId, item.uuid)
                if (key != null && key in retiredItems && !openDeferredItem) {
                    note("queue item ignored reason=completed_item")
                    return
                }
                if (key != null) retiredItems.remove(key)
                val repeatedItem = this.streamId == streamId && sameItemId(item.uuid, itemUuid) && item.url == url
                val currentPlayer = activity?.takeUnless { it.isFinishing }
                val retainsSource = !dismissedByUser && item.url == url && nextApp == itemApp &&
                    currentPlayer?.ownsCurrentItem() == true
                if (dismissedByUser && !openLiveItem && !playLiveItem && repeatedItem) {
                    note("queue item ignored reason=repeated_after_back")
                    return
                }
                val resumePosition = if (repeatedItem || retainsSource) startMillis else item.startMillis
                acknowledgeQueueSelection(streamId, item.uuid)
                claimPlaybackStream(streamId)
                playbackStateRevision++
                clearPhoneStop()
                val liveHandoff = awaitingLiveReplacement && playing
                cancelLiveRemoval()
                if (nextApp != null && itemApp != null && nextApp != itemApp &&
                    currentPlayer?.isLiveItem() == true) finishLivePlayback("source_switch")
                if (!repeatedItem) {
                    this.streamId?.takeIf { it != streamId }?.let {
                        rememberInactiveItem(it, itemUuid)
                        supersededStreams.add(it)
                        while (supersededStreams.size > 32) supersededStreams.remove(supersededStreams.first())
                    }
                    if (!retainsSource) {
                        cancelOpening()
                        playbackGeneration++
                        pendingSeekMillis = null
                        playing = false
                    }
                }
                note("queue item accepted stream=$streamId generation=$playbackGeneration sameItem=$repeatedItem " +
                    "liveUi=$openLiveItem livePlay=$playLiveItem tap=$tappedReopen deferredUi=$openDeferredItem " +
                    "deferredPlay=$playDeferredItem handoff=$liveHandoff retainsSource=$retainsSource " +
                    "source=${sourceKey?.takeLast(12) ?: "unknown"}")
                inactiveItems.remove(streamId)
                inactivePlayRequests.remove(streamId)
                dismissedLiveApp = null
                itemApp = nextApp
                clearTapRequest()
                dismissedByUser = false
                clearLiveWait()
                clearDeferredOpen()
                finishIphoneLoads() // a new item replaces one the iPhone did not stop first
                this.streamId = streamId
                url = item.url
                itemUuid = item.uuid
                streaming = queued?.get("mediaType") == "streaming"
                note(VideoDiagnostics.queueItem(queued?.get("clientBundleID"), item.url, queued?.get("mediaType")))
                note("queue item fields=${VideoDiagnostics.keys(message.keys)} itemFields=${VideoDiagnostics.keys(queued?.keys ?: emptySet<Any>())}")
                startMillis = resumePosition
                if (openLiveItem || playLiveItem || openDeferredItem || playDeferredItem || liveHandoff) playing = true
                if (liveHandoff) note("live item handoff accepted stream=$streamId")
                if (retainsSource) {
                    note("remote item updated action=retain_player_source stream=$streamId")
                    currentPlayer?.applyRate()
                } else currentPlayer?.load()
                if (openLiveItem || playLiveItem || openDeferredItem || playDeferredItem) show()
            }
            "setRate" -> {
                val nextPlaying = ((message["rate"] as? Number)?.toDouble() ?: 0.0) > 0.0
                if (this.streamId == streamId) {
                    playbackStateRevision++
                    clearPhoneStop()
                }
                note("rate received stream=$streamId playing=$nextPlaying currentStream=${this.streamId} " +
                    "dismissed=$dismissedByUser awaitingReplacement=$awaitingLiveReplacement")
                val tappedItem = pendingTappedItem
                if (tappedItem?.stream == streamId) {
                    if (!nextPlaying || !hasTapRequest(receivedAt) || !VideoInCar.allowed) {
                        clearTapRequest()
                        note("staged CarPlay tap handoff cancelled stream=$streamId")
                        return
                    }
                    deferredPlayStream = streamId
                    deferredPlayUntil = tapRequestUntil
                    pendingTappedItem = null
                    handle(streamId, tappedItem.message, tappedItem.receivedAt)
                    return
                }
                if (nextPlaying && dismissedByUser && this.streamId == streamId && receivedAt < resumeBlockedUntil) {
                    // Keep reporting pause even if an in-flight/repeated phone command asks to play.
                    note("play ignored reason=back_guard")
                    reply(streamId, VideoInCar.playbackStateNotification(false, itemUuid))
                    return
                }
                if (nextPlaying && !VideoInCar.allowed) {
                    note("video player requested while not parked", warn = true)
                    return
                }
                if (url == null || this.streamId != streamId) {
                    deferredPlayStream = if (nextPlaying) streamId else null
                    deferredPlayUntil = if (nextPlaying) receivedAt + LIVE_OPEN_REQUEST_MILLIS else 0L
                    if (!nextPlaying) {
                        deferredUiUntil = 0L
                        liveUiRequestUntil = 0L
                        cancelOpening()
                        openAfterClose = false
                    }
                    note("play request deferred playing=$nextPlaying reason=no_matching_item")
                    if (url == null) show()
                    return
                }
                if (waitingForLiveItem) {
                    livePlayRequestUntil = if (nextPlaying) receivedAt + LIVE_OPEN_REQUEST_MILLIS else 0L
                    if (!nextPlaying) {
                        liveUiRequestUntil = 0L
                    }
                    note("live play request waiting for an item playing=$nextPlaying")
                    return
                }
                if (nextPlaying) dismissedByUser = false
                playing = nextPlaying
                // The iPhone starts the item at once, as Apple's receiver plays it straight
                // away, so open the player now instead of waiting for Now Playing's video button.
                if (playing) show()
                activity?.takeUnless { it.isFinishing }?.applyRate()
                if (nextPlaying) activity?.let { onPlayerPlaybackStateChanged(it, playbackGeneration) }
            }
            "seek" -> {
                VideoInCar.seekMillis(message)?.takeIf { this.streamId == streamId && url != null }?.let {
                    pendingSeekMillis = it
                    activity?.applySeek()
                }
                if (request) reply(streamId, VideoInCar.seekResponse(message["messageID"]))
            }
            "playbackInfo" -> if (request) {
                if (this.streamId == streamId && !ownsRemoteItem(streamId)) {
                    note("playback info ignored reason=no_remote_item stream=$streamId")
                    return
                }
                val ownsItem = this.streamId == streamId && url != null && !inactiveItems.containsKey(streamId)
                val response = VideoInCar.playbackInfoResponse(message["messageID"],
                    if (ownsItem) itemUuid else inactiveItems[streamId], if (ownsItem) playerState() else null)
                if (ownsItem) reply(streamId, response)
                else {
                    val info = (response["info"] as Map<*, *>).toMutableMap().apply { put("playbackState", "paused") }
                    note("inactive playback state replied stream=$streamId currentStream=${this.streamId}")
                    reply(streamId, response + ("info" to info))
                }
            }
            "property" -> if (request) {
                val ownsItem = this.streamId == streamId && url != null && !inactiveItems.containsKey(streamId) &&
                    ownsRemoteItem(streamId)
                val response = VideoInCar.propertyResponse(message["messageID"], message["property"],
                    if (ownsItem) playerState() else null)
                reply(streamId, if (!ownsItem && message["property"] == "hasEnabledAudio") response + ("value" to false) else response)
            }
            "unhandledURL" -> onUrlLoaded(message)
            "stop", "removePlayQueueItem" -> {
                val target = (message["item"] as? Map<*, *>)?.get("uuid") ?: message["uuid"]
                val reason = when {
                    this.streamId != null && this.streamId != streamId -> "other_stream"
                    target != null && !sameItemId(target, itemUuid) -> "other_item"
                    else -> null
                }
                if (reason != null) {
                    note("$type ignored reason=$reason targeted=${target != null}")
                    return
                }
                val visiblePlayer = activity?.takeUnless { it.isFinishing }
                val liveSession = visiblePlayer?.isLiveItem() == true
                note("$type accepted targeted=${target != null} generation=$playbackGeneration " +
                    "playing=$playing visible=${visiblePlayer != null} live=$liveSession dismissed=$dismissedByUser " +
                    "fields=${VideoDiagnostics.keys(message.keys)}")
                if (type == "removePlayQueueItem" && !dismissedByUser &&
                    url != null && liveSession && (target != null || awaitingLiveReplacement)) {
                    awaitLiveReplacement()
                    return
                }
                if (type == "removePlayQueueItem" && target == null) {
                    note("removePlayQueueItem action=stop reason=no_target stream=$streamId")
                    stop()
                    return
                }
                if (!liveSession && !dismissedByUser && url != null && visiblePlayer?.ownsCurrentItem() == true) {
                    if (type == "stop") {
                        val rejected = rejectedLiveHandoff
                        if (playing && rejected?.stream == streamId && rejected.generation == playbackGeneration &&
                            receivedAt < rejected.until) {
                            note("phone stop ignored reason=blocked_live_handoff stream=$streamId")
                            reportRetainedPlaybackState("stop_ignored")
                        } else if (playing) awaitPhoneStop(streamId, visiblePlayer)
                        else pauseOnDemandPlayer(streamId, visiblePlayer)
                    } else note("on-demand item removed action=retain_player stream=$streamId playing=$playing")
                    return
                }
                if (url != null && liveSession) finishLivePlayback("remote_stop")
                stop(keepRemoteOwner = type == "stop")
            }
            "setProperty" -> Unit
            else -> note("video message ${VideoDiagnostics.word(type)} keys=${VideoDiagnostics.keys(message.keys)}")
        }
    }

    private fun sameItemId(left: Any?, right: Any?): Boolean =
        if (left is ByteArray && right is ByteArray) left.contentEquals(right) else left == right

    private fun ownsRemoteItem(stream: Long): Boolean =
        remoteQueueItems[stream]?.let { sameItemId(it, itemUuid) } == true

    private fun acknowledgeQueueSelection(stream: Long, uuid: Any?) {
        if (uuid == null) return
        val before = remoteQueueItems.put(stream, uuid)
        while (remoteQueueItems.size > 64) remoteQueueItems.remove(remoteQueueItems.keys.first())
        reply(stream, VideoInCar.currentItemChangedNotification(uuid, before, "ReasonAddToPlayQueue"))
    }

    private fun acknowledgeQueueRemoval(stream: Long, target: Any?) {
        val before = remoteQueueItems[stream] ?: return
        if (target != null && !sameItemId(target, before)) return
        remoteQueueItems.remove(stream)
        if (playbackOwnerStream == stream) playbackOwnerStream = null
        reply(stream, VideoInCar.itemRemovedNotification(before))
        reply(stream, VideoInCar.currentItemChangedNotification(null, before, "ReasonRemoveFromPlayqueue"))
    }

    private fun claimPlaybackStream(stream: Long) {
        playbackOwnerStream?.takeIf { it != stream }?.let { previous ->
            reply(previous, VideoInCar.playbackInterruptedNotification())
            note("remote playback interrupted reason=source_switch stream=$previous nextStream=$stream")
        }
        playbackOwnerStream = stream
    }

    private fun identityFingerprint(value: Any?): String = itemKey(0, value)?.takeLast(16)
        ?: if (value == null) "none" else "unsupported"

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

    /** What the iPhone answered to [resolveOnIphone]. */
    class LoadedUrl(val status: Int?, val data: ByteArray?, val location: String?)

    private val pendingUrls = ConcurrentHashMap<Long, CompletableFuture<Map<*, *>>>()
    private val nextUrlRequest = AtomicLong(1)

    /**
     * Asks the iPhone to load a URL the car cannot (an app's own scheme), as Apple's receiver
     * does: unhandledURL request with FCUP_Response_URL / _IsContentKeyRequest / _RequestID, answered
     * with FCUP_Response_Data, _StatusCode and _Headers (Location). Blocking; a loader thread.
     */
    fun resolveOnIphone(url: String): LoadedUrl? {
        val stream = streamId ?: return null
        val id = nextUrlRequest.getAndIncrement()
        val answer = CompletableFuture<Map<*, *>>()
        pendingUrls[id] = answer
        reply(stream, linkedMapOf(
            "type" to "unhandledURL",
            "kind" to "request",
            "request" to linkedMapOf(
                "FCUP_Response_URL" to url,
                "FCUP_Response_IsContentKeyRequest" to false,
                "FCUP_Response_RequestID" to id,
            ),
            "item" to linkedMapOf("uuid" to itemUuid),
        ))
        noteIphoneLoad(VideoDiagnostics.IphoneLoads.Kind.ASKED,
            "asked the iPhone to load a ${VideoDiagnostics.scheme(url)} URL request=$id")
        val response = try {
            answer.get(URL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: Exception) {
            noteIphoneLoad(VideoDiagnostics.IphoneLoads.Kind.NO_ANSWER,
                "no iPhone answer for request=$id within ${URL_TIMEOUT_SECONDS}s", warn = true)
            null
        } finally {
            pendingUrls.remove(id)
        }
        response ?: return null
        val headers = response["FCUP_Response_Headers"] as? Map<*, *>
        return LoadedUrl(
            status = (response["FCUP_Response_StatusCode"] as? Number)?.toInt(),
            data = response["FCUP_Response_Data"] as? ByteArray,
            location = (headers?.get("Location") ?: headers?.get("location")) as? String,
        )
    }

    private fun onUrlLoaded(message: Map<String, Any?>) {
        val response = message["response"] as? Map<*, *>
        val id = (response?.get("FCUP_Response_RequestID") as? Number)?.toLong()
        noteIphoneLoad(VideoDiagnostics.IphoneLoads.Kind.ANSWER,
            "iPhone unhandledURL answer request=$id keys=${VideoDiagnostics.keys(message.keys)} " +
                "responseKeys=${response?.keys?.let(VideoDiagnostics::keys)}")
        if (id != null) pendingUrls[id]?.complete(response)
    }

    // While the player is closed the item stays paused where it was, so the iPhone keeps its position.
    private fun playerState(): VideoInCar.PlayerState? =
        activity?.takeIf { !it.isFinishing && it.ownsCurrentItem() }?.state()
            ?: url?.let { VideoInCar.PlayerState(false, false, startMillis / 1000.0, 0.0, 0.0) }

    private fun reportRetainedPlaybackState(reason: String) {
        val stream = streamId ?: return
        if (url == null || dismissedByUser || inactiveItems.containsKey(stream) ||
            activity?.takeUnless { it.isFinishing }?.ownsCurrentItem() != true || !ownsRemoteItem(stream)) return
        note("retained player state sync reason=$reason stream=$stream playing=$playing item=${identityFingerprint(itemUuid)}")
        reply(stream, VideoInCar.playbackStateNotification(playing, itemUuid, playerState()), playbackStateRevision)
    }

    private fun reply(streamId: Long, message: Map<String, Any?>, expectedStateRevision: Long? = null) {
        val target = controller ?: return
        val expectedSession = commandGeneration
        val expectedItem = itemUuid
        val stateMessage = message["type"] == "playbackState"
        val queueMessage = message["type"] == "notification" &&
            message["name"] in setOf("currentItemChanged", "itemRemoved")
        if (queueMessage) note("queue notification queued stream=$streamId event=${VideoDiagnostics.word(message["name"])}")
        if (stateMessage) note("playback state notification queued stream=$streamId state=${VideoDiagnostics.word(message["name"])} " +
            "item=${identityFingerprint(expectedItem)}")
        sender.execute {
            if (controller !== target || commandGeneration != expectedSession) {
                note("video reply skipped reason=old_session stream=$streamId")
                return@execute
            }
            if (expectedStateRevision != null && (controller !== target || playbackStateRevision != expectedStateRevision ||
                this.streamId != streamId || !sameItemId(itemUuid, expectedItem) || url == null)) {
                note("playback state notification skipped reason=stale_state stream=$streamId")
                return@execute
            }
            if (stateMessage) note("playback state notification write_begin stream=$streamId " +
                "state=${VideoDiagnostics.word(message["name"])} item=${identityFingerprint(expectedItem)}")
            // A reply that cannot be sent must never take CarPlay down with it.
            val sent = runCatching { target.sendVideoMessage(streamId, message) }
                .onFailure { note("reply ${VideoDiagnostics.word(message["type"])} failed: ${it.javaClass.simpleName}", warn = true) }
                .getOrDefault(false)
            if (!sent) note("reply ${VideoDiagnostics.word(message["type"])} not sent", warn = true)
            if (queueMessage) note("queue notification sent=$sent stream=$streamId event=${VideoDiagnostics.word(message["name"])}")
            if (message["type"] == "playbackState") note("playback state notification sent=$sent stream=$streamId " +
                "state=${VideoDiagnostics.word(message["name"])} item=${identityFingerprint(expectedItem)}")
        }
    }

    private fun show(retryOnTimeout: Boolean = true) {
        val context = appContext ?: return
        when {
            url == null -> note("video player requested without a playable item", warn = true)
            !VideoInCar.allowed -> note("video player requested while not parked", warn = true)
            dismissedByUser || waitingForLiveItem || openingLaunch != null -> Unit
            activity != null -> openAfterClose = activity?.isFinishing == true
            else -> {
                val launch = ++nextLaunch
                openingLaunch = launch
                // Hold the host canvas before the player changes the system bars.
                playerOnScreen = true
                val intent = Intent(context, CarPlayVideoActivity::class.java)
                    .putExtra(EXTRA_PLAYER_LAUNCH, launch)
                    .putExtra(EXTRA_PLAYER_GENERATION, playbackGeneration)
                // Starting from the live host keeps the player over the correct CarPlay task/display.
                val host = CarPlayScreen.current()
                val started = if (host != null) OwnScreenStart.start(host, intent) else try {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                } catch (error: RuntimeException) {
                    note("could not open video player reason=${error.javaClass.simpleName}", warn = true)
                    false
                }
                if (!started) {
                    cancelOpening()
                    return
                }
                awaitPlayer(launch, retryOnTimeout)
            }
        }
    }

    private fun awaitPlayer(launch: Long, retryOnTimeout: Boolean) {
        openingTimeout = Runnable {
            if (openingLaunch == launch) {
                cancelOpening()
                note("player open timed out retry=$retryOnTimeout")
                if (retryOnTimeout) show(retryOnTimeout = false)
            }
        }.also { main.postDelayed(it, PLAYER_OPEN_TIMEOUT_MILLIS) }
    }

    private fun cancelOpening() {
        openingTimeout?.let(main::removeCallbacks)
        openingTimeout = null
        openingLaunch = null
        if (activity == null) onPlayerGone()
    }

    private fun closePlayer(reason: String) {
        playbackStateRevision++
        clearPhoneStop()
        cancelOpening()
        clearLiveWait()
        clearDeferredOpen()
        openAfterClose = false
        val player = activity ?: return
        note("closing the video player: $reason")
        player.finish()
    }

    private fun finishIphoneLoads() {
        iphoneLoads.finish()?.let(::note)
    }

    private fun clearLiveWait() {
        waitingForLiveItem = false
        liveUiRequestUntil = 0L
        livePlayRequestUntil = 0L
    }

    private fun clearDeferredOpen() {
        deferredPlayStream = null
        deferredPlayUntil = 0L
        deferredUiUntil = 0L
    }

    private fun rememberClosedLiveSource() {
        closedLiveSource = itemKey(0, url)
    }

    private fun rememberInactiveItem(stream: Long, uuid: Any?) {
        inactiveItems[stream] = uuid
        while (inactiveItems.size > 64) {
            val oldest = inactiveItems.keys.first()
            inactiveItems.remove(oldest)
            inactivePlayRequests.remove(oldest)
        }
    }

    private fun cancelLiveRemoval() {
        awaitingLiveReplacement = false
    }

    private fun awaitLiveReplacement() {
        if (awaitingLiveReplacement) return
        awaitingLiveReplacement = true
        note("live item removal retained player awaiting=replacement_or_stop")
    }

    private fun cancelPhoneStop() {
        pendingPhoneStop?.let(main::removeCallbacks)
        pendingPhoneStop = null
        pendingPhoneStopStream = null
    }

    private fun clearPhoneStop() {
        cancelPhoneStop()
        rejectedLiveHandoff = null
    }

    private fun awaitPhoneStop(stream: Long, player: CarPlayVideoActivity) {
        if (pendingPhoneStopStream == stream) return
        cancelPhoneStop()
        val generation = playbackGeneration
        pendingPhoneStopStream = stream
        note("phone stop waiting for handoff stream=$stream windowMs=$PHONE_STOP_HANDOFF_MILLIS")
        pendingPhoneStop = Runnable {
            cancelPhoneStop()
            if (streamId == stream && playbackGeneration == generation && activity === player &&
                !player.isFinishing && !dismissedByUser && url != null) pauseOnDemandPlayer(stream, player)
        }.also { main.postDelayed(it, PHONE_STOP_HANDOFF_MILLIS) }
    }

    private fun pauseOnDemandPlayer(stream: Long, player: CarPlayVideoActivity) {
        playbackStateRevision++
        clearPhoneStop()
        playing = false
        clearTapRequest()
        clearLiveWait()
        clearDeferredOpen()
        cancelOpening()
        openAfterClose = false
        pendingSeekMillis = null
        player.applyRate()
        note("on-demand stop action=pause_keep_player stream=$stream")
    }

    private fun stop(keepRemoteOwner: Boolean = false) {
        playbackStateRevision++
        if (!keepRemoteOwner) playbackOwnerStream = null
        lastPlayerPlaybackReport = null
        clearPhoneStop()
        streamId?.let { rememberInactiveItem(it, itemUuid) }
        cancelLiveRemoval()
        pendingTappedItem = null
        playbackGeneration++
        finishIphoneLoads()
        dismissedByUser = false
        clearLiveWait()
        clearDeferredOpen()
        cancelOpening()
        openAfterClose = false
        url = null
        itemUuid = null
        playing = false
        pendingSeekMillis = null
        activity?.finish()
    }
}
