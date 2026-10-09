package com.shilapi.xcertplay

import android.content.Context

object GeekModeManager {
    private const val PREFS = "diplay"
    private const val KEY_GEEK_MODE = "geek_mode_enabled"
    const val TAP_TARGET = 5

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_GEEK_MODE, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_GEEK_MODE, enabled)
            .apply()
    }

    fun toggle(context: Context): Boolean {
        val next = !isEnabled(context)
        setEnabled(context, next)
        return next
    }
}
