package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.airplay.rcs.uisync.*

/** Receipt/sequence layer only. It does not claim that an OEM Local UI layout was rendered. */
class UltraUiSync {
    var session: Long? = null
        private set
    var received = -1L
        private set
    var lastCommand: String? = null
        private set
    private var sent = 0L
    private var resetReply: CarPlayUiSyncMessage? = null
    private var commandReply: CarPlayUiSyncMessage? = null

    fun receive(message: CarPlayUiSyncMessage): CarPlayUiSyncMessage? = when (message.type) {
        CarPlayUiSyncMessageType.RESET -> {
            if (session == message.sessionSequenceNumber && received == message.packetSequenceNumber && resetReply != null) resetReply
            else {
                session = message.sessionSequenceNumber; received = message.packetSequenceNumber
                sent = 0; lastCommand = null; commandReply = null
                CarPlayUiSyncMessageFactory.resetComplete(requireNotNull(session), sent++, received,
                    noAck = true, version = message.version).also { resetReply = it }
            }
        }
        CarPlayUiSyncMessageType.COMMAND -> {
            if (session != message.sessionSequenceNumber || message.packetSequenceNumber < received) null
            else if (message.packetSequenceNumber == received) if (message.noAck) null else commandReply
            else {
                received = message.packetSequenceNumber
                lastCommand = message.payload?.get("command") as? String
                resetReply = null
                if (message.noAck) { commandReply = null; null }
                else CarPlayUiSyncMessageFactory.commandAck(requireNotNull(session), sent++, received,
                    noAck = true).also { commandReply = it }
            }
        }
        CarPlayUiSyncMessageType.RESET_COMPLETE, CarPlayUiSyncMessageType.COMMAND_ACK -> null
    }
}
