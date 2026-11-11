package com.shilapi.xcertplay

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.shilapi.xcertplay.airplay.VideoInCar
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.doAnswer
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyMap
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

    @Before fun setUp() {
        CarPlayVideo.activity = null
        CarPlayVideo.onVideoSessionEnded()
        idle()
        CarPlayVideo.attach(RuntimeEnvironment.getApplication(), mock(CarPlayController::class.java))
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

    @Test fun earlyUiRequestOpensOnceTheFirstItemArrivesWithoutAnotherTap() {
        CarPlayVideo.onVideoUiRequested()
        idle()
        assertNull(shadowOf(host).nextStartedActivity)
        queue()
        assertEquals(CarPlayVideoActivity::class.java.name, shadowOf(host).nextStartedActivity.component?.className)
    }

    @Test fun earlyPlayAndUiRequestsAreRememberedWithoutDuplicateLaunches() {
        CarPlayVideo.onVideoUiRequested()
        idle()
        rate()
        assertNull(shadowOf(host).nextStartedActivity)
        queue()
        assertTrue(CarPlayVideo.playing)
        assertEquals(CarPlayVideoActivity::class.java.name, shadowOf(host).nextStartedActivity.component?.className)
        rate()
        assertNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun playForANewStreamWaitsForItsItemInsteadOfOpeningThePreviousItem() {
        queue()
        CarPlayVideo.onVideoMessage(4, mapOf("type" to "setRate", "rate" to 1.0))
        idle()
        assertNull(shadowOf(host).nextStartedActivity)
        // Late commands for the old stream must not erase the new stream's pending intent.
        queue()
        message(mapOf("type" to "setRate", "rate" to 0.0))
        assertNull(shadowOf(host).nextStartedActivity)
        CarPlayVideo.onVideoMessage(4, mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
            "uuid" to "second", "Content-Location" to "https://example.com/second.mp4")))
        idle()
        assertTrue(CarPlayVideo.playing)
        assertEquals("https://example.com/second.mp4", CarPlayVideo.url)
        assertEquals(CarPlayVideoActivity::class.java.name, shadowOf(host).nextStartedActivity.component?.className)
    }

    @Test fun staleOrCancelledEarlyRequestsCannotOpenALaterItem() {
        for (cancel in listOf<() -> Unit>(
            { message(mapOf("type" to "stop")) },
            { CarPlayVideo.dismissPlayer(0) },
            { org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(31)) },
            { CarPlayVideo.onVideoSessionEnded(); idle() },
        )) {
            CarPlayVideo.onVideoSessionEnded(); idle()
            CarPlayVideo.onVideoUiRequested(); idle()
            cancel()
            queue()
            assertNull(shadowOf(host).nextStartedActivity)
        }
    }

    @Test fun largeVideoOptInReflectsThePersistedSettingWithoutReconnect() {
        val context = RuntimeEnvironment.getApplication()
        for (enabled in listOf(false, true, false)) {
            AirPlayPersistence.saveVideoUseIphoneNetwork(context, enabled)
            assertEquals(enabled, CarPlayVideo.usesIphoneNetworkForVideo())
        }
    }

    @Test fun sameTaskLauncherHostAlsoOwnsThePlayerLaunch() {
        host = Robolectric.buildActivity(SameTaskCarPlayHostActivity::class.java).get()
        ReflectionHelpers.setField(CarPlayScreen, "open", WeakReference(host))
        play()
        val started = shadowOf(host).nextStartedActivity
        assertEquals(CarPlayVideoActivity::class.java.name, started.component?.className)
        assertEquals(0, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test fun returningPausesAtTheSavedPositionAndAllowsTheSameItemToResume() {
        play()
        shadowOf(host).nextStartedActivity
        CarPlayVideo.dismissPlayer(12_345)
        assertFalse(CarPlayVideo.playing)
        assertFalse(CarPlayVideo.attachPlayer(mock(CarPlayVideoActivity::class.java)))
        assertEquals(12_345, CarPlayVideo.startMillis)
        assertEquals("https://example.com/first.mp4", CarPlayVideo.url)
        assertNull(shadowOf(host).nextStartedActivity)
        rate()
        assertTrue(CarPlayVideo.playing)
        assertEquals(12_345, CarPlayVideo.startMillis)
        assertNotNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun videoUiRequestReopensThePausedItemWithoutRequiringNewMetadata() {
        play()
        shadowOf(host).nextStartedActivity
        CarPlayVideo.dismissPlayer(12_345)
        assertFalse(CarPlayVideo.attachPlayer(mock(CarPlayVideoActivity::class.java)))
        CarPlayVideo.onVideoUiRequested()
        idle()
        assertEquals(12_345, CarPlayVideo.startMillis)
        assertEquals("https://example.com/first.mp4", CarPlayVideo.url)
        assertFalse(CarPlayVideo.playing)
        assertEquals(CarPlayVideoActivity::class.java.name, shadowOf(host).nextStartedActivity.component?.className)
    }

    @Test fun choosingANewItemCanPlayAfterDismissal() {
        play()
        shadowOf(host).nextStartedActivity
        CarPlayVideo.dismissPlayer(12_345)
        assertFalse(CarPlayVideo.attachPlayer(mock(CarPlayVideoActivity::class.java)))
        CarPlayVideo.onVideoUiRequested()
        idle()
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
        assertEquals("https://example.com/first.mp4", CarPlayVideo.url)
        rate()
        assertTrue(CarPlayVideo.playing)
        assertNotNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun naturalCompletionReturnsToCarPlayAndAllowsTheSameVideoToBeQueuedAgain() {
        play()
        shadowOf(host).nextStartedActivity
        val screen = Robolectric.buildActivity(CarPlayVideoActivity::class.java).get()
        ReflectionHelpers.setField(screen, "player", mock(androidx.media3.exoplayer.ExoPlayer::class.java))
        ReflectionHelpers.setField(screen, "controls", View(screen).apply { visibility = View.GONE })
        ReflectionHelpers.setField(screen, "loadedUrl", CarPlayVideo.url)
        CarPlayVideo.activity = screen
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "onPlaybackCompleted")
        assertTrue(screen.isFinishing)
        assertFalse(CarPlayVideo.playing)
        assertNull(CarPlayVideo.url)
        assertEquals(host.javaClass.name, shadowOf(screen).nextStartedActivity.component?.className)
        CarPlayVideo.activity = null
        rate()
        assertNull(shadowOf(host).nextStartedActivity)
        CarPlayVideo.onVideoUiRequested()
        idle()
        queue()
        rate()
        assertEquals(CarPlayVideoActivity::class.java.name, shadowOf(host).nextStartedActivity.component?.className)
    }

    @Test fun stoppingOrReplacingAnItemReleasesPendingIphoneLoadsWithoutWaitingForTimeout() {
        for (stop in listOf<() -> Unit>(
            { message(mapOf("type" to "stop")) },
            { queue("second") },
            { CarPlayVideo.dismissPlayer(0) },
            { CarPlayVideo.onVideoSessionEnded(); idle() },
            { CarPlayVideo.onVideoAllowedChanged(false); idle() },
        )) {
            CarPlayVideo.onVideoUiRequested()
            idle()
            queue()
            val sent = CountDownLatch(1)
            val controller = mock(CarPlayController::class.java)
            doAnswer { sent.countDown(); true }.`when`(controller).sendVideoMessage(anyLong(), anyMap())
            ReflectionHelpers.setField(CarPlayVideo, "controller", controller)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit<CarPlayVideo.LoadedUrl?> { CarPlayVideo.resolveOnIphone("http://example.com/live") }
                assertTrue(sent.await(2, TimeUnit.SECONDS))
                stop()
                assertNull(result.get(2, TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun waitingLoadsAreCancelledOnEveryPlaybackBoundaryAndNeverAdoptTheReplacementItem() {
        AirPlayPersistence.saveVideoUseIphoneNetwork(RuntimeEnvironment.getApplication(), true)
        try {
            for (stop in listOf<() -> Unit>(
                { message(mapOf("type" to "stop")) },
                { queue("replacement") },
                { CarPlayVideo.dismissPlayer(0) },
                { CarPlayVideo.onPlaybackCompleted() },
                { CarPlayVideo.onVideoSessionEnded(); idle() },
            )) {
                CarPlayVideo.onVideoUiRequested(); idle(); queue()
                val sent = CountDownLatch(4)
                val controller = mock(CarPlayController::class.java)
                val sends = java.util.concurrent.atomic.AtomicInteger()
                doAnswer {
                    val message = it.getArgument<Map<String, Any?>>(1)
                    if (message["type"] == "unhandledURL") { sends.incrementAndGet(); sent.countDown() }
                    true
                }.`when`(controller)
                    .sendVideoMessage(anyLong(), anyMap())
                ReflectionHelpers.setField(CarPlayVideo, "controller", controller)
                val pool = Executors.newFixedThreadPool(5)
                try {
                    val active = List(4) { pool.submit<CarPlayVideo.LoadedUrl?> {
                        CarPlayVideo.resolveOnIphone("https://example.com/segment", CarPlayVideo.playbackGeneration, relay = true)
                    } }
                    assertTrue(sent.await(2, TimeUnit.SECONDS))
                    val waiting = pool.submit<CarPlayVideo.LoadedUrl?> {
                        CarPlayVideo.resolveOnIphone("https://example.com/waiting", CarPlayVideo.playbackGeneration, relay = true)
                    }
                    val gate: Any = ReflectionHelpers.getField(CarPlayVideo, "urlAdmission")
                    val lock: Any = ReflectionHelpers.getField(gate, "lock")
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                    fun queued(): Int = synchronized(lock) {
                        ReflectionHelpers.getField<Collection<*>>(gate, "waiting").size
                    }
                    while (queued() == 0 && System.nanoTime() < deadline) Thread.yield()
                    assertEquals(1, queued())
                    stop()
                    active.forEach { assertNull(it.get(2, TimeUnit.SECONDS)) }
                    assertNull(waiting.get(2, TimeUnit.SECONDS))
                    assertEquals(4, sends.get())
                } finally { pool.shutdownNow() }
            }
        } finally {
            AirPlayPersistence.saveVideoUseIphoneNetwork(RuntimeEnvironment.getApplication(), false)
        }
    }

    @Test fun cancelledUrlWorkIsRemovedEvenWhenTheControlWriterIsBlocked() {
        AirPlayPersistence.saveVideoUseIphoneNetwork(RuntimeEnvironment.getApplication(), true)
        queue()
        val writerEntered = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        val controller = mock(CarPlayController::class.java)
        doAnswer { writerEntered.countDown(); releaseWriter.await(3, TimeUnit.SECONDS); true }.`when`(controller)
            .sendVideoMessage(anyLong(), anyMap())
        ReflectionHelpers.setField(CarPlayVideo, "controller", controller)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val loads = List(4) { pool.submit<CarPlayVideo.LoadedUrl?> {
                CarPlayVideo.resolveOnIphone("https://example.com/segment", CarPlayVideo.playbackGeneration, relay = true)
            } }
            assertTrue(writerEntered.await(2, TimeUnit.SECONDS))
            val sender: java.util.concurrent.ThreadPoolExecutor = ReflectionHelpers.getField(CarPlayVideo, "sender")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (sender.queue.size < 3 && System.nanoTime() < deadline) Thread.yield()
            assertEquals(3, sender.queue.size)
            message(mapOf("type" to "stop"))
            loads.forEach { assertNull(it.get(2, TimeUnit.SECONDS)) }
            assertEquals(0, sender.queue.size)
        } finally {
            releaseWriter.countDown()
            pool.shutdownNow()
            AirPlayPersistence.saveVideoUseIphoneNetwork(RuntimeEnvironment.getApplication(), false)
        }
    }

    @Test fun legacyAppSchemeLoadsBypassRelayAdmissionAndKeepTheShorterTimeout() {
        queue()
        val sent = CountDownLatch(5)
        val controller = mock(CarPlayController::class.java)
        doAnswer { sent.countDown(); true }.`when`(controller).sendVideoMessage(anyLong(), anyMap())
        ReflectionHelpers.setField(CarPlayVideo, "controller", controller)
        val pool = Executors.newFixedThreadPool(5)
        try {
            val loads = List(5) { pool.submit<CarPlayVideo.LoadedUrl?> {
                CarPlayVideo.resolveOnIphone("appvideo://segment")
            } }
            assertTrue("Legacy requests must all reach the iPhone", sent.await(3, TimeUnit.SECONDS))
            loads.forEach { assertNull(it.get(15, TimeUnit.SECONDS)) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun rejectedResourcesWakeOnlyTheMatchingStreamWithoutWaitingForTimeout() {
        queue()
        val sent = CountDownLatch(1)
        val controller = mock(CarPlayController::class.java)
        doAnswer { sent.countDown(); true }.`when`(controller).sendVideoMessage(anyLong(), anyMap())
        ReflectionHelpers.setField(CarPlayVideo, "controller", controller)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<CarPlayVideo.LoadedUrl?> { CarPlayVideo.resolveOnIphone("http://example.com/live") }
            assertTrue(sent.await(2, TimeUnit.SECONDS))
            CarPlayVideo.onVideoResourceRejected(4, "memory_budget", 21_558_983)
            assertFalse(result.isDone)
            CarPlayVideo.onVideoResourceRejected(3, "memory_budget", 21_558_983)
            val failure = assertThrows(java.util.concurrent.ExecutionException::class.java) {
                result.get(2, TimeUnit.SECONDS)
            }
            assertTrue(failure.cause is IphoneVideoResourceRejectedException)
            assertEquals("memory_budget", (failure.cause as IphoneVideoResourceRejectedException).reason)
        } finally { executor.shutdownNow() }
    }

    @Test fun repeatedQueueMessageKeepsPendingLoadAndIphoneResponsePreservesDataAndHeaders() {
        queue()
        val sent = CountDownLatch(1)
        val request = AtomicReference<Map<String, Any?>>()
        val requestStream = java.util.concurrent.atomic.AtomicLong()
        val controller = mock(CarPlayController::class.java)
        doAnswer { call ->
            request.set(call.getArgument(1))
            requestStream.set(call.getArgument(0))
            sent.countDown()
            true
        }.`when`(controller).sendVideoMessage(anyLong(), anyMap())
        ReflectionHelpers.setField(CarPlayVideo, "controller", controller)
        // Overlay commands are on another negotiated stream. They must not retarget the item's loads.
        CarPlayVideo.onVideoMessage(4, mapOf("type" to "property"))
        idle()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<CarPlayVideo.LoadedUrl?> { CarPlayVideo.resolveOnIphone("http://example.com/live") }
            assertTrue(sent.await(2, TimeUnit.SECONDS))
            assertEquals(3L, requestStream.get())
            queue()
            assertFalse(result.isDone)
            val id = (request.get()["request"] as Map<*, *>)["FCUP_Response_RequestID"]
            val requestId = (id as Number).toLong()
            assertFalse(CarPlayVideo.claimVideoResourceResponse(4, requestId))
            assertTrue(CarPlayVideo.claimVideoResourceResponse(3, requestId))
            assertFalse(CarPlayVideo.claimVideoResourceResponse(3, requestId))
            val wrongStreamFile = com.shilapi.xcertplay.airplay.VideoResourceStore
                .shared(RuntimeEnvironment.getApplication().cacheDir).save(byteArrayOf(1, 2, 3))
            CarPlayVideo.onVideoMessage(4, mapOf("type" to "unhandledURL", "response" to mapOf(
                "FCUP_Response_RequestID" to id, "FCUP_Response_Data" to wrongStreamFile)))
            idle()
            assertFalse(wrongStreamFile.file.exists())
            assertFalse(result.isDone)
            // No main-loop idle: a busy UI must not retain or delay the resource response.
            CarPlayVideo.onVideoMessage(3, mapOf("type" to "unhandledURL", "kind" to "response", "response" to mapOf(
                "FCUP_Response_RequestID" to id,
                "FCUP_Response_StatusCode" to 200,
                "FCUP_Response_Data" to byteArrayOf(7, 8),
                "FCUP_Response_Headers" to mapOf("Content-Type" to "video/mp2t"),
            )))
            val loaded = result.get(2, TimeUnit.SECONDS)!!
            assertFalse(CarPlayVideo.claimVideoResourceResponse(3, requestId))
            assertArrayEquals(byteArrayOf(7, 8), loaded.data)
            assertEquals(listOf("video/mp2t"), loaded.headers["Content-Type"])
            CarPlayVideo.onVideoSessionEnded()
            idle()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun queuedOldUiAndMediaCommandsCannotSurviveReturning() {
        play()
        shadowOf(host).nextStartedActivity
        // These callbacks have arrived, but have not run on the main thread yet.
        CarPlayVideo.onVideoUiRequested()
        CarPlayVideo.onVideoMessage(3, mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
            "uuid" to "old-late", "Content-Location" to "https://example.com/old.mp4")))
        CarPlayVideo.dismissPlayer(0)
        idle()
        assertEquals("https://example.com/first.mp4", CarPlayVideo.url)
        assertFalse(CarPlayVideo.playing)
        assertNull(shadowOf(host).nextStartedActivity)
        rate()
        assertNotNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun anOldActivityCannotAttachToTheNextPlaybackOrClearItsOpeningState() {
        play()
        val old = shadowOf(host).nextStartedActivity
        CarPlayVideo.dismissPlayer(0)
        CarPlayVideo.onVideoUiRequested(); idle(); queue(); rate()
        val next = shadowOf(host).nextStartedActivity
        val stale = Robolectric.buildActivity(CarPlayVideoActivity::class.java, old).get()
        assertFalse(CarPlayVideo.attachPlayer(stale))
        CarPlayVideo.onVideoUiRequested(); idle()
        assertNull(shadowOf(host).nextStartedActivity)
        val current = preparedScreen(next)
        assertTrue(CarPlayVideo.attachPlayer(current))
        assertFalse(CarPlayVideo.attachPlayer(preparedScreen(next)))
        assertSame(current, CarPlayVideo.activity)
        CarPlayVideo.dismissPlayer(0)
        assertTrue(current.playbackReleased)
    }

    @Test fun aNewRequestWaitsUntilTheClosingPlayerHasReleasedAndDetached() {
        play()
        val first = preparedScreen(shadowOf(host).nextStartedActivity)
        val engine = ReflectionHelpers.getField<androidx.media3.exoplayer.ExoPlayer>(first, "player")
        assertTrue(CarPlayVideo.attachPlayer(first))
        CarPlayVideo.dismissPlayer(0)
        assertTrue(first.playbackReleased)
        org.mockito.Mockito.verify(engine).release()
        CarPlayVideo.onVideoUiRequested(); idle(); queue(); rate()
        assertNull(shadowOf(host).nextStartedActivity)
        CarPlayVideo.detachPlayer(first, false)
        val second = preparedScreen(shadowOf(host).nextStartedActivity)
        assertTrue(CarPlayVideo.attachPlayer(second))
        first.closePlayback() // Idempotent cleanup; never double-release the old engine.
        org.mockito.Mockito.verify(engine, org.mockito.Mockito.times(1)).release()
        CarPlayVideo.detachPlayer(first, false)
        assertSame(second, CarPlayVideo.activity)
        CarPlayVideo.dismissPlayer(0)
    }

    @Test fun removingAnotherItemOrStreamCannotStopTheCurrentPlayback() {
        queue("second")
        message(mapOf("type" to "removePlayQueueItem", "item" to mapOf("uuid" to "first")))
        message(mapOf("type" to "stop", "uuid" to "first"))
        CarPlayVideo.onVideoMessage(4, mapOf("type" to "stop")); idle()
        message(mapOf("type" to "removePlayQueueItem"))
        assertEquals("https://example.com/second.mp4", CarPlayVideo.url)
        message(mapOf("type" to "removePlayQueueItem", "item" to mapOf("uuid" to "second")))
        assertNull(CarPlayVideo.url)
    }

    @Test fun aTimedOutOldLaunchCannotAttachWhenAnExplicitRetryHasStarted() {
        play()
        val old = shadowOf(host).nextStartedActivity
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(6))
        CarPlayVideo.onVideoUiRequested(); idle()
        val retry = shadowOf(host).nextStartedActivity
        assertNotNull(retry)
        assertFalse(CarPlayVideo.attachPlayer(preparedScreen(old)))
        assertTrue(CarPlayVideo.attachPlayer(preparedScreen(retry)))
        CarPlayVideo.dismissPlayer(0)
    }

    @Test fun returningCancelsPendingLoadsAndClosesLateFilesEvenWhenTheSameItemIsReopened() {
        queue()
        val generation = CarPlayVideo.playbackGeneration
        val sent = CountDownLatch(1)
        val id = java.util.concurrent.atomic.AtomicLong()
        val controller = mock(CarPlayController::class.java)
        doAnswer { call ->
            val request = (call.getArgument<Map<String, Any?>>(1)["request"] as? Map<*, *>)
            (request?.get("FCUP_Response_RequestID") as? Number)?.let { id.set(it.toLong()); sent.countDown() }
            true
        }.`when`(controller).sendVideoMessage(anyLong(), anyMap())
        ReflectionHelpers.setField(CarPlayVideo, "controller", controller)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<CarPlayVideo.LoadedUrl?> {
                CarPlayVideo.resolveOnIphone("https://example.com/segment", generation)
            }
            assertTrue(sent.await(2, TimeUnit.SECONDS))
            CarPlayVideo.dismissPlayer(0)
            assertNull(result.get(2, TimeUnit.SECONDS))
            CarPlayVideo.onVideoUiRequested(); idle(); queue()
            assertNull(CarPlayVideo.resolveOnIphone("https://example.com/old-segment", generation))
            val file = com.shilapi.xcertplay.airplay.VideoResourceStore
                .shared(RuntimeEnvironment.getApplication().cacheDir).save(ByteArray(300_000))
            CarPlayVideo.onVideoMessage(3, mapOf("type" to "unhandledURL", "response" to mapOf(
                "FCUP_Response_RequestID" to id.get(), "FCUP_Response_Data" to file)))
            assertFalse(file.file.exists())
            assertFalse(CarPlayVideo.claimVideoResourceResponse(3, id.get()))
        } finally { executor.shutdownNow() }
    }

    @Test fun automaticLaunchRetriesAreBoundedAndReturningCancelsThem() {
        play()
        shadowOf(host).nextStartedActivity
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(6))
        assertNotNull(shadowOf(host).nextStartedActivity)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(6))
        assertNull(shadowOf(host).nextStartedActivity)
        CarPlayVideo.onVideoUiRequested(); idle()
        assertNotNull(shadowOf(host).nextStartedActivity)
        CarPlayVideo.dismissPlayer(0)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(6))
        assertNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun anOldStopQueuedBeforeReturnCannotCancelTheNextExplicitRequest() {
        play()
        shadowOf(host).nextStartedActivity
        CarPlayVideo.onVideoMessage(3, mapOf("type" to "stop"))
        CarPlayVideo.dismissPlayer(0)
        CarPlayVideo.onVideoUiRequested()
        CarPlayVideo.onVideoMessage(3, mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
            "uuid" to "first", "Content-Location" to "https://example.com/first.mp4")))
        idle()
        assertNotNull(shadowOf(host).nextStartedActivity)
        assertEquals("https://example.com/first.mp4", CarPlayVideo.url)
    }

    @Test fun binaryItemIdentifiersAreMatchedByContentWhenStopping() {
        message(mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
            "uuid" to byteArrayOf(1, 2, 3), "Content-Location" to "https://example.com/first.mp4")))
        message(mapOf("type" to "removePlayQueueItem", "item" to mapOf("uuid" to byteArrayOf(1, 2, 4))))
        assertNotNull(CarPlayVideo.url)
        message(mapOf("type" to "removePlayQueueItem", "item" to mapOf("uuid" to byteArrayOf(1, 2, 3))))
        assertNull(CarPlayVideo.url)
    }

    @Test fun remoteStopAndTheNextItemInOneBatchStillSwitchChannels() {
        play()
        val old = shadowOf(host).nextStartedActivity
        CarPlayVideo.onVideoMessage(3, mapOf("type" to "stop"))
        CarPlayVideo.onVideoMessage(3, mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
            "uuid" to "second", "Content-Location" to "https://example.com/second.mp4")))
        CarPlayVideo.onVideoMessage(3, mapOf("type" to "setRate", "rate" to 1.0))
        idle()
        assertTrue(CarPlayVideo.playing)
        assertEquals("https://example.com/second.mp4", CarPlayVideo.url)
        val next = shadowOf(host).nextStartedActivity
        assertNotNull(next)
        assertFalse(CarPlayVideo.attachPlayer(preparedScreen(old)))
        assertTrue(CarPlayVideo.attachPlayer(preparedScreen(next)))
        CarPlayVideo.dismissPlayer(0)
    }

    @Test fun aRemoteChannelSwitchWaitsForTheOldAttachedPlayerToRelease() {
        play()
        val first = preparedScreen(shadowOf(host).nextStartedActivity)
        assertTrue(CarPlayVideo.attachPlayer(first))
        message(mapOf("type" to "stop"))
        queue("second")
        rate()
        assertTrue(first.playbackReleased)
        assertNull(shadowOf(host).nextStartedActivity)
        CarPlayVideo.detachPlayer(first, false)
        assertNotNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun earlyPlayAfterRemoteStopWaitsForTheNextItemOnTheSameStream() {
        play()
        shadowOf(host).nextStartedActivity
        CarPlayVideo.onVideoMessage(3, mapOf("type" to "stop"))
        CarPlayVideo.onVideoMessage(3, mapOf("type" to "setRate", "rate" to 1.0))
        CarPlayVideo.onVideoMessage(3, mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
            "uuid" to "second", "Content-Location" to "https://example.com/second.mp4")))
        idle()
        assertTrue(CarPlayVideo.playing)
        assertEquals("https://example.com/second.mp4", CarPlayVideo.url)
        assertNotNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun appleTvCanSendTheNextItemBeforeItsUiRequestAfterCompletion() {
        play()
        shadowOf(host).nextStartedActivity
        CarPlayVideo.onPlaybackCompleted()
        CarPlayVideo.onVideoMessage(4, mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
            "uuid" to "next-trailer", "Content-Location" to "https://example.com/trailer.m3u8",
            "clientBundleID" to "com.apple.tv", "mediaType" to "streaming")))
        idle()
        assertEquals("https://example.com/trailer.m3u8", CarPlayVideo.url)
        assertNull(shadowOf(host).nextStartedActivity)
        CarPlayVideo.onVideoUiRequested(); idle()
        assertNotNull(shadowOf(host).nextStartedActivity)
        CarPlayVideo.onVideoUiRequested(); idle()
        assertNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun phoneHandoffCanStartAFreshItemOnTheSameChannelWithoutRequestUi() {
        play()
        shadowOf(host).nextStartedActivity
        CarPlayVideo.dismissPlayer(0)
        message(mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
            "uuid" to "new-phone-player", "Content-Location" to "https://example.com/first.mp4")))
        rate()
        assertTrue(CarPlayVideo.playing)
        assertNotNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun destroyingAnAlreadyStoppedPlayerDoesNotRetireTheNextPhoneHandoff() {
        play()
        val first = preparedScreen(shadowOf(host).nextStartedActivity)
        assertTrue(CarPlayVideo.attachPlayer(first))
        message(mapOf("type" to "stop"))
        val generation = CarPlayVideo.playbackGeneration
        CarPlayVideo.detachPlayer(first, false)
        assertEquals(generation, CarPlayVideo.playbackGeneration)
        queue("phone-handoff")
        rate()
        assertTrue(CarPlayVideo.playing)
        assertNotNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun anOlderRetiredItemStillCannotOpenAfterAnotherPlaybackHasEnded() {
        play()
        shadowOf(host).nextStartedActivity
        CarPlayVideo.onPlaybackCompleted()
        queue("second")
        rate()
        assertNotNull(shadowOf(host).nextStartedActivity)
        CarPlayVideo.onPlaybackCompleted()
        queue("first")
        rate()
        assertNull(CarPlayVideo.url)
        assertNull(shadowOf(host).nextStartedActivity)
        CarPlayVideo.onVideoUiRequested(); idle(); queue("first"); rate()
        assertNotNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun retiredMetadataRemainsBoundedAndContainsNoVideoUrls() {
        repeat(40) {
            CarPlayVideo.onVideoUiRequested(); idle(); queue("video-$it"); rate()
            shadowOf(host).nextStartedActivity
            CarPlayVideo.onPlaybackCompleted()
        }
        val retired = ReflectionHelpers.getField<Set<String>>(CarPlayVideo, "retiredItems")
        assertEquals(32, retired.size)
        assertTrue(retired.all { !it.contains("http") && it.length < 100 })
    }

    @Test fun sharedProtocolHandlesUiItemAndPlayOrdersForDifferentProviders() {
        val orders = listOf("UIP", "UPI", "IUP", "IPU", "PUI", "PIU", "IP", "PI")
        var sequence = 0
        for (app in listOf("com.apple.tv", "com.tubo.appname", "org.example.other-player")) {
            for (order in orders) {
                CarPlayVideo.onPlaybackCompleted()
                val starts = mutableListOf<Intent>()
                for (action in order) {
                    when (action) {
                        'U' -> { CarPlayVideo.onVideoUiRequested(); idle() }
                        'I' -> message(mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
                            "uuid" to "protocol-item-${sequence++}", "Content-Location" to "https://example.com/same-channel.m3u8",
                            "clientBundleID" to app, "mediaType" to "streaming")))
                        'P' -> rate()
                    }
                    shadowOf(host).nextStartedActivity?.let(starts::add)
                }
                assertEquals("$app order=$order", 1, starts.size)
                assertTrue("$app order=$order", CarPlayVideo.playing)
            }
        }
    }

    @Test fun playFirstCannotReviveARetiredItemAndPauseCancelsTheDeferredPlay() {
        play()
        shadowOf(host).nextStartedActivity
        CarPlayVideo.onPlaybackCompleted()
        rate()
        queue("first")
        assertNull(shadowOf(host).nextStartedActivity)
        message(mapOf("type" to "setRate", "rate" to 0.0))
        queue("fresh-paused-item")
        assertFalse(CarPlayVideo.playing)
        assertNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun oldPlayerDestructionCannotDiscardFreshMetadataWaitingForPlay() {
        play()
        val first = preparedScreen(shadowOf(host).nextStartedActivity)
        assertTrue(CarPlayVideo.attachPlayer(first))
        message(mapOf("type" to "stop"))
        queue("fresh-paused-metadata")
        val generation = CarPlayVideo.playbackGeneration
        CarPlayVideo.detachPlayer(first, false)
        assertEquals(generation, CarPlayVideo.playbackGeneration)
        assertEquals("https://example.com/fresh-paused-metadata.mp4", CarPlayVideo.url)
        assertNull(shadowOf(host).nextStartedActivity)
        rate()
        assertNotNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun webRelayAppliesOnlyToStreamingItemsAndUsesTheSettingForTheNextVideo() {
        play()
        val screen = preparedScreen(shadowOf(host).nextStartedActivity)
        val direct = mock(DefaultMediaSourceFactory::class.java)
        val relay = mock(DefaultMediaSourceFactory::class.java)
        val directSource = mock(MediaSource::class.java)
        val relaySource = mock(MediaSource::class.java)
        org.mockito.Mockito.`when`(direct.createMediaSource(any(MediaItem::class.java))).thenReturn(directSource)
        org.mockito.Mockito.`when`(relay.createMediaSource(any(MediaItem::class.java))).thenReturn(relaySource)
        ReflectionHelpers.setField(screen, "directMediaSources", direct)
        ReflectionHelpers.setField(screen, "iphoneMediaSources", relay)
        assertTrue(CarPlayVideo.attachPlayer(screen))
        val player = ReflectionHelpers.getField<androidx.media3.exoplayer.ExoPlayer>(screen, "player")
        val context = RuntimeEnvironment.getApplication()

        try {
            AirPlayPersistence.saveVideoUseIphoneNetwork(context, true)
            screen.load()
            org.mockito.Mockito.verify(player).setMediaSource(directSource, 0L)
            verifyNoInteractions(relay)

            fun openStream(id: String) = message(mapOf(
                "type" to "insertPlayQueueItem",
                "item" to mapOf("uuid" to id, "Content-Location" to "https://example.com/$id.m3u8", "mediaType" to "streaming"),
            ))

            openStream("first-stream")
            org.mockito.Mockito.verify(player).setMediaSource(relaySource, 0L)
            AirPlayPersistence.saveVideoUseIphoneNetwork(context, false)
            openStream("first-stream") // Repeated metadata is still the current video.
            org.mockito.Mockito.verify(player).setMediaSource(directSource, 0L)
            openStream("next-stream")
            org.mockito.Mockito.verify(player, org.mockito.Mockito.times(2)).setMediaSource(directSource, 0L)
        } finally {
            AirPlayPersistence.saveVideoUseIphoneNetwork(context, false)
        }
    }

    @Test fun leavingParkReleasesThePlayerAndResumesOnlyWhenPlayIsRequestedInPark() {
        play()
        val first = preparedScreen(shadowOf(host).nextStartedActivity)
        val engine = ReflectionHelpers.getField<androidx.media3.exoplayer.ExoPlayer>(first, "player")
        org.mockito.Mockito.`when`(engine.currentPosition).thenReturn(12_345L)
        ReflectionHelpers.setField(first, "prepared", true)
        assertTrue(CarPlayVideo.attachPlayer(first))
        val oldGeneration = CarPlayVideo.playbackGeneration
        VideoInCar.allowed = false
        CarPlayVideo.onVideoAllowedChanged(false)
        idle()
        assertTrue(first.playbackReleased)
        assertNull(CarPlayVideo.resolveOnIphone("https://example.com/old-segment", oldGeneration))
        CarPlayVideo.detachPlayer(first, false)
        assertFalse(CarPlayVideo.playing)
        assertEquals("https://example.com/first.mp4", CarPlayVideo.url)
        assertEquals(12_345, CarPlayVideo.startMillis)
        rate()
        assertNull(shadowOf(host).nextStartedActivity)
        assertFalse(CarPlayVideo.playing)
        VideoInCar.allowed = true
        CarPlayVideo.onVideoAllowedChanged(true)
        idle()
        assertNull(shadowOf(host).nextStartedActivity)
        rate()
        assertNotNull(shadowOf(host).nextStartedActivity)
        assertEquals(12_345, CarPlayVideo.startMillis)
    }

    @Test fun unexpectedDestructionOfTheCurrentPlayerPreservesItsItemAndPosition() {
        play()
        val first = preparedScreen(shadowOf(host).nextStartedActivity)
        val engine = ReflectionHelpers.getField<androidx.media3.exoplayer.ExoPlayer>(first, "player")
        org.mockito.Mockito.`when`(engine.currentPosition).thenReturn(23_456L)
        ReflectionHelpers.setField(first, "prepared", true)
        assertTrue(CarPlayVideo.attachPlayer(first))
        ReflectionHelpers.callInstanceMethod<Unit>(first, "onDestroy")
        assertTrue(first.playbackReleased)
        assertFalse(CarPlayVideo.playing)
        assertEquals("https://example.com/first.mp4", CarPlayVideo.url)
        assertEquals(23_456, CarPlayVideo.startMillis)
        rate()
        assertNotNull(shadowOf(host).nextStartedActivity)
        assertEquals(23_456, CarPlayVideo.startMillis)
    }

    private fun preparedScreen(intent: Intent): CarPlayVideoActivity =
        Robolectric.buildActivity(CarPlayVideoActivity::class.java, intent).get().also {
            ReflectionHelpers.setField(it, "player", mock(androidx.media3.exoplayer.ExoPlayer::class.java))
            ReflectionHelpers.setField(it, "controls", View(it).apply { visibility = View.GONE })
        }

    private fun play() { queue(); rate() }
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
