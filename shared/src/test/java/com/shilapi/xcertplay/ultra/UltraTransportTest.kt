package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.airplay.rcs.*
import com.shilapi.xcertplay.airplay.rcs.catalog.*
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import com.shilapi.xcertplay.airplay.rcs.transport.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [29])
class UltraTransportTest {
    @Test fun independentStreamIdsAndTeardownPreserveOtherChannels() {
        val closed = AtomicInteger()
        val diagnostics = java.util.concurrent.CopyOnWriteArrayList<String>()
        val factory = object : RcsDataStreamHandlerFactory {
            override fun create(stream: RcsDataStream) = object : RcsDataStreamHandler {
                override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) { closed.incrementAndGet() }
            }
        }
        val display = AirPlayDisplayConfig(1920, 532)
        val runtime = AirPlayUltraRuntime.builder().rcs(listOf(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL), factory).build()
        val config = AirPlayConfig("test", "00", "00", "1", AirPlayDisplayConfig(800, 480),
            ultra = AirPlayUltraConfig(display, runtime = runtime))
        val session = AirPlaySession(Socket(), config, AirPlayIdentity.generate(), PairingStore(), null,
            object : AirPlaySessionListener {
                override fun onDebugLog(message: String) { diagnostics += message }
            }, object : AirPlayMediaHandler {})
        PairVerify::class.java.getDeclaredField("sharedSecret").apply { isAccessible = true }
            .set(session.pairVerify, ByteArray(32) { it.toByte() })
        val streams = UltraDataStreams(session, config)
        try {
            val request = mapOf("clientTypeUUID" to RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL.uuid.toString(), "seed" to 1L)
            assertNull(streams.open(request))
            assertTrue(diagnostics.any { it == "Ultra channel rejected reason=feature-or-handler-unavailable" })
            streams.negotiated(listOf("uiSync"))
            val first = requireNotNull(streams.open(request)); val second = requireNotNull(streams.open(request))
            assertNotEquals(first["streamID"], second["streamID"])
            assertNotEquals(1L, first["streamID"])
            streams.responseSent()
            streams.teardown(mapOf("streams" to listOf(mapOf("type" to 130, "streamID" to first["streamID"]))))
            assertEquals(1, closed.get())
            streams.close(); assertEquals(2, closed.get())
            assertEquals(2, diagnostics.count { it.contains("event=closed failureClass=none") })
            assertNull(streams.open(request))
        } finally { streams.close(); session.close() }
    }

    @Test fun directionalEncryptionSurvivesFragmentationAndRejectsTampering() {
        val a = ByteArray(32) { it.toByte() }; val b = ByteArray(32) { (it + 32).toByte() }
        val host = RcsFrameCodec.duplex(a, b); val phone = RcsFrameCodec.duplex(b, a)
        val body = ByteArray(40000) { (it % 251).toByte() }
        val wire = phone.encrypt(body)
        val head = host.decrypt(wire.copyOf(12))
        assertTrue(head.data.isEmpty())
        val full = host.decrypt(head.rest + wire.copyOfRange(12, wire.size))
        assertArrayEquals(body, full.data); assertTrue(full.rest.isEmpty())
        assertArrayEquals(byteArrayOf(1, 2), phone.decrypt(host.encrypt(byteArrayOf(1, 2))).data)
        val altered = phone.encrypt(body).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertTrue(runCatching { host.decrypt(altered) }.isFailure)
    }
    @Test fun unsignedSeedsAreBoundedAndPreserved() {
        assertEquals("18446744073709551615", UltraDataStreams.unsignedSeed(-1L))
        assertEquals("4", UltraDataStreams.unsignedSeed(4))
        assertNull(UltraDataStreams.unsignedSeed(BigInteger.ONE.shiftLeft(64)))
        assertNull(UltraDataStreams.unsignedSeed("4"))
        assertNull(UltraDataStreams.unsignedSeed(1.5))
    }
}
