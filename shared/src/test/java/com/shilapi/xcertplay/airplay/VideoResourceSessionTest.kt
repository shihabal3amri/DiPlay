package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class VideoResourceSessionTest {
    @Test fun negotiatedReplyOfTheReportedSizeDoesNotNeedAnOutstandingUiWindowOrCloseCarPlay() = peer { p ->
        p.negotiate()
        p.sendBody(21_558_983, 3)
        assertEquals("200", p.reply().path)
        assertTrue(p.logs.joinToString("\n"), p.logs.any { "bytes=21558983 action=receive" in it })
        p.probe()
    }

    @Test fun aRejectedVideoResourceIsDrainedAndTheControlSessionRemainsUsable() = peer { p ->
        p.negotiate()
        p.sendBody(VideoInCar.MAX_RESOURCE_COMMAND_BYTES + 1, 3)
        assertEquals("200", p.reply().path)
        assertTrue(p.logs.any { "action=drain reason=resource_size_limit" in it })
        p.probe()
    }

    @Test fun videoRepliesOver64MiBAreDrainedWithoutClosingCarPlay() = peer { p ->
        p.negotiate()
        val size = 64 * 1024 * 1024 + 1
        p.sendBody(size, 3)
        assertEquals("200", p.reply().path)
        assertTrue(p.logs.any { "bytes=$size action=drain reason=resource_size_limit" in it })
        p.probe()
    }

    @Test fun memoryPressureRejectsOnlyTheResourceAndKeepsEncryptedControlUsable() =
        peer(ControlBodyBudget({ 64L * 1024 * 1024 }, { 64L * 1024 * 1024 })) { p ->
            p.negotiate()
            p.sendBody(21_558_983, 3)
            assertEquals("200", p.reply().path)
            assertTrue(p.logs.joinToString("\n"), p.logs.any { "action=drain reason=memory_budget" in it })
            p.probe()
        }

    @Test fun unnegotiatedStreamStillClosesAtTheOriginalLimit() = peer { p ->
        p.negotiate()
        p.sendHeader(2 * 1024 * 1024, 4)
        assertThrows(java.io.IOException::class.java) { p.reply() }
    }

    @Test fun negotiatedRepliesOverOneMiBAreDrainedWhenRelayStartsDisabled() = peer(optIn = { false }) { p ->
        p.negotiate()
        p.sendBody(1024 * 1024 + 1, 3)
        assertEquals("200", p.reply().path)
        assertTrue(p.logs.any { "action=drain reason=relay_disabled" in it })
        p.probe()
    }

    @Test fun disablingTheSettingDrainsLargeVideoRepliesWithoutReconnecting() {
        val enabled = java.util.concurrent.atomic.AtomicBoolean(true)
        peer(optIn = enabled::get) { p ->
            p.negotiate()
            p.sendBody(2 * 1024 * 1024, 3)
            assertEquals("200", p.reply().path)
            enabled.set(false)
            p.sendBody(2 * 1024 * 1024, 3)
            assertEquals("200", p.reply().path)
            assertTrue(p.logs.any { "action=drain reason=relay_disabled" in it })
            p.probe()
        }
    }

    @Test fun enablingVideoForwardingDoesNotLiftTheLimitForOtherControlPaths() = peer { p ->
        p.negotiate()
        p.sendHeader(2 * 1024 * 1024, 3, "/feedback")
        assertThrows(java.io.IOException::class.java) { p.reply() }
    }

    @Test fun negotiatedStreamRegistryIsBoundedAndTeardownReclaimsItsSlot() = peer { p ->
        repeat(16) { p.negotiate() }
        p.negotiate()
        assertEquals(16, p.logs.count { "video resource stream registered" in it })
        assertTrue(p.logs.any { "reason=stream_limit" in it })
        p.teardown(3)
        p.negotiate()
        assertEquals(17, p.logs.count { "video resource stream registered" in it })
        p.probe()
    }

    private class Peer(val socket: Socket, val logs: MutableList<String>) {
        private val cipher = ControlCipher(ByteArray(32) { 2 }, ByteArray(32) { 1 })
        private var encrypted = ByteArray(0)
        private var plaintext = ByteArray(0)

        fun negotiate() {
            val body = BplistCodec.encode(mapOf("streams" to listOf(mapOf("type" to 130L,
                "clientTypeUUID" to VideoInCar.REMOTE_CONTROL_UUIDS.first(), "controlType" to 1L))))
            send("SETUP / RTSP/1.0\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body)
            assertEquals("200", reply().path)
        }

        fun sendHeader(size: Int, stream: Int, path: String = "/command") =
            send("POST $path RTSP/1.0\r\nX-Apple-StreamID: $stream\r\nContent-Length: $size\r\n\r\n".toByteArray())

        fun teardown(stream: Long) {
            val body = BplistCodec.encode(mapOf("streams" to listOf(mapOf("type" to 130L, "streamID" to stream))))
            send("TEARDOWN / RTSP/1.0\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body)
            assertEquals("200", reply().path)
        }

        fun sendBody(size: Int, stream: Int) {
            sendHeader(size, stream)
            var left = size
            val chunk = ByteArray(16 * 1024)
            while (left > 0) {
                val count = minOf(left, chunk.size)
                send(if (count == chunk.size) chunk else chunk.copyOf(count))
                left -= count
            }
        }

        private fun send(bytes: ByteArray) { socket.getOutputStream().write(cipher.encrypt(bytes)); socket.getOutputStream().flush() }

        fun reply(): RtspMessage.Request {
            val bytes = ByteArray(16 * 1024)
            while (true) {
                val count = socket.getInputStream().read(bytes)
                if (count < 0) throw java.io.EOFException("Control session closed")
                encrypted += bytes.copyOf(count)
                val decoded = cipher.decrypt(encrypted)
                encrypted = decoded.rest
                plaintext += decoded.data
                val parsed = RtspMessage.parseMessages(plaintext)
                plaintext = parsed.rest
                if (parsed.messages.isNotEmpty()) return parsed.messages.single()
            }
        }

        fun probe() {
            send("POST /x RTSP/1.0\r\nContent-Length: 0\r\n\r\n".toByteArray())
            assertEquals("200", reply().path)
        }
    }

    private fun peer(
        budget: ControlBodyBudget = ControlBodyBudget({ 512L * 1024 * 1024 }, { 512L * 1024 * 1024 }),
        optIn: () -> Boolean = { true },
        test: (Peer) -> Unit,
    ) {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val socket = Socket(InetAddress.getLoopbackAddress(), server.localPort).apply { soTimeout = 10_000 }
        val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
        val nextId = java.util.concurrent.atomic.AtomicLong(3)
        val session = AirPlaySession(server.accept(),
            AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "366.0",
                main = AirPlayDisplayConfig(1280, 720), videoInCar = true),
            AirPlayIdentity.generate(), PairingStore(), null,
            object : AirPlaySessionListener {
                override fun allowsLargeVideoResources(session: AirPlaySession) = optIn()
                override val wantsTrace = false
                override fun onDebugLog(message: String) { logs.add(message) }
            }, object : AirPlayMediaHandler {
                override fun onDataStream(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?> =
                    mapOf("type" to 130L, "streamID" to nextId.getAndIncrement())
            },
        )
        session.cipher = ControlCipher(ByteArray(32) { 1 }, ByteArray(32) { 2 })
        // Keep receive-path coverage independent of heap retained by other Robolectric classes.
        // Admission under actual memory pressure is exercised by ControlMessageMemoryTest.
        session.controlBodyBudget = budget
        try { session.start(); test(Peer(socket, logs)) }
        finally { session.close(); socket.close(); server.close() }
    }
}
