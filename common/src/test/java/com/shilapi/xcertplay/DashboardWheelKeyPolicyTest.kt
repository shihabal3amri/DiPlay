package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class DashboardWheelKeyPolicyTest {
    @Test fun learningUsesExplicitMenuMediaCodesAndExcludesDrivingControls() {
        assertTrue(DashboardWheelKeyPolicy.isPageKeyAllowed(200082))
        assertTrue(DashboardWheelKeyPolicy.isPageKeyAllowed(200600))
        assertTrue(DashboardWheelKeyPolicy.isPageKeyAllowed(300050))
        listOf(0, 19, 20, 21, 22, 23, 200024, 200025, 210007, 210008, 210009,
            210010, NativeDashboardSettings.MODE_KEY, 300001, 300002, 300010, 300042, 999999).forEach {
            assertFalse("Must not use unconfirmed/ADAS/roller key $it", DashboardWheelKeyPolicy.isPageKeyAllowed(it))
        }
        val codes = DashboardWheelKeyPolicy.registeredCodes(true, true, false, 200082, true)
        assertEquals(DashboardWheelKeyPolicy.learningCandidates, codes.toList())
        assertFalse(codes.isEmpty())
        assertFalse(NativeDashboardSettings.MODE_KEY in codes)
        assertEquals(14, codes.size)
        assertEquals(codes.size, codes.distinct().size)
    }

    @Test fun unboundDashboardObservesCandidatesWhileConfirmedBindingNarrowsRegistration() {
        assertEquals(listOf(200110) + DashboardWheelKeyPolicy.learningCandidates,
            DashboardWheelKeyPolicy.registeredCodes(false, true, false, 0, false).toList())
        assertEquals(listOf(200110, 200082), DashboardWheelKeyPolicy.registeredCodes(false, true, false, 200082, false).toList())
        assertEquals(listOf(200110, 300050), DashboardWheelKeyPolicy.registeredCodes(false, true, false, 300050, false).toList())
        assertEquals(listOf(200110), DashboardWheelKeyPolicy.registeredCodes(false, true, false, 200024, false).toList())
        assertTrue(DashboardWheelKeyPolicy.passivelyObservesCandidates(true, 0, false))
        assertFalse(DashboardWheelKeyPolicy.passivelyObservesCandidates(false, 0, false))
        assertFalse(DashboardWheelKeyPolicy.passivelyObservesCandidates(true, 300050, false))
        assertFalse(DashboardWheelKeyPolicy.passivelyObservesCandidates(true, 0, true))
        assertTrue(DashboardWheelKeyPolicy.registeredCodes(false, false, false, 0, false).isEmpty())
    }

    @Test fun bluetoothMediaExclusionRemainsExceptAnExplicitPageBinding() {
        val usual = DashboardWheelKeyPolicy.registeredCodes(true, true, true, 200082, false)
        assertEquals(listOf(GeelyWheelKeyCodes.KEY_VOICE_ASSIST, 200110, 200082), usual.toList())
        val mediaAsPage = DashboardWheelKeyPolicy.registeredCodes(true, true, false, GeelyWheelKeyCodes.KEY_NEXT, false)
        assertEquals(1, mediaAsPage.count { it == GeelyWheelKeyCodes.KEY_NEXT })
    }

    @Test fun calibratedModeIsRegisteredOnceAndNeverOfferedForPageLearning() {
        assertTrue(DashboardWheelKeyPolicy.isModeKeyAllowed(200400))
        assertTrue(DashboardWheelKeyPolicy.isModeKeyAllowed(200110))
        listOf(0,200024,210007,210008,999999).forEach { assertFalse(DashboardWheelKeyPolicy.isModeKeyAllowed(it)) }
        assertFalse(DashboardWheelKeyPolicy.isPageKeyAllowed(200400,200400))
        assertEquals(listOf(200400,200082),
            DashboardWheelKeyPolicy.registeredCodes(false,true,false,200082,false,200400).toList())
        val passive=DashboardWheelKeyPolicy.registeredCodes(false,true,false,0,false,200400).toList()
        assertEquals(1,passive.count { it==200400 })
        assertFalse(200110 in passive)
        val learning=DashboardWheelKeyPolicy.registeredCodes(true,true,false,0,true,200400).toList()
        assertEquals(DashboardWheelKeyPolicy.learningCandidates-200400,learning)
        assertEquals(listOf(200400),
            DashboardWheelKeyPolicy.registeredCodes(false,true,false,200400,false,200400).toList())
    }

    @Test fun debounceRejectsLongPressAndDuplicatesButDoesNotShareTimingAcrossKeys() {
        val keys = DashboardWheelDebounce()
        assertFalse(keys.accept(200110, true, 1000))
        assertTrue(keys.accept(200110, false, 1000))
        assertTrue(keys.accept(200082, false, 1001))
        assertFalse(keys.accept(200110, false, 1100))
        assertFalse(keys.accept(200082, false, 1101))
        assertTrue(keys.accept(200110, false, 1350))
        keys.reset()
        assertTrue(keys.accept(200110, false, 1351))
    }
}
