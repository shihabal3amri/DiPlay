package com.shilapi.xcertplay.airplay

import java.net.Socket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class VideoControlStreamTeardownTest {
    @Test fun retiringVideoControlNotifiesItsOwnerAndKeepsTheIapTunnel() {
        val ended = mutableListOf<Long>()
        val mediaEnded = mutableListOf<Int>()
        val session = AirPlaySession(
            socket = Socket(),
            config = AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "366.0",
                main = AirPlayDisplayConfig(1280, 720)),
            identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
            listener = object : AirPlaySessionListener {
                override fun onRemoteControlStreamEnded(session: AirPlaySession, streamId: Long) {
                    ended += streamId
                }
            },
            media = object : AirPlayMediaHandler {
                override fun onTeardown(session: AirPlaySession, type: Int) { mediaEnded += type }
            },
        )
        try {
            session.activeStreams.add(130)
            fun teardown(id: Long) {
                val body = BplistCodec.encode(mapOf("streams" to listOf(mapOf("type" to 130L, "streamID" to id))))
                AirPlaySession::class.java.getDeclaredMethod("handle", RtspMessage.Request::class.java)
                    .apply { isAccessible = true }.invoke(session,
                        RtspMessage.Request("TEARDOWN", "rtsp://test", "RTSP/1.0", emptyMap(), body))
            }
            teardown(3)
            assertEquals(listOf(3L), ended)
            assertTrue(session.activeStreams.contains(130))
            assertTrue(mediaEnded.isEmpty())
            teardown(1)
            assertEquals(listOf(3L), ended)
            assertEquals(listOf(130), mediaEnded)
            assertFalse(session.activeStreams.contains(130))
        } finally {
            session.close()
        }
    }
}
