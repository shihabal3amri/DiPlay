package com.shilapi.xcertplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri
import com.shilapi.xcertplay.host.R

/** Opens DiPlay from a boot or Bluetooth trigger, or posts a notification when Android will not allow that. */
internal object AutoStartLauncher {
    /** Intent extra naming the trigger that opened DiPlay: [TRIGGER_BOOT] or [TRIGGER_BLUETOOTH]. */
    const val EXTRA_AUTO_START = "auto_start"
    const val TRIGGER_BOOT = "boot"
    const val TRIGGER_BLUETOOTH = "bluetooth"

    private const val TAG = "diplay-autostart"
    private const val CHANNEL = "diplay_autostart"
    private const val NOTIFICATION_ID = 41

    fun onTrigger(context: Context, trigger: AutoStartPolicy.Trigger, connectedAddress: String? = null) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val state = AutoStartPolicy.State(
            startOnBoot = AirPlayPersistence.loadAutoStartOnBoot(app),
            startOnBluetooth = DiPlayPreferences.autoStartOnBluetooth(app),
            selectedPhoneAddress = DiPlayPreferences.phoneAddress(app),
            sessionActive = CarPlayBackgroundSession.hasSession(),
            canDrawOverlays = canDrawOverlays(app),
            sdkInt = Build.VERSION.SDK_INT,
            lastLaunchMillis = DiPlayPreferences.lastAutoStartMillis(app),
            nowMillis = now,
        )
        when (val decision = AutoStartPolicy.decide(trigger, connectedAddress, state)) {
            is AutoStartPolicy.Decision.Ignore -> Log.i(TAG, "$trigger ignored: ${decision.reason}")
            AutoStartPolicy.Decision.Launch -> {
                DiPlayPreferences.saveLastAutoStartMillis(app, now)
                try {
                    app.startActivity(launchIntent(app, trigger))
                    Log.i(TAG, "$trigger opened DiPlay")
                } catch (error: RuntimeException) {
                    Log.w(TAG, "$trigger could not open DiPlay; showing a notification instead", error)
                    notify(app, trigger)
                }
            }
            AutoStartPolicy.Decision.Notify -> {
                DiPlayPreferences.saveLastAutoStartMillis(app, now)
                notify(app, trigger)
            }
        }
    }

    /** Android 10+ only lets an app open itself from the background with the overlay permission. */
    fun canDrawOverlays(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(context)

    fun overlayPermissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())

    private fun launchIntent(context: Context, trigger: AutoStartPolicy.Trigger): Intent =
        Intent(context, DiPlayActivity::class.java)
            .putExtra(EXTRA_AUTO_START, if (trigger == AutoStartPolicy.Trigger.BLUETOOTH) TRIGGER_BLUETOOTH else TRIGGER_BOOT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun notify(context: Context, trigger: AutoStartPolicy.Trigger) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) {
            Log.i(TAG, "$trigger: notifications are off, nothing shown")
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Automatic start", NotificationManager.IMPORTANCE_HIGH),
        )
        val open = PendingIntent.getActivity(
            context, 0, launchIntent(context, trigger),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when (trigger) {
            AutoStartPolicy.Trigger.BLUETOOTH -> "Your iPhone is connected. Tap to start CarPlay."
            AutoStartPolicy.Trigger.BOOT -> "Tap to start CarPlay."
        }
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_diplay_notification)
            .setContentTitle("DiPlay")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
