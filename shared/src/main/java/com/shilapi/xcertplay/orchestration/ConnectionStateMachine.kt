package com.shilapi.xcertplay.orchestration

/** Explicit connection states for the car projection session lifecycle. */
enum class ConnectionState {
    IDLE,
    DISCOVERING,
    DEVICE_SELECTED,
    PAIRING,
    CONNECTING,
    AUTHENTICATING,
    STREAMING,
    RECOVERING,
    DISCONNECTING;

    fun canTransitionTo(next: ConnectionState): Boolean = when (this) {
        IDLE -> next in setOf(DISCOVERING, DEVICE_SELECTED, CONNECTING, DISCONNECTING)
        DISCOVERING -> next in setOf(DEVICE_SELECTED, IDLE, RECOVERING, DISCONNECTING)
        DEVICE_SELECTED -> next in setOf(PAIRING, CONNECTING, IDLE, DISCONNECTING)
        PAIRING -> next in setOf(CONNECTING, IDLE, RECOVERING, DISCONNECTING)
        CONNECTING -> next in setOf(AUTHENTICATING, STREAMING, RECOVERING, DISCONNECTING, IDLE)
        AUTHENTICATING -> next in setOf(STREAMING, RECOVERING, DISCONNECTING, IDLE)
        STREAMING -> next in setOf(RECOVERING, DISCONNECTING, IDLE)
        RECOVERING -> next in setOf(CONNECTING, AUTHENTICATING, STREAMING, IDLE, DISCONNECTING)
        DISCONNECTING -> next in setOf(IDLE)
    }
}

/** Event log record representing a state transition for session timeline diagnostics. */
data class StateTransitionRecord(
    val from: ConnectionState,
    val to: ConnectionState,
    val timestampMs: Long = System.currentTimeMillis(),
    val reason: String? = null
)

/** Thread-safe connection state machine for session management. */
class ConnectionStateMachine(
    private var currentState: ConnectionState = ConnectionState.IDLE,
    private val onStateChanged: ((ConnectionState, ConnectionState, String?) -> Unit)? = null
) {
    private val lock = Any()
    private val history = mutableListOf<StateTransitionRecord>()

    val state: ConnectionState
        get() = synchronized(lock) { currentState }

    fun transitionTo(nextState: ConnectionState, reason: String? = null): Boolean = synchronized(lock) {
        if (currentState == nextState) return true
        if (!currentState.canTransitionTo(nextState)) {
            val errorRecord = StateTransitionRecord(
                from = currentState,
                to = nextState,
                reason = "Invalid transition attempted: $reason"
            )
            history.add(errorRecord)
            return false
        }
        val previous = currentState
        currentState = nextState
        history.add(StateTransitionRecord(previous, nextState, reason = reason))
        onStateChanged?.invoke(previous, nextState, reason)
        return true
    }

    fun reset(): Boolean = synchronized(lock) {
        if (currentState == ConnectionState.IDLE) return true
        if (currentState != ConnectionState.DISCONNECTING) {
            transitionTo(ConnectionState.DISCONNECTING, "Resetting")
        }
        transitionTo(ConnectionState.IDLE, "Session reset")
    }

    fun getHistory(): List<StateTransitionRecord> = synchronized(lock) {
        history.toList()
    }
}
