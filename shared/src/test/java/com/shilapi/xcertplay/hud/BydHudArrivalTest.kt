package com.shilapi.xcertplay.hud

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BydHudArrivalTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val arrival = 1_794_407_760L // 2026-11-11 14:36:00 UTC
    private val now = 1_794_405_020_000L // 2026-11-11 13:50:20 UTC

    @Test fun onlyTheDenzaN9GetsAnArrivalTime() {
        // #301: no other HUD has been sent a non-empty field 26, so they keep it empty.
        assertTrue(BydHudArrival.enabled(BydHudArrival.DENZA_N9_FINGERPRINT))
        for (fingerprint in listOf(
            "BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260722.221155:user/release-keys", // DiLink 5.1
            "BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260705.021352:user/release-keys", // Leopard 5
            "BYD-AUTO/DiLink5.0/DiLink5.0:12/SKQ1.230128.001/eng.build.20251111.182747:user/release-keys",
            BydHudArrival.DENZA_N9_FINGERPRINT.replace("011226", "011227"),
            "",
            null,
        )) {
            assertFalse("fingerprint=$fingerprint", BydHudArrival.enabled(fingerprint))
        }
    }

    @Test fun theIphoneArrivalTimeWinsOverTheTimeLeft() {
        assertEquals("14:36", BydHudArrival.text(now, arrival, remainingSeconds = 60, utc, use24Hour = true))
    }

    @Test fun withoutAnArrivalTimeItIsNowPlusTheTimeLeft() {
        assertEquals("14:20", BydHudArrival.text(now, null, remainingSeconds = 1_800, utc, use24Hour = true))
        assertEquals("13:50", BydHudArrival.text(now, 0, remainingSeconds = 0, utc, use24Hour = true))
    }

    @Test fun followsTheHeadUnitClockFormatAndTimeZone() {
        assertEquals("2:36", BydHudArrival.text(now, arrival, null, utc, use24Hour = false))
        assertEquals("18:36", BydHudArrival.text(now, arrival, null, TimeZone.getTimeZone("Asia/Dubai"), use24Hour = true))
        assertEquals("09:05", BydHudArrival.text(0, 9 * 3_600L + 5 * 60L, null, utc, use24Hour = true))
    }

    @Test fun nothingToShowLeavesTheFieldEmpty() {
        assertEquals("", BydHudArrival.text(now, null, null, utc, use24Hour = true))
        assertEquals("", BydHudArrival.text(now, 0, -1, utc, use24Hour = true))
    }

    @Test fun theHudGetsTheBareClockTime() {
        // A stock N9 HUD shows "13:18到", so the HUD seems to add "到" itself. Unconfirmed on the car.
        val text = BydHudArrival.text(now, arrival, null, utc, use24Hour = true)
        assertFalse(text.contains("到"))
        assertEquals(text, BydHudArrival.TEMPLATE.format(text))
    }

    @Test fun theArrivalTimeTravelsInFieldTwentySix() {
        val eta = BydHudArrival.text(now, arrival, null, utc, use24Hour = true)
        val hex = BydHudPayload.guidance(distanceMeters = 120, maneuver = 2, eta = eta).joinToString("") { "%02x".format(it) }
        assertTrue(hex, hex.contains("d20105" + "31343a3336")) // field 26 = "14:36"
    }
}
