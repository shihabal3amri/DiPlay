package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WiredTeardownGuardTest {
    @Test fun pendingOwnerBlocksUntilItsTeardownReallyFinishes() {
        var done = false
        WiredTeardownGuard.hold({ done })
        assertFalse(WiredTeardownGuard.ready())
        done = true
        assertTrue(WiredTeardownGuard.ready())
        assertTrue(WiredTeardownGuard.ready())
    }

    @Test fun failedOwnerIsDistinctFromPendingAndRemainsBlocked() {
        var closed = false
        var failed = false
        WiredTeardownGuard.hold({ closed }, { failed })
        assertTrue(WiredTeardownGuard.status() == WiredTeardownGuard.Status.PENDING)
        failed = true
        assertTrue(WiredTeardownGuard.status() == WiredTeardownGuard.Status.FAILED)
        assertFalse(WiredTeardownGuard.ready())
        closed = true
        assertTrue(WiredTeardownGuard.ready())
    }
}
