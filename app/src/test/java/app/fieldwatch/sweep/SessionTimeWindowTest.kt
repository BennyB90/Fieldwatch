package app.fieldwatch.sweep

import org.junit.Assert.*
import org.junit.Test

class SessionTimeWindowTest {
    private val startedAt = 1_000_000L
    private val startedElapsed = 10_000L
    private val window = SessionTimeWindow(startedAt, startedElapsed, SessionTimeWindow.ROOM_DURATION_MS)

    @Test fun collectionEndsAtTwentyMinutesEvenWhenTheTickerHasNotRun() {
        val duration = SessionTimeWindow.ROOM_DURATION_MS
        assertEquals(SessionTimeWindow.State.OPEN, window.state(startedAt + duration - 1, startedElapsed + duration - 1))
        assertEquals(SessionTimeWindow.State.EXPIRED, window.state(startedAt + duration, startedElapsed + duration))
        assertFalse(window.acceptsMeasurement(startedElapsed + duration, startedAt + duration, startedElapsed + duration))
        assertFalse(window.acceptsMeasurement(startedElapsed + duration - 1, startedAt + duration + 60_000, startedElapsed + duration + 60_000))
    }

    @Test fun queuedMeasurementsMustBelongToThisWindowAndStillBeProcessedInTime() {
        assertTrue(window.acceptsMeasurement(startedElapsed + 1_000, startedAt + 10_000, startedElapsed + 10_000))
        assertFalse(window.acceptsMeasurement(null, startedAt + 10_000, startedElapsed + 10_000))
        assertFalse(window.acceptsMeasurement(startedElapsed - 1, startedAt + 10_000, startedElapsed + 10_000))
        assertFalse(window.acceptsMeasurement(startedElapsed + 10_001, startedAt + 10_000, startedElapsed + 10_000))
    }

    @Test fun backwardsClockChangeInterruptsRatherThanSilentlyDroppingFutureSightings() {
        assertEquals(SessionTimeWindow.State.CLOCK_CHANGED, window.state(startedAt - 60_000, startedElapsed + 10_000))
        assertFalse(window.acceptsMeasurement(startedElapsed + 1_000, startedAt - 60_000, startedElapsed + 10_000))
    }

    @Test fun correctionIsDetectedEvenWhenWallClockRemainsAfterSessionStart() {
        assertEquals(SessionTimeWindow.State.CLOCK_CHANGED, window.state(startedAt + 60_000, startedElapsed + 120_000))
    }

    @Test fun forwardClockChangeIsNotMistakenForSessionDurationExpiry() {
        assertEquals(SessionTimeWindow.State.CLOCK_CHANGED, window.state(startedAt + 3_600_000, startedElapsed + 10_000))
    }

    @Test fun smallTimeSynchronizationAdjustmentsAreTolerated() {
        assertEquals(SessionTimeWindow.State.OPEN, window.state(startedAt + 15_000, startedElapsed + 10_000))
        assertEquals(SessionTimeWindow.State.OPEN, window.state(startedAt + 5_000, startedElapsed + 10_000))
        assertEquals(SessionTimeWindow.State.CLOCK_CHANGED, window.state(startedAt + 15_001, startedElapsed + 10_000))
        assertEquals(SessionTimeWindow.State.CLOCK_CHANGED, window.state(startedAt + 4_999, startedElapsed + 10_000))
    }

    @Test fun rebootOrOverflowCannotCreateAnOpenWindow() {
        assertEquals(SessionTimeWindow.State.CLOCK_CHANGED, window.state(startedAt + 1, startedElapsed - 1))
        assertEquals(SessionTimeWindow.State.CLOCK_CHANGED, window.state(Long.MIN_VALUE, Long.MAX_VALUE))
    }
}
