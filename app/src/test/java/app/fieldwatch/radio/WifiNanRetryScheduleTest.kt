package app.fieldwatch.radio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiNanRetryScheduleTest {
    @Test fun repeatedTickerCallsAttemptAtMostOncePerThirtySeconds() {
        val retry = WifiNanRetrySchedule()
        assertTrue(retry.tryAttempt(1_000))
        for (elapsed in 1_001L until 31_000L) assertFalse(retry.tryAttempt(elapsed))
        assertTrue(retry.tryAttempt(31_000))
        assertFalse(retry.tryAttempt(31_001))
    }

    @Test fun attachTimeoutWaitsThirtySecondsAfterFailure() {
        val retry = WifiNanRetrySchedule()
        assertTrue(retry.tryAttempt(1_000))
        retry.defer(11_000)
        assertFalse(retry.tryAttempt(31_000))
        assertFalse(retry.tryAttempt(40_999))
        assertTrue(retry.tryAttempt(41_000))
    }

    @Test fun explicitNewSessionOrAvailabilityChangeCanResetTheDeadline() {
        val retry = WifiNanRetrySchedule()
        assertTrue(retry.tryAttempt(1_000))
        retry.reset()
        assertTrue(retry.tryAttempt(2_000))
        assertFalse(retry.tryAttempt(2_001))
    }

    @Test fun invalidClockAndOverflowCannotRemoveTheBackoff() {
        val retry = WifiNanRetrySchedule()
        assertFalse(retry.tryAttempt(-1))
        assertTrue(retry.tryAttempt(Long.MAX_VALUE - 1))
        assertFalse(retry.tryAttempt(Long.MAX_VALUE - 1))
    }
}
