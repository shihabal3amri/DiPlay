// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Picks the CarPlay screen component by the "same task" setting.
 *
 * Since 0.2.12 the CarPlay screen runs in its own task (taskAffinity `.carplay` on
 * [CarPlayHostActivity]), so launchers whose picture-in-picture card shows only the task of the
 * DiPlay main page (e.g. Dudu desktop) lose the CarPlay picture once it opens.
 * [CarPlayHostActivityMainTask] declares the same screen with the application's default affinity,
 * so launching through it keeps CarPlay and the main page in one task. A real activity is required
 * rather than an activity-alias: an alias always inherits the target activity's taskAffinity at
 * package parse time (AOSP PackageParser.parseActivityAlias), so it cannot change it.
 */
internal object CarPlayHostLauncher {

    /** The component of [CarPlayHostActivity] with the default affinity, used when the "same task" setting is on. */
    const val MAIN_TASK_COMPONENT = "com.shilapi.xcertplay.CarPlayHostActivityMainTask"

    /** The component to open: the main-task class when the "same task" setting is on. */
    fun component(context: Context): ComponentName =
        if (AirPlayPersistence.loadCarPlaySameTask(context)) {
            ComponentName(context, CarPlayHostActivityMainTask::class.java)
        } else {
            ComponentName(context, CarPlayHostActivity::class.java)
        }

    /** A fresh intent for the CarPlay screen component, with the given [flags] added. */
    fun intent(context: Context, flags: Int = 0): Intent =
        Intent().setComponent(component(context)).addFlags(flags)
}
