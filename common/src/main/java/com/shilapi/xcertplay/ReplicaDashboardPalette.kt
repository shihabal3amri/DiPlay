package com.shilapi.xcertplay

import android.graphics.Color

/** Daylight colors for classic gauges and overlay chrome; never applied to decoded video pixels. */
internal object ReplicaDayPalette {
    val background = Color.BLACK
    val surface = Color.rgb(248,250,247)
    val text = Color.rgb(24,44,37)
    val secondary = Color.rgb(81,99,90)
    val inactive = Color.rgb(155,168,160)
    val divider = Color.rgb(196,206,199)
    val green = Color.rgb(0,123,79)
    val red = Color.rgb(198,33,41)
    val amber = Color.rgb(154,90,0)
    val blue = Color.rgb(0,104,184)
    val dialBase = intArrayOf(Color.rgb(249,251,248),Color.rgb(229,236,231),Color.rgb(214,224,216))
    // Fixed scale colors: 0 / 25% / 50% / 75% / 100%, never normalized to the current reading.
    val dialSweep = intArrayOf(0xFFEDF3EE.toInt(),0xFFC0DACB.toInt(),0xFF71A98E.toInt(),0xFF32795C.toInt(),0xFF104732.toInt())
    val bottomBand = intArrayOf(0xFFF2F5F2.toInt(),0xFFE8EEE9.toInt())
    fun progressColor(fraction: Float): Int {
        val scaled = fraction.coerceIn(0f,1f) * 4
        val index = scaled.toInt().coerceAtMost(3)
        val t = scaled-index
        val a = dialSweep[index]; val b = dialSweep[index+1]
        fun channel(start: Int, end: Int) = (start+(end-start)*t).toInt()
        return Color.rgb(channel(Color.red(a),Color.red(b)),channel(Color.green(a),Color.green(b)),channel(Color.blue(a),Color.blue(b)))
    }
    fun progressTextColor(fraction: Float): Int {
        val luminance = Color.luminance(progressColor(fraction))
        val darkContrast = (luminance+.05f)/(Color.luminance(text)+.05f)
        val lightContrast = 1.05f/(luminance+.05f)
        return if (lightContrast > darkContrast) Color.WHITE else text
    }
    val bezel = intArrayOf(Color.rgb(128,151,138),Color.rgb(195,207,198),Color.rgb(125,145,131))
}
