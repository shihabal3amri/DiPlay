package com.shilapi.xcertplay.hud

import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The arrival time in the windshield HUD's field 26 ([BydHudPayload.guidance]'s `eta`), Denza N9 only (#301).
 *
 * With the field empty, the N9 HUD shows "到" ("arrive") on its own. A photo of a stock N9 HUD shows
 * "13:18到", so the HUD seems to add the "到" itself and to want the bare clock time. That form is not
 * confirmed on the car: if it needs another one, such as AMap's "预计13:18到达", change [TEMPLATE].
 *
 * Every other car keeps the empty field 26 it has always had. Neither DiPlay nor DiAuto has sent another
 * car a non-empty field 26, so nothing shows how those HUDs would draw it.
 */
internal object BydHudArrival {
    /** What the HUD gets; `%s` is the arrival clock time ("14:36", or "2:36" on a 12-hour head unit). */
    const val TEMPLATE = "%s"

    /** Same firmware as `DiLink51ClusterLayout.DENZA_N9_FINGERPRINT` in :common. */
    const val DENZA_N9_FINGERPRINT = "BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260705.011226:user/release-keys"
    private val FINGERPRINTS = setOf(DENZA_N9_FINGERPRINT)

    fun enabled(fingerprint: String? = Build.FINGERPRINT): Boolean = fingerprint in FINGERPRINTS

    /**
     * The field text: the iPhone's arrival time when it sends one, otherwise [nowMillis] plus the route time
     * left, otherwise empty.
     */
    fun text(
        nowMillis: Long,
        arrivalEpochSeconds: Long?,
        remainingSeconds: Long?,
        zone: TimeZone,
        use24Hour: Boolean,
    ): String {
        val arrivalMillis = when {
            arrivalEpochSeconds != null && arrivalEpochSeconds > 0 -> arrivalEpochSeconds * 1000L
            remainingSeconds != null && remainingSeconds >= 0 -> nowMillis + remainingSeconds * 1000L
            else -> return ""
        }
        val clock = SimpleDateFormat(if (use24Hour) "HH:mm" else "h:mm", Locale.US).apply { timeZone = zone }
        return String.format(Locale.US, TEMPLATE, clock.format(Date(arrivalMillis)))
    }
}
