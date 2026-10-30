package com.shilapi.xcertplay

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.drawable.InsetDrawable
import android.view.ContextThemeWrapper
import com.shilapi.xcertplay.host.R
import kotlin.math.roundToInt

internal interface AppAppearanceOwner {
    val currentAppNight: Boolean
}

internal fun Context.appDialogTheme(): Int = if (
    (this as? AppAppearanceOwner)?.currentAppNight ?: resolveAppNightNow()
) {
    R.style.Theme_Xcertplay_Dialog_Dark
} else {
    R.style.Theme_Xcertplay_Dialog_Light
}

internal fun Context.appDialogContext(): Context = ContextThemeWrapper(this, appDialogTheme())

internal fun Context.appDialogBuilder(): AlertDialog.Builder = AlertDialog.Builder(this, appDialogTheme())

internal fun Context.appDialog(): Dialog = Dialog(this, appDialogTheme())

/** Reserve visible space above and below tall dialogs using the native window background padding. */
internal fun Dialog.addVerticalWindowInsets() {
    val dialogWindow = window ?: return
    val background = dialogWindow.decorView.background ?: return
    val inset = (APP_DIALOG_VERTICAL_INSET_DP * context.resources.displayMetrics.density).roundToInt()
    dialogWindow.setBackgroundDrawable(InsetDrawable(background, 0, inset, 0, inset))
}

private const val APP_DIALOG_VERTICAL_INSET_DP = 24
