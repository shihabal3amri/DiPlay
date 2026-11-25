package com.shilapi.xcertplay.airplay

import java.net.Socket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class VideoResourceCommandTest {
    @Test fun nestedResourceOverTheOrdinaryCodecBudgetReachesOnlyAnAuthenticatedNegotiatedVideoStream() {
        val resource = ByteArray(21_558_983) { (it % 251).toByte() }
        val message = mapOf("type" to "unhandledURL", "kind" to "response",
            "response" to mapOf("FCUP_Response_RequestID" to 7L, "FCUP_Response_Data" to resource))
        val inner = BplistCodec.encode(message)
        val body = BplistCodec.encode(mapOf("type" to "remoteControl", "params" to mapOf("data" to inner)))
        assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(body) }
        val scenarios = listOf(
            Triple(false, true, 3L), Triple(true, false, 3L), Triple(true, true, 4L), Triple(true, true, 3L),
        )
        for (optIn in listOf(false, true)) {
            for ((authenticated, negotiated, stream) in scenarios) {
                var received: Map<String, Any?>? = null
                var commands = 0
                val session = AirPlaySession(Socket(),
                    AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "366.0",
                        main = AirPlayDisplayConfig(1280, 720), videoInCar = true),
                    AirPlayIdentity.generate(), PairingStore(), null,
                    object : AirPlaySessionListener {
                        override fun allowsLargeVideoResources(session: AirPlaySession) = optIn
                        override fun onRemoteControlMessage(session: AirPlaySession, streamId: Long, message: Map<String, Any?>) {
                            received = message
                        }
                        override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) { commands++ }
                    }, object : AirPlayMediaHandler {
                        override fun onDataStream(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?> =
                            mapOf("type" to 130L, "streamID" to 3L)
                    },
                )
                try {
                    if (authenticated) session.cipher = ControlCipher(ByteArray(32), ByteArray(32))
                    if (negotiated) {
                        val setup = BplistCodec.encode(mapOf("streams" to listOf(mapOf("type" to 130L,
                            "clientTypeUUID" to VideoInCar.REMOTE_CONTROL_UUIDS.first(), "controlType" to 1L))))
                        AirPlaySession::class.java.getDeclaredMethod("handleSetup", RtspMessage.Request::class.java)
                            .apply { isAccessible = true }.invoke(session,
                                RtspMessage.Request("SETUP", "/", "RTSP/1.0", emptyMap(), setup))
                    }
                    val request = RtspMessage.Request("POST", "/command", "RTSP/1.0",
                        mapOf("x-apple-streamid" to stream.toString()), body)
                    AirPlaySession::class.java.getDeclaredMethod("handleCommand", RtspMessage.Request::class.java)
                        .apply { isAccessible = true }.invoke(session, request)
                    if (optIn && authenticated && negotiated && stream == 3L) {
                        val view = (received!!["response"] as Map<*, *>)["FCUP_Response_Data"] as BplistCodec.DataSlice
                        assertSame(body, view.source)
                        assertArrayEquals(resource, view.source.copyOfRange(view.offset, view.offset + view.size))
                        received = null
                        for (unexpected in listOf(
                            mapOf("type" to "other", "params" to mapOf("blob" to ByteArray(2 * 1024 * 1024))),
                            mapOf("type" to "remoteControl", "params" to mapOf("data" to BplistCodec.encode(
                                mapOf("type" to "property", "blob" to ByteArray(2 * 1024 * 1024))))),
                            mapOf("type" to "remoteControl", "params" to mapOf("data" to BplistCodec.encode(
                                mapOf("type" to "unhandledURL", "kind" to "request", "response" to mapOf(
                                    "FCUP_Response_Data" to ByteArray(2 * 1024 * 1024)))))),
                        )) {
                            AirPlaySession::class.java.getDeclaredMethod("handleCommand", RtspMessage.Request::class.java)
                                .apply { isAccessible = true }.invoke(session, request.copy(body = BplistCodec.encode(unexpected)))
                        }
                        assertNull(received)
                        assertEquals(0, commands)
                    } else assertNull(received)
                } finally { session.close() }
            }
        }
    }

    @Test fun videoCodecStillRejectsBodiesAboveItsFixedLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            BplistCodec.decodeVideoResource(ByteArray(VideoInCar.MAX_RESOURCE_COMMAND_BYTES + 1))
        }
    }
}
