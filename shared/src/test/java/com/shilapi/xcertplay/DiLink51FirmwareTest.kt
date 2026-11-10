package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

/** The firmware builds the cluster layout and the standalone HUD output each gate on. */
class DiLink51FirmwareTest {
    @Test fun everyKnownBuildStaysOnTheMeasuredDiLink51Baseline() {
        for (fingerprint in DiLink51Firmware.FINGERPRINTS) {
            assertTrue(fingerprint.startsWith("BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build2026"))
            assertTrue(DiLink51Firmware.supported(fingerprint))
        }
        assertTrue(DiLink51Firmware.hudReceiverSupported(DiLink51Firmware.FINGERPRINT))
        assertTrue(DiLink51Firmware.hudReceiverSupported(DiLink51Firmware.TANG_L_FINGERPRINT))
        assertFalse(DiLink51Firmware.hudReceiverSupported(DiLink51Firmware.LEOPARD5_FINGERPRINT))
        assertFalse(DiLink51Firmware.hudReceiverSupported(DiLink51Firmware.DENZA_N9_FINGERPRINT))
        assertFalse(DiLink51Firmware.supported("BYD-AUTO/DiLink5.0/DiLink5.0:12/test"))
    }
}
