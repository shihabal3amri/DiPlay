package com.shilapi.xcertplay

import com.shilapi.xcertplay.DiLink51ClusterLayout.Theme
import com.shilapi.xcertplay.DiLink51ClusterLayout.Plan
import org.junit.Assert.*
import org.junit.Test

class DiLink51ClusterLayoutTest {
    private val names = listOf(DiLink51ClusterLayout.BASE, DiLink51ClusterLayout.FULL, DiLink51ClusterLayout.SIDE)
    private val firmware = DiLink51ClusterLayout.FINGERPRINT

    @Test fun mapUsesFullLayerWhileBothOtherThemesUseSideLayer() {
        assertEquals(DiLink51ClusterLayout.FULL, DiLink51ClusterLayout.displayName(names, firmware, Theme.MAP))
        for (theme in listOf(Theme.SCENARIO, Theme.SIMPLE)) {
            assertEquals(DiLink51ClusterLayout.SIDE, DiLink51ClusterLayout.displayName(names, firmware, theme))
            assertEquals(Plan(1320, 0, 600, 720, false), DiLink51ClusterLayout.plan(1920, 720, theme))
        }
    }

    @Test fun missingSideLayerCannotCoverScenarioWithAFullMap() {
        assertNull(DiLink51ClusterLayout.displayName(listOf(DiLink51ClusterLayout.BASE, DiLink51ClusterLayout.FULL), firmware, Theme.SCENARIO))
    }

    @Test fun fullMapKeepsSpeedAndGearBandsOutsideTheVideoViewport() {
        val plan = DiLink51ClusterLayout.plan(1920, 720, Theme.MAP)!!
        assertTrue(plan.top >= 144)
        assertTrue(plan.top + plan.height <= 624)
        assertEquals(0, plan.left)
        assertEquals(1920, plan.width)
        assertEquals(0, plan.sourceLeft)
        assertEquals(240, plan.sourceTop)
        assertNull(DiLink51ClusterLayout.streamConfig().safeArea)
    }

    @Test fun oneContinuousStreamSupportsBothViewportsWithoutStretching() {
        val config = DiLink51ClusterLayout.streamConfig()
        assertEquals(1920, config.widthPixels)
        assertEquals(720, config.heightPixels)
        assertEquals(292, config.widthPhysicalMm)
        assertEquals(110, config.heightPhysicalMm)
        assertEquals(0, config.features)
        assertEquals(config, DiLink51ClusterLayout.streamConfig(1920, 720))
        for (theme in Theme.entries) {
            val plan = DiLink51ClusterLayout.plan(1920, 720, theme)!!
            assertEquals(config.widthPixels, plan.streamWidth)
            assertEquals(config.heightPixels, plan.streamHeight)
            assertTrue(plan.sourceLeft >= 0)
            assertTrue(plan.sourceTop >= 0)
            assertTrue(plan.sourceLeft + plan.width <= config.widthPixels)
            assertEquals(config.heightPixels, plan.sourceTop + plan.height)
            // Preserve the centered, bottom-anchored car marker in every crop.
            assertTrue(960 in plan.sourceLeft until plan.sourceLeft + plan.width)
            assertTrue(640 in plan.sourceTop until plan.sourceTop + plan.height)
        }
        val side = DiLink51ClusterLayout.plan(1920, 720, Theme.SIMPLE)!!
        assertEquals(660, side.sourceLeft)
        assertEquals(0, side.sourceTop)
    }

    @Test fun diLink5AndUnknownFirmwareRetainThePrDisplaySelection() {
        for (fingerprint in listOf("BYD-AUTO/DiLink5.0/DiLink5.0:12/test", "unknown")) {
            assertFalse(DiLink51ClusterLayout.supported(fingerprint))
            assertEquals(DiLink51ClusterLayout.BASE, DiLink51ClusterLayout.displayName(names, fingerprint, Theme.SIMPLE))
            assertEquals(DiLink51ClusterLayout.FULL, DiLink51ClusterLayout.displayName(names.drop(1), fingerprint, Theme.MAP))
        }
    }

    @Test fun theLeopard5FirmwareUsesTheSharedLayers() {
        // #301: the plain layer that unverified firmware gets is one this cluster never shows.
        val leopard = DiLink51ClusterLayout.LEOPARD5_FINGERPRINT
        assertTrue(DiLink51ClusterLayout.supported(leopard))
        assertEquals(DiLink51ClusterLayout.FULL, DiLink51ClusterLayout.displayName(names, leopard, Theme.MAP))
        assertEquals(DiLink51ClusterLayout.SIDE, DiLink51ClusterLayout.displayName(names, leopard, Theme.SCENARIO))
        assertFalse(DiLink51ClusterLayout.supported(leopard.replace("20260705", "20260706")))
    }

    @Test fun theDenzaN9FirmwareUsesTheSharedLayers() {
        // #301: like the Leopard 5, the N9 cluster never shows the plain layer.
        val n9 = DiLink51ClusterLayout.DENZA_N9_FINGERPRINT
        assertTrue(DiLink51ClusterLayout.supported(n9))
        assertEquals(DiLink51ClusterLayout.FULL, DiLink51ClusterLayout.displayName(names, n9, Theme.MAP))
        assertEquals(DiLink51ClusterLayout.SIDE, DiLink51ClusterLayout.displayName(names, n9, Theme.SCENARIO))
        assertFalse(DiLink51ClusterLayout.supported(n9.replace("011226", "011227")))
    }

