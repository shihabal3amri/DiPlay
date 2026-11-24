package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConnectionStateMachineTest {

    private lateinit var stateMachine: ConnectionStateMachine

    @Before
    fun setUp() {
        stateMachine = ConnectionStateMachine()
    }

    @Test
    fun initialState_isIdle() {
        assertEquals(ConnectionState.IDLE, stateMachine.state)
    }

    @Test
    fun validLifecycle_transitionsSuccessfully() {
        assertTrue(stateMachine.transitionTo(ConnectionState.CONNECTING, "Starting connection"))
        assertEquals(ConnectionState.CONNECTING, stateMachine.state)

        assertTrue(stateMachine.transitionTo(ConnectionState.AUTHENTICATING, "Handshake"))
        assertEquals(ConnectionState.AUTHENTICATING, stateMachine.state)

        assertTrue(stateMachine.transitionTo(ConnectionState.STREAMING, "Video ready"))
        assertEquals(ConnectionState.STREAMING, stateMachine.state)

        assertTrue(stateMachine.transitionTo(ConnectionState.DISCONNECTING, "User stop"))
        assertEquals(ConnectionState.DISCONNECTING, stateMachine.state)

        assertTrue(stateMachine.transitionTo(ConnectionState.IDLE, "Disconnected"))
        assertEquals(ConnectionState.IDLE, stateMachine.state)
    }

    @Test
    fun invalidTransition_isRejected() {
        // Direct transition from IDLE to STREAMING should fail
        assertFalse(stateMachine.transitionTo(ConnectionState.STREAMING, "Direct stream illegal"))
        assertEquals(ConnectionState.IDLE, stateMachine.state)
    }

    @Test
    fun reset_returnsToIdle() {
        stateMachine.transitionTo(ConnectionState.CONNECTING, "Connecting")
        assertTrue(stateMachine.reset())
        assertEquals(ConnectionState.IDLE, stateMachine.state)
    }
}
