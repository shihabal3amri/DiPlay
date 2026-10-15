package com.shilapi.xcertplay

/** A secondary display reported by [android.hardware.display.DisplayManager]. */
data class GeelyHudDisplay(
    val id: Int,
    val name: String,
    val width: Int,
    val height: Int,
)
