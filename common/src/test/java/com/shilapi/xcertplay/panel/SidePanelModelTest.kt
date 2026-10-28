package com.shilapi.xcertplay.panel

import org.junit.Assert.assertEquals
import org.junit.Test

/** Queue moves keep index 0 as the hero and clamp stray targets; pure, no Context so persistence skips. */
class SidePanelModelTest {
    private fun model(kinds: List<String>) =
        SidePanelModel(kinds.map { SidePanelWidget(it, TileSize.SMALL) })

    @Test fun moveToFrontBecomesHero() {
        val m = model(listOf("clock", "battery", "tyres"))
        m.move(2, 0)
        assertEquals(listOf("tyres", "clock", "battery"), m.widgets.map { it.kind })
    }

    @Test fun moveAdjacentForward() {
        val m = model(listOf("clock", "battery", "tyres"))
        m.move(0, 1)
        assertEquals(listOf("battery", "clock", "tyres"), m.widgets.map { it.kind })
    }

    @Test fun moveSameIndexIsNoop() {
        val m = model(listOf("clock", "battery"))
        m.move(1, 1)
        assertEquals(listOf("clock", "battery"), m.widgets.map { it.kind })
    }

    @Test fun moveOutOfBoundsClamps() {
        val m = model(listOf("clock", "battery"))
        m.move(0, 99)
        assertEquals(listOf("battery", "clock"), m.widgets.map { it.kind })
    }
}
