package com.shilapi.xcertplay.media

/**
 * Notices a music track that says it plays while the car takes no audio from it (#1229).
 *
 * On a DiLink 3.0 (Android 9) the track stayed PLAYING with its head at 0 for over a minute: the buffer filled,
 * and after that every write returned 0 at once. That is also what Android's blocking write does when the native
 * track is not active; only while it is active does the write wait for room, so a car that stops taking audio from
 * an active track holds the renderer's worker inside one write.
 *
 * Both need [STALL_NS] of unbroken evidence: refused writes with no frame accepted and the head in one place, or
 * packets that keep arriving while one write stays blocked. A track that ran dry, is paused or only plays slowly
 * never matches, because its writes are accepted. Each stream reports at most [MAX_REPORTS] stalls, and only the
 * first asks for a rebuild.
 */
internal class MediaTrackStall {
    /** [headFrames] is null for a blocked write, whose track is not asked. */
    data class Stall(val headFrames: Long?, val stalledNs: Long, val zeroWrites: Int, val rebuild: Boolean)

    // Audio worker only.
    private var refusedSinceNs = 0L
    private var lastRefusedNs = 0L
    private var refusedHead = 0
    private var refusedWrites = 0

    // Guarded by this: the worker's write and the packets that arrive meanwhile.
    private var stalls = 0
    private var writing = false
    private var blockedSinceNs = 0L
    private var lastArrivalNs = 0L

    /** Worker: a write to the playing track begins. */
    @Synchronized
    fun writeStarted() {
        writing = true
        blockedSinceNs = 0L
    }

    /** Worker: the write returned. Waits out a pause [packetArrived] is issuing on its track. */
    @Synchronized
    fun writeEnded() {
        writing = false
    }

    /** Worker: the track took frames, or is not playing: nothing is stuck. */
    fun clear() {
        refusedWrites = 0
    }

    /** Worker: the playing track refused a write that ran from [startedNs] to [endedNs]. */
    fun refused(startedNs: Long, endedNs: Long, head: Int): Stall? {
        if (refusedWrites == 0 || head != refusedHead || startedNs - lastRefusedNs > MAX_EVIDENCE_GAP_NS) {
            refusedSinceNs = startedNs
            refusedHead = head
            refusedWrites = 0
        }
        refusedWrites++
        lastRefusedNs = endedNs
        if (refusedWrites < MIN_REFUSED_WRITES || endedNs - refusedSinceNs < STALL_NS) return null
        val count = refusedWrites
        refusedWrites = 0
        return stalled(head.toLong() and 0xffff_ffffL, endedNs - refusedSinceNs, count)
    }

    /**
     * Any thread: a packet arrived at [nowNs]. When the worker's write has been blocked for [STALL_NS], runs
     * [onBlocked] while that write is still in progress, so a pause there reaches the blocked track and no other.
     */
    @Synchronized
    fun packetArrived(nowNs: Long, onBlocked: (Stall) -> Unit) {
        if (!writing) return
        if (blockedSinceNs == 0L || nowNs - lastArrivalNs > MAX_EVIDENCE_GAP_NS) blockedSinceNs = nowNs
        lastArrivalNs = nowNs
        if (nowNs - blockedSinceNs < STALL_NS) return
        val stall = stalled(null, nowNs - blockedSinceNs, 0)
        blockedSinceNs = 0L
        stall?.let(onBlocked)
    }

    @Synchronized
    private fun stalled(head: Long?, stalledNs: Long, zeroWrites: Int): Stall? {
        if (stalls >= MAX_REPORTS) return null
        return Stall(head, stalledNs, zeroWrites, rebuild = stalls++ == 0)
    }

    companion object {
        const val STALL_NS = 3_000_000_000L
        /** Wi-Fi hands music over in bursts up to about half a second apart; a longer gap is not evidence. */
        const val MAX_EVIDENCE_GAP_NS = 1_000_000_000L
        const val MIN_REFUSED_WRITES = 5
        const val MAX_REPORTS = 2
    }
}

/** Android's music volume when a music stream starts. A head unit may set its real volume outside Android. */
class MusicVolume(val value: Int, val max: Int, val muted: Boolean, val fixed: Boolean)

/** The text of each line about music the car may not be playing: numbers and fixed words only. */
object MediaAudioLines {
    fun notConsuming(headFrames: Long?, stalledNs: Long, zeroWrites: Int, queuedPackets: Int, rebuild: Boolean): String {
        val seconds = stalledNs / 1_000_000_000L
        val evidence = when (headFrames) {
            null -> "media track write blocked (for $seconds s, "
            0L -> "media track never started (head=0 for $seconds s, zeroWrites=$zeroWrites, "
            else -> "media track stopped (head=$headFrames for $seconds s, zeroWrites=$zeroWrites, "
        }
        return "Audio: car is not consuming CarPlay audio; ${evidence}queue=$queuedPackets); " +
            if (rebuild) "rebuilding the track once" else "not rebuilding it again"
    }

    fun rebuilt(recreated: Boolean) = "Audio: media track rebuilt recreated=$recreated"

    fun consumingAgain(headFrames: Long) = "Audio: car consumes CarPlay audio after the rebuild head=$headFrames"

    /** [streamOverride] is the legacy stream music was moved to, whose volume may be another one; 0 for none. */
    fun started(volume: MusicVolume?, routeType: Int?, streamOverride: Int) = "Audio: media start " +
        (volume?.let { "musicVolume=${it.value}/${it.max} muted=${it.muted} fixed=${it.fixed}" }
            ?: "musicVolume=unavailable") +
        " routeType=${routeType ?: -1} streamOverride=$streamOverride"
}
