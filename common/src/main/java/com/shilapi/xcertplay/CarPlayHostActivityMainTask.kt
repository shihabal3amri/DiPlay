// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

/**
 * Same screen as [CarPlayHostActivity] declared with the application's default task affinity (the
 * manifest entry has no android:taskAffinity), so launchers whose picture-in-picture card shows
 * only the main page's task (e.g. Dudu desktop) keep showing CarPlay. CarPlayHostLauncher picks
 * this class when the "same task" setting is on.
 *
 * A real class is required rather than an activity-alias: an alias always inherits the target
 * activity's taskAffinity at package parse time (AOSP PackageParser.parseActivityAlias), so an
 * alias cannot run the same screen in a different task.
 */
class CarPlayHostActivityMainTask : CarPlayHostActivity()
