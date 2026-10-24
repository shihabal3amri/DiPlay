package com.shilapi.xcertplay

import com.shilapi.xcertplay.ultra.VehicleSignal
import org.junit.Assert.*
import org.junit.Test

class Fx11AutoHoldSourceTest {
    private fun frame(vararg pairs: Pair<Int,Int>, initial: Boolean = false): String {
        val data = ByteArray(213); data[0] = pairs.size.toByte()
        pairs.forEachIndexed { i, (id,value) -> data[1+i*2] = id.toByte(); data[2+i*2] = value.toByte() }
        return "FRAME ${if(initial) 1 else 0} " + data.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    @Test fun verifiedLampPairTracksHoldingIndependentlyOfSwitchAndPosition() {
        var now = 0L; val state = Fx11AutoHoldState({now})
        state.accept("ONLINE"); state.accept(frame(35 to 1))
        assertTrue(state.snapshot().isEmpty())
        state.accept(frame(58 to 1, 56 to 3, 32 to 1, 18 to 1))
        assertEquals(true,state.snapshot()[VehicleSignal.AUTO_HOLD_ACTIVE]?.value)
        now = 10000; state.accept(frame(35 to 0))
        assertEquals(true,state.snapshot()[VehicleSignal.AUTO_HOLD_ACTIVE]?.value)
        state.accept(frame(32 to 0)) // functionality stays enabled, physical holding ended
        assertEquals(false,state.snapshot()[VehicleSignal.AUTO_HOLD_ACTIVE]?.value)
        assertEquals(32,state.snapshot()[VehicleSignal.AUTO_HOLD_ACTIVE]?.propertyId)
    }
    @Test fun missingSnapshotDisconnectInvalidAndStaleNeverImplyOffOrRetainGreen() {
        var now = 0L; val state = Fx11AutoHoldState({now})
        for (failure in listOf("OFFLINE", "ONLINE", "INVALID", "STATUS -13", frame(32 to 3), frame(32 to 1,32 to 0), "FRAME 0 012001")) {
            state.accept(frame(32 to 1)); state.accept(failure); assertTrue(failure,state.snapshot().isEmpty())
        }
        state.accept(frame(32 to 1)); state.accept(frame(35 to 1,initial=true)); assertTrue(state.snapshot().isEmpty())
        state.accept(frame(32 to 1)); now=20000;state.accept("ALIVE");now=40000
        assertEquals(true,state.snapshot()[VehicleSignal.AUTO_HOLD_ACTIVE]?.value)
        now=46000;state.accept("ALIVE");assertTrue(state.snapshot().isEmpty())
        state.accept(frame(32 to 1));now=72000;assertTrue(state.snapshot().isEmpty())
        state.accept(frame(35 to 0));assertTrue(state.snapshot().isEmpty())
    }
    @Test fun paddingCountAndHexMustBeValid() {
        val state=Fx11AutoHoldState({0L})
        for (bad in listOf(frame(32 to 1).dropLast(2)+"01", "FRAME 0 ff"+"00".repeat(212), frame(32 to 1).replace("012001","0120+1"))) {
            state.accept(frame(32 to 1));state.accept(bad);assertTrue(state.snapshot().isEmpty())
        }
    }
    @Test fun replaysCapturedHoldingThenReleasedSequence() {
        val state = Fx11AutoHoldState({0L})
        val frames = javaClass.getResourceAsStream("/fx11-autohold-confirmed.txt")!!.bufferedReader().readLines()
        val seen = frames.map { state.accept(it); state.snapshot()[VehicleSignal.AUTO_HOLD_ACTIVE]?.value }
        assertTrue(seen.contains(true)); assertTrue(seen.contains(false))
        assertTrue(seen.indexOf(true) < seen.indexOf(false))
        assertFalse(seen.contains(null))
    }

    @Test fun discoversCurrentPortOnlyForVerifiedQnxService() {
        val inventory="""
[Host: qnx, IP: 192.168.118.2]
    [OtherService]
        > tcp://0:1234
    [TelltaleModule]
        > ipc://@/tmp/fdb-ipc22
        > tcp://0:65479 udp://65515
""".trimIndent()
        assertEquals("tcp://192.168.118.2:65479",Fx11AutoHoldSource.endpoint(inventory))
        assertEquals("tcp://192.168.118.2:61234",Fx11AutoHoldSource.endpoint(inventory.replace("65479","61234")))
        assertNull(Fx11AutoHoldSource.endpoint(inventory.replace("118.2","118.9")))
        assertNull(Fx11AutoHoldSource.endpoint(inventory.replace("65479","99999")))
        assertNull(Fx11AutoHoldSource.endpoint(inventory.replace("TelltaleModule","Other")))
    }
}
