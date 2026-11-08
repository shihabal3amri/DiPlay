package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.View.MeasureSpec
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.AdasCarColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CarColorSwatchGridTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val picked = mutableListOf<AdasCarColor?>()
    private var customAsked = 0

    private fun grid(initial: AdasCarColor? = null) = CarColorSwatchGrid(
        context, Color.BLUE, initial, onPicked = { picked += it }, onCustom = { customAsked++ },
    )

    private fun swatches(grid: CarColorSwatchGrid): List<View> = (0 until grid.childCount).map(grid::getChildAt)

    private fun swatch(grid: CarColorSwatchGrid, name: String): View = swatches(grid).first { it.contentDescription == name }

    private fun blue() = AdasCarColor.PRESETS.first { it.key == "blue" }

    private fun selected(grid: CarColorSwatchGrid): List<String> = swatches(grid).filter { it.isSelected }.map { it.contentDescription.toString() }

    @Test fun theGridHasOriginalEveryReadyMadeColorAndCustomInThatOrder() {
        val names = swatches(grid()).map { it.contentDescription.toString() }
        assertEquals(AdasCarColorLabels.of(context, null), names.first())
        assertEquals(AdasCarColor.PRESETS.map { AdasCarColorLabels.preset(context, it) }, names.subList(1, names.size - 1))
        assertEquals(context.getString(R.string.settings_adas_scene_color_custom), names.last())
    }

    @Test fun theSelectionIsShownOnTheMatchingSwatch() {
        assertEquals(listOf(AdasCarColorLabels.of(context, null)), selected(grid(null)))
        assertEquals(listOf(AdasCarColorLabels.preset(context, blue())), selected(grid(blue().color)))
        assertEquals(listOf(context.getString(R.string.settings_adas_scene_color_custom)), selected(grid(AdasCarColor(10, 20, 100))))
    }

    @Test fun aTapSelectsAndReportsOnceAndTheSameSwatchAgainReportsNothing() {
        val grid = grid()
        swatch(grid, AdasCarColorLabels.preset(context, blue())).performClick()
        swatch(grid, AdasCarColorLabels.preset(context, blue())).performClick()
        assertEquals(listOf<AdasCarColor?>(blue().color), picked)
        assertEquals(blue().color, grid.selection)
        swatch(grid, AdasCarColorLabels.of(context, null)).performClick()
        assertEquals(listOf<AdasCarColor?>(blue().color, null), picked)
        assertNull(grid.selection)
    }

    @Test fun customAsksForAColorAndDoesNotSelectOneItself() {
        val grid = grid()
        swatch(grid, context.getString(R.string.settings_adas_scene_color_custom)).performClick()
        assertEquals(1, customAsked)
        assertEquals(emptyList<AdasCarColor?>(), picked)
        assertNull(grid.selection)
        grid.choose(AdasCarColor(10, 20, 100))
        assertEquals(listOf<AdasCarColor?>(AdasCarColor(10, 20, 100)), picked)
        assertEquals(listOf(context.getString(R.string.settings_adas_scene_color_custom)), selected(grid))
    }

    @Test fun aDisabledGridIgnoresTapsAndAChosenColorChangesTheSelectionQuietly() {
        val grid = grid()
        grid.setInteractive(false)
        swatches(grid).forEach { assertFalse(it.isEnabled) }
        swatch(grid, AdasCarColorLabels.preset(context, blue())).performClick()
        swatch(grid, context.getString(R.string.settings_adas_scene_color_custom)).performClick()
        grid.choose(blue().color)
        assertEquals(emptyList<AdasCarColor?>(), picked)
        assertEquals(0, customAsked)
        assertNull(grid.selection)
        grid.setInteractive(true)
        grid.setSelection(blue().color)
        assertEquals(emptyList<AdasCarColor?>(), picked)
        assertEquals(listOf(AdasCarColorLabels.preset(context, blue())), selected(grid))
    }

    @Test fun theSwatchesWrapOntoRowsWhenTheWidthIsShort() {
        val grid = grid()
        grid.measure(MeasureSpec.makeMeasureSpec(10_000, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val oneRow = grid.measuredHeight
        val narrow = grid()
        narrow.measure(MeasureSpec.makeMeasureSpec(300, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        assertTrue("${narrow.measuredHeight} > $oneRow", narrow.measuredHeight > oneRow)
        assertTrue(narrow.measuredWidth <= 300)
    }

    @Test fun theSwatchColorsLookLikeTheirNames() {
        fun rgb(key: String) = AdasCarColorLabels.swatchRgb(AdasCarColor.PRESETS.first { it.key == key }.color)
        assertEquals(Color.WHITE, rgb("white"))
        val black = Color.red(rgb("black"))
        val gray = Color.red(rgb("gray"))
        assertTrue("$black < $gray", black < gray && gray < 255 && black < 80)
        for (key in listOf("white", "black", "gray")) assertEquals(key, Color.red(rgb(key)), Color.blue(rgb(key)))
        assertTrue(Color.red(rgb("red")) > 200 && Color.blue(rgb("red")) < 100)
        assertTrue(Color.green(rgb("green")) > Color.red(rgb("green")) && Color.green(rgb("green")) > Color.blue(rgb("green")))
        assertTrue(Color.blue(rgb("blue")) > 200 && Color.red(rgb("blue")) < 100)
        assertEquals(AdasCarColor.PRESETS.size, AdasCarColor.PRESETS.map { rgb(it.key) }.toSet().size)
    }

    @Test fun everyCustomColorHasASwatchColor() {
        for (hue in listOf(0, 59, 60, 119, 120, 179, 180, 239, 240, 299, 300, 359)) {
            for (saturation in listOf(0, 50, 100)) {
                for (brightness in listOf(AdasCarColor.BRIGHTNESS_MIN, 100, AdasCarColor.BRIGHTNESS_MAX)) {
                    AdasCarColorLabels.swatchRgb(AdasCarColor(hue, saturation, brightness))
                }
            }
        }
    }
}
