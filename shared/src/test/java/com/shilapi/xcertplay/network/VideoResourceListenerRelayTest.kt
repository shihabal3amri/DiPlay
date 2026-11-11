package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercise the real service -> wireless relay -> controller -> live video-setting path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class VideoResourceListenerRelayTest {
    @Test fun realServiceAndWirelessRelayPreserveTheLiveSettingAndOriginalBodyLimit() {
        val enabled = AtomicBoolean(false)
        val rejected = java.util.concurrent.LinkedBlockingQueue<Triple<Long, String, Int>>()
        val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
        val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480), port = 0, videoInCar = true)
        val media = object : AirPlayMediaHandler {
            override fun onDataStream(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?> =
                mapOf("type" to 130L, "streamID" to 3L)
        }
        val controller = CarPlayController(RuntimeEnvironment.getApplication(),
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRELESS,
                identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test", manufacturer = "test",
                    serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
            config, AirPlayIdentity.generate(), PairingStore(),
            object : AirPlaySessionListener {
                override val wantsTrace = false
                override fun onDebugLog(message: String) { logs.add(message) }
            }, media, {},
        )
        controller.videoListener = object : CarPlayVideoListener {
            override fun usesIphoneNetworkForVideo() = enabled.get()
            override fun onVideoResourceRejected(streamId: Long, reason: String, bodyBytes: Int) {
                rejected.offer(Triple(streamId, reason, bodyBytes))
            }
            override fun readParked(): Boolean = true
            override fun onVideoAllowedChanged(allowed: Boolean) {}
            override fun onVideoMessage(streamId: Long, message: Map<String, Any?>) {}
            override fun onVideoUiRequested() {}
            override fun onVideoSessionEnded() {}
        }
        val phase = CarPlayController::class.java.getDeclaredField("phase").apply { isAccessible = true }
        phase.set(controller, phase.type.enumConstants.single { (it as Enum<*>).name == "WIRELESS" })
        val watchdog = FirstTcpWatchdog(AirPlayListenerIdentity(1), { _, _ -> {} }, {})
        val relay = CarPlayController::class.java.getDeclaredMethod("wirelessSessionListener", Int::class.javaPrimitiveType,
            FirstTcpWatchdog::class.java).apply { isAccessible = true }.invoke(controller, 0, watchdog) as AirPlaySessionListener
        val serviceOwner = Robolectric.buildService(CarPlayVpnService::class.java).create()
        val service = serviceOwner.get()
        // Install before accept/start: runControl captures its budget when the session starts.
        // Keep admission-path coverage independent of heap held by other Robolectric classes.
        service.controlBodyBudget = ControlBodyBudget({ 512L * 1024 * 1024 }, { 512L * 1024 * 1024 })
        var socket: Socket? = null
        try {
            assertEquals(CarPlayVpnService.AttachResult.Started, service.attachWireless(InetAddress.getLoopbackAddress(),
                config, AirPlayIdentity.generate(), PairingStore(), null, relay, media))
            val server = CarPlayVpnService::class.java.getDeclaredField("serverSocket").apply { isAccessible = true }
                .get(service) as ServerSocket
            socket = Socket(InetAddress.getLoopbackAddress(), server.localPort).apply { soTimeout = 10_000 }
            val sessionsField = CarPlayVpnService::class.java.getDeclaredField("sessions").apply { isAccessible = true }
            val lock = CarPlayVpnService::class.java.getDeclaredField("sessionsLock").apply { isAccessible = true }.get(service)
            val deadline = System.nanoTime() + 2_000_000_000L
            var session: AirPlaySession? = null
            while (session == null && System.nanoTime() < deadline) {
                session = synchronized(lock) { (sessionsField.get(service) as Set<*>).singleOrNull() as? AirPlaySession }
                if (session == null) Thread.sleep(5)
            }
            val active = session ?: error("Service did not accept the test connection")
            CarPlayController::class.java.getDeclaredField("activeSession").apply { isAccessible = true }.set(controller, active)
            val actualListener = AirPlaySession::class.java.getDeclaredField("listener").apply { isAccessible = true }
                .get(active) as AirPlaySessionListener
            assertFalse(actualListener.allowsLargeVideoResources(active))
            enabled.set(true)
            assertTrue("Service listener must forward the enabled setting through every relay", actualListener.allowsLargeVideoResources(active))
            active.cipher = ControlCipher(ByteArray(32) { 1 }, ByteArray(32) { 2 })
            val cipher = ControlCipher(ByteArray(32) { 2 }, ByteArray(32) { 1 })
            fun send(bytes: ByteArray) { socket.getOutputStream().write(cipher.encrypt(bytes)); socket.getOutputStream().flush() }
            var encrypted = ByteArray(0)
            var plain = ByteArray(0)
            fun reply(): RtspMessage.Request {
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = socket.getInputStream().read(buffer)
                    if (count < 0) throw java.io.EOFException()
                    encrypted += buffer.copyOf(count)
                    val decoded = cipher.decrypt(encrypted); encrypted = decoded.rest; plain += decoded.data
                    val parsed = RtspMessage.parseMessages(plain); plain = parsed.rest
                    if (parsed.messages.isNotEmpty()) return parsed.messages.single()
                }
            }
            val setup = BplistCodec.encode(mapOf("streams" to listOf(mapOf("type" to 130L, "controlType" to 1L,
                "clientTypeUUID" to VideoInCar.REMOTE_CONTROL_UUIDS.first()))))
            send("SETUP / RTSP/1.0\r\nContent-Length: ${setup.size}\r\n\r\n".toByteArray() + setup)
            assertEquals("200", reply().path)
            val size = 2 * 1024 * 1024
            fun header() = "POST /command RTSP/1.0\r\nX-Apple-StreamID: 3\r\nContent-Length: $size\r\n\r\n".toByteArray()
            send(header())
            repeat(size / (16 * 1024)) { send(ByteArray(16 * 1024)) }
            assertEquals("200", reply().path)
            assertTrue(logs.joinToString("\n"), logs.any { "bytes=$size action=receive" in it })
            val oversized = VideoInCar.MAX_RESOURCE_COMMAND_BYTES + 1
            send("POST /command RTSP/1.0\r\nX-Apple-StreamID: 3\r\nContent-Length: $oversized\r\n\r\n".toByteArray())
            val chunk = ByteArray(16 * 1024)
            repeat(oversized / chunk.size) { send(chunk) }
            send(ByteArray(oversized % chunk.size))
            assertEquals("200", reply().path)
            assertEquals(Triple(3L, "resource_size_limit", oversized), rejected.poll(2, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(rejected.isEmpty())
            enabled.set(false)
            assertFalse(actualListener.allowsLargeVideoResources(active))
            send(header())
            assertThrows(java.io.IOException::class.java) { reply() }
        } finally {
            socket?.close()
            service.detach()
            serviceOwner.destroy()
            controller.close()
            assertTrue(controller.awaitClosed(2_000))
        }
    }
}
