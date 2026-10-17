package com.shilapi.xcertplay.media

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class VideoCodecLifecycleGuardTest {
    @Test fun aBusyFactoryDoesNotAdmitAnotherCandidateAndTimeoutDoesNotStealItsLease() {
        var now = 0L
        val gate = VideoCodecLifecycleGuard { now }
        val first = gate.startup("main", { true }, 0)!!
        first.phase("create")
        now += VideoCodecLifecycleGuard.PHASE_TIMEOUT_NS + 1
        assertNull(gate.startup("cluster", { true }, 0))
        assertTrue(gate.blockedPhase()!!.contains("phase=create"))
        try { first.phase("configure"); fail("late create must not enter configure") }
        catch (_: VideoCodecLifecycleGuard.StartupAborted) { }
        first.close(); first.close()
        gate.startup("next", { true }, 0)!!.close()
    }

    @Test fun closingAWorkerBetweenPhasesPreventsStartButKeepsCleanupPossible() {
        val gate = VideoCodecLifecycleGuard()
        val lease = gate.startup("closed", { true }, 0)!!
        lease.phase("configure")
        try { lease.phase("start") { false }; fail("closed worker must not start") }
        catch (_: VideoCodecLifecycleGuard.StartupAborted) { }
        assertNotNull(gate.blockedPhase())
        lease.close()
        gate.cleanup("release").close()
    }

    @Test fun cleanupRetainsOwnershipUntilTheOtherNativeCallReturnsAndRestoresInterrupt() {
        val gate = VideoCodecLifecycleGuard()
        val first = gate.startup("blocked", { true }, 0)!!
        val entered = CountDownLatch(1)
        val done = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val cleanup = thread {
            entered.countDown()
            gate.cleanup("release").use { interrupted.set(Thread.currentThread().isInterrupted) }
            done.countDown()
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        cleanup.interrupt()
        assertFalse(done.await(30, TimeUnit.MILLISECONDS))
        assertNull(gate.startup("duplicate", { true }, 0))
        first.close()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertTrue(interrupted.get())
        cleanup.join()
        assertNull(gate.blockedPhase())
    }

    @Test fun interruptedStartupWaitDoesNotPublishASecondLease() {
        val gate = VideoCodecLifecycleGuard()
        val held = gate.startup("held", { true }, 0)!!
        val interrupted = CountDownLatch(1)
        val waiter = thread {
            try { gate.startup("waiting", { true }); fail("must be interrupted") }
            catch (_: InterruptedException) { interrupted.countDown() }
        }
        waiter.interrupt()
        assertTrue(interrupted.await(2, TimeUnit.SECONDS))
        assertTrue(gate.blockedPhase()!!.startsWith("held"))
        held.close(); waiter.join()
    }
}
