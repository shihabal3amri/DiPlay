package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.media.MediaTrackStall.Stall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a playing music track the car takes nothing from counts as stalled (#1229), on a fake clock. */
class MediaTrackStallTest {
    private val watch = MediaTrackStall()
    private var nowNs = 1_000L * SECOND

    private fun after(millis: Long) { nowNs += millis * 1_000_000L }
    private fun refuse(head: Int = 0): Stall? = watch.refused(nowNs, nowNs, head)

    /** Refused writes [everyMillis] apart for [millis]; the stall and the time it took to show, if any. */
    private fun refuseFor(millis: Long, everyMillis: Long = 20, head: Int = 0): Pair<Stall, Long>? {
        val startNs = nowNs
        while (nowNs - startNs <= millis * 1_000_000L) {
            refuse(head)?.let { return it to (nowNs - startNs) / 1_000_000L }
            after(everyMillis)
        }
        return null
    }

    @Test fun aTrackThatNeverStartsIsStalledAfterThreeSecondsOfRefusedWrites() {
        // Report 6: 234 AAC packets in 5 s, every write after the first 0.5 s returned 0.
        val (stall, millis) = requireNotNull(refuseFor(10_000))
        assertEquals(3_000L, millis)
        assertEquals(Stall(headFrames = 0L, stalledNs = 3 * SECOND, zeroWrites = 151, rebuild = true), stall)
    }

    @Test fun lessThanThreeSecondsIsNotAStall() {
        assertNull(refuseFor(2_980))
    }

    @Test fun oneAcceptedWriteStartsTheWaitAgain() {
        assertNull(refuseFor(2_900))
        watch.clear()
        assertNull(refuseFor(2_900))
        assertNotNull(refuseFor(200))
    }

    @Test fun aHeadThatMovesIsNotAStall() {
        repeat(500) {
            assertNull(refuse(head = it))
            after(20)
        }
    }

    @Test fun aHeadFrozenMidSongIsAStallAndIsReadUnsigned() {
        assertNull(refuse(head = -5))
        after(20)
        val (stall, _) = requireNotNull(refuseFor(4_000, head = -6))
        assertEquals(0xffff_fffaL, stall.headFrames)
    }

    @Test fun packetsThatStopArrivingAreNotEvidence() {
        // Paused music sends nothing; a lone refused write every 1.2 s never adds up.
        assertNull(refuseFor(60_000, everyMillis = 1_200))
    }

    @Test fun wifiBurstsHalfASecondApartStillCount() {
        val (_, millis) = requireNotNull(refuseFor(10_000, everyMillis = 500))
        assertEquals(3_000L, millis)
    }

    @Test fun threeSecondsNeedSeveralRefusedWrites() {
        assertNull(refuseFor(3_000, everyMillis = 1_000))
        assertNotNull(refuse())
    }

    @Test fun writesThatWaitBeforeTheyRefuseCountToo() {
        var stall: Stall? = null
        var writes = 0
        while (stall == null && writes < 20) {
            val startNs = nowNs
            after(1_500)
            stall = watch.refused(startNs, nowNs, 0)
            writes++
        }
        assertEquals(MediaTrackStall.MIN_REFUSED_WRITES, writes)
        assertNotNull(stall)
    }

    @Test fun onlyTheFirstStallRebuildsAndOnlyTwoAreReported() {
        val stalls = (1..5).mapNotNull { refuseFor(10_000)?.first }
        assertEquals(listOf(true, false), stalls.map { it.rebuild })
    }

    /** Packets [everyMillis] apart for [millis] while the worker is or is not inside a write. */
    private fun arriveFor(millis: Long, everyMillis: Long = 20): List<Stall> {
        val stalls = mutableListOf<Stall>()
        val startNs = nowNs
        while (nowNs - startNs <= millis * 1_000_000L) {
            watch.packetArrived(nowNs, stalls::add)
            after(everyMillis)
        }
        return stalls
    }

    @Test fun aWriteBlockedWhilePacketsKeepArrivingIsAStall() {
        watch.writeStarted()
        assertTrue(arriveFor(2_980).isEmpty())
        assertEquals(listOf(Stall(null, 3 * SECOND, 0, rebuild = true)), arriveFor(40))
    }

    @Test fun writesThatKeepReturningAreNotBlocked() {
        repeat(20) {
            watch.writeStarted()
            assertTrue(arriveFor(500).isEmpty())
            watch.writeEnded()
        }
        assertTrue(arriveFor(10_000).isEmpty())
    }

    @Test fun aProcessThatWasFrozenDoesNotLookBlocked() {
        watch.writeStarted()
        assertTrue(arriveFor(100).isEmpty())
        after(30_000)
        // Thawed: the waiting packets are read at once, and then the write returns.
        repeat(100) { watch.packetArrived(nowNs) { error("not a stall") } }
        watch.writeEnded()
        assertTrue(arriveFor(5_000).isEmpty())
    }

    @Test fun aBlockedWriteAndRefusedWritesShareOneRebuild() {
        watch.writeStarted()
        assertTrue(arriveFor(3_020).single().rebuild)
        watch.writeEnded()
        assertFalse(requireNotNull(refuseFor(10_000)).first.rebuild)
        watch.writeStarted()
        assertTrue(arriveFor(10_000).isEmpty())
    }

    @Test fun theLinesSayItInPlainWords() {
        assertEquals("Audio: car is not consuming CarPlay audio; media track never started " +
            "(head=0 for 3 s, zeroWrites=151, queue=0); rebuilding the track once",
            MediaAudioLines.notConsuming(0L, 3_020_000_000L, 151, 0, rebuild = true))
        assertEquals("Audio: car is not consuming CarPlay audio; media track stopped " +
            "(head=471046 for 4 s, zeroWrites=9, queue=2); not rebuilding it again",
            MediaAudioLines.notConsuming(471_046L, 4 * SECOND, 9, 2, rebuild = false))
        assertEquals("Audio: car is not consuming CarPlay audio; media track write blocked " +
            "(for 3 s, queue=32); rebuilding the track once",
            MediaAudioLines.notConsuming(null, 3 * SECOND, 0, 32, rebuild = true))
        assertEquals("Audio: media start musicVolume=3/39 muted=false fixed=false routeType=2 streamOverride=0",
            MediaAudioLines.started(MusicVolume(3, 39, muted = false, fixed = false), 2, 0))
        assertEquals("Audio: media start musicVolume=unavailable routeType=-1 streamOverride=14",
            MediaAudioLines.started(null, null, 14))
    }

    private companion object {
        const val SECOND = 1_000_000_000L
    }
}
