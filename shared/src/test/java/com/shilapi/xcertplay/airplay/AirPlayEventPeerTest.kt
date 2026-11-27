package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AirPlayEventPeerTest {
    @org.junit.Before fun needsIpv6Loopback() {
        org.junit.Assume.assumeTrue("no IPv6 loopback", runCatching {
            java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("::1")).use { true }
        }.getOrDefault(false))
    }

    private val logs = CopyOnWriteArrayList<String>()

    private fun openEvent(session: AirPlaySession): Int {
        val method = AirPlaySession::class.java.getDeclaredMethod("openEvent")
        method.isAccessible = true
        return method.invoke(session) as Int
    }

    /** A session whose control peer is 127.0.0.1; [paired] gives it a pair-verify secret for the event keys. */
    private fun withSession(paired: Boolean = false, block: (AirPlaySession) -> Unit) {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { control ->
            val client = Socket(InetAddress.getByName("127.0.0.1"), control.localPort)
            val accepted = control.accept()
            val session = AirPlaySession(
                socket = client,
                config = AirPlayConfig(
                    deviceName = "test",
                    deviceId = "02:00:00:00:00:02",
                    btMac = "02:00:00:00:00:01",
                    sourceVersion = "1.0",
                    main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
                ),
                identity = AirPlayIdentity.generate(),
                pairings = PairingStore(),
                mfi = null,
                listener = object : AirPlaySessionListener {
                    override fun onDebugLog(message: String) { logs += message }
                },
                media = object : AirPlayMediaHandler {},
            )
            if (paired) {
                session.pairVerify.javaClass.getDeclaredField("sharedSecret").apply { isAccessible = true }
                    .set(session.pairVerify, ByteArray(32).also(SecureRandom()::nextBytes))
            }
            try { block(session) } finally { session.close(); accepted.close() }
        }
    }

    @Test
    fun aStrangerCannotTakeTheEventSlot() = withSession { session ->
        val port = openEvent(session)
        Socket(InetAddress.getByName("::1"), port).use { Thread.sleep(500) }
        try {
            Socket(InetAddress.getByName("127.0.0.1"), port).use { Thread.sleep(500) }
        } catch (error: java.net.ConnectException) {
            fail("control peer could not connect after a stranger took the slot: $error")
        }
        assertTrue("stranger was accepted: $logs", logs.none { it.contains("accepted from /0:0:0:0:0:0:0:1") })
        assertTrue("control peer was not accepted: $logs", logs.any { it.contains("accepted from /127.0.0.1") })
    }

    @Test
    fun theReportSaysTheIphoneClosedTheEventChannel() = withSession(paired = true) { session ->
        // #1378: this line had no reason, so a report could not tell this from DiPlay closing the session.
        eventPeer(session) { }
        awaitLog("airplay event connection closed ")
        assertTrue(logs.toString(), logs.contains("airplay event connection closed reason=peer EOF"))
    }

    @Test
    fun theReportSaysAnEventRecordDidNotOpen() = withSession(paired = true) { session ->
        // A 16-byte record and its tag, sealed with nobody's key.
        eventPeer(session) { it.write(byteArrayOf(16, 0) + ByteArray(32) { 7 }) }
        awaitLog("airplay event connection closed ")
        assertTrue(logs.toString(), logs.any { Regex("airplay event decrypt failed encrypted=34 error=[A-Za-z]+").matches(it) })
        assertTrue(logs.toString(), logs.contains("airplay event connection closed reason=decrypt failed"))
    }

    @Test
    fun theReportSaysTheEventChannelFailedToRead() = withSession(paired = true) { session ->
        eventPeer(session) { socket -> socket.setSoLinger(true, 0) } // Closing now resets the connection.
        awaitLog("airplay event connection closed ")
        assertTrue(logs.toString(), logs.any { it.startsWith("airplay event read failed error=SocketException: ") })
        assertTrue(logs.toString(), logs.contains("airplay event connection closed reason=read failed"))
    }

    @Test
    fun theReportSaysACommandCouldNotBeSent() = withSession(paired = true) { session ->
        eventPeer(session) {
            AirPlaySession::class.java.getDeclaredField("eventSocket").apply { isAccessible = true }
                .let { field -> (field.get(session) as Socket).shutdownOutput() }
            assertFalse(session.sendCommand(mapOf("type" to "forceKeyFrame")))
        }
        assertTrue(logs.toString(), logs.any { it.startsWith("airplay event command failed type=forceKeyFrame error=SocketException") })
    }

    @Test
    fun aSessionDiPlayClosesGivesNoFailure() = withSession(paired = true) { session ->
        eventPeer(session) {
            session.close()
            awaitLog("airplay event connection closed ")
        }
        assertTrue(logs.toString(), logs.contains("airplay event connection closed reason=session closed"))
        assertTrue(logs.toString(), logs.none { it.startsWith("airplay event read failed") })
    }

    /** Connects to the event port as the iPhone, waits for the event keys, then runs [block] and closes. */
    private fun eventPeer(session: AirPlaySession, block: (Socket) -> Unit) {
        val port = openEvent(session)
        Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
            awaitLog("airplay video event ready")
            block(socket)
        }
    }

    private fun awaitLog(prefix: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (logs.none { it.startsWith(prefix) } && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("no \"$prefix\" in $logs", logs.any { it.startsWith(prefix) })
    }

    private fun Socket.write(bytes: ByteArray) = getOutputStream().run { write(bytes); flush() }

    @Test
    fun theControlPeerIsAccepted() = withSession { session ->
        val port = openEvent(session)
        Socket(InetAddress.getByName("127.0.0.1"), port).use { Thread.sleep(500) }
        assertTrue("control peer was not accepted: $logs", logs.any { it.contains("accepted from /127.0.0.1") })
    }
}
