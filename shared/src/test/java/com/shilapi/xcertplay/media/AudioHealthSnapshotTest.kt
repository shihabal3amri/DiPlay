package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class AudioHealthSnapshotTest {
    @Test fun worstCaseCountersRemainUnderExportLimitAndKeepClockAndRecoveryEvidence() {
        val line=AudioHealthSnapshot("media".repeat(100),Long.MAX_VALUE,Long.MAX_VALUE,Int.MAX_VALUE,
            Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE,Int.MAX_VALUE,Int.MAX_VALUE,Int.MAX_VALUE,false).line()
        assertTrue(line.length<700)
        assertTrue(line.contains("queueWaitMaxMs="))
        assertTrue(line.contains("workerCycleMaxMs="))
        assertTrue(line.contains("receiveGapMaxMs="))
        assertTrue(line.contains("rebuffers=${Int.MAX_VALUE}"))
        assertTrue(line.contains("actualPlaybackRate=${Int.MAX_VALUE} trimEnabled=false"))
        assertFalse(line.contains("media".repeat(6)))
    }
}
