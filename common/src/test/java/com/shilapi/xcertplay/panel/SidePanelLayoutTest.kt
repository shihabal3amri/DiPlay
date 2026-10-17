package com.shilapi.xcertplay.panel

import com.shilapi.xcertplay.host.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The grid packs tiles by width and keeps a tall tile's column reserved; pure, no head unit. */
class SidePanelLayoutTest {
    private fun w(kind: String, size: TileSize) = SidePanelWidget(kind, size)

    @Test fun narrowPanelHasOneColumnWideTilesFit() {
        assertEquals(1, SidePanelLayout.columns(200))
        val placed = SidePanelLayout.pack(listOf(w("clock", TileSize.WIDE)), 1)
        assertEquals(1, placed.size)
        assertEquals(1, placed[0].colSpan) // clamped to the column count, never wider than the panel
    }

    @Test fun threeColumnsFitThreeSmallTiles() {
        val placed = SidePanelLayout.pack(listOf(w("soc", TileSize.SMALL), w("gear", TileSize.SMALL), w("speed", TileSize.SMALL)), 3)
        assertEquals(3, placed.size)
        assertEquals(listOf(0, 1, 2), placed.map { it.col })
        assertTrue(placed.all { it.row == 0 })
    }

    @Test fun wideTileTakesTwoColumnsAndForcesNextToNewRow() {
        val placed = SidePanelLayout.pack(listOf(w("energy", TileSize.WIDE), w("gear", TileSize.SMALL)), 2)
        assertEquals(0, placed[0].col)
        assertEquals(2, placed[0].colSpan)
        assertEquals(0, placed[1].col)
        assertEquals(1, placed[1].row)
    }

    @Test fun tallTileReservesItsColumnOnTheRowBelow() {
        val placed = SidePanelLayout.pack(listOf(w("accelPedal", TileSize.TALL), w("speed", TileSize.SMALL)), 2)
        val accel = placed.first { it.widget.kind == "accelPedal" }
        val speed = placed.first { it.widget.kind == "speed" }
        assertEquals(0, accel.col)
        assertEquals(2, accel.rowSpan)
        // The speed tile must not land in the accelerator's reserved second row.
        assertTrue(!(speed.col == 0 && speed.row == 1))
    }

    @Test fun lastTilesDropOffWhenNarrowAndFull() {
        val many = (1..6).map { w("soc", TileSize.SMALL) }
        val placed = SidePanelLayout.pack(many, 2)
        assertTrue(placed.size <= 6)
    }

    @Test fun firstWidgetIsHeroAndRestPacks() {
        val widgets = listOf(w("clock", TileSize.WIDE), w("soc", TileSize.SMALL), w("gear", TileSize.SMALL))
        val packed = SidePanelLayout.packWithHero(widgets, 2)
        assertEquals("clock", packed.hero?.widget?.kind)
        assertEquals(2, packed.hero?.colSpan) // the hero always spans the panel
        assertEquals(listOf("soc", "gear"), packed.rest.map { it.widget.kind })
    }

    @Test fun emptyListHasNoHero() {
        val packed = SidePanelLayout.packWithHero(emptyList(), 2)
        assertNull(packed.hero)
        assertTrue(packed.rest.isEmpty())
    }

    @Test fun singleWidgetIsOnlyHero() {
        val packed = SidePanelLayout.packWithHero(listOf(w("clock", TileSize.WIDE)), 2)
        assertEquals("clock", packed.hero?.widget?.kind)
        assertTrue(packed.rest.isEmpty())
    }
}

/** The formatter rounds, signs and units each signal; and drops a tile whose value is absent. */
class TileFormatterTest {
    @Test fun percentRoundsToWhole() = assertEquals("63", TileFormatter.value("soc", 62.6).primary)

    @Test fun lowChargeWarns() = assertEquals(TileAccent.WARN, TileFormatter.value("soc", 12.0).accent)

    @Test fun kwhKeepsOneDecimal() = assertEquals("25.1", TileFormatter.value("remainKwh", 25.14).primary)

    @Test fun zeroEnergyIsUnanswered() = assertFalse(TileFormatter.value("remainKwh", 0.0).present)

    @Test fun pedalCarriesFractionBar() = assertEquals(0.6f, TileFormatter.value("accelPedal", 60.0).fraction!!, 0.001f)

    @Test fun chargingPowerIsGreen() = assertEquals(TileAccent.GOOD, TileFormatter.value("batteryPower", -30.0).accent)

