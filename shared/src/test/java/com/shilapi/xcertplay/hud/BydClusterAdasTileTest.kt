package com.shilapi.xcertplay.hud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.shilapi.xcertplay.hud.BydClusterAdasTile.BigSceneWatch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BydClusterAdasTileTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun line(time: String, value: Int, id: String = "26f00028") =
        "10-26 $time 31876 31876 D BYDAutoInstrumentDevice: postEvent device_type: 1007, event_type =$id, value = $value"

    private fun log(vararg lines: String) = lines.joinToString("\n")

    @Before fun setUp() {
        BydOutputSettings.setKeepAdasTileSmall(context, false)
        BydClusterAdasTile.resetForTest()
    }

    @After fun tearDown() {
        BydOutputSettings.setKeepAdasTileSmall(context, false)
        BydClusterAdasTile.read = { null }
        BydClusterAdasTile.send = BydClusterAdasTile::showSmall
        BydClusterAdasTile.receiverAvailable = BydClusterAdasTile::available
        BydClusterAdasTile.mapShown = { BydClusterMapPause.clusterMapShown }
        BydClusterAdasTile.resetForTest()
    }

    @Test fun theFrameIsTheOneTheMenuKeyIsSeenToSet() {
        assertEquals("26,F0,00,28,01,01", BydClusterAdasTile.SMALL_FRAME)
        assertEquals(5, BydClusterAdasTile.SMALL_REPEATS)
    }

    @Test fun theBroadcastIsExplicitAndCarriesTheWholeFrame() {
        val intent = BydClusterAdasTile.intent(BydClusterAdasTile.SMALL_FRAME)
        assertEquals("com.byd.cluster.spi", intent.action)
        assertEquals(ComponentName("com.byd.clusterdebug", "com.byd.clusterdebug.BroadcastReceiverCAN"), intent.component)
        assertEquals("26,F0,00,28,01,01", intent.getStringExtra("wholeFrame"))
        assertTrue(intent.flags and Intent.FLAG_INCLUDE_STOPPED_PACKAGES != 0)
    }

    @Test fun showSmallBroadcastsOnlyTheSmallFrameRepeatedly() {
        assertTrue(BydClusterAdasTile.showSmall(context))
        val sent = shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents
            .filter { it.action == BydClusterAdasTile.ACTION }
        assertEquals(BydClusterAdasTile.SMALL_REPEATS, sent.size)
        assertTrue(sent.all { it.getStringExtra("wholeFrame") == BydClusterAdasTile.SMALL_FRAME })
    }

    @Test fun aHeadUnitWithoutTheReceiverIsNotAvailable() {
        assertFalse(BydClusterAdasTile.available(context))
    }

    @Test fun onlyTheAdasViewEventIsParsed() {
        val events = BydClusterAdasTile.parse(log(
            line("16:29:08.963", 6),
            line("16:29:09.100", 2, id = "23500018"),
            "10-26 16:29:09.200 31876 31876 D SomethingElse: postEvent device_type: 1007, event_type =26f00028, value = 6",
            line("16:29:10.000", 1),
            "garbage",
        ))
        assertEquals(listOf(BydClusterAdasTile.Event("10-26 16:29:08.963", 6), BydClusterAdasTile.Event("10-26 16:29:10.000", 1)), events)
    }

    @Test fun anOldEventInTheFirstReadNeverTriggersASend() {
        val watch = BigSceneWatch { 0 }
        assertFalse(watch.onRead(log(line("16:29:08.963", 6))))
        assertFalse(watch.onRead(log(line("16:29:08.963", 6))))
    }

    @Test fun aNewLargeSceneEventTriggersASendOnce() {
        val watch = BigSceneWatch { 0 }
        watch.onRead(log(line("16:29:00.000", 1)))
        assertTrue(watch.onRead(log(line("16:29:00.000", 1), line("16:29:08.963", 6))))
        assertFalse(watch.onRead(log(line("16:29:00.000", 1), line("16:29:08.963", 6))))
    }

    @Test fun aSceneThatWasAlreadyDismissedInTheSameReadIsLeftAlone() {
        val watch = BigSceneWatch { 0 }
        watch.onRead(log(line("16:29:00.000", 1)))
        assertFalse(watch.onRead(log(line("16:29:08.000", 6), line("16:29:09.000", 1))))
    }

    @Test fun otherViewValuesNeverTriggerASend() {
        val watch = BigSceneWatch { 0 }
        watch.onRead(log(line("16:29:00.000", 1)))
        assertFalse(watch.onRead(log(line("16:29:05.000", 3))))
    }

    @Test fun aCarThatKeepsReopeningTheSceneIsAnsweredAtMostOncePerGapAndSixPerMinute() {
        var now = 0L
        val watch = BigSceneWatch { now }
        watch.onRead(log(line("16:30:00.000", 1)))
        var second = 1
        fun reopen(): Boolean = watch.onRead(log(line("16:30:%02d.000".format(second++), 6)))
        assertTrue(reopen())
        now += 1_000
        assertFalse(reopen())
        var sent = 1
        repeat(12) { now += 4_000; if (reopen()) sent++ }
        assertEquals(6, sent)
        now += 61_000
        assertTrue(watch.onRead(log(line("16:31:30.000", 6))))
    }

    @Test fun resetForgetsWhatWasSeen() {
        val watch = BigSceneWatch { 0 }
        watch.onRead(log(line("16:29:00.000", 1)))
        watch.reset()
        assertFalse(watch.onRead(log(line("16:29:08.963", 6))))
    }

    @Test fun theSettingIsOffByDefault() {
        assertFalse(BydOutputSettings.keepAdasTileSmall(context))
        BydOutputSettings.setKeepAdasTileSmall(context, true)
        assertTrue(BydOutputSettings.keepAdasTileSmall(context))
    }

    @Test fun nothingIsReadWhileTheSettingIsOffOrTheMapIsNotOnTheCluster() {
        var reads = 0
        BydClusterAdasTile.read = { reads++; null }
        BydClusterAdasTile.receiverAvailable = { true }
        BydClusterAdasTile.mapShown = { true }
        BydClusterAdasTile.tick(context)
        assertEquals(0, reads)
        BydOutputSettings.setKeepAdasTileSmall(context, true)
        BydClusterAdasTile.mapShown = { false }
        BydClusterAdasTile.tick(context)
        assertEquals(0, reads)
        BydClusterAdasTile.mapShown = { true }
        BydClusterAdasTile.receiverAvailable = { false }
        BydClusterAdasTile.tick(context)
        assertEquals(0, reads)
    }

    @Test fun tickSendsTheSmallTileRequestWhenTheCarOpensTheLargeScene() {
        BydOutputSettings.setKeepAdasTileSmall(context, true)
        BydClusterAdasTile.receiverAvailable = { true }
        BydClusterAdasTile.mapShown = { true }
        var sends = 0
        BydClusterAdasTile.send = { sends++; true }
        val outputs = ArrayDeque(listOf(
            log(line("16:29:00.000", 1)),
            log(line("16:29:00.000", 1), line("16:29:08.963", 6)),
            log(line("16:29:00.000", 1), line("16:29:08.963", 6)),
        ))
        BydClusterAdasTile.read = { outputs.removeFirst() }
        repeat(3) { BydClusterAdasTile.tick(context) }
        assertEquals(1, sends)
    }

    @Test fun leavingTheMapForgetsTheLog() {
        BydOutputSettings.setKeepAdasTileSmall(context, true)
        BydClusterAdasTile.receiverAvailable = { true }
        var shown = true
        BydClusterAdasTile.mapShown = { shown }
        var sends = 0
        BydClusterAdasTile.send = { sends++; true }
        var output = log(line("16:29:00.000", 1))
        BydClusterAdasTile.read = { output }
        BydClusterAdasTile.tick(context)
        shown = false
        BydClusterAdasTile.tick(context)
        shown = true
        output = log(line("16:29:08.963", 6))
        BydClusterAdasTile.tick(context)
        assertEquals(0, sends)
    }
}
