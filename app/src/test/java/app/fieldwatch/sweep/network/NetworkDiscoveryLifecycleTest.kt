package app.fieldwatch.sweep.network

import org.junit.Assert.*
import org.junit.Test

class NetworkDiscoveryLifecycleTest {
    @Test fun localTimeoutAndCaughtExceptionPreserveLastActualReceipt() {
        assertEquals(1_000L, NetworkDiscoveryReceiptTime.afterOutcome(1_000, null))
        val resolvedReceipt = NetworkDiscoveryReceiptTime.afterOutcome(1_000, 1_250)
        assertEquals(1_250L, resolvedReceipt)
        assertEquals(1_250L, NetworkDiscoveryReceiptTime.afterOutcome(resolvedReceipt, null))
    }

    @Test fun genuineAndroidCallbackUsesItsReceiptTimeWithoutGuessingPacketAge() {
        assertEquals(2_000L, NetworkDiscoveryReceiptTime.afterOutcome(1_000, 2_000))
        // A wall-clock correction remains visible; elapsed/packet age must not be invented.
        assertEquals(900L, NetworkDiscoveryReceiptTime.afterOutcome(1_000, 900))
    }

    @Test fun logicalRestartCannotOverlapAnUncancelledNativeResolution() {
        val lease = NetworkDiscoveryResolutionLease<Any, String>()
        val guard = NetworkDiscoveryGuard()
        val previous = Any()
        val current = Any()
        val oldToken = guard.begin("wifiA", "wlan0")
        assertTrue(lease.acquire(previous, "old work"))
        guard.cancel()
        val currentToken = guard.begin("wifiA", "wlan0")
        assertFalse(guard.active(oldToken))
        assertTrue(guard.active(currentToken))
        assertTrue(lease.busy())
        assertFalse(lease.acquire(current, "new work"))
        assertEquals("old work", lease.complete(previous))
        assertTrue(lease.acquire(current, "new work"))
        assertEquals("new work", lease.work(current))
    }

    @Test fun unknownOrLateOldCallbackCannotRetireTheCurrentResolver() {
        val lease = NetworkDiscoveryResolutionLease<Any, String>()
        val previous = Any()
        val current = Any()
        assertTrue(lease.acquire(previous, "old work"))
        assertNull(lease.complete(Any()))
        assertTrue(lease.busy())
        assertEquals("old work", lease.complete(previous))
        assertTrue(lease.acquire(current, "new work"))
        assertNull(lease.complete(previous))
        assertTrue(lease.busy())
        assertEquals("new work", lease.snapshot()?.work)
    }

    @Test fun failedStopRetainsHandleAndBackoffIsBoundedUntilActualAcknowledgment() {
        val cleanup = NetworkDiscoveryCleanup<Any>(capacity = 1)
        val listener = Any()
        assertTrue(cleanup.track(listener))
        cleanup.requestStop(listener, 0)
        assertTrue(cleanup.tryStop(listener, 0))
        // A thrown stop or failure callback is not an acknowledgment and cannot bypass backoff.
        assertTrue(cleanup.pending())
        assertFalse(cleanup.tryStop(listener, 249))
        assertTrue(cleanup.tryStop(listener, 250))
        assertTrue(cleanup.tryStop(listener, 750))
        assertTrue(cleanup.tryStop(listener, 1_750))
        assertFalse(cleanup.tryStop(listener, 3_750))
        assertTrue(cleanup.exhausted(3_750))
        assertNull(cleanup.nextDelayMs(3_750))
        assertTrue(cleanup.pending())
        assertFalse(cleanup.track(Any()))
        assertTrue(cleanup.acknowledge(listener))
        assertFalse(cleanup.pending())
    }

    @Test fun staleTerminalReceiptCannotReleaseANewListenerWithTheSameServiceType() {
        val cleanup = NetworkDiscoveryCleanup<Any>(capacity = 1)
        val old = Any()
        val current = Any()
        assertTrue(cleanup.track(old))
        cleanup.requestStop(old, 0)
        assertFalse(cleanup.track(current))
        assertTrue(cleanup.acknowledge(old))
        assertTrue(cleanup.track(current))
        cleanup.requestStop(current, 100)
        assertFalse(cleanup.acknowledge(old))
        assertTrue(cleanup.contains(current))
        assertTrue(cleanup.pending())
    }

    @Test fun lateStartAndRepeatedStopCannotResetAnExhaustedAutomaticBudget() {
        val cleanup = NetworkDiscoveryCleanup<Any>(capacity = 1)
        val listener = Any()
        cleanup.track(listener)
        cleanup.requestStop(listener, 0)
        for (now in listOf(0L, 250L, 750L, 1_750L)) assertTrue(cleanup.tryStop(listener, now))
        cleanup.requestStop(listener, 4_000) // Late onDiscoveryStarted/repeated user Stop.
        assertFalse(cleanup.tryStop(listener, 4_000))
        assertTrue(cleanup.exhausted(4_000))
        cleanup.retry(4_000) // An explicit cleanup-only retry retains the handle.
        assertTrue(cleanup.pending())
        assertTrue(cleanup.tryStop(listener, 4_000))
        assertFalse(cleanup.tryStop(listener, 4_001))
    }

    @Test fun startFailureReceiptCanRetireAPendingHandleWithoutInventingAStopReceipt() {
        val cleanup = NetworkDiscoveryCleanup<Any>(capacity = 2)
        val failedStart = Any()
        val waitingStop = Any()
        cleanup.track(failedStart); cleanup.track(waitingStop)
        cleanup.requestStop(failedStart, 0); cleanup.requestStop(waitingStop, 0)
        assertTrue(cleanup.acknowledge(failedStart)) // Actual onStartDiscoveryFailed.
        assertTrue(cleanup.pending())
        assertTrue(cleanup.contains(waitingStop))
        assertTrue(cleanup.acknowledge(waitingStop)) // Actual onDiscoveryStopped.
        assertFalse(cleanup.pending())
    }
}
