package com.shilapi.xcertplay

import android.content.Context
import android.provider.Settings
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.ConnectionFailureCause
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** #1180: with "Video while parked" on and the gear unreadable, Home and the report say why video stays off. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en")
class VideoGearProblemTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("diplay_last_problem", Context.MODE_PRIVATE)
    private val off = "Video while parked is off: DiPlay cannot read the gear."

    @Before fun setUp() {
        prefs.edit().clear().commit()
        BydOutputSettings.setVideoWhileParked(context, true)
    }

    @After fun tearDown() {
        prefs.edit().clear().commit()
        BydOutputSettings.setVideoWhileParked(context, false)
        ReflectionHelpers.setField(CarPlayVideo, "appContext", null)
    }

    private fun notPaired() = LastConnectionProblem.follow(context, CarPlayStatus.Failed("failed",
        startupFailure = WirelessStartupFailure.HOTSPOT_CONFIGURATION, knownCause = ConnectionFailureCause.CHOSEN_IPHONE_NOT_PAIRED))

    @Test fun anUnapprovedAdbKeyIsNamedOnHomeDuringTheSessionAndInTheReport() {
        LastConnectionProblem.videoGearUnreadable(context, LocalAdb.Access.NOT_APPROVED, replied = false)

        val hint = context.getString(R.string.adb_not_approved)
        assertEquals("$off $hint", LastConnectionProblem.homeLine(context, sessionActive = true))
        assertEquals("$off $hint", LastConnectionProblem.homeLine(context, sessionActive = false))
        val report = LastConnectionProblem.report(context)
        assertTrue(report, report.matches(Regex(Regex.escape("Last problem: $off $hint (video:gear:NOT_APPROVED) at ") +
            "\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} [+-]\\d{4}")))
    }

    @Test fun eachReasonGivesItsOwnHint() {
        fun hint(adb: LocalAdb.Access?, replied: Boolean): String? {
            LastConnectionProblem.videoGearUnreadable(context, adb, replied)
            return LastConnectionProblem.homeLine(context)?.removePrefix("$off ")
        }
        Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0)
        assertEquals(context.getString(R.string.adb_off), hint(LocalAdb.Access.UNREACHABLE, false))
        Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 1)
        assertEquals(context.getString(R.string.adb_network_not_answering), hint(LocalAdb.Access.UNREACHABLE, false))
        assertEquals(context.getString(R.string.adb_pairing_only), hint(LocalAdb.Access.UNSUPPORTED, false))
        assertEquals(context.getString(R.string.adb_vehicle_gear_unreadable), hint(LocalAdb.Access.READY, true))
        assertEquals(context.getString(R.string.adb_check_failed), hint(LocalAdb.Access.READY, false))
        assertEquals(context.getString(R.string.adb_not_checked), hint(null, false))
        assertEquals("video:gear:unknown", prefs.getString("problem", null))
    }

    @Test fun aConnectionProblemStaysOffHomeDuringASessionAndIsNotDroppedByAReadableGear() {
        notPaired()
        assertNull(LastConnectionProblem.homeLine(context, sessionActive = true))
        LastConnectionProblem.videoGearReadable(context)
        assertEquals(context.getString(R.string.chosen_iphone_not_paired),
            LastConnectionProblem.load(context)?.problem?.line()?.let(context::getString))
    }

    @Test fun aStartingSessionForgetsAConnectionProblemButNotVideoThatStaysOff() {
        notPaired()
        LastConnectionProblem.sessionStarted(context)
        assertNull(LastConnectionProblem.load(context))

        // The video gate reads the gear as the controller starts, before the session is active.
        LastConnectionProblem.videoGearUnreadable(context, LocalAdb.Access.NOT_APPROVED, replied = false)
        LastConnectionProblem.sessionStarted(context)
        assertEquals("video:gear:NOT_APPROVED", prefs.getString("problem", null))

        LastConnectionProblem.videoGearReadable(context)
        assertNull(LastConnectionProblem.load(context))
    }

    @Test fun turningTheSettingOffHidesIt() {
        LastConnectionProblem.videoGearUnreadable(context, LocalAdb.Access.NOT_APPROVED, replied = false)
        BydOutputSettings.setVideoWhileParked(context, false)
        assertNull(LastConnectionProblem.homeLine(context, sessionActive = true))
    }

    @Test fun theSameReasonIsWrittenOnceSoItsTimeIsWhenItStarted() {
        LastConnectionProblem.videoGearUnreadable(context, LocalAdb.Access.NOT_APPROVED, replied = false)
        prefs.edit().putLong("at", 1_000L).commit()
        LastConnectionProblem.videoGearUnreadable(context, LocalAdb.Access.NOT_APPROVED, replied = false)
        assertEquals(1_000L, LastConnectionProblem.load(context)?.atMillis)
    }

    @Test fun aVideoValueThisVersionCannotReadIsRemoved() {
        for (token in listOf("video:gear:SOMETHING_NEW", "video:other", "video:")) {
            prefs.edit().putString("problem", token).putLong("at", 1_000L).commit()
            assertNull(token, LastConnectionProblem.load(context))
        }
    }

    @Test fun theVideoHostRecordsAndClearsItFromTheGate() {
        ReflectionHelpers.setField(CarPlayVideo, "appContext", context)
        CarPlayVideo.onGearObserved(null) // no read has run here, so the ADB state is unknown
        assertEquals("video:gear:unknown", prefs.getString("problem", null))
        CarPlayVideo.onGearObserved(false)
        assertNull(LastConnectionProblem.load(context))
    }
}
