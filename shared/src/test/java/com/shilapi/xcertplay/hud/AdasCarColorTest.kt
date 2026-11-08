package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AdasCarColorTest {
    @Test fun aColorIsSavedAsHueSaturationAndBrightness() {
        assertEquals("215,80,100", AdasCarColor(215, 80, 100).toPreference())
        assertEquals("0,0,135", AdasCarColor(0, 0, 135).toPreference())
    }

    @Test fun theSavedFormReadsBack() {
        for (preset in AdasCarColor.PRESETS) assertEquals(preset.key, preset.color, AdasCarColor.fromPreference(preset.color.toPreference()))
        assertEquals(AdasCarColor(10, 20, 100), AdasCarColor.fromPreference(" 10, 20 ,100 "))
    }

    @Test fun anythingElseMeansBydsOwnColor() {
        for (value in listOf(null, "", "1,2", "a,b,c", "1,2,3,4", "360,0,100", "-1,0,100", "0,101,100", "0,0,29", "0,0,151", "1.5,2,100")) {
            assertNull(value, AdasCarColor.fromPreference(value))
        }
    }

    @Test fun valuesOutsideTheRangesAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { AdasCarColor(360, 0, 100) }
        assertThrows(IllegalArgumentException::class.java) { AdasCarColor(0, -1, 100) }
        assertThrows(IllegalArgumentException::class.java) { AdasCarColor(0, 101, 100) }
        assertThrows(IllegalArgumentException::class.java) { AdasCarColor(0, 0, AdasCarColor.BRIGHTNESS_MIN - 1) }
        assertThrows(IllegalArgumentException::class.java) { AdasCarColor(0, 0, AdasCarColor.BRIGHTNESS_MAX + 1) }
        AdasCarColor(359, 100, AdasCarColor.BRIGHTNESS_MAX)
        AdasCarColor(0, 0, AdasCarColor.BRIGHTNESS_MIN)
    }

    @Test fun thePresetsAreDistinctAndEachHasAnInternalKey() {
        val presets = AdasCarColor.PRESETS
        assertEquals(presets.size, presets.map { it.key }.toSet().size)
        assertEquals(presets.size, presets.map { it.color }.toSet().size)
        assertTrue(presets.all { Regex("[a-z]+").matches(it.key) })
        assertTrue(presets.map { it.key }.containsAll(listOf("white", "black", "gray", "red", "blue")))
    }

    @Test fun grayWhiteAndBlackAreNeutralAndTheOthersAreColored() {
        for (key in listOf("white", "black", "gray")) assertEquals(key, 0, AdasCarColor.PRESETS.first { it.key == key }.color.saturation)
        for (preset in AdasCarColor.PRESETS.filter { it.key !in setOf("white", "black", "gray") }) {
            assertNotEquals(preset.key, 0, preset.color.saturation)
        }
    }

    @Test fun aPresetIsRecognizedAndACustomColorOrNoColorIsNot() {
        assertEquals("blue", AdasCarColor.presetOf(AdasCarColor(215, 80, 100))?.key)
        assertNull(AdasCarColor.presetOf(AdasCarColor(216, 80, 100)))
        assertNull(AdasCarColor.presetOf(null))
    }
}
