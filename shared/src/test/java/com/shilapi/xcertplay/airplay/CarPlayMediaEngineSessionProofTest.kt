package com.shilapi.xcertplay.airplay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the engine tells the handoff watchdog about a session that has no screen to render on (#1215). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayMediaEngineSessionProofTest {
    @Test fun decryptedMainAudioShowsTheSessionReceivedAudio() {
        // 100 carries calls, prompts, Siri and PCM music; 102 carries AAC music, as in #1215's report.
        for (type in listOf(100, 102)) {
            val delivered = CountDownLatch(1)
            val engine = CarPlayMediaEngine(object : MediaSink {
                override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
                    delivered.countDown()
                }
            })
            val session = session()
            try {
                val stream = setup("media")
                val response = engine.onAudio(session, type, stream)!!
                assertFalse(session.hasReceivedAudio)
                send(response["dataPort"] as Int, key(engine, session, stream))
                assertTrue("type $type", delivered.await(10, TimeUnit.SECONDS))
                assertTrue("type $type", session.hasReceivedAudio)
                session.close()
                assertFalse("A closed session proves nothing", session.hasReceivedAudio)
            } finally {
                engine.onSessionClosed(session)
                session.close()
            }
        }
    }

    @Test fun undecryptablePacketsDoNotCount() {
        val delivered = CountDownLatch(1)
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
                delivered.countDown()
            }
        })
        val session = session()
        try {
            val stream = setup("media")
            val port = engine.onAudio(session, 102, stream)!!["dataPort"] as Int
            // A well-formed RTP packet sealed with another key: it fails authentication.
            send(port, ByteArray(32).also(SecureRandom()::nextBytes))
            val failures = authenticationFailures(engine)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (failures.get() == 0 && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals("The packet was not processed", 1, failures.get())
            assertFalse(session.hasReceivedAudio)
            assertEquals(1L, delivered.count)
            // The same stream with the right key counts, so the wait above watched the right stream.
            send(port, key(engine, session, stream))
            assertTrue(delivered.await(10, TimeUnit.SECONDS))
            assertTrue(session.hasReceivedAudio)
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test fun altAudioDoesNotCount() {
        val delivered = CountDownLatch(1)
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
                delivered.countDown()
            }
        })
        val session = session()
        try {
            val alt = setup("default")
            val altResponse = engine.onAudio(session, 101, alt)!!
            // The alt stream is set up with the session, so its packets are no sign of activity.
            send(altResponse["dataPort"] as Int, key(engine, session, alt))
            assertTrue(delivered.await(10, TimeUnit.SECONDS))
            assertFalse(session.hasReceivedAudio)
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test fun aDecryptedMainScreenFrameShowsTheSessionReceivedVideo() {
        val frames = LinkedBlockingQueue<List<Byte>>()
        val engine = CarPlayMediaEngine(frameSink(frames))
        val session = session(connectedSocket())
        try {
            screenPeer(engine, session, type = 110) { key, send ->
                // A surface need not exist: the flag is set before the sink (and its decoder) sees the frame.
                assertFalse(session.hasReceivedMainVideo)
                send(sealedFrame(key, 0, NAL))
                assertEquals(ANNEX_B, frames.poll(10, TimeUnit.SECONDS))
                assertTrue(session.hasReceivedMainVideo)
            }
            session.close()
            assertFalse("A closed session proves nothing", session.hasReceivedMainVideo)
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test fun keepalivesAndConfigurationOnTheMainScreenStreamDoNotCount() {
        // #975 and #249: the stuck iPhone's main screen stream carries only 128-byte keepalives
        // (opcode 2, no body), about one a second.
        val frames = LinkedBlockingQueue<List<Byte>>()
        val configs = LinkedBlockingQueue<Int>()
        val engine = CarPlayMediaEngine(frameSink(frames, configs))
        val session = session(connectedSocket())
        try {
            screenPeer(engine, session, type = 110) { key, send ->
                repeat(5) { send(screenHeader(opcode = 2, bodySize = 0)) }
                send(screenHeader(opcode = 1, bodySize = AVCC.size) + AVCC)
                // Shorter than the authentication tag, so never a sealed frame.
                send(screenHeader(opcode = 0, bodySize = 8) + ByteArray(8))
                // Sealed with the session's key, but with no picture in it.
                send(sealedFrame(key, 0, ByteArray(0)))
                assertEquals(AVCC.size, configs.poll(10, TimeUnit.SECONDS))
                assertEquals(emptyList<Byte>(), frames.poll(10, TimeUnit.SECONDS))
                assertFalse(session.hasReceivedMainVideo)
                // The same stream's first picture counts, so the messages above were read as sent.
                send(sealedFrame(key, 1, NAL))
                assertEquals(ANNEX_B, frames.poll(10, TimeUnit.SECONDS))
                assertTrue(session.hasReceivedMainVideo)
            }
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test fun anUndecryptableMainScreenFrameDoesNotCount() {
        val frames = LinkedBlockingQueue<List<Byte>>()
        val engine = CarPlayMediaEngine(frameSink(frames))
        val session = session(connectedSocket())
        try {
            screenPeer(engine, session, type = 110) { _, send ->
                send(sealedFrame(ByteArray(32).also(SecureRandom()::nextBytes), 0, NAL))
                // It fails authentication, which ends the stream and its session.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (!session.isClosed && System.nanoTime() < deadline) Thread.sleep(5)
                assertTrue("The frame was not processed", session.isClosed)
                assertTrue(frames.isEmpty())
                // Not only because the session closed.
                assertFalse(
                    AirPlaySession::class.java.getDeclaredField("mainVideoReceived").apply { isAccessible = true }
                        .getBoolean(session),
                )
            }
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test fun clusterVideoDoesNotCount() {
        val frames = LinkedBlockingQueue<List<Byte>>()
        val engine = CarPlayMediaEngine(frameSink(frames))
        val session = session(connectedSocket())
        try {
            screenPeer(engine, session, type = 111) { key, send ->
                send(sealedFrame(key, 0, NAL))
                assertEquals(ANNEX_B, frames.poll(10, TimeUnit.SECONDS))
                assertFalse(session.hasReceivedMainVideo)
            }
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test fun mainScreenOutputComesFromTheSinksMainScreen() {
        val asked = mutableListOf<Int>()
        var attached = false
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun hasVideoOutput(type: Int): Boolean {
                asked += type
                return attached
            }
        })
        assertFalse(engine.hasMainScreenOutput())
        attached = true
        assertTrue(engine.hasMainScreenOutput())
        assertEquals(listOf(110, 110), asked)
        assertTrue("Without a sink answer the strict rendered-frame rule applies",
            CarPlayMediaEngine(object : MediaSink {}).hasMainScreenOutput())
    }

    @Test fun mainScreenAttachmentsComeFromTheSinksMainScreen() {
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun videoOutputAttachments(type: Int): Long = if (type == 110) 4L else 9L
        })
        assertEquals(4L, engine.mainScreenOutputAttachments())
    }

    /** The failed-authentication count of the engine's only audio stream. */
    private fun authenticationFailures(engine: CarPlayMediaEngine): AtomicInteger {
        val streams = CarPlayMediaEngine::class.java.getDeclaredField("streams").apply { isAccessible = true }
            .get(engine) as Map<*, *>
        val stream = streams.values.single() as AudioStream
        return AudioStream::class.java.getDeclaredField("authenticationFailures").apply { isAccessible = true }
            .get(stream) as AtomicInteger
    }

    private fun setup(audioType: String): Map<String, Any?> = mapOf(
        "audioType" to audioType,
        "audioFormat" to 0x8000L,
        "streamConnectionID" to 42L,
    )

    private fun key(engine: CarPlayMediaEngine, session: AirPlaySession, stream: Map<String, Any?>): ByteArray =
        CarPlayMediaEngine::class.java.getDeclaredMethod("outputKey", AirPlaySession::class.java, Map::class.java)
            .apply { isAccessible = true }.invoke(engine, session, stream) as ByteArray

    /** One RTP packet as the iPhone sends it: header, sealed payload, then the 8-byte nonce. */
    private fun send(port: Int, key: ByteArray) {
        val header = ByteBuffer.allocate(12).apply {
            put(0x80.toByte()); put(0x60.toByte()); putShort(1); putInt(1024); putInt(7)
        }.array()
        val tail = ByteBuffer.allocate(8).putLong(1L).array()
        val nonce = ByteArray(12).also { tail.copyInto(it, 4) }
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, ByteArray(16), header.copyOfRange(4, 12))
        val wire = header + sealed + tail
        DatagramSocket().use { it.send(DatagramPacket(wire, wire.size, InetAddress.getLoopbackAddress(), port)) }
    }

    private fun frameSink(
        frames: LinkedBlockingQueue<List<Byte>>,
        configs: LinkedBlockingQueue<Int> = LinkedBlockingQueue(),
    ) = object : MediaSink {
        override fun onVideoConfig(type: Int, codecData: ByteArray) { configs += codecData.size }
        override fun onVideoFrame(type: Int, naluBytes: ByteArray) { frames += naluBytes.toList() }
    }

    /** Sets up screen stream [type] and connects to it as the iPhone; [send] writes one message. */
    private fun screenPeer(
        engine: CarPlayMediaEngine,
        session: AirPlaySession,
        type: Int,
        block: (key: ByteArray, send: (ByteArray) -> Unit) -> Unit,
    ) {
        val stream = mapOf<String, Any?>("streamConnectionID" to 42L)
        val port = engine.onScreen(session, type, stream)!!
        Socket(InetAddress.getLoopbackAddress(), port).use { peer ->
            block(key(engine, session, stream)) { message ->
                peer.getOutputStream().write(message)
                peer.getOutputStream().flush()
            }
        }
    }

    private fun screenHeader(opcode: Int, bodySize: Int) = ByteArray(128).also {
        it[0] = bodySize.toByte()
        it[4] = opcode.toByte()
    }

    /** A video frame message: header, then [nal] (if any) length-prefixed and sealed with [key]. */
    private fun sealedFrame(key: ByteArray, counter: Long, nal: ByteArray): ByteArray {
        val plain = if (nal.isEmpty()) nal else byteArrayOf(0, 0, 0, nal.size.toByte()) + nal
        val header = screenHeader(opcode = 0, bodySize = plain.size + ScreenCodec.TAG_SIZE)
        return header + AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(counter), plain, header)
    }

    // onScreen needs the control peer's address.
    private fun connectedSocket(): Socket = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
        Socket(server.inetAddress, server.localPort)
    }

    private fun session(socket: Socket = Socket()): AirPlaySession {
        val session = AirPlaySession(
            socket = socket,
            config = AirPlayConfig(
                deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
                sourceVersion = "1.0", main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            ),
            identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
            listener = object : AirPlaySessionListener {}, media = object : AirPlayMediaHandler {},
        )
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        session.pairVerify.javaClass.getDeclaredField("sharedSecret").apply { isAccessible = true }
            .set(session.pairVerify, secret)
        return session
    }

    private companion object {
        val NAL = byteArrayOf(0x65, 0x11)
        val ANNEX_B = listOf<Byte>(0, 0, 0, 1, 0x65, 0x11)
        val AVCC = byteArrayOf(1, 0x64, 0, 0x1f, 0xff.toByte(), 0xe1.toByte(), 0, 4, 0x67, 0x64, 0, 0x1f)
    }
}
