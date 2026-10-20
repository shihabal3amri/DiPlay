package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.compat.AudioFocusRequestCompat

/** Optional, per-renderer navigation focus, independent of the media focus coordinator.
 *
 * Some head units gate their navigation output on a guidance focus request. Request only
 * when PCM arrives, then release after the prompt drains, even if the iPhone keeps the
 * stream open. The renderer worker owns all calls to [acquire] and [release].
 */
internal class NavigationAudioFocus(context: Context?, private val report: (String) -> Unit) {
    private val manager = context?.applicationContext?.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequestCompat? = null

    fun acquire() {
        val audioManager = manager ?: return
        if (focus != null) return
        runCatching {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequestCompat(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                attributes,
                AudioManager.OnAudioFocusChangeListener { change ->
                    emit("Audio: navigation focus change=$change")
                },
                Handler(Looper.getMainLooper()),
            )
            focus = request
            val result = request.request(audioManager)
            emit("Audio: navigation focus requested=true usage=${attributes.usage} result=$result")
        }.onFailure { emit("Audio: navigation focus error=${it.javaClass.simpleName}") }
    }

    fun release(reason: String) {
        val previous = focus ?: return
        focus = null
        val audioManager = manager ?: return
        runCatching { previous.abandon(audioManager) }
            .onSuccess { emit("Audio: navigation focus abandoned=true reason=$reason result=$it") }
            .onFailure { emit("Audio: navigation focus abandonError=${it.javaClass.simpleName}") }
    }

    private fun emit(line: String) { runCatching { report(line) } }
}
