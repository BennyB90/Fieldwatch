package app.fieldwatch.sweep.network

import app.fieldwatch.sweep.SweepRadioGate
import org.junit.Assert.*
import org.junit.Test

class NetworkRunLeaseTest {
    @Test fun delayedAndOffscreenResultsDoNotExtendTheWindow() {
        val lease = NetworkRunLease(SweepRadioGate())
        assertTrue(lease.acquire("one", 10_000L))
        assertTrue(lease.accepts("one", 69_999L, true))
        assertFalse(lease.accepts("one", 70_000L, true))
        assertFalse(lease.accepts("one", 9_999L, true))
        assertFalse(lease.accepts("one", 11_000L, false))
    }

    @Test fun oldCallbacksAndReleasesCannotOwnAnotherScan() {
        val gate = SweepRadioGate()
        val lease = NetworkRunLease(gate)
        assertTrue(lease.acquire("one", 0))
        assertTrue(lease.release("one"))
        assertTrue(lease.acquire("two", 1_000))
        assertFalse(lease.accepts("one", 1_001, true))
        assertFalse(lease.release("one"))
        assertTrue(gate.held())
        assertTrue(lease.release("two"))
        assertFalse(gate.held())
    }

    @Test fun competingRadioModesKeepTheirOwnLease() {
        val gate = SweepRadioGate()
        val lease = NetworkRunLease(gate)
        assertTrue(gate.acquire("room"))
        assertFalse(lease.acquire("one", 0))
        assertFalse(lease.release("one"))
        assertTrue(gate.held())
        assertTrue(gate.release("room"))
        assertTrue(lease.acquire("one", 0))
        assertFalse(gate.acquire("travel"))
    }
}
