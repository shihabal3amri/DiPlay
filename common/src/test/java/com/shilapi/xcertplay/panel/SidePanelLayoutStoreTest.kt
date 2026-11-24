package com.shilapi.xcertplay.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored line round-trips without a Context (parse/encode are the whole persistence contract), so this is
 * plain JUnit: a saved `id[SIZE];…` line reads back the same widgets, unknown ids drop, and duplicates collapse.
 */
class SidePanelLayoutStoreTest {
    @Test fun encodesThenParsesBackSizes() {
        val widgets = listOf(SidePanelWidget("clock", TileSize.WIDE), SidePanelWidget("soc", TileSize.SMALL))
        val parsed = SidePanelLayoutStore.parse(SidePanelLayoutStore.encode(widgets))
        assertEquals(listOf("clock", "soc"), parsed.map { it.kind })
        assertEquals(TileSize.WIDE, parsed[0].size)
        assertEquals(TileSize.SMALL, parsed[1].size)
    }

    @Test fun unknownIdIsDropped() {
        val parsed = SidePanelLayoutStore.parse("clock[WIDE];ghost[SMALL]")
        assertEquals(listOf("clock"), parsed.map { it.kind })
    }

    @Test fun corruptLineIsIgnoredNotFatal() {
        val parsed = SidePanelLayoutStore.parse("clock[WIDE];nonsense;tyres[SMALL]")
        assertEquals(listOf("clock", "tyres"), parsed.map { it.kind })
    }

    @Test fun unknownSizeFallsBackToDefault() {
        val parsed = SidePanelLayoutStore.parse("tyres[GIGANTIC]")
        assertEquals(TileCatalog.byId.getValue("tyres").defaultSize, parsed[0].size)
    }

    @Test fun duplicateKindsCollapseToOne() {
        val parsed = SidePanelLayoutStore.parse("clock[WIDE];clock[SMALL]")
        assertEquals(listOf("clock"), parsed.map { it.kind })
    }

    @Test fun defaultLayoutIsTheOriginalCards() {
        assertEquals(listOf("clock", "battery", "tyres"), TileCatalog.defaultLayout.map { it.kind })
        assertTrue(SidePanelLayoutStore.parse("").isEmpty())
    }

    @Test fun groupedTilesRoundTrip() {
        val parsed = SidePanelLayoutStore.parse("doors[LARGE];motorRpm[WIDE];seatbelts[LARGE]")
        assertEquals(listOf("doors", "motorRpm", "seatbelts"), parsed.map { it.kind })
    }
}

/** Every tile the catalog offers must resolve to signals the repository can actually read. */
class TileCatalogStructureTest {
    @Test fun everyFieldIdIsKnownToTheRepository() {
        for (kind in TileCatalog.all) {
            for (field in kind.fields) {
                assertTrue("$kind/$field", com.shilapi.xcertplay.hud.BydPanelFields.byId.containsKey(field))
            }
        }
    }

    @Test fun everyValueAndMultiTileHasATitle() {
        for (kind in TileCatalog.all) {
            assertTrue(kind.id, kind.titleRes != 0)
        }
    }

    @Test fun multiTileLabelsAlignWithFields() {
        for (kind in TileCatalog.all.filter { it.format == TileFormat.MULTI }) {
            assertEquals(kind.id, kind.fields.size, kind.labels.size)
        }
    }

    @Test fun singlesDoNotDuplicateAGroupedField() {
        val grouped = TileCatalog.all.filter { it.format == TileFormat.MULTI }.flatMap { it.fields }.toSet()
        val singles = TileCatalog.all.filter { !it.composite && it.format == TileFormat.VALUE }.flatMap { it.fields }
        assertEquals(emptySet<String>(), singles.filter { it in grouped }.toSet())
    }
}
