package app.fieldwatch.radio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiNanSessionGateTest {
    @Test fun pendingAttachCannotBecomeCurrentAfterStopAndRestart() {
        val gate = WifiNanSessionGate()
        val oldAttach = gate.begin()
        gate.cancel()
        val newAttach = gate.begin()
        assertFalse(gate.accepts(oldAttach))
        assertTrue(gate.accepts(newAttach))
    }

    @Test fun availabilityRecoveryRejectsFormerDiscoveryAndFailureCallbacks() {
        val gate = WifiNanSessionGate()
        val formerSubscription = gate.begin()
        gate.cancel()
        val recoveredSubscription = gate.begin()
        assertFalse(gate.accepts(formerSubscription))
        assertTrue(gate.accepts(recoveredSubscription))
        gate.cancel()
        assertFalse(gate.accepts(recoveredSubscription))
    }

    @Test fun cancelWithoutAnAttachAndRepeatedTeardownCannotAdmitAnyOldToken() {
        val gate = WifiNanSessionGate()
        gate.cancel()
        val tokens = (1..50).map { gate.begin().also { gate.cancel() } }
        gate.cancel()
        tokens.forEach { assertFalse(gate.accepts(it)) }
        assertFalse(gate.accepts(0))
    }
}
