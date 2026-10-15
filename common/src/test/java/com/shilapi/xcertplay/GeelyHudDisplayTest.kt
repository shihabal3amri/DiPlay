package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GeelyHudDisplayTest {

    @Test fun storesDisplayIdentity() {
        val display = GeelyHudDisplay(id = 2, name = "Hud Display", width = 800, height = 480)
        assertEquals(2, display.id)
        assertEquals("Hud Display", display.name)
        assertEquals(800, display.width)
        assertEquals(480, display.height)
    }

    @Test fun equalWhenSameFields() {
        val first = GeelyHudDisplay(1, "Hud", 800, 480)
        val second = GeelyHudDisplay(1, "Hud", 800, 480)
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test fun notEqualWhenDifferent() {
        val first = GeelyHudDisplay(1, "Hud", 800, 480)
        val second = GeelyHudDisplay(2, "Hud", 800, 480)
        assertNotEquals(first, second)
    }

    @Test fun copyPreservesUnchangedFields() {
        val original = GeelyHudDisplay(3, "Secondary", 1024, 600)
        val renamed = original.copy(name = "Renamed")
        assertEquals(3, renamed.id)
        assertEquals("Renamed", renamed.name)
        assertEquals(1024, renamed.width)
        assertEquals(600, renamed.height)
    }
}
