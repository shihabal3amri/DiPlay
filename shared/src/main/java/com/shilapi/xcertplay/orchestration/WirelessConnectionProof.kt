package com.shilapi.xcertplay.orchestration

/**
 * Confirms one current session only after authenticated tunnel readiness AND a rendered frame.
 *
 * Separately, [isUsable] says whether that session works without the tunneled iAP2 channel, which
 * decides whether the handoff watchdog keeps it.
 */
internal class WirelessConnectionProof<S : Any> {
    private var generation = -1
    private var session: S? = null
    private var authenticated = false
    private var rendered = false
    // Kept once [isUsable] has accepted the current session, so a later check cannot drop it.
    private var usable = false
    private var confirmation: (() -> Unit)? = null

    @Synchronized fun begin(generation: Int, confirmation: () -> Unit) {
        clear()
        this.generation = generation
        this.confirmation = confirmation
    }

    @Synchronized fun activate(generation: Int, session: S) {
        if (this.generation != generation || this.session === session) return
        this.session = session
        authenticated = false
        rendered = false
        usable = false
    }

    @Synchronized fun authenticated(generation: Int) {
        if (this.generation != generation || session == null) return
        authenticated = true
        confirmIfReady()
    }

    @Synchronized fun rendered(generation: Int, session: S) {
        if (this.generation != generation || this.session !== session) return
        rendered = true
        confirmIfReady()
    }


    @Synchronized fun hasActiveSession(generation: Int): Boolean =
        this.generation == generation && session != null
    @Synchronized fun hasRenderedFrame(generation: Int): Boolean =
        this.generation == generation && session != null && rendered

    /**
     * Whether [session], the current one, has shown that CarPlay works. A rendered frame shows it.
     * With no screen attached ([screenAttached] false, e.g. CarPlay hidden behind another app) no
     * frame can render, so audio the iPhone sent on the session ([audioReceived]) shows it instead.
     * A dead session sends neither. With a screen attached, audio alone is not enough: a session
     * that plays sound behind a black screen still fails. Once accepted, the session stays accepted.
     */
    @Synchronized fun isUsable(generation: Int, session: S, audioReceived: Boolean, screenAttached: Boolean): Boolean {
        if (this.generation != generation || this.session !== session) return false
        if (rendered || (audioReceived && !screenAttached)) usable = true
        return usable
    }

    @Synchronized fun end(generation: Int, session: S) {
        if (this.generation != generation || this.session !== session) return
        this.session = null
        authenticated = false
        rendered = false
        usable = false
    }

    @Synchronized fun clear() {
        generation = -1
        session = null
        authenticated = false
        rendered = false
        usable = false
        confirmation = null
    }

    private fun confirmIfReady() {
        if (!authenticated || !rendered) return
        val callback = confirmation ?: return
        confirmation = null
        callback()
    }
}
