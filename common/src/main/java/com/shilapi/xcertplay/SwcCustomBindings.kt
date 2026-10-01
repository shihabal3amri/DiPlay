package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.CarPlayMediaButton

/**
 * Persists user-defined steering-wheel key → CarPlay action bindings.
 *
 * Each binding maps one Android keyCode to one of the five CarPlay media-button actions
 * (PLAY_PAUSE, NEXT, PREVIOUS, PLAY, PAUSE).  Bindings survive app restarts; clearing
 * a binding restores the built-in [CarPlayMediaButton.forKeyCode] mapping.
 *
 * SharedPreferences file: "xcertplay_airplay" (shared with [AirPlayPersistence]).
 * Keys:  "swc_custom_play_pause", "swc_custom_next", "swc_custom_previous",
 *        "swc_custom_play",       "swc_custom_pause"
 * Value: keyCode as Int (0 = not bound / cleared).
 */
object SwcCustomBindings {

    /** All bindable CarPlay actions, in display order. */
    enum class Action(
        val carPlayIndex: Int,
        val prefKey: String,
        val labelKey: String,
    ) {
        PLAY_PAUSE(CarPlayMediaButton.PLAY_PAUSE, "swc_custom_play_pause", "swc_action_play_pause"),
        NEXT(CarPlayMediaButton.NEXT,             "swc_custom_next",       "swc_action_next"),
        PREVIOUS(CarPlayMediaButton.PREVIOUS,     "swc_custom_previous",   "swc_action_previous"),
        PLAY(CarPlayMediaButton.PLAY,             "swc_custom_play",       "swc_action_play"),
        PAUSE(CarPlayMediaButton.PAUSE,           "swc_custom_pause",      "swc_action_pause"),
    }

    private const val PREFS_NAME = "xcertplay_airplay"
    private const val NO_BINDING = 0

    // ── Read ──────────────────────────────────────────────────────────────────

    /** Returns the custom keyCode bound to [action], or 0 if none is set. */
    fun loadBinding(context: Context, action: Action): Int =
        prefs(context).getInt(action.prefKey, NO_BINDING)

    /** All current custom bindings as a map of keyCode → carPlayIndex. */
    private fun allBindings(context: Context): Map<Int, Int> =
        Action.entries
            .mapNotNull { action ->
                val code = loadBinding(context, action)
                if (code != NO_BINDING) code to action.carPlayIndex else null
            }
            .toMap()

    /**
     * Returns the CarPlay button index for [keyCode] using the user's custom bindings,
     * falling back to [CarPlayMediaButton.forKeyCode] when no custom binding exists.
     */
    fun resolveKeyCode(context: Context, keyCode: Int): Int? =
        allBindings(context)[keyCode] ?: CarPlayMediaButton.forKeyCode(keyCode)

    /**
     * Returns the [Action] whose custom binding matches [keyCode], or null.
     * Used to detect conflicts when a new binding is being set.
     */
    fun conflictingAction(context: Context, keyCode: Int): Action? =
        Action.entries.firstOrNull { loadBinding(context, it) == keyCode }

    // ── Write ─────────────────────────────────────────────────────────────────

    /** Binds [keyCode] to [action]. Pass 0 to clear. */
    fun saveBinding(context: Context, action: Action, keyCode: Int) {
        // Remove the same keyCode from any other action first (each keyCode may only bind one action).
        if (keyCode != NO_BINDING) {
            Action.entries.filter { it != action && loadBinding(context, it) == keyCode }
                .forEach { clearBinding(context, it) }
        }
        prefs(context).edit().putInt(action.prefKey, keyCode).apply()
    }

    /** Clears the custom binding for [action], restoring the built-in mapping. */
    fun clearBinding(context: Context, action: Action) {
        prefs(context).edit().remove(action.prefKey).apply()
    }

    /** Clears all custom bindings. */
    fun clearAll(context: Context) {
        val editor = prefs(context).edit()
        Action.entries.forEach { editor.remove(it.prefKey) }
        editor.apply()
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
