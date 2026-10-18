package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.orchestration.CarPlayController

/** Session-owned observer; neither ADB nor vendor mute flags are needed. Main-thread only. */
internal class VehicleMediaMuteController(
    context: Context,
    private val controller: CarPlayController,
) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val policy = VehicleMediaMutePolicy()
    private val preferences = this.context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "vehicle_media_mute_pause_enabled") handler.post {
            policy.clear()
            sample()
        }
    }
    init { preferences.registerOnSharedPreferenceChangeListener(preferenceListener) }
    private var mediaOwner: Any? = null
    private var hasMedia = false
    @Volatile private var stopped = false
    private var registered = false
    private var lastLoggedState: String? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { sample() }
    }
    private val poll = Runnable { sample() }

    fun playbackChanged() { sample() }
    fun mediaChanged() { sample() }
    fun manualControl() { policy.manualControl() }

    private fun sample() {
        if (stopped) return
        handler.removeCallbacks(poll)
        val enabled = AirPlayPersistence.loadVehicleMediaMutePauseEnabled(context)
        val owner = controller.activeAirPlaySessionToken()
        val connected = owner != null
        val info = controller.nowPlayingSnapshot()
        if (mediaOwner !== owner) { mediaOwner = owner; hasMedia = false }
        if (owner != null && (info.playing || info.title != null)) hasMedia = true
        val validMedia = hasMedia
        val background = CarPlayBackgroundSession.snapshot()
        // An absent matching sink cannot prove that no call/Siri is using priority audio.
        val priorityVoice = inCall(context) || background == null || background.controller !== controller || background.sink.hasPriorityVoiceAudio()
        val observing = enabled && connected && validMedia
        setReceiverRegistered(observing)
        val mute = if (observing) runCatching {
            context.getSystemService(AudioManager::class.java)?.isStreamMute(AudioManager.STREAM_MUSIC)
        }.getOrNull() else null
        val state = "enabled=$enabled connected=$connected media=$validMedia mute=$mute priorityVoice=$priorityVoice"
        if (state != lastLoggedState) {
            Log.i(TAG, "observe $state")
            lastLoggedState = state
        }
        val decision = synchronized(policy) {
            policy.observe(owner, enabled, connected, validMedia, priorityVoice, mute, info.playing)
                ?.let { it to policy.revision }
        }
        if (decision != null) {
            val (command, revision) = decision
            val index = if (command == VehicleMediaMutePolicy.Command.PAUSE) CarPlayMediaButton.PAUSE else CarPlayMediaButton.PLAY
            val sent = owner != null && controller.sendMediaButtonForSession(index, owner) {
                val current = CarPlayBackgroundSession.snapshot()
                val stillMuted = runCatching {
                    context.getSystemService(AudioManager::class.java)?.isStreamMute(AudioManager.STREAM_MUSIC)
                }.getOrNull()
                !stopped && policy.revision == revision && AirPlayPersistence.loadVehicleMediaMutePauseEnabled(context) &&
                    stillMuted == (command == VehicleMediaMutePolicy.Command.PAUSE) && !inCall(context) &&
                    current != null && current.controller === controller && !current.sink.hasPriorityVoiceAudio()
            }
            if (!sent) policy.commandFailed()
            Log.i(TAG, "command=$command accepted=$sent")
        }
        if (observing) handler.postDelayed(poll, POLL_MILLIS)
    }

    private fun setReceiverRegistered(wanted: Boolean) {
        if (wanted == registered) return
        if (wanted) {
            val filter = IntentFilter().apply {
                addAction("android.media.VOLUME_CHANGED_ACTION")
                addAction("android.media.STREAM_MUTE_CHANGED_ACTION")
            }
            registered = runCatching {
                if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                else { @Suppress("DEPRECATION") context.registerReceiver(receiver, filter) }
                true
            }.getOrDefault(false)
        } else {
            if (registered) runCatching { context.unregisterReceiver(receiver) }
            registered = false
        }
    }

    fun stop() {
        stopped = true
        handler.removeCallbacks(poll)
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        setReceiverRegistered(false)
        policy.clear()
    }

    companion object {
        private const val TAG = "DiPlay-VehicleMute"
        private const val POLL_MILLIS = 500L
    }
}
