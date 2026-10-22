package com.shilapi.xcertplay.hud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Hides or restores the instrument cluster's ADAS pane.
 *
 * BYD's ClusterDebug app has an exported receiver without a permission that passes a frame to
 * `BYDAutoTestDevice.set(TEST_SIMULATE_DOWN_SET)`, a real write to the car's bus. On a Han the pane is hidden by
 * the frame `43,20,00,00,01,01`, sent 50 times (verified on the car, parked and driving). Frames alone do not
 * bring it back: the cluster's own reset, the AutoContainer commands 12 then 53, does. Hiding hides everything
 * the pane shows, including the distance and lane warnings, so it is a manual action while parked.
 */
object BydClusterAdasPane {
    private const val TAG = "DiPlay-AdasPane"
    const val PACKAGE = "com.byd.clusterdebug"
    const val RECEIVER = "com.byd.clusterdebug.BroadcastReceiverCAN"
    const val ACTION = "com.byd.cluster.spi"
    const val EXTRA_FRAME = "wholeFrame"
    const val HIDE_FRAME = "43,20,00,00,01,01"
    const val HIDE_REPEATS = 50
    val RESTORE_COMMANDS = listOf(12, 53)
    const val RESTORE_GAP_MILLIS = 1_200L
    private val shell = BydAdbShell(TAG)

    enum class HideDecision { ALLOWED, UNAVAILABLE, NOT_PARKED, GEAR_UNKNOWN }

    /** The car must be known to be in P; an unreadable gear never counts as parked. */
    fun hideDecision(available: Boolean, parked: Boolean?): HideDecision = when {
        !available -> HideDecision.UNAVAILABLE
        parked == null -> HideDecision.GEAR_UNKNOWN
        !parked -> HideDecision.NOT_PARKED
        else -> HideDecision.ALLOWED
    }

    /** Whether the head unit has the receiver, enabled and exported; nothing is sent. */
    fun available(context: Context): Boolean = runCatching {
        val info = context.packageManager.getReceiverInfo(ComponentName(PACKAGE, RECEIVER), 0)
        info.enabled && info.exported && info.permission == null
    }.getOrDefault(false)

    fun intent(frame: String): Intent = Intent(ACTION)
        .setComponent(ComponentName(PACKAGE, RECEIVER))
        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        .putExtra(EXTRA_FRAME, frame)

    /** Sends the hide frame; true when every broadcast was handed to Android. This does not confirm the cluster. */
    fun hide(context: Context): Boolean = runCatching {
        repeat(HIDE_REPEATS) { context.sendBroadcast(intent(HIDE_FRAME)) }
        Log.i(TAG, "sent $HIDE_REPEATS x $HIDE_FRAME")
        true
    }.getOrElse {
        Log.w(TAG, "could not send $HIDE_FRAME: ${it.javaClass.simpleName}")
        false
    }

    /** Blocking: resets the cluster's mode through the car's network ADB, so call it off the UI thread. */
    fun restore(context: Context): Boolean = restore({ shell.run(context.applicationContext, it) }, Thread::sleep)

    internal fun restore(run: (String) -> String?, pause: (Long) -> Unit): Boolean {
        RESTORE_COMMANDS.forEachIndexed { index, code ->
            if (index > 0) pause(RESTORE_GAP_MILLIS)
            if (BydParcel.value(run(command(code))) == null) {
                Log.w(TAG, "cluster reset command $code was not accepted")
                return false
            }
        }
        Log.i(TAG, "cluster reset sent")
        return true
    }

    internal fun command(code: Int) = "service call AutoContainer 2 i32 1000 i32 $code s16 \"\""
}
