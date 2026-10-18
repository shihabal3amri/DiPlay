package com.shilapi.xcertplay

/** Vehicle mute edges control the current phone session, without toggling playback. */
internal class VehicleMediaMutePolicy {
    enum class Command { PAUSE, PLAY }
    @Volatile var revision = 0L
        private set
    private var session: Any? = null
    private var muted: Boolean? = null
    private var pendingPlay = false
    private var pendingPause = false

    @Synchronized fun clear() { session = null; muted = null; manualControl() }
    @Synchronized fun manualControl() { revision++; pendingPlay = false; pendingPause = false }
    @Synchronized fun commandFailed() { manualControl() }

    @Synchronized fun observe(owner: Any?, enabled: Boolean, connected: Boolean, validMedia: Boolean,
        priorityVoice: Boolean, mute: Boolean?, playing: Boolean?): Command? {
        if (owner == null || !enabled || !connected || !validMedia) { clear(); return null }
        if (session !== owner) { clear(); session = owner }
        val previousMute = muted
        muted = mute
        if (mute == null) { manualControl(); return null }
        if (mute) pendingPlay = false else pendingPause = false
        if (previousMute != true && mute && (playing == true || priorityVoice)) pendingPause = true
        if (previousMute == true && !mute) pendingPlay = true
        if (priorityVoice) return null
        if (pendingPlay && !mute) {
            pendingPlay = false
            return Command.PLAY
        }
        if (pendingPause && mute) {
            pendingPause = false
            return if (playing == true) Command.PAUSE else null
        }
        return null
    }
}
