package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Why a screen stream ended, as the diagnostic report gets it (#1378): kinds told apart, no contents. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ScreenStreamEndReasonTest {
    @org.junit.Before fun needsIpv6Loopback() {
        org.junit.Assume.assumeTrue("no IPv6 loopback", runCatching {
            java.net.ServerSocket(0, 1, InetAddress.getByName("::1")).use { true }
        }.getOrDefault(false))
    }

    private val key = ByteArray(32) { it.toByte() }

    @Test fun aCloseBetweenMessagesIsAPeerEof() {
        assertEquals("peer EOF", endReason { })
        assertEquals("peer EOF", endReason { it.write(sealedFrame(0, NAL)) })
    }

    @Test fun aCloseInsideAHeaderSaysHowMuchOfItArrived() {
        assertEquals("peer EOF in header after 10 of 128 bytes", endReason { it.write(ByteArray(10)) })
    }

    @Test fun aCloseInsideABodySaysHowMuchOfItArrived() {
        assertEquals("peer EOF in body after 20 of 64 bytes", endReason { it.write(header(0, 64) + ByteArray(20)) })
    }

    @Test fun anOutOfRangeBodySizeIsNotAPeerEof() {
        // 0xffffffff reads as -1 and used to end the stream as if the iPhone had closed it.
        val negative = ByteArray(128).also { for (index in 0..3) it[index] = 0xff.toByte() }
        assertEquals("body size out of range bodySize=4294967295 max=8388608", endReason { it.write(negative) })
        assertEquals("body size out of range bodySize=8388609 max=8388608", endReason { it.write(header(0, 8 * 1024 * 1024 + 1)) })
    }

    @Test fun aFrameThatDoesNotOpenGivesItsCounterAndSizeButNoContents() {
        // The second frame is sealed for counter 5, so it does not open as frame 1.
        val second = sealedFrame(5, NAL)
        val reason = endReason { it.write(sealedFrame(0, NAL) + second) }!!
        val bodySize = second.size - 128
        assertTrue(reason, Regex("frame decrypt failed frame=1 bodySize=$bodySize error=[A-Za-z]+").matches(reason))
    }

    @Test fun aResetConnectionIsASocketErrorWithItsClassAndMessage() {
        val reason = endReason { socket ->
            socket.write(ByteArray(10))
            socket.setSoLinger(true, 0) // Closing now resets the connection.
        }!!
        assertTrue(reason, reason.startsWith("socket error SocketException: "))
    }

    @Test fun aFailureToHandleAFrameIsNotASocketErrorAndItsMessageIsOneBoundedLine() {
        val reason = endReason(onFrame = { throw IllegalStateException("first line\nsecond line " + "x".repeat(500)) }) {
            it.write(sealedFrame(0, NAL))
        }!!
        assertTrue(reason, reason.startsWith("receive error IllegalStateException: first line second line x"))
        assertFalse(reason, reason.contains('\n'))
        val message = reason.substringAfter("IllegalStateException: ")
        assertEquals(StreamEndDiagnostics.MAX_MESSAGE_CHARS, message.length)
    }

    @Test fun aStreamDiPlayClosesItselfGivesNoReason() {
        val stream = ScreenStream(key, InetAddress.getLoopbackAddress())
        val closes = LinkedBlockingQueue<String>()
        val port = stream.listen(object : ScreenStream.Listener {
            override fun onClosed(cause: Throwable?) { closes.add("closed") }
        })
        Socket(InetAddress.getLoopbackAddress(), port).use {
            Thread.sleep(SETTLE_MILLIS)
            stream.close()
            Thread.sleep(SETTLE_MILLIS)
        }
        assertEquals(null, closes.peek())
        assertEquals(null, stream.endReason)
    }

    /** Connects as the iPhone, runs [peer] on the connection, closes it and returns the stream's end reason. */
    private fun endReason(onFrame: (ByteArray) -> Unit = {}, peer: (Socket) -> Unit): String? {
        val stream = ScreenStream(key, InetAddress.getLoopbackAddress())
        val reasons = LinkedBlockingQueue<String>()
        try {
            val port = stream.listen(object : ScreenStream.Listener {
                override fun onFrame(naluBytes: ByteArray) = onFrame(naluBytes)
                override fun onClosed(cause: Throwable?) { reasons.add(stream.endReason ?: "unset") }
            })
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                peer(socket)
                Thread.sleep(SETTLE_MILLIS)
            }
            return reasons.poll(5, TimeUnit.SECONDS)
        } finally { stream.close() }
    }

    private fun Socket.write(bytes: ByteArray) = getOutputStream().run { write(bytes); flush() }

    private fun header(opcode: Int, bodySize: Int) = ByteArray(128).also {
        it[0] = bodySize.toByte()
        it[1] = (bodySize shr 8).toByte()
        it[2] = (bodySize shr 16).toByte()
        it[3] = (bodySize shr 24).toByte()
        it[4] = opcode.toByte()
    }

    private fun sealedFrame(counter: Long, nal: ByteArray): ByteArray {
        val plain = byteArrayOf(0, 0, 0, nal.size.toByte()) + nal
        val head = header(0, plain.size + ScreenCodec.TAG_SIZE)
        return head + AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(counter), plain, head)
    }

    private companion object {
        val NAL = byteArrayOf(0x65, 0x11)
        const val SETTLE_MILLIS = 300L
    }
}
