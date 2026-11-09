package com.shilapi.xcertplay

/** Retains a retiring wired owner across Activities until both teardown and its worker finish. */
internal object WiredTeardownGuard {
    enum class Status { READY, PENDING, FAILED }
    private data class Owner(val isClosed: () -> Boolean, val hasFailed: () -> Boolean)
    private val pending = mutableListOf<Owner>()

    @Synchronized fun hold(checkClosed: () -> Boolean, checkFailed: () -> Boolean = { false }) {
        pending.add(Owner(checkClosed, checkFailed))
    }

    @Synchronized fun status(): Status {
        pending.removeAll { it.isClosed() }
        return when {
            pending.isEmpty() -> Status.READY
            pending.any { it.hasFailed() } -> Status.FAILED
            else -> Status.PENDING
        }
    }

    fun ready() = status() == Status.READY
}
