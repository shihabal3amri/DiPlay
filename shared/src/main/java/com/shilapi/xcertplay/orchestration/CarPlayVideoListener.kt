package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.VideoInCar
import java.io.Closeable

/** iOS 27 video in car, played by the host in the car's own player (see [VideoInCar]). */
interface CarPlayVideoListener {
    /** Whether the car is in P, or null when it cannot tell. Blocking; read once a second while CarPlay runs. */
    fun readParked(): Boolean?

    /** Video became allowed or not; when not, the player must close. Any thread. */
    fun onVideoAllowedChanged(allowed: Boolean)

    /** A playback message from the iPhone, e.g. insertPlayQueueItem, setRate, seek, stop. Any thread. */
    fun onVideoMessage(streamId: Long, message: Map<String, Any?>)

    /** The iPhone asks the car to show its video player (requestUI "videoplayback:"). Any thread. */
    fun onVideoUiRequested()

    /** The CarPlay session ended; the player must close. Any thread. */
    fun onVideoSessionEnded()
}

/**
 * Owns [VideoInCar.allowed]. Normally only P permits video; unknown gear keeps it off.
 * The explicit connection-scoped override skips gear reads altogether. Changes go to [onChanged].
 */
internal class VideoInCarGate(
    private val readParked: () -> Boolean?,
    private val onChanged: (Boolean) -> Unit,
    private val onObserved: (Boolean?) -> Unit = {},
    private val forceVideoInCar: Boolean = false,
) : Closeable {
    @Volatile private var closed = false
    private var observed = false
    private var lastParked: Boolean? = null

    fun start() {
        if (forceVideoInCar) {
            update(null)
            return
        }
        Thread({
            while (!closed) {
                update(runCatching(readParked).getOrNull())
                try {
                    Thread.sleep(POLL_MILLIS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "diplay-video-gate").apply { isDaemon = true }.start()
    }

    internal fun update(parked: Boolean?) {
        if (!forceVideoInCar && (!observed || parked != lastParked)) {
            observed = true
            lastParked = parked
            onObserved(parked)
        }
        val allowed = forceVideoInCar || parked == true
        if (allowed == VideoInCar.allowed || closed) return
        VideoInCar.allowed = allowed
        onChanged(allowed)
    }

    override fun close() {
        closed = true
        VideoInCar.allowed = false
    }

    private companion object {
        const val POLL_MILLIS = 1_000L
    }
}
