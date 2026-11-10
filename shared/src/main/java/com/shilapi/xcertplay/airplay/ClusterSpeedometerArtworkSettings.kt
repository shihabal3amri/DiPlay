package com.shilapi.xcertplay.airplay

import android.content.Context
import android.content.SharedPreferences

/** A separate opt-in: the original solid album-color preference keeps its meaning. */
object ClusterSpeedometerArtworkSettings {
    private const val KEY = "cluster_speedometer_artwork_background"
    private fun prefs(context: Context) = context.getSharedPreferences("xcertplay_airplay",Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean(KEY,false)
    fun save(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean(KEY,enabled).apply() }
    fun observe(context: Context, changed: () -> Unit): () -> Unit {
        val preferences=prefs(context)
        val listener=SharedPreferences.OnSharedPreferenceChangeListener { _,key -> if(key==null || key==KEY) changed() }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