    @Test fun the1920ClustersKeepTheirMeasuredPlans() {
        // The DiLink 5.1 and Leopard 5 clusters are 1920x720; the N9 side-map change must not reach them.
        for (theme in listOf(Theme.SCENARIO, Theme.SIMPLE)) {
            assertEquals(Plan(1320, 0, 600, 720, false, 1920, 720), DiLink51ClusterLayout.plan(1920, 720, theme))
        }
        assertEquals(Plan(0, 144, 1920, 480, true, 1920, 720), DiLink51ClusterLayout.plan(1920, 720, Theme.MAP))
    }

    @Test fun theN9FullMapIsTheMeasuredOneScaledAcross() {
        val measured = DiLink51ClusterLayout.plan(1920, 720, Theme.MAP)!!
        val wide = DiLink51ClusterLayout.plan(2560, 720, Theme.MAP)!!
        assertEquals(Plan(0, 144, 2560, 480, true, 2560, 720), wide)
        assertEquals(measured.left * 4, wide.left * 3)
        assertEquals(measured.width * 4, wide.width * 3)
        assertEquals(measured.top, wide.top)
        assertEquals(measured.height, wide.height)
    }

    @Test fun theN9SideMapClearsTheTopRightIconsAndTheBottomReadouts() {
        // #301: the 4/3-scaled box (x=1760..2560, full height) ran under both rows. Positions on the
        // 2560x720 cluster, read from the owner's photos.
        val statusIcons = Box(1428, 15, 2010, 71) // car, temperature, P, seat belt, ..., lamp icons
        val readouts = Box(1805, 651, 2354, 694) // power bar, P, trip distance
        val stockNavigationCard = Box(2070, 274, 2245, 507) // arrow and road name, no map outside China
        val clearance = 20
        for (theme in listOf(Theme.SCENARIO, Theme.SIMPLE)) {
            val plan = DiLink51ClusterLayout.plan(2560, 720, theme)!!
            assertEquals(Plan(1760, 96, 800, 528, false, 2560, 720), plan)
            val side = Box(plan.left, plan.top, plan.left + plan.width, plan.top + plan.height)
            assertTrue(side.left >= 0 && side.right <= 2560 && side.top >= 0 && side.bottom <= 720)
            assertFalse(side.overlaps(statusIcons.grow(clearance)))
            assertFalse(side.overlaps(readouts.grow(clearance)))
            assertTrue(side.contains(stockNavigationCard))
            val cardCentre = (stockNavigationCard.left + stockNavigationCard.right) / 2
            assertTrue(kotlin.math.abs((side.left + side.right) / 2 - cardCentre) <= 10)
            // Smaller than the 4/3-scaled box it replaces.
            assertTrue(plan.width * plan.height < 800 * 720)
        }
    }

    @Test fun theN9StreamSizeIsKnownBeforeItsClusterDisplayAppears() {
        // The cluster stream is negotiated before the display exists (#981), so the size comes from the firmware.
        assertEquals(2560 to 720, DiLink51ClusterLayout.streamSize(DiLink51ClusterLayout.DENZA_N9_FINGERPRINT))
        assertEquals(1920 to 720, DiLink51ClusterLayout.streamSize(firmware))
        assertEquals(1920 to 720, DiLink51ClusterLayout.streamSize(DiLink51ClusterLayout.LEOPARD5_FINGERPRINT))
        val (width, height) = DiLink51ClusterLayout.streamSize(DiLink51ClusterLayout.DENZA_N9_FINGERPRINT)
        for (theme in Theme.entries) {
            val plan = DiLink51ClusterLayout.plan(2560, 720, theme)!!
            assertEquals(width, plan.streamWidth)
            assertEquals(height, plan.streamHeight)
        }
    }

    @Test fun theWideN9ClusterGetsAStreamOfItsOwnSizeCroppedAtNativeScale() {
        val config = DiLink51ClusterLayout.streamConfig(2560, 720)
        assertEquals(2560, config.widthPixels)
        assertEquals(720, config.heightPixels)
        assertEquals(0, config.features)
        assertNull(config.safeArea)
        for (theme in Theme.entries) {
            val plan = DiLink51ClusterLayout.plan(2560, 720, theme)!!
            assertEquals(config.widthPixels, plan.streamWidth)
            assertEquals(config.heightPixels, plan.streamHeight)
            assertTrue(plan.sourceLeft >= 0)
            assertTrue(plan.sourceLeft + plan.width <= config.widthPixels)
            assertEquals(config.heightPixels, plan.sourceTop + plan.height)
            assertTrue(1280 in plan.sourceLeft until plan.sourceLeft + plan.width)
            assertTrue(640 in plan.sourceTop until plan.sourceTop + plan.height)
        }
        assertEquals(880, DiLink51ClusterLayout.plan(2560, 720, Theme.SIMPLE)!!.sourceLeft)
        assertEquals(240, DiLink51ClusterLayout.plan(2560, 720, Theme.MAP)!!.sourceTop)
    }

    @Test fun theN9SideCropIsCentredAndBottomAligned() {
        val plan = DiLink51ClusterLayout.plan(2560, 720, Theme.SCENARIO)!!
        assertEquals(880, plan.sourceLeft)
        assertEquals(192, plan.sourceTop)
        assertEquals(720, plan.sourceTop + plan.height)
    }

    @Test fun unmeasuredPanelSizesAreNotGivenTheWrongReadoutMask() {
        assertNull(DiLink51ClusterLayout.plan(1280, 480, Theme.MAP))
        assertNull(DiLink51ClusterLayout.plan(2560, 1080, Theme.MAP))
        assertNull(DiLink51ClusterLayout.plan(2400, 720, Theme.SCENARIO))
    }

    private data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun grow(by: Int) = Box(left - by, top - by, right + by, bottom + by)
        fun overlaps(other: Box) = left < other.right && other.left < right && top < other.bottom && other.top < bottom
        fun contains(other: Box) = other.left >= left && other.right <= right && other.top >= top && other.bottom <= bottom
    }
}