    @Test fun dischargingPowerIsSigned() = assertEquals("+45", TileFormatter.value("batteryPower", 45.0).primary)

    @Test fun gearCodeBecomesLetter() = assertEquals("D", TileFormatter.value("gear", 4.0).primary)

    @Test fun energyModeBecomesText() = assertEquals("EV", TileFormatter.value("workMode", 1.0).primary)

    @Test fun driveModeBecomesText() = assertEquals("ECO", TileFormatter.value("driveMode", 1.0).primary)

    @Test fun powerStateBecomesText() = assertEquals("DRV", TileFormatter.value("powerState", 2.0).primary)

    @Test fun openDoorWarns() {
        val readout = TileFormatter.value("doorFL", 1.0)
        assertEquals("OPEN", readout.primary)
        assertEquals(TileAccent.WARN, readout.accent)
    }

    @Test fun closedDoorIsDash() = assertEquals("—", TileFormatter.value("doorFL", 0.0).primary)

    @Test fun trunkClosedIsShut() = assertEquals("SHUT", TileFormatter.value("trunk", 2.0).primary)

    @Test fun hazardSignalWarns() {
        val readout = TileFormatter.value("turnSignal", 6.0)
        assertEquals("⚠", readout.primary)
        assertEquals(TileAccent.WARN, readout.accent)
    }

    @Test fun buckledBeltIsGood() = assertEquals(TileAccent.GOOD, TileFormatter.value("seatbeltFL", 1.0).accent)

    @Test fun seatLevelZeroIsDash() = assertEquals("—", TileFormatter.value("seatHeatDriver", 0.0).primary)

    @Test fun motorCurrentIsSignedAmps() = assertEquals("+7.9", TileFormatter.value("motorCurrentFront", 7.9).primary)

    @Test fun multiTilePairsValuesWithLabels() {
        val cells = TileFormatter.cells(
            listOf("doorFL", "doorFR"),
            listOf("FL", "FR"),
            mapOf("doorFL" to 1.0, "doorFR" to 0.0),
        )
        assertEquals("OPEN", cells[0].value)
        assertEquals("FL", cells[0].label)
        assertEquals("—", cells[1].value)
        assertEquals("FR", cells[1].label)
    }

    @Test fun multiTileAbsentValueIsDash() {
        val cells = TileFormatter.cells(listOf("motorRpmFront"), listOf("F"), mapOf("motorRpmFront" to null))
        assertEquals("—", cells[0].value)
    }

    @Test fun absentValueHidesTile() {
        val readout = TileFormatter.value("soc", null)
        assertTrue(!readout.present)
        assertEquals("—", readout.primary)
    }

    @Test fun compositeShowsBothValues() {
        val readout = TileFormatter.composite("soc", 55.0, "remainKwh", 40.0)
        assertEquals("55", readout.primary)
        assertEquals("40 kWh", readout.secondary)
    }

    @Test fun compositeWithoutSecondaryOmitsSubline() {
        assertEquals(null, TileFormatter.composite("soc", 55.0, "remainKwh", null).secondary)
    }

    // Localised status words: the formatter carries the resource id, the panel resolves the text.

    @Test fun closedWindowShowsClosedWord() =
        assertEquals(R.string.panel_state_closed, TileFormatter.value("windowFL", 0.0).primaryRes)

    @Test fun openWindowShowsPercentValue() {
        val readout = TileFormatter.value("windowFL", 45.0)
        assertEquals("45%", readout.primary)
        assertEquals("", readout.unit)
    }

    @Test fun unlockedLockCarriesLocalisedWord() {
        val readout = TileFormatter.value("lockFL", 0.0)
        assertEquals(R.string.panel_state_unlocked, readout.primaryRes)
        assertEquals(TileAccent.WARN, readout.accent)
    }

    @Test fun lockedLockCarriesLocalisedWord() {
        val readout = TileFormatter.value("lockFL", 2.0)
        assertEquals(R.string.panel_state_locked, readout.primaryRes)
        assertEquals(TileAccent.GOOD, readout.accent)
    }

    @Test fun openDoorCarriesLocalisedWord() =
        assertEquals(R.string.panel_state_open, TileFormatter.value("doorFL", 1.0).primaryRes)

    @Test fun ignitionDriveCarriesLocalisedWord() =
        assertEquals(R.string.panel_state_drive, TileFormatter.value("powerState", 2.0).primaryRes)
}
