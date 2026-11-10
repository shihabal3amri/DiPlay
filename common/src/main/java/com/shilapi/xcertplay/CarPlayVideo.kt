package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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
    private const val PLAYER_OPEN_TIMEOUT_MILLIS = 5_000L
    private const val EXTRA_PLAYER_LAUNCH = "video_player_launch"

    private val main = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor { Thread(it, "diplay-video-reply").apply { isDaemon = true } }
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
    private var dismissedByUser = false
    private var waitingForLiveItem = false
    private var liveUiRequestUntil = 0L
    private var livePlayRequestUntil = 0L
    @Volatile private var resumeBlockedUntil = 0L
    private var nextLaunch = 0L
    private var openingLaunch: Long? = null
    private var openingTimeout: Runnable? = null
    private var openAfterClose = false
    var activity: CarPlayVideoActivity? = null

    fun attach(context: Context, next: CarPlayController) {
        appContext = context.applicationContext
        controller = next
        next.videoListener = this
    }

    /** A queued Activity start may arrive after Back, stop, or a session/gear change. */
    fun attachPlayer(next: CarPlayVideoActivity): Boolean {
        val launch = next.intent?.getLongExtra(EXTRA_PLAYER_LAUNCH, -1L)
        if (launch == null || launch != openingLaunch) return false
        cancelOpening()
        if (url == null || !VideoInCar.allowed || dismissedByUser || activity != null) return false
        activity = next
        return true
    }

    fun detachPlayer(previous: CarPlayVideoActivity, positionMillis: Int?, changingConfigurations: Boolean) {
        if (activity !== previous) return
        activity = null
        if (openAfterClose) {
            openAfterClose = false
            show()
        } else if (changingConfigurations) {
            val launch = previous.intent.getLongExtra(EXTRA_PLAYER_LAUNCH, -1L)
            if (url != null && VideoInCar.allowed && !dismissedByUser) {
                openingLaunch = launch
                awaitPlayer(launch, retryOnTimeout = true)
            }
        } else {
            onPlayerClosed(positionMillis)
        }
    }

    override fun readParked(): Boolean? = appContext?.let(BydNavigationOutputs::parked)

    override fun onVideoAllowedChanged(allowed: Boolean) {
        if (!allowed) main.post { closePlayer("the car left P") }
    }

    override fun onVideoSessionEnded() {
        main.post {
            stop()
            streamId = null
        }
    }

    override fun onVideoUiRequested() {
        val guardAtRequest = resumeBlockedUntil
        main.post {
            if (guardAtRequest != resumeBlockedUntil || url == null || !VideoInCar.allowed) return@post
            if (waitingForLiveItem) {
                liveUiRequestUntil = SystemClock.uptimeMillis() + LIVE_OPEN_REQUEST_MILLIS
                return@post
            }
            if (dismissedByUser) setPlaying(true)
            show()
        }
    }

    override fun onVideoMessage(streamId: Long, message: Map<String, Any?>) {
        val receivedAt = SystemClock.uptimeMillis()
        main.post { handle(streamId, message, receivedAt) }
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
        if (next) dismissedByUser = false
        playing = next
        activity?.takeUnless { it.isFinishing }?.applyRate()
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

    /** The player closed on the car (Back, or the car left P): pause, so the iPhone shows it paused. */
    fun onPlayerClosed(positionMillis: Int?) {
        positionMillis?.let { startMillis = it }
        if (playing) setPlaying(false)
    }

    /** Pause locally and ignore in-flight play requests briefly while the iPhone catches up. */
    fun dismissPlayer(positionMillis: Int?, isLive: Boolean = false) {
        dismissedByUser = true
        waitingForLiveItem = isLive && url != null
        liveUiRequestUntil = 0L
        livePlayRequestUntil = 0L
        resumeBlockedUntil = SystemClock.uptimeMillis() + BACK_PLAY_GUARD_MILLIS
        cancelOpening()
        openAfterClose = false
        onPlayerClosed(positionMillis)
    }

    private fun handle(streamId: Long, message: Map<String, Any?>, receivedAt: Long) {
        val request = message["kind"] == "request"
        val type = message["type"] as? String
        if (type != "stop" && type != "removePlayQueueItem") this.streamId = streamId
        when (type) {
            "insertPlayQueueItem" -> {
                // An app's own scheme is loaded through the iPhone (IphoneResolvingDataSource).
                val item = VideoInCar.parseItem(message, iphoneLoadsAppSchemes = true)
                if (item == null) {
                    Log.w(TAG, "queue item this player cannot play")
                    return
                }
                val openLiveItem = waitingForLiveItem && receivedAt < liveUiRequestUntil
                val playLiveItem = waitingForLiveItem && receivedAt < livePlayRequestUntil
                if (dismissedByUser && !openLiveItem && sameItemId(item.uuid, itemUuid) && item.url == url) return
                dismissedByUser = false
                waitingForLiveItem = false
                liveUiRequestUntil = 0L
                livePlayRequestUntil = 0L
                Log.i(TAG, "queue item app=${(message["item"] as? Map<*, *>)?.get("clientBundleID")}")
                url = item.url
                itemUuid = item.uuid
                val queued = message["item"] as? Map<*, *>
                streaming = queued?.get("mediaType") == "streaming"
                Log.i(TAG, "queue item host=${android.net.Uri.parse(item.url).host} mediaType=${queued?.get("mediaType")}")
                startMillis = item.startMillis
                activity?.takeUnless { it.isFinishing }?.load()
                if (playLiveItem) playing = true
                if (openLiveItem || playLiveItem) show()
            }
            "setRate" -> {
                val nextPlaying = ((message["rate"] as? Number)?.toDouble() ?: 0.0) > 0.0
                if (waitingForLiveItem) {
                    livePlayRequestUntil = if (nextPlaying && VideoInCar.allowed) receivedAt + LIVE_OPEN_REQUEST_MILLIS else 0L
                    return
                }
                if (nextPlaying && dismissedByUser && receivedAt < resumeBlockedUntil) {
                    // Keep reporting pause even if an in-flight/repeated phone command asks to play.
                    reply(streamId, VideoInCar.playbackStateNotification(false, itemUuid))
                    return
                }
                if (nextPlaying && !VideoInCar.allowed) return
                if (nextPlaying) dismissedByUser = false
                playing = nextPlaying
                // The iPhone starts the item at once, as Apple's receiver plays it straight
                // away, so open the player now instead of waiting for Now Playing's video button.
                if (playing) show()
                activity?.takeUnless { it.isFinishing }?.applyRate()
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
            "unhandledURL" -> onUrlLoaded(message)
            "stop", "removePlayQueueItem" -> {
                if (this.streamId != null && this.streamId != streamId) return
                val target = (message["item"] as? Map<*, *>)?.get("uuid") ?: message["uuid"]
                if (target != null && !sameItemId(target, itemUuid)) return
                if (type == "removePlayQueueItem" && (waitingForLiveItem || target == null)) return
                stop()
            }
            "setProperty" -> Unit
            else -> Log.i(TAG, "video message $type keys=${message.keys}")
        }
    }

    private fun sameItemId(left: Any?, right: Any?): Boolean =
        if (left is ByteArray && right is ByteArray) left.contentEquals(right) else left == right

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
        Log.i(TAG, "asked the iPhone to load a ${android.net.Uri.parse(url).scheme} URL request=$id")
        val response = try {
            answer.get(URL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: Exception) {
            Log.w(TAG, "no iPhone answer for request=$id")
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
        Log.i(TAG, "iPhone unhandledURL answer request=$id keys=${message.keys} responseKeys=${response?.keys}")
        if (id != null) pendingUrls[id]?.complete(response)
    }

    // While the player is closed the item stays paused where it was, so the iPhone keeps its position.
    private fun playerState(): VideoInCar.PlayerState? =
        activity?.state() ?: url?.let { VideoInCar.PlayerState(false, false, startMillis / 1000.0, 0.0, 0.0) }

    private fun reply(streamId: Long, message: Map<String, Any?>) {
        val target = controller ?: return
        sender.execute {
            // A reply that cannot be sent must never take CarPlay down with it.
            val sent = runCatching { target.sendVideoMessage(streamId, message) }
                .onFailure { Log.w(TAG, "reply ${message["type"]} failed: ${it.javaClass.simpleName}") }
                .getOrDefault(false)
            if (!sent) Log.w(TAG, "reply ${message["type"]} not sent")
        }
    }

    private fun show(retryOnTimeout: Boolean = true) {
        val context = appContext ?: return
        when {
            url == null -> Log.w(TAG, "video player requested without a playable item")
            !VideoInCar.allowed -> Log.w(TAG, "video player requested while not parked")
            dismissedByUser || waitingForLiveItem || openingLaunch != null -> Unit
            activity != null -> openAfterClose = activity?.isFinishing == true
            else -> {
                val launch = ++nextLaunch
                openingLaunch = launch
                val intent = Intent(context, CarPlayVideoActivity::class.java).putExtra(EXTRA_PLAYER_LAUNCH, launch)
                // Starting from the live host keeps the player over the correct CarPlay task/display.
                val host = CarPlayScreen.current()
                val started = if (host != null) OwnScreenStart.start(host, intent) else try {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                } catch (error: RuntimeException) {
                    Log.w(TAG, "could not open video player", error)
                    false
                }
                if (!started) {
                    openingLaunch = null
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
                if (retryOnTimeout) show(retryOnTimeout = false)
            }
        }.also { main.postDelayed(it, PLAYER_OPEN_TIMEOUT_MILLIS) }
    }

    private fun cancelOpening() {
        openingTimeout?.let(main::removeCallbacks)
        openingTimeout = null
        openingLaunch = null
    }

    private fun closePlayer(reason: String) {
        cancelOpening()
        openAfterClose = false
        val player = activity ?: return
        Log.i(TAG, "closing the video player: $reason")
        player.finish()
    }

    private fun stop() {
        dismissedByUser = false
        waitingForLiveItem = false
        liveUiRequestUntil = 0L
        livePlayRequestUntil = 0L
        cancelOpening()
        openAfterClose = false
        url = null
        itemUuid = null
        playing = false
        pendingSeekMillis = null
        activity?.finish()
    }
}
