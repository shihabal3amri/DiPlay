package com.shilapi.xcertplay

import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.airplay.VideoInCar
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.lang.ref.WeakReference
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.never
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayVideoNavigationTest {
    private lateinit var host: CarPlayHostActivity
    private val report = java.util.concurrent.CopyOnWriteArrayList<String>()

    @Before fun setUp() {
        CarPlayVideo.activity = null
        CarPlayVideo.onVideoSessionEnded()
        idle()
        CarPlayVideo.attach(RuntimeEnvironment.getApplication(), mock(CarPlayController::class.java), report::add)
        VideoInCar.allowed = true
        host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        ReflectionHelpers.setField(CarPlayScreen, "open", WeakReference(host))
    }

    @After fun tearDown() {
        CarPlayVideo.activity = null
        CarPlayVideo.onVideoSessionEnded()
        idle()
        VideoInCar.allowed = false
        ReflectionHelpers.setField(CarPlayScreen, "open", null)
    }

    @Test fun playerOpensOverTheLiveHostWithoutCreatingAnotherTaskOrDuplicateLaunches() {
        play()
        val started = shadowOf(host).nextStartedActivity
        assertEquals(CarPlayVideoActivity::class.java.name, started.component?.className)
        assertEquals(0, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        rate()
        CarPlayVideo.onVideoUiRequested()
        idle()
        assertNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun sameTaskLauncherHostAlsoOwnsThePlayerLaunch() {
        host = Robolectric.buildActivity(SameTaskCarPlayHostActivity::class.java).get()
        ReflectionHelpers.setField(CarPlayScreen, "open", WeakReference(host))
        play()
        val started = shadowOf(host).nextStartedActivity
        assertEquals(CarPlayVideoActivity::class.java.name, started.component?.className)
        assertEquals(0, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test fun dismissImmediatelyPausesAndRejectsDelayedCommandsAndPendingActivityStarts() {
        play()
        shadowOf(host).nextStartedActivity
        CarPlayVideo.dismissPlayer(12_345)
        assertFalse(CarPlayVideo.playing)
        assertFalse(CarPlayVideo.attachPlayer(mock(CarPlayVideoActivity::class.java)))
        rate()
        queue()
        rate()
        assertFalse(CarPlayVideo.playing)
        assertEquals(12_345, CarPlayVideo.startMillis)
        assertNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun explicitVideoUiRequestResumesTheDismissedItemImmediately() {
        play()
        shadowOf(host).nextStartedActivity
        CarPlayVideo.dismissPlayer(12_345)
        assertFalse(CarPlayVideo.attachPlayer(mock(CarPlayVideoActivity::class.java)))
        CarPlayVideo.onVideoUiRequested()
        idle()
        assertEquals(CarPlayVideoActivity::class.java.name, shadowOf(host).nextStartedActivity.component?.className)
        assertTrue(CarPlayVideo.playing)
        assertEquals(12_345, CarPlayVideo.startMillis)
    }

    @Test fun playAfterTheBackGuardResumesAtTheSavedPosition() {
        play()
        shadowOf(host).nextStartedActivity
        CarPlayVideo.dismissPlayer(12_345)
        rate()
        assertNull(shadowOf(host).nextStartedActivity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        rate()
        assertTrue(CarPlayVideo.playing)
        assertEquals(12_345, CarPlayVideo.startMillis)
        assertEquals(CarPlayVideoActivity::class.java.name, shadowOf(host).nextStartedActivity.component?.className)
    }

    @Test fun aRepeatedItemDuringAnExplicitReopenKeepsTheBackPosition() {
        play()
        shadowOf(host).nextStartedActivity
        CarPlayVideo.dismissPlayer(12_345)
        CarPlayVideo.onVideoUiRequested()
        idle()
        shadowOf(host).nextStartedActivity
        queue()
        assertEquals(12_345, CarPlayVideo.startMillis)
        assertTrue(CarPlayVideo.playing)
    }

    @Test fun returningFromLivePlaybackWaitsForTheItemAndIgnoresRemovalOfThePreviousChannel() {
        play()
        shadowOf(host).nextStartedActivity
        CarPlayVideo.dismissPlayer(null, isLive = true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        rate()
        CarPlayVideo.onVideoUiRequested()
        idle()
        assertNull(shadowOf(host).nextStartedActivity)
        message(mapOf("type" to "removePlayQueueItem", "item" to mapOf("uuid" to "first")))
        queue("next-channel")
        rate()
        message(mapOf("type" to "removePlayQueueItem", "item" to mapOf("uuid" to "first")))
        assertEquals("https://example.com/next-channel.mp4", CarPlayVideo.url)
        assertTrue(CarPlayVideo.playing)
        assertEquals(CarPlayVideoActivity::class.java.name, shadowOf(host).nextStartedActivity.component?.className)
        assertNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun liveVideoUiRequestCannotReopenAnOldSourceWithoutANewItem() {
        play()
        shadowOf(host).nextStartedActivity
        CarPlayVideo.dismissPlayer(12_345, isLive = true)
        CarPlayVideo.onVideoUiRequested()
        idle()
        assertNull(shadowOf(host).nextStartedActivity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertNull(shadowOf(host).nextStartedActivity)
        assertFalse(CarPlayVideo.playing)
        assertNull(CarPlayVideo.url)
        assertTrue(report.any { it.contains("fallback=false") })
    }

    @Test fun closedLiveChannelsCanBeRevisitedRepeatedlyWithATapAndFreshItems() {
        for ((index, channel) in listOf("one", "two", "one", "two", "one").withIndex()) {
            tapCarPlay()
            message(mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
                "uuid" to "selection-$index", "clientBundleID" to "example.live", "mediaType" to "streaming",
                "Content-Location" to "https://example.com/$channel.m3u8")))
            rate()
            assertEquals("https://example.com/$channel.m3u8", CarPlayVideo.url)
            assertTrue(CarPlayVideo.playing)
            assertNotNull(shadowOf(host).nextStartedActivity)
            CarPlayVideo.dismissPlayer(null, isLive = true)
            assertNull(CarPlayVideo.url)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            rate()
            assertNull(shadowOf(host).nextStartedActivity)
        }
    }

    @Test fun anotherLiveChannelMustActuallyPlayBeforeThePhoneCanSelectTheClosedChannel() {
        play()
        shadowOf(host).nextStartedActivity
        CarPlayVideo.dismissPlayer(null, isLive = true)

        queue("next-channel")
        rate()
        shadowOf(host).nextStartedActivity
        val player = mock(CarPlayVideoActivity::class.java)
        CarPlayVideo.activity = player
        `when`(player.ownsCurrentItem()).thenReturn(true)
        `when`(player.isLiveItem()).thenReturn(true)
        `when`(player.state()).thenReturn(VideoInCar.PlayerState(false, false, 0.0, Double.NaN, 0.0))
        CarPlayVideo.onPlayerPlaybackStateChanged(player, CarPlayVideo.playbackGeneration)
        queue()
        assertEquals("https://example.com/next-channel.mp4", CarPlayVideo.url)

        `when`(player.state()).thenReturn(VideoInCar.PlayerState(true, true, 0.0, Double.NaN, 10.0))
        `when`(player.isLiveItem()).thenReturn(false)
        CarPlayVideo.onPlayerPlaybackStateChanged(player, CarPlayVideo.playbackGeneration)
        queue()
        assertEquals("https://example.com/next-channel.mp4", CarPlayVideo.url)

        `when`(player.isLiveItem()).thenReturn(true)
        CarPlayVideo.onPlayerPlaybackStateChanged(player, CarPlayVideo.playbackGeneration)
        queue()
        assertEquals("https://example.com/first.mp4", CarPlayVideo.url)
        rate()
        assertTrue(CarPlayVideo.playing)
        verify(player, never()).finish()
    }

    @Test fun anExpiredTapCannotReopenAClosedLiveSource() {
        play()
        shadowOf(host).nextStartedActivity
        CarPlayVideo.dismissPlayer(null, isLive = true)
        tapCarPlay()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        queue()
        rate()
        assertNull(CarPlayVideo.url)
        assertFalse(CarPlayVideo.playing)
        assertNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun backBeforeHlsPreparationRetiresTheSourceAndRejectsLaterReplay() {
        message(mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
            "uuid" to "pending-live", "clientBundleID" to "example.live", "mediaType" to "streaming",
            "Content-Location" to "https://example.com/live.m3u8")))
        rate()
        val screen = Robolectric.buildActivity(CarPlayVideoActivity::class.java, shadowOf(host).nextStartedActivity).get()
        assertFalse(screen.isLiveItem()) // No manifest or timeline has loaded.
        ReflectionHelpers.callInstanceMethod<Void>(screen, "returnToCarPlay")
        shadowOf(screen).nextStartedActivity
        assertNull(CarPlayVideo.url)
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            rate()
            message(mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
                "uuid" to "retry-$it", "mediaType" to "streaming",
                "Content-Location" to "https://example.com/live.m3u8")))
            assertNull(CarPlayVideo.url)
            assertFalse(CarPlayVideo.playing)
            assertNull(shadowOf(host).nextStartedActivity)
        }
    }

    @Test fun backFromPreparedOnDemandHlsPreservesItsPositionAndResume() {
        message(mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
            "uuid" to "vod", "mediaType" to "streaming", "Content-Location" to "https://example.com/vod.m3u8")))
        rate()
        val screen = Robolectric.buildActivity(CarPlayVideoActivity::class.java, shadowOf(host).nextStartedActivity).get()
        val player = mock(androidx.media3.exoplayer.ExoPlayer::class.java)
        `when`(player.currentPosition).thenReturn(12_345L)
        ReflectionHelpers.setField(screen, "player", player)
        ReflectionHelpers.setField(screen, "prepared", true)
        ReflectionHelpers.callInstanceMethod<Void>(screen, "returnToCarPlay")
        shadowOf(screen).nextStartedActivity
        assertEquals("https://example.com/vod.m3u8", CarPlayVideo.url)
        assertEquals(12_345, CarPlayVideo.startMillis)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        rate()
        assertTrue(CarPlayVideo.playing)
        assertNotNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun aLiveUiRequestDoesNotReopenTheClosedSourceButCanOpenAnotherChannel() {
        play()
        shadowOf(host).nextStartedActivity
        CarPlayVideo.dismissPlayer(12_345, isLive = true)
        rate()
        queue()
        assertNull(shadowOf(host).nextStartedActivity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        rate()
        queue()
        assertNull(shadowOf(host).nextStartedActivity)
        CarPlayVideo.onVideoUiRequested()
        idle()
        queue()
        assertNull(shadowOf(host).nextStartedActivity)
        queue("next-channel")
        assertNotNull(shadowOf(host).nextStartedActivity)
        assertTrue(CarPlayVideo.playing)
        assertEquals(0, CarPlayVideo.startMillis)
    }

    @Test fun liveUiRequestStartsOnlyAfterTheNewItemArrives() {
        play()
        shadowOf(host).nextStartedActivity
        CarPlayVideo.dismissPlayer(null, isLive = true)
        CarPlayVideo.onVideoUiRequested()
        idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        queue("next-channel")
        assertNotNull(shadowOf(host).nextStartedActivity)
        assertTrue(CarPlayVideo.playing)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun aLiveOpeningIsCancelledWhenTheSessionEndsOrTheGateCloses() {
        for (endSession in listOf(false, true)) {
            CarPlayVideo.onVideoSessionEnded()
            idle()
            VideoInCar.allowed = true
            play()
            shadowOf(host).nextStartedActivity
            CarPlayVideo.dismissPlayer(null, isLive = true)
            CarPlayVideo.onVideoUiRequested()
            idle()
            if (endSession) CarPlayVideo.onVideoSessionEnded() else {
                VideoInCar.allowed = false
                CarPlayVideo.onVideoAllowedChanged(false)
            }
            idle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            assertNull(shadowOf(host).nextStartedActivity)
        }
    }

    @Test fun pausingFromThePhoneCancelsThePendingLiveUiRequest() {
        play()
        shadowOf(host).nextStartedActivity
        CarPlayVideo.dismissPlayer(null, isLive = true)
        CarPlayVideo.onVideoUiRequested()
        idle()
        message(mapOf("type" to "setRate", "rate" to 0.0))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertFalse(CarPlayVideo.playing)
        assertNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun repeatedLivePlayCommandsWithoutANewItemDoNotAutomaticallyReopenAfterBack() {
        play()
        shadowOf(host).nextStartedActivity
        CarPlayVideo.dismissPlayer(null, isLive = true)
        repeat(6) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            rate()
            assertNull(shadowOf(host).nextStartedActivity)
        }
    }

    @Test fun untargetedRemovalAlsoCancelsTheLiveWait() {
        play()
        shadowOf(host).nextStartedActivity
        message(mapOf("type" to "removePlayQueueItem"))
        assertNull(CarPlayVideo.url)
        play()
        shadowOf(host).nextStartedActivity
        CarPlayVideo.dismissPlayer(null, isLive = true)
        message(mapOf("type" to "removePlayQueueItem"))
        assertNull(CarPlayVideo.url)
        assertTrue(report.any { it.contains("removePlayQueueItem accepted") })
    }

    @Test fun stopsForOtherItemsAndStreamsCannotCloseTheCurrentItem() {
        play()
        shadowOf(host).nextStartedActivity
        CarPlayVideo.onVideoMessage(8, mapOf("type" to "stop"))
        idle()
        message(mapOf("type" to "stop", "uuid" to "other"))
        assertNotNull(CarPlayVideo.url)
        message(mapOf("type" to "stop", "uuid" to "first"))
        assertNull(CarPlayVideo.url)
        assertTrue(report.any { it.contains("stop ignored reason=other_stream") })
        assertTrue(report.any { it.contains("stop ignored reason=other_item") })
    }

    @Test fun recreatingThePlayerKeepsItsPositionAndAcceptsTheReplacementActivity() {
        play()
        val intent = shadowOf(host).nextStartedActivity
        val oldPlayer = Robolectric.buildActivity(CarPlayVideoActivity::class.java, intent).get()
        assertTrue(CarPlayVideo.attachPlayer(oldPlayer))
        CarPlayVideo.detachPlayer(oldPlayer, 24_680, changingConfigurations = true)
        assertEquals(24_680, CarPlayVideo.startMillis)
        assertTrue(CarPlayVideo.playing)
        assertTrue(CarPlayVideo.attachPlayer(Robolectric.buildActivity(CarPlayVideoActivity::class.java, intent).get()))
    }

    @Test fun refusedRequestsAndBackDecisionsReachTheReportWithoutUrls() {
        CarPlayVideo.onVideoUiRequested()
        idle()
        assertTrue(report.any { it.contains("without a playable item") })
        play()
        shadowOf(host).nextStartedActivity
        CarPlayVideo.dismissPlayer(null)
        rate()
        queue()
        VideoInCar.allowed = false
        CarPlayVideo.onVideoUiRequested()
        idle()
        rate()
        assertTrue(report.any { it.contains("reason=back_guard") })
        assertTrue(report.any { it.contains("reason=repeated_after_back") })
        assertTrue(report.any { it.contains("while not parked") })
        assertFalse(report.any { it.contains("https://") })
    }

    @Test fun playBeforeTheItemWaitsAndThenStartsTheNewItem() {
        rate()
        assertNull(shadowOf(host).nextStartedActivity)
        queue()
        assertTrue(CarPlayVideo.playing)
        assertNotNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun playOnAnotherStreamCannotOpenTheOldStreamsItem() {
        play()
        shadowOf(host).nextStartedActivity
        CarPlayVideo.dismissPlayer(null)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        CarPlayVideo.onVideoMessage(8, mapOf("type" to "setRate", "rate" to 1.0))
        idle()
        assertNull(shadowOf(host).nextStartedActivity)
        CarPlayVideo.onVideoMessage(8, mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
            "uuid" to "next-stream", "Content-Location" to "https://example.com/next-stream.mp4")))
        idle()
        assertEquals("https://example.com/next-stream.mp4", CarPlayVideo.url)
        assertNotNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun theBackGuardDoesNotDiscardPlayForANewStream() {
        play()
        shadowOf(host).nextStartedActivity
        CarPlayVideo.dismissPlayer(null, isLive = true)
        CarPlayVideo.onVideoMessage(8, mapOf("type" to "setRate", "rate" to 1.0))
        idle()
        assertNull(shadowOf(host).nextStartedActivity)
        CarPlayVideo.onVideoMessage(8, mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
            "uuid" to "next-stream", "Content-Location" to "https://example.com/next-stream.mp4")))
        idle()
        assertTrue(CarPlayVideo.playing)
        assertNotNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun aNewStreamsPlayCannotResumeAnItemPausedByPlayerDestruction() {
        play()
        val screen = Robolectric.buildActivity(CarPlayVideoActivity::class.java, shadowOf(host).nextStartedActivity).get()
        assertTrue(CarPlayVideo.attachPlayer(screen))
        CarPlayVideo.detachPlayer(screen, 12_345, changingConfigurations = false)
        CarPlayVideo.onVideoMessage(8, mapOf("type" to "setRate", "rate" to 1.0))
        idle()
        assertFalse(CarPlayVideo.playing)
        assertNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun completingAnItemRejectsItsLateReplayButAllowsAnExplicitUiRequest() {
        play()
        shadowOf(host).nextStartedActivity
        CarPlayVideo.onPlaybackCompleted()
        assertNull(CarPlayVideo.url)
        rate()
        queue()
        assertNull(CarPlayVideo.url)
        assertNull(shadowOf(host).nextStartedActivity)
        CarPlayVideo.onVideoUiRequested()
        idle()
        queue()
        assertTrue(CarPlayVideo.playing)
        assertNotNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun endingTheSessionClearsDeferredPlay() {
        rate()
        CarPlayVideo.onVideoSessionEnded()
        idle()
        queue()
        assertFalse(CarPlayVideo.playing)
        assertNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun aSilentStartTimeoutRetriesOnceAndRejectsLateActivities() {
        play()
        val original = shadowOf(host).nextStartedActivity
        assertTrue(CarPlayVideo.playerOnScreen)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        val retry = shadowOf(host).nextStartedActivity
        assertNotNull(retry)
        assertTrue(CarPlayVideo.playerOnScreen)
        assertFalse(CarPlayVideo.attachPlayer(Robolectric.buildActivity(CarPlayVideoActivity::class.java, original).get()))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertNull(shadowOf(host).nextStartedActivity)
        assertFalse(CarPlayVideo.playerOnScreen)
        CarPlayVideo.onVideoUiRequested()
        idle()
        val current = shadowOf(host).nextStartedActivity
        assertFalse(CarPlayVideo.attachPlayer(Robolectric.buildActivity(CarPlayVideoActivity::class.java, retry).get()))
        assertTrue(CarPlayVideo.attachPlayer(Robolectric.buildActivity(CarPlayVideoActivity::class.java, current).get()))
    }

    @Test fun choosingANewItemCanPlayAfterDismissal() {
        play()
        shadowOf(host).nextStartedActivity
        CarPlayVideo.dismissPlayer(12_345)
        assertFalse(CarPlayVideo.attachPlayer(mock(CarPlayVideoActivity::class.java)))
        queue("second")
        rate()
        assertTrue(CarPlayVideo.playing)
        assertEquals(CarPlayVideoActivity::class.java.name, shadowOf(host).nextStartedActivity.component?.className)
    }

    @Test fun lateActivityStartAfterStopClosesWithoutCreatingAPlayer() {
        play()
        message(mapOf("type" to "stop"))
        val screen = Robolectric.buildActivity(CarPlayVideoActivity::class.java).create()
        assertTrue(screen.get().isFinishing)
        screen.destroy() // No ExoPlayer was initialized; teardown must also be safe.
        assertNull(CarPlayVideo.activity)
    }

    @Test fun backToCarPlayButtonPausesAndTargetsTheExistingHostInsteadOfTheStartPage() {
        play()
        shadowOf(host).nextStartedActivity
        val screen = Robolectric.buildActivity(CarPlayVideoActivity::class.java).get()
        val controls = ReflectionHelpers.callInstanceMethod<View>(screen, "controlBar")
        descendants(controls).filterIsInstance<TextView>()
            .single { it.text.contains(screen.getString(R.string.video_back_to_carplay)) }.performClick()
        assertFalse(CarPlayVideo.playing)
        assertTrue(screen.isFinishing)
        assertEquals(host.javaClass.name, shadowOf(screen).nextStartedActivity.component?.className)
        rate()
        assertFalse(CarPlayVideo.playing)
        assertNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun liveRemovalKeepsPausedPlayerBeyondTheOldTimeout() {
        val player = openMockLivePlayer()
        message(mapOf("type" to "setRate", "rate" to 0.0))
        message(mapOf("type" to "removePlayQueueItem", "uuid" to "first"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15))
        assertNotNull(CarPlayVideo.url)
        assertSame(player, CarPlayVideo.activity)
        verify(player, never()).finish()
        assertTrue(report.any { it.contains("live item removal retained player") })
    }

    @Test fun repeatedLiveReplacementsKeepTheSamePlayer() {
        val player = openMockLivePlayer()
        var current = "first"
        repeat(4) { index ->
            message(mapOf("type" to "setRate", "rate" to 0.0))
            message(mapOf("type" to "removePlayQueueItem", "uuid" to current))
            message(mapOf("type" to "removePlayQueueItem"))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
            current = "replacement$index"
            queue(current)
            rate()
            assertTrue(CarPlayVideo.playing)
            assertSame(player, CarPlayVideo.activity)
            assertNull(shadowOf(host).nextStartedActivity)
        }
        verify(player, never()).finish()
    }

    @Test fun explicitStopClosesTheRetainedLivePlayer() {
        val player = openMockLivePlayer()
        message(mapOf("type" to "removePlayQueueItem", "uuid" to "first"))
        message(mapOf("type" to "stop"))
        assertNull(CarPlayVideo.url)
        verify(player).finish()
    }

    @Test fun returningDuringLiveHandoffAllowsADifferentChannelFromTheSameApp() {
        openMockLivePlayer()
        message(mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
            "uuid" to "first", "Content-Location" to "https://example.com/live.m3u8",
            "clientBundleID" to "example.live", "mediaType" to "streaming")))
        rate()
        message(mapOf("type" to "removePlayQueueItem", "uuid" to "first"))
        CarPlayVideo.dismissPlayer(null, isLive = true)
        CarPlayVideo.activity = null
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15))
        message(mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
            "uuid" to "changed", "Content-Location" to "https://example.com/new-live.m3u8",
            "clientBundleID" to "example.live", "mediaType" to "streaming")))
        rate()
        assertEquals("https://example.com/new-live.m3u8", CarPlayVideo.url)
        assertTrue(CarPlayVideo.playing)
        assertNotNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun sameSourceRemoteItemsPreserveThePlayerAndPlaybackGeneration() {
        val player = openMockLivePlayer()
        `when`(player.ownsCurrentItem()).thenReturn(true)
        val generation = CarPlayVideo.playbackGeneration
        var current = "first"
        repeat(4) { index ->
            message(mapOf("type" to "removePlayQueueItem", "uuid" to current))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
            current = "remote$index"
            message(mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
                "uuid" to current, "Content-Location" to "https://example.com/first.mp4",
                "startPosition" to 0.0)))
            rate()
            assertEquals(generation, CarPlayVideo.playbackGeneration)
            assertTrue(CarPlayVideo.playing)
            assertNull(shadowOf(host).nextStartedActivity)
        }
        verify(player, never()).load()
        verify(player, never()).finish()
        assertEquals(4, report.count { it.contains("action=retain_player_source") })
    }

    @Test fun sameSourceOnANewStreamUpdatesRemoteOwnershipWithoutReloading() {
        val player = openMockLivePlayer()
        `when`(player.ownsCurrentItem()).thenReturn(true)
        val generation = CarPlayVideo.playbackGeneration
        CarPlayVideo.onVideoMessage(8, mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
            "uuid" to "new-stream-item", "Content-Location" to "https://example.com/first.mp4")))
        idle()
        assertEquals(generation, CarPlayVideo.playbackGeneration)
        message(mapOf("type" to "stop"))
        assertNotNull(CarPlayVideo.url)
        verify(player, never()).load()
        verify(player, never()).finish()
        CarPlayVideo.onVideoMessage(8, mapOf("type" to "stop"))
        idle()
        assertNull(CarPlayVideo.url)
        verify(player).finish()
    }

    @Test fun differentSourceStillLoadsANewPlaybackGeneration() {
        val player = openMockLivePlayer()
        `when`(player.ownsCurrentItem()).thenReturn(true)
        val generation = CarPlayVideo.playbackGeneration
        queue("different-source")
        assertTrue(CarPlayVideo.playbackGeneration > generation)
        verify(player).load()
    }

    @Test fun iphoneSwitchToADifferentChannelWorksWithoutCarPlayTap() {
        val player = openMockLivePlayer()
        `when`(player.ownsCurrentItem()).thenReturn(true)
        for ((index, app) in listOf("example.live", "example.browser", "example.live").withIndex()) {
            val source = if (index == 2) "other-live.m3u8" else if (app == "example.live") "live.m3u8" else "movie.mp4"
            message(mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
                "uuid" to "app-switch$index", "clientBundleID" to app,
                "Content-Location" to "https://example.com/$source")))
            rate()
            assertEquals("https://example.com/$source", CarPlayVideo.url)
            assertTrue(CarPlayVideo.playing)
            assertNull(shadowOf(host).nextStartedActivity)
            `when`(player.isLiveItem()).thenReturn(app == "example.live")
        }
        verify(player, org.mockito.Mockito.times(3)).load()
        verify(player, never()).finish()
        assertFalse(report.any { it.contains("CarPlay tap armed") })
    }

    @Test fun itemIdentityDiagnosticsAreStableAcrossStreamsWithoutExposingRawValues() {
        val source = "https://example.com/private/live.m3u8?token=private-token"
        val item = mapOf("uuid" to "private-uuid", "Content-Location" to source,
            "playerLoggingID" to "private-player-id", "playerItemLoggingID" to byteArrayOf(1, 2, 3),
            "clientBundleID" to "example.live")
        for (stream in listOf(3L, 8L, 3L)) {
            CarPlayVideo.onVideoMessage(stream, mapOf("type" to "insertPlayQueueItem", "item" to item))
            idle()
        }
        val identities = report.filter { it.contains("queue item identity ") }
        assertEquals(3, identities.size)
        for (field in listOf("source", "uuid", "player", "playerItem")) {
            val values = identities.map { it.substringAfter(" $field=").substringBefore(' ') }
            assertEquals(1, values.toSet().size)
            assertTrue(values.first().matches(Regex("[0-9a-f]{16}")))
        }
        val text = report.joinToString("\n")
        for (secret in listOf(source, "private-token", "private-uuid", "private-player-id")) {
            assertFalse(text.contains(secret))
        }
        assertTrue(report.any { it.contains("reason=inactive_stream") || it.contains("reason=superseded_stream") })
    }

    @Test fun leavingLiveForAnotherAppInterruptsOnlyTheOldStreamAndKeepsDirectSwitching() {
        val player = openMockLivePlayer()
        `when`(player.ownsCurrentItem()).thenReturn(true)
        ReflectionHelpers.setField(CarPlayVideo, "itemApp", "example.live")
        val target = ReflectionHelpers.getField<CarPlayController>(CarPlayVideo, "controller")
        CarPlayVideo.pendingSeekMillis = 42_000
        CarPlayVideo.onVideoMessage(8, mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
            "uuid" to "browser", "clientBundleID" to "example.browser",
            "Content-Location" to "https://example.com/browser.mp4")))
        CarPlayVideo.onVideoMessage(8, mapOf("type" to "setRate", "rate" to 1.0))
        idle()
        val interrupted = stateNotifications(target, 3).single()
        assertEquals("stopped", interrupted["name"])
        assertEquals("interrupted", interrupted["reason"])
        assertTrue(CarPlayVideo.playing)
        assertNull(CarPlayVideo.pendingSeekMillis)
        assertEquals("https://example.com/browser.mp4", CarPlayVideo.url)
        assertTrue(report.any { it.contains("live playback ended reason=source_switch") })
        assertEquals(0, pausedReplies(target, 8L))
        verify(player, never()).finish()
    }

    @Test fun phoneStopIsQuietWhileLiveBackStillPauses() {
        for (back in listOf(false, true)) {
            CarPlayVideo.activity = null
            CarPlayVideo.onVideoSessionEnded()
            idle()
            openMockLivePlayer()
            val target = ReflectionHelpers.getField<CarPlayController>(CarPlayVideo, "controller")
            org.mockito.Mockito.clearInvocations(target)
            message(mapOf("type" to "setRate", "rate" to 0.0))
            if (back) CarPlayVideo.dismissPlayer(null, isLive = true)
            else message(mapOf("type" to "stop"))
            if (back) assertPausedReply(target) else assertEquals(0, pausedReplies(target, 3))
            assertFalse(CarPlayVideo.playing)
            assertNull(CarPlayVideo.url)
            CarPlayVideo.activity = null
        }
    }

    @Test fun stoppingOnDemandDoesNotUseTheLiveExitNotification() {
        val player = openMockLivePlayer()
        `when`(player.isLiveItem()).thenReturn(false)
        val target = ReflectionHelpers.getField<CarPlayController>(CarPlayVideo, "controller")
        message(mapOf("type" to "stop"))
        assertNull(CarPlayVideo.url)
        verify(player).finish()
        assertEquals(0, pausedReplies(target, 3L))
        assertFalse(report.any { it.contains("live playback ended") })
    }

    private fun assertPausedReply(target: CarPlayController) {
        assertTrue(pausedReplies(target, 3L) > 0)
    }

    private fun pausedReplies(target: CarPlayController, stream: Long): Int {
        ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(CarPlayVideo, "sender")
            .submit {}.get(2, java.util.concurrent.TimeUnit.SECONDS)
        return org.mockito.Mockito.mockingDetails(target).invocations.count {
            val message = it.arguments.getOrNull(1) as? Map<*, *>
            it.method.name == "sendVideoMessage" && it.arguments.firstOrNull() == stream &&
                message?.get("type") == "playbackState" && message["name"] == "paused"
        }
    }

    @Test fun replacedLiveUrlIsBlockedWithoutReturningFirst() {
        val player = browserAfterLiveExit()
        org.mockito.Mockito.clearInvocations(player)
        rejectedLiveItem()
        assertEquals("https://example.com/browser.mp4", CarPlayVideo.url)
        assertTrue(CarPlayVideo.playing)
        verify(player, never()).finish()
        verify(player, never()).load()
        assertTrue(report.any { it.contains("reason=closed_live_source stream=9") })
    }

    @Test fun rejectedLiveCommandsCannotPauseSeekOrCloseTheBrowser() {
        val player = browserAfterLiveExit()
        rejectedLiveItem()
        org.mockito.Mockito.clearInvocations(player)
        send(9, mapOf("type" to "setRate", "rate" to 0.0))
        send(9, mapOf("kind" to "request", "messageID" to 12L, "type" to "seek", "time" to VideoInCar.cmTime(20.0)))
        send(9, mapOf("type" to "stop"))
        send(9, mapOf("type" to "removePlayQueueItem"))
        assertEquals("https://example.com/browser.mp4", CarPlayVideo.url)
        assertTrue(CarPlayVideo.playing)
        assertNull(CarPlayVideo.pendingSeekMillis)
        verify(player, never()).applySeek()
        verify(player, never()).applyRate()
        verify(player, never()).finish()
    }

    @Test fun inactiveStateQueriesNeverExposeTheBrowserItemOrPlaybackRanges() {
        browserAfterLiveExit()
        rejectedLiveItem()
        val target = ReflectionHelpers.getField<CarPlayController>(CarPlayVideo, "controller")
        for (stream in listOf(3L, 9L, 8L)) {
            send(stream, mapOf("kind" to "request", "type" to "playbackInfo", "messageID" to stream))
            send(stream, mapOf("kind" to "request", "type" to "property", "property" to "loadedTimeRanges", "messageID" to stream + 100))
        }
        drainVideoReplies()
        val replies = org.mockito.Mockito.mockingDetails(target).invocations.filter { it.method.name == "sendVideoMessage" }
        for (stream in listOf(3L, 9L, 8L)) {
            val reply = replies.map { it.arguments[1] as Map<*, *> }.single { it["messageID"] == stream }
            val info = reply["info"] as Map<*, *>
            val item = info["item"] as Map<*, *>
            val ranges = replies.map { it.arguments[1] as Map<*, *> }.single { it["messageID"] == stream + 100 }
            if (stream == 8L) {
                assertEquals("browser", item["uuid"])
                assertEquals(1.0, info["rate"])
                assertNotNull(info["position"])
                assertNotNull(ranges["value"])
            } else {
                assertNotEquals("browser", item["uuid"])
                assertEquals(0.0, info["rate"])
                assertEquals("paused", info["playbackState"])
                assertFalse(info.containsKey("position"))
                assertFalse(ranges.containsKey("value"))
            }
        }
    }

    @Test fun browserPhonePausePlayAndStopRemainEffectiveAfterALiveRejection() {
        val player = browserAfterLiveExit()
        rejectedLiveItem()
        send(8, mapOf("type" to "setRate", "rate" to 0.0))
        assertFalse(CarPlayVideo.playing)
        send(8, mapOf("type" to "setRate", "rate" to 1.0))
        assertTrue(CarPlayVideo.playing)
        send(8, mapOf("type" to "stop"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        assertEquals("https://example.com/browser.mp4", CarPlayVideo.url)
        assertFalse(CarPlayVideo.playing)
        verify(player, never()).finish()
    }

    @Test fun aRejectedStreamCanStartADifferentChannelWithItsOwnPlayFirstRequest() {
        browserAfterLiveExit()
        rejectedLiveItem()
        send(9, mapOf("type" to "setRate", "rate" to 1.0))
        send(9, mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
            "uuid" to "channel-two", "clientBundleID" to "example.live", "Content-Location" to "https://example.com/channel-two.m3u8")))
        assertEquals("https://example.com/channel-two.m3u8", CarPlayVideo.url)
        assertTrue(CarPlayVideo.playing)
        assertEquals(0L, ReflectionHelpers.getField<Long>(CarPlayVideo, "deferredPlayUntil"))
    }

    @Test fun backClosedLiveUrlCannotBeReopenedByUiButAnotherChannelCanPlay() {
        openMockLivePlayer()
        CarPlayVideo.dismissPlayer(null, isLive = true)
        CarPlayVideo.activity = null
        CarPlayVideo.onVideoUiRequested()
        idle()
        queue()
        rate()
        assertNull(CarPlayVideo.url)
        assertNull(shadowOf(host).nextStartedActivity)
        queue("other-channel")
        assertEquals("https://example.com/other-channel.mp4", CarPlayVideo.url)
        assertTrue(CarPlayVideo.playing)
    }

    @Test fun remoteLiveStopAlsoRetiresItsUrlBeforeTheBrowserStarts() {
        openMockLivePlayer()
        message(mapOf("type" to "stop"))
        CarPlayVideo.activity = null
        send(8, mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
            "uuid" to "browser", "clientBundleID" to "example.browser", "Content-Location" to "https://example.com/browser.mp4")))
        send(8, mapOf("type" to "setRate", "rate" to 1.0))
        rejectedLiveItem()
        assertEquals("https://example.com/browser.mp4", CarPlayVideo.url)
    }

    @Test fun untargetedRemovalStopsCurrentOnDemandEvenAfterABlockedLiveRequest() {
        val player = browserAfterLiveExit()
        rejectedLiveItem()
        send(8, mapOf("type" to "removePlayQueueItem"))
        assertNull(CarPlayVideo.url)
        assertFalse(CarPlayVideo.playing)
        verify(player).finish()
    }

    @Test fun untargetedRemovalStopsCurrentLiveWhenNotAwaitingReplacement() {
        val player = openMockLivePlayer()
        message(mapOf("type" to "removePlayQueueItem"))
        assertNull(CarPlayVideo.url)
        assertFalse(CarPlayVideo.playing)
        verify(player).finish()
    }

    @Test fun browserRemovalAndStopKeepTheScreenAfterABlockedLiveTakeover() {
        val player = browserAfterLiveExit()
        org.mockito.Mockito.clearInvocations(player)
        val generation = CarPlayVideo.playbackGeneration
        rejectedLiveItem()
        send(8, mapOf("type" to "removePlayQueueItem", "item" to mapOf("uuid" to "browser")))
        assertTrue(CarPlayVideo.playing)
        send(8, mapOf("type" to "stop"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(CarPlayVideo.playing)
        assertEquals("https://example.com/browser.mp4", CarPlayVideo.url)
        assertEquals(generation, CarPlayVideo.playbackGeneration)
        assertSame(player, CarPlayVideo.activity)
        verify(player, never()).finish()
        verify(player, never()).load()
    }

    @Test fun iphonePauseThenRemovalAndStopCanResumeWithoutClosingOrReloading() {
        val player = browserAfterLiveExit()
        org.mockito.Mockito.clearInvocations(player)
        val generation = CarPlayVideo.playbackGeneration
        send(8, mapOf("type" to "setRate", "rate" to 0.0))
        send(8, mapOf("type" to "removePlayQueueItem", "item" to mapOf("uuid" to "browser")))
        send(8, mapOf("type" to "stop"))
        assertFalse(CarPlayVideo.playing)
        send(8, mapOf("type" to "setRate", "rate" to 1.0))
        assertTrue(CarPlayVideo.playing)
        assertEquals(generation, CarPlayVideo.playbackGeneration)
        verify(player, never()).finish()
        verify(player, never()).load()
        assertNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun aBlockedLiveArrivingAfterBrowserRemovalCannotReplaceTheRetainedScreen() {
        val player = browserAfterLiveExit()
        org.mockito.Mockito.clearInvocations(player)
        send(8, mapOf("type" to "removePlayQueueItem", "item" to mapOf("uuid" to "browser")))
        send(8, mapOf("type" to "stop"))
        rejectedLiveItem()
        send(9, mapOf("type" to "setRate", "rate" to 1.0))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals("https://example.com/browser.mp4", CarPlayVideo.url)
        assertTrue(CarPlayVideo.playing)
        verify(player, never()).finish()
        verify(player, never()).load()
    }

    @Test fun aRetainedBrowserStillClosesWhenPlaybackCompletes() {
        val player = browserAfterLiveExit()
        send(8, mapOf("type" to "removePlayQueueItem", "item" to mapOf("uuid" to "browser")))
        CarPlayVideo.onPlaybackCompleted()
        assertNull(CarPlayVideo.url)
        verify(player).finish()
    }

    @Test fun aPhoneStopWithoutABlockedHandoffStillPausesAfterTheShortWindow() {
        val player = browserAfterLiveExit()
        send(8, mapOf("type" to "stop"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(299))
        assertTrue(CarPlayVideo.playing)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2))
        assertFalse(CarPlayVideo.playing)
        verify(player, never()).finish()
    }

    @Test fun blockedHandoffWithinOneHundredMillisCancelsThePendingStop() {
        val player = browserAfterLiveExit()
        org.mockito.Mockito.clearInvocations(player)
        send(8, mapOf("type" to "removePlayQueueItem", "item" to mapOf("uuid" to "browser")))
        send(8, mapOf("type" to "stop"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        rejectedLiveItem()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(CarPlayVideo.playing)
        verify(player, never()).applyRate()
        verify(player, never()).finish()
        assertTrue(report.any { it.contains("phone stop cancelled reason=blocked_live_handoff") })
    }

    @Test fun explicitPhonePauseIsImmediateAndCannotBeUndoneByABlockedHandoff() {
        browserAfterLiveExit()
        send(8, mapOf("type" to "stop"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        send(8, mapOf("type" to "setRate", "rate" to 0.0))
        assertFalse(CarPlayVideo.playing)
        rejectedLiveItem()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertFalse(CarPlayVideo.playing)
    }

    @Test fun explicitPhonePlayCancelsAnOlderPendingStop() {
        browserAfterLiveExit()
        send(8, mapOf("type" to "stop"))
        send(8, mapOf("type" to "setRate", "rate" to 1.0))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(CarPlayVideo.playing)
    }

    @Test fun aDifferentChannelIsNotPausedByThePreviousBrowserStopTimer() {
        val player = browserAfterLiveExit()
        send(8, mapOf("type" to "stop"))
        send(9, mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
            "uuid" to "other-channel", "clientBundleID" to "example.live", "Content-Location" to "https://example.com/other-channel.m3u8")))
        `when`(player.isLiveItem()).thenReturn(true)
        send(9, mapOf("type" to "setRate", "rate" to 1.0))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals("https://example.com/other-channel.m3u8", CarPlayVideo.url)
        assertTrue(CarPlayVideo.playing)
    }

    @Test fun cancellingABlockedHandoffReportsPlayingOnlyToTheRetainedBrowser() {
        val player = browserAfterLiveExit()
        val target = ReflectionHelpers.getField<CarPlayController>(CarPlayVideo, "controller")
        `when`(target.sendVideoMessage(8, VideoInCar.playbackStateNotification(true, "browser", player.state()))).thenReturn(true)
        send(8, mapOf("type" to "stop"))
        rejectedLiveItem()
        val notifications = stateNotifications(target, 8)
        assertEquals(1, notifications.count { it["name"] == "playing" })
        assertEquals("browser", (notifications.single()["item"] as Map<*, *>)["uuid"])
        assertTrue(stateNotifications(target, 9).isEmpty())
        assertTrue(report.any { it.contains("playback state notification sent=true stream=8 state=playing") })
        assertTrue(CarPlayVideo.playing)
        verify(player, never()).finish()
    }

    @Test fun aStopAfterTheRejectedLiveItemAlsoReportsTheRetainedState() {
        browserAfterLiveExit()
        val target = ReflectionHelpers.getField<CarPlayController>(CarPlayVideo, "controller")
        rejectedLiveItem()
        send(8, mapOf("type" to "stop"))
        assertEquals(1, stateNotifications(target, 8).count { it["name"] == "playing" })
        assertTrue(report.any { it.contains("retained player state sync reason=stop_ignored") })
    }

    @Test fun aQueuedPlayingSyncCannotOverrideALaterPhonePause() {
        browserAfterLiveExit()
        val target = ReflectionHelpers.getField<CarPlayController>(CarPlayVideo, "controller")
        val release = holdVideoReplies()
        try {
            send(8, mapOf("type" to "stop"))
            rejectedLiveItem()
            send(8, mapOf("type" to "setRate", "rate" to 0.0))
        } finally { release.countDown() }
        assertTrue(stateNotifications(target, 8).isEmpty())
        assertFalse(CarPlayVideo.playing)
        assertTrue(report.any { it.contains("notification skipped reason=stale_state stream=8") })
    }

    @Test fun aQueuedPlayingSyncCannotReferToASourceThatWasReplaced() {
        browserAfterLiveExit()
        val target = ReflectionHelpers.getField<CarPlayController>(CarPlayVideo, "controller")
        val release = holdVideoReplies()
        try {
            send(8, mapOf("type" to "stop"))
            rejectedLiveItem()
            send(9, mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
                "uuid" to "other", "clientBundleID" to "example.live", "Content-Location" to "https://example.com/other-live.m3u8")))
            send(9, mapOf("type" to "setRate", "rate" to 1.0))
        } finally { release.countDown() }
        assertEquals(listOf(VideoInCar.playbackInterruptedNotification()), stateNotifications(target, 8))
        assertTrue(report.any { it.contains("notification skipped reason=stale_state stream=8") })
        assertEquals("https://example.com/other-live.m3u8", CarPlayVideo.url)
        assertTrue(CarPlayVideo.playing)
    }

    @Test fun carControlsSendFullPlayingInfoAndRetainPauseFormat() {
        val player = browserAfterLiveExit()
        val target = ReflectionHelpers.getField<CarPlayController>(CarPlayVideo, "controller")
        `when`(target.sendVideoMessage(8, VideoInCar.playbackStateNotification(false, "browser"))).thenReturn(true)
        `when`(target.sendVideoMessage(8, VideoInCar.playbackStateNotification(true, "browser", player.state()))).thenReturn(true)
        CarPlayVideo.setPlaying(false)
        CarPlayVideo.setPlaying(true)
        val notifications = stateNotifications(target, 8)
        assertEquals(listOf("paused", "playing"), notifications.map { it["name"] })
        assertFalse(notifications.first().containsKey("item"))
        assertEquals("browser", (notifications.last()["item"] as Map<*, *>)["uuid"])
        assertNotNull(notifications.last()["params"])
        assertTrue(report.any { it.contains("player state requested stream=8 playing=false") })
        assertTrue(report.any { it.contains("playback state notification sent=true stream=8 state=paused") })
        report.filter { it.contains("playback state notification") }.forEach {
            assertEquals(it, DiagnosticRedactor.redact(it))
        }
        for (line in listOf("video event write_begin cseq=3 stream=8 op=playbackState state=paused",
            "video event write_complete cseq=3 stream=8", "video event response cseq=3 stream=8 op=playbackState state=paused status=200")) {
            assertEquals(line, DiagnosticRedactor.redact(line))
        }
    }

    @Test fun failedStateSyncIsLoggedWithoutStoppingLocalPlayback() {
        browserAfterLiveExit()
        val target = ReflectionHelpers.getField<CarPlayController>(CarPlayVideo, "controller")
        send(8, mapOf("type" to "stop"))
        rejectedLiveItem()
        stateNotifications(target, 8)
        assertTrue(CarPlayVideo.playing)
        assertTrue(report.any { it.contains("playback state notification sent=false stream=8 state=playing") })
    }

    @Test fun acceptedQueueSelectionIsAcknowledgedBeforeActualPlaying() {
        val player = openMockLivePlayer()
        `when`(player.ownsCurrentItem()).thenReturn(true)
        val target = ReflectionHelpers.getField<CarPlayController>(CarPlayVideo, "controller")
        CarPlayVideo.onPlayerPlaybackStateChanged(player, CarPlayVideo.playbackGeneration)
        val messages = videoMessages(target, 3)
        assertEquals("currentItemChanged", messages.first()["name"])
        assertEquals(mapOf("uuid" to "first"), messages.first()["itemCurrent"])
        assertFalse(messages.first().containsKey("itemBefore"))
        assertEquals("playing", messages.last()["name"])
    }

    @Test fun removedRemoteItemDoesNotExposeOrControlTheRetainedLivePlayer() {
        val player = openMockLivePlayer()
        `when`(player.ownsCurrentItem()).thenReturn(true)
        val target = ReflectionHelpers.getField<CarPlayController>(CarPlayVideo, "controller")
        videoMessages(target, 3)
        org.mockito.Mockito.clearInvocations(target)
        message(mapOf("type" to "removePlayQueueItem", "item" to mapOf("uuid" to "first")))
        message(mapOf("type" to "playbackInfo", "kind" to "request", "messageID" to 1L))
        CarPlayVideo.onPlayerPlaybackStateChanged(player, CarPlayVideo.playbackGeneration)
        val messages = videoMessages(target, 3)
        assertEquals(listOf("itemRemoved", "currentItemChanged"), messages.map { it["name"] })
        assertFalse(messages.last().containsKey("itemCurrent"))
        assertEquals(mapOf("uuid" to "first"), messages.last()["itemBefore"])
        assertEquals("https://example.com/first.mp4", CarPlayVideo.url)
        assertTrue(CarPlayVideo.playing)
        verify(player, never()).finish()
    }

    @Test fun lateOldStreamRemovalAcknowledgesOnlyThatStream() {
        val player = openMockLivePlayer()
        `when`(player.ownsCurrentItem()).thenReturn(true)
        val target = ReflectionHelpers.getField<CarPlayController>(CarPlayVideo, "controller")
        send(8, mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
            "uuid" to "browser", "Content-Location" to "https://example.com/browser.mp4")))
        send(8, mapOf("type" to "setRate", "rate" to 1.0))
        videoMessages(target, 3)
        org.mockito.Mockito.clearInvocations(target)
        message(mapOf("type" to "removePlayQueueItem", "item" to mapOf("uuid" to "first")))
        assertEquals(listOf("itemRemoved", "currentItemChanged"), videoMessages(target, 3).map { it["name"] })
        assertTrue(videoMessages(target, 8).isEmpty())
        assertEquals("https://example.com/browser.mp4", CarPlayVideo.url)
        assertTrue(CarPlayVideo.playing)
        verify(player, never()).finish()
    }

    @Test fun aBlockedLiveSourceIsNeverAcknowledgedAsSelected() {
        browserAfterLiveExit()
        val target = ReflectionHelpers.getField<CarPlayController>(CarPlayVideo, "controller")
        rejectedLiveItem()
        assertTrue(videoMessages(target, 9).none { it["name"] == "currentItemChanged" })
        assertEquals("https://example.com/browser.mp4", CarPlayVideo.url)
    }

    @Test fun handoffAfterPhoneStopInterruptsTheOldOwnerWithoutEchoingPause() {
        openMockLivePlayer()
        val target = ReflectionHelpers.getField<CarPlayController>(CarPlayVideo, "controller")
        message(mapOf("type" to "stop"))
        assertTrue(stateNotifications(target, 3).isEmpty())
        send(8, mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
            "uuid" to "browser", "Content-Location" to "https://example.com/browser.mp4")))
        val states = stateNotifications(target, 3)
        assertEquals(1, states.size)
        assertEquals("stopped", states.single()["name"])
        assertEquals("interrupted", states.single()["reason"])
    }

    @Test fun teardownRetiresRemoteControlsWithoutClosingTheRetainedPlayer() {
        val player = openMockLivePlayer()
        `when`(player.ownsCurrentItem()).thenReturn(true)
        val target = ReflectionHelpers.getField<CarPlayController>(CarPlayVideo, "controller")
        videoMessages(target, 3)
        org.mockito.Mockito.clearInvocations(target)
        CarPlayVideo.onVideoStreamEnded(3)
        idle()
        CarPlayVideo.setPlaying(false)
        CarPlayVideo.setPlaying(true)
        CarPlayVideo.onPlayerPlaybackStateChanged(player, CarPlayVideo.playbackGeneration)
        assertTrue(videoMessages(target, 3).isEmpty())
        assertEquals("https://example.com/first.mp4", CarPlayVideo.url)
        verify(player, never()).finish()
        send(8, mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
            "uuid" to "reattached", "Content-Location" to "https://example.com/first.mp4")))
        assertTrue(stateNotifications(target, 3).isEmpty())
        assertEquals("currentItemChanged", videoMessages(target, 8).single()["name"])
    }

    @Test fun queuedQueueAcknowledgementsCannotLeakIntoANewSession() {
        openMockLivePlayer()
        val target = ReflectionHelpers.getField<CarPlayController>(CarPlayVideo, "controller")
        videoMessages(target, 3)
        org.mockito.Mockito.clearInvocations(target)
        val release = holdVideoReplies()
        try {
            send(8, mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
                "uuid" to "browser", "Content-Location" to "https://example.com/browser.mp4")))
            CarPlayVideo.onVideoSessionEnded()
            idle()
        } finally {
            release.countDown()
        }
        assertTrue(videoMessages(target, 8).isEmpty())
        assertTrue(stateNotifications(target, 3).isEmpty())
    }

    @Test fun removingAnUnknownItemDoesNotClearTheCurrentSelection() {
        val player = openMockLivePlayer()
        `when`(player.ownsCurrentItem()).thenReturn(true)
        val target = ReflectionHelpers.getField<CarPlayController>(CarPlayVideo, "controller")
        videoMessages(target, 3)
        org.mockito.Mockito.clearInvocations(target)
        message(mapOf("type" to "removePlayQueueItem", "item" to mapOf("uuid" to "unknown")))
        assertTrue(videoMessages(target, 3).isEmpty())
        CarPlayVideo.onPlayerPlaybackStateChanged(player, CarPlayVideo.playbackGeneration)
        assertEquals("playing", stateNotifications(target, 3).single()["name"])
    }

    private fun videoMessages(target: CarPlayController, stream: Long): List<Map<*, *>> {
        drainVideoReplies()
        return org.mockito.Mockito.mockingDetails(target).invocations.filter {
            it.method.name == "sendVideoMessage" && it.arguments[0] == stream
        }.map { it.arguments[1] as Map<*, *> }
    }

    @Test fun stateAdapterKeepsMedia3PlaybackIntentAndLiveWindowSeparate() {
        val activity = Robolectric.buildActivity(CarPlayVideoActivity::class.java).get()
        val player = mock(androidx.media3.exoplayer.ExoPlayer::class.java)
        ReflectionHelpers.setField(activity, "player", player)
        ReflectionHelpers.setField(activity, "prepared", true)
        `when`(player.duration).thenReturn(40_000L)
        `when`(player.currentPosition).thenReturn(28_000L)
        `when`(player.bufferedPosition).thenReturn(28_000L)
        `when`(player.isCurrentMediaItemLive).thenReturn(true)
        `when`(player.playWhenReady).thenReturn(true)
        `when`(player.playbackState).thenReturn(androidx.media3.common.Player.STATE_BUFFERING)
        val state = activity.state()
        assertTrue(state.durationSeconds.isNaN())
        assertEquals(40.0, state.seekableDurationSeconds, 0.0)
        assertTrue(state.playWhenReady)
        assertTrue(state.buffering)
        assertFalse(state.playing)
        val info = VideoInCar.playbackInfoResponse(1L, "live", state)["info"] as Map<*, *>
        assertEquals(1.0, info["rate"])
        assertEquals("loading", info["playbackState"])
    }

    @Test fun playerTransitionsReportLoadingThenFullPlayingWithoutCarInput() {
        val player = openMockLivePlayer()
        `when`(player.ownsCurrentItem()).thenReturn(true)
        val state = VideoInCar.PlayerState(true, false, 12.0, 90.0, 12.0,
            playWhenReady = true, buffering = true)
        `when`(player.state()).thenReturn(state)
        val target = ReflectionHelpers.getField<CarPlayController>(CarPlayVideo, "controller")
        CarPlayVideo.onPlayerPlaybackStateChanged(player, CarPlayVideo.playbackGeneration)
        `when`(player.state()).thenReturn(state.copy(playing = true, buffering = false, bufferedSeconds = 24.0))
        CarPlayVideo.onPlayerPlaybackStateChanged(player, CarPlayVideo.playbackGeneration)
        CarPlayVideo.onPlayerPlaybackStateChanged(player, CarPlayVideo.playbackGeneration)
        val notifications = stateNotifications(target, 3)
        assertEquals(listOf("loading", "playing"), notifications.map { it["name"] })
        val params = notifications.last()["params"] as Map<*, *>
        assertEquals(1.0, params["rate"])
        assertEquals(VideoInCar.cmTime(12.0), params["position"])
        assertTrue(report.any { it.contains("player lifecycle state=playing stream=3") })
    }

    @Test fun unchangedPlaybackReportsAgainOnANewControlStreamWithoutReloading() {
        val player = openMockLivePlayer()
        `when`(player.ownsCurrentItem()).thenReturn(true)
        `when`(player.state()).thenReturn(VideoInCar.PlayerState(true, true, 12.0, 90.0, 24.0))
        val target = ReflectionHelpers.getField<CarPlayController>(CarPlayVideo, "controller")
        val generation = CarPlayVideo.playbackGeneration
        CarPlayVideo.onPlayerPlaybackStateChanged(player, generation)
        stateNotifications(target, 3)
        org.mockito.Mockito.clearInvocations(target, player)
        send(8, mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
            "uuid" to "reattached", "Content-Location" to "https://example.com/first.mp4")))
        send(8, mapOf("type" to "setRate", "rate" to 1.0))
        assertEquals(generation, CarPlayVideo.playbackGeneration)
        verify(player, never()).load()
        val message = stateNotifications(target, 8).single()
        assertEquals("playing", message["name"])
        assertEquals(mapOf("uuid" to "reattached"), message["item"])
        assertNotNull(message["params"])
        assertEquals(listOf("stopped"), stateNotifications(target, 3).map { it["name"] })
    }

    @Test fun stalePlayerCallbacksCannotReportForANewerSource() {
        val player = openMockLivePlayer()
        `when`(player.ownsCurrentItem()).thenReturn(true)
        `when`(player.state()).thenReturn(VideoInCar.PlayerState(true, true, 12.0, 90.0, 24.0))
        val generation = CarPlayVideo.playbackGeneration
        val target = ReflectionHelpers.getField<CarPlayController>(CarPlayVideo, "controller")
        send(8, mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
            "uuid" to "new", "Content-Location" to "https://example.com/new.mp4")))
        CarPlayVideo.onPlayerPlaybackStateChanged(player, generation)
        CarPlayVideo.onPlayerPlaybackStateChanged(mock(CarPlayVideoActivity::class.java), CarPlayVideo.playbackGeneration)
        assertTrue(stateNotifications(target, 8).isEmpty())
    }

    @Test fun queuedActualPlayingCannotOverrideALaterPhonePause() {
        val player = openMockLivePlayer()
        `when`(player.ownsCurrentItem()).thenReturn(true)
        `when`(player.state()).thenReturn(VideoInCar.PlayerState(true, true, 12.0, 90.0, 24.0))
        val target = ReflectionHelpers.getField<CarPlayController>(CarPlayVideo, "controller")
        val release = holdVideoReplies()
        try {
            CarPlayVideo.onPlayerPlaybackStateChanged(player, CarPlayVideo.playbackGeneration)
            send(3, mapOf("type" to "setRate", "rate" to 0.0))
        } finally {
            release.countDown()
        }
        assertTrue(stateNotifications(target, 3).isEmpty())
        assertFalse(CarPlayVideo.playing)
    }

    private fun stateNotifications(target: CarPlayController, stream: Long): List<Map<*, *>> {
        drainVideoReplies()
        return org.mockito.Mockito.mockingDetails(target).invocations.filter {
            it.method.name == "sendVideoMessage" && it.arguments[0] == stream
        }.map { it.arguments[1] as Map<*, *> }.filter { it["type"] == "playbackState" }
    }

    private fun holdVideoReplies(): java.util.concurrent.CountDownLatch {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(CarPlayVideo, "sender").execute {
            started.countDown()
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
        }
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
        return release
    }

    private fun browserAfterLiveExit(): CarPlayVideoActivity {
        val player = openMockLivePlayer()
        `when`(player.ownsCurrentItem()).thenReturn(true)
        `when`(player.state()).thenReturn(VideoInCar.PlayerState(true, true, 12.0, 88.0, 20.0))
        ReflectionHelpers.setField(CarPlayVideo, "itemApp", "example.live")
        send(8, mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
            "uuid" to "browser", "clientBundleID" to "example.browser", "Content-Location" to "https://example.com/browser.mp4")))
        `when`(player.isLiveItem()).thenReturn(false)
        send(8, mapOf("type" to "setRate", "rate" to 1.0))
        drainVideoReplies()
        org.mockito.Mockito.clearInvocations(ReflectionHelpers.getField<CarPlayController>(CarPlayVideo, "controller"))
        return player
    }

    private fun rejectedLiveItem() = send(9, mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
        "uuid" to "rejected-live", "clientBundleID" to "example.live", "Content-Location" to "https://example.com/first.mp4")))

    private fun send(stream: Long, value: Map<String, Any?>) { CarPlayVideo.onVideoMessage(stream, value); idle() }

    private fun drainVideoReplies() {
        ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(CarPlayVideo, "sender")
            .submit {}.get(2, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun openMockLivePlayer(): CarPlayVideoActivity {
        play()
        shadowOf(host).nextStartedActivity
        return mock(CarPlayVideoActivity::class.java).also {
            `when`(it.isLiveItem()).thenReturn(true)
            `when`(it.state()).thenAnswer {
                VideoInCar.PlayerState(true, CarPlayVideo.playing, 12.0, Double.NaN, 20.0,
                    seekableDurationSeconds = 40.0)
            }
            CarPlayVideo.activity = it
        }
    }

    private fun play() { queue(); rate() }
    private fun tapCarPlay() {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, down, action, 10f, 10f, 0)
            try { CarPlayVideo.onCarPlayTouch(event, queued = true) } finally { event.recycle() }
        }
    }
    private fun queue(id: String = "first") = message(mapOf(
        "type" to "insertPlayQueueItem",
        "item" to mapOf("uuid" to id, "Content-Location" to "https://example.com/$id.mp4"),
    ))
    private fun rate() = message(mapOf("type" to "setRate", "rate" to 1.0))
    private fun message(message: Map<String, Any?>) { CarPlayVideo.onVideoMessage(3, message); idle() }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
