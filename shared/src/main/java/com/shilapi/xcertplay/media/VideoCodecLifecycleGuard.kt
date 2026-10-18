package com.shilapi.xcertplay.media

/**
 * Opt-in, process-local admission for video codec startup and cleanup. A native call keeps its
 * lease until it actually returns; a timeout never pretends to cancel native work or frees a peer.
 * Playback, audio and microphone work do not acquire this gate.
 */
internal class VideoCodecLifecycleGuard(private val now: () -> Long = System::nanoTime) {
    private val monitor = Object()
    private var active: Lease? = null
    private var waitingCleanups = 0

    internal inner class Lease(val label: String) : AutoCloseable {
        private var phase = "admission"
        private var since = now()
        private var closed = false
        /** Validate the completed phase before entering another native phase. Cleanup is always allowed. */
        fun phase(next: String, running: () -> Boolean = { true }) {
            synchronized(monitor) {
                check(active === this && !closed)
                if (!running() || now() - since > PHASE_TIMEOUT_NS) throw StartupAborted()
                phase = next
                since = now()
            }
        }
        fun cleanupPhase(next: String) { synchronized(monitor) {
            check(active === this && !closed)
            phase = next
            since = now()
        } }
        fun description(): String = synchronized(monitor) { "$label phase=$phase elapsedMs=${(now() - since) / 1_000_000L}" }
        override fun close() { synchronized(monitor) {
            if (closed) return
            closed = true
            if (active === this) { active = null; monitor.notifyAll() }
        } }
    }

    internal class StartupAborted : IllegalStateException("video codec startup admission expired or worker closed")

    /** A busy factory never creates a second candidate on another thread. Interrupts abort admission. */
    fun startup(label: String, running: () -> Boolean, waitNanos: Long = ADMISSION_WAIT_NS): Lease? {
        val deadline = now() + waitNanos
        synchronized(monitor) {
            while (active != null || waitingCleanups > 0) {
                if (!running()) return null
                val left = deadline - now()
                if (left <= 0) return null
                monitor.wait((left / 1_000_000L).coerceAtLeast(1L))
            }
            if (!running()) return null
            return Lease(label).also { active = it }
        }
    }

    /** Keep ownership of a real codec until cleanup can run; never discard an unreleased reference. */
    fun cleanup(label: String): Lease {
        var interrupted = false
        synchronized(monitor) {
            waitingCleanups++
            try {
                while (active != null) {
                    try { monitor.wait(100L) } catch (_: InterruptedException) { interrupted = true }
                }
                return Lease(label).also { active = it }
            } finally {
                waitingCleanups--
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }

    fun blockedPhase(): String? = synchronized(monitor) { active?.description() }

    companion object {
        const val ADMISSION_WAIT_NS = 750_000_000L
        const val PHASE_TIMEOUT_NS = 5_000_000_000L
        val process = VideoCodecLifecycleGuard()
    }
}
