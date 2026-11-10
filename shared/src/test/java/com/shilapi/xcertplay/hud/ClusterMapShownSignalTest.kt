package com.shilapi.xcertplay.hud

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * One host report reaches two consumers. A centre-screen copy of the dashboard map (the centre card or
 * a launcher embedding it) must stop the stream pause, but must not switch the DiLink 3 cluster back
 * to its stock view.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterMapShownSignalTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var originalReadMode: (android.content.Context) -> BydClusterNaviMode?

    @Before
    fun setUp() {
        originalReadMode = BydClusterMapPause.readMode
    }

    @After
    fun tearDown() {
        BydNavigationOutputs.setClusterMapShown(false)
        BydClusterMapPause.readMode = originalReadMode
        BydClusterMapPause.streamControl = null
        BydClusterMapPause.onNaviMode = null
    }

    @Test
    fun mapOnClusterWithoutCopyReachesBothConsumers() {
        BydNavigationOutputs.setClusterMapShown(true, mirrored = false)

        assertTrue(BydClusterMapPause.clusterMapShown)
        assertTrue(BydClusterBridge.mapShown)
    }

    @Test
    fun centreCopyStopsThePauseButKeepsClusterProjection() {
        BydNavigationOutputs.setClusterMapShown(true, mirrored = true)

        assertFalse("a copy on the centre screen must keep the stream running", BydClusterMapPause.clusterMapShown)
        assertTrue("the cluster still shows the map; DiLink 3 must stay in projection", BydClusterBridge.mapShown)
    }

    @Test
    fun mapOffClusterReachesBothConsumersWithOrWithoutCopy() {
        BydNavigationOutputs.setClusterMapShown(true, mirrored = false)
        BydNavigationOutputs.setClusterMapShown(false, mirrored = true)
        assertFalse(BydClusterMapPause.clusterMapShown)
        assertFalse(BydClusterBridge.mapShown)

        BydNavigationOutputs.setClusterMapShown(true, mirrored = false)
        BydNavigationOutputs.setClusterMapShown(false)
        assertFalse(BydClusterMapPause.clusterMapShown)
        assertFalse(BydClusterBridge.mapShown)
    }

    @Test
    fun centreCopyKeepsTheStreamWhileTheWheelModeHidesTheMap() {
        BydClusterMapPause.readMode = { BydClusterNaviMode.OFF }
        var streaming = true
        BydClusterMapPause.streamControl = { streaming = it }
        BydClusterMapPause::class.java.getDeclaredField("context")
            .apply { isAccessible = true }.set(null, context)
        val tick = BydClusterMapPause::class.java.getDeclaredMethod("tick").apply { isAccessible = true }
        BydOutputSettings.setClusterStreamPause(context, true)

        BydNavigationOutputs.setClusterMapShown(true, mirrored = false)
        tick.invoke(BydClusterMapPause)
        assertFalse("without a copy the pause stops the stream in Off", streaming)

        BydNavigationOutputs.setClusterMapShown(true, mirrored = true)
        tick.invoke(BydClusterMapPause)
        assertTrue("the centre copy still needs the stream", streaming)
        assertTrue(BydClusterBridge.mapShown)
    }
}
