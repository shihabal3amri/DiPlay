package com.shilapi.xcertplay.hud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.shilapi.xcertplay.hud.BydClusterAdasPane.HideDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BydClusterAdasPaneTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun sent(): List<Intent> = shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents
        .filter { it.action == BydClusterAdasPane.ACTION }

    @Test fun theHideFrameIsTheOneVerifiedOnAHan() {
        assertEquals("43,20,00,00,01,01", BydClusterAdasPane.HIDE_FRAME)
        assertEquals(50, BydClusterAdasPane.HIDE_REPEATS)
    }

    @Test fun theBroadcastIsExplicitAndCarriesTheWholeFrame() {
        val intent = BydClusterAdasPane.intent(BydClusterAdasPane.HIDE_FRAME)
        assertEquals("com.byd.cluster.spi", intent.action)
        assertEquals(ComponentName("com.byd.clusterdebug", "com.byd.clusterdebug.BroadcastReceiverCAN"), intent.component)
        assertEquals("43,20,00,00,01,01", intent.getStringExtra("wholeFrame"))
        assertTrue(intent.flags and Intent.FLAG_INCLUDE_STOPPED_PACKAGES != 0)
    }

    @Test fun hidingIsOnlyAllowedWhileTheCarIsKnownToBeParked() {
        assertEquals(HideDecision.ALLOWED, BydClusterAdasPane.hideDecision(available = true, parked = true))
        assertEquals(HideDecision.NOT_PARKED, BydClusterAdasPane.hideDecision(available = true, parked = false))
        assertEquals(HideDecision.GEAR_UNKNOWN, BydClusterAdasPane.hideDecision(available = true, parked = null))
        assertEquals(HideDecision.UNAVAILABLE, BydClusterAdasPane.hideDecision(available = false, parked = true))
        assertEquals(HideDecision.UNAVAILABLE, BydClusterAdasPane.hideDecision(available = false, parked = null))
    }

    @Test fun hideBroadcastsTheFrameRepeatedly() {
        assertTrue(BydClusterAdasPane.hide(context))
        assertEquals(BydClusterAdasPane.HIDE_REPEATS, sent().size)
        assertTrue(sent().all { it.getStringExtra("wholeFrame") == BydClusterAdasPane.HIDE_FRAME })
    }

    @Test fun restoreResetsTheClusterWithCommands12Then53AndAPauseBetween() {
        val log = mutableListOf<String>()
        val ok = BydClusterAdasPane.restore(
            run = { command -> log += command; "Result: Parcel(00000000 00000000   '........')" },
            pause = { log += "pause $it" },
        )
        assertTrue(ok)
        assertEquals(listOf(
            "service call AutoContainer 2 i32 1000 i32 12 s16 \"\"",
            "pause 1200",
            "service call AutoContainer 2 i32 1000 i32 53 s16 \"\"",
        ), log)
    }

    @Test fun restoreStopsAtTheFirstCommandTheCarDoesNotAccept() {
        val log = mutableListOf<String>()
        assertFalse(BydClusterAdasPane.restore({ log += it; null }, { }))
        assertEquals(1, log.size)
        log.clear()
        assertFalse(BydClusterAdasPane.restore({ log += it; "Result: Parcel(ffffffff 00000000 '........')" }, { }))
        assertEquals(1, log.size)
    }

    @Test fun aHeadUnitWithoutTheReceiverIsNotAvailable() {
        assertFalse(BydClusterAdasPane.available(context))
    }
}
