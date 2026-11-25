package app.fieldwatch.sweep.travel

import app.fieldwatch.domain.Observation
import app.fieldwatch.domain.RadioKind
import org.junit.Assert.*
import org.junit.Test

class TravelVisibilityAndTimingTest {
    private val session = TravelSession("one", 100_000L, 10_000L, locationConsent = true)
    private fun observation(measured: Long? = 20_000L) = Observation(RadioKind.BLE, "AA:BB:CC:DD:EE:01", "", -60,
        0, 2402, false, listOf("FD5A"), null, "", "", "", 110_000L, elapsedRealtimeMs = measured)

    @Test fun permissionAloneDoesNotEstablishVisibleStatusOrAlerts() {
        assertFalse(TravelVisibility.available(false, true, false, false).ready)
        assertFalse(TravelVisibility.available(true, false, false, false).ready)
        val statusBlocked = TravelVisibility.available(true, true, true, false)
        assertFalse(statusBlocked.ready)
        assertTrue(statusBlocked.message.contains("Travel observation"))
        val alertsBlocked = TravelVisibility.available(true, true, false, true)
        assertFalse(alertsBlocked.ready)
        assertTrue(alertsBlocked.message.contains("Repeated tracker observations"))
        assertTrue(TravelVisibility.available(true, true, false, false).ready)
    }

    @Test fun absentOldFutureOrNonfreshMeasurementCannotCreateSightings() {
        assertNull(TravelSampleTiming.elapsed(observation(null), session, 120_000L, 30_000L))
        assertNull(TravelSampleTiming.elapsed(observation(9_999L), session, 120_000L, 30_000L))
        assertNull(TravelSampleTiming.elapsed(observation(30_001L), session, 120_000L, 30_000L))
        assertNull(TravelSampleTiming.elapsed(observation().copy(fresh = false), session, 120_000L, 30_000L))
        assertNull(TravelSampleTiming.elapsed(observation().copy(at = 99_999L), session, 120_000L, 30_000L))
        assertEquals(20_000L, TravelSampleTiming.elapsed(observation(), session, 120_000L, 30_000L))
    }

    @Test fun delayedBleMeasurementDoesNotUseLaterProcessingTimeForGps() {
        val measured = TravelSampleTiming.elapsed(observation(), session, 120_000L, 30_000L)!!
        val laterFix = TravelLocation(40.1, -74.1, 5f, 25_000L)
        assertNull(TravelAnalyzer.validLocation(laterFix, measured))
        assertNotNull(TravelAnalyzer.validLocation(laterFix, 30_000L))
    }

    @Test fun statusSeparatesConsentFromActualFreshFixAvailability() {
        assertTrue(TravelReceptionStatus.text(false, false, false, false, false, null, 40_000L).contains("Bluetooth waiting"))
        assertTrue(TravelReceptionStatus.text(true, false, true, true, false, null, 40_000L).contains("GPS off"))
        assertTrue(TravelReceptionStatus.text(true, true, true, false, false, null, 40_000L).contains("GPS unavailable"))
        assertTrue(TravelReceptionStatus.text(true, true, true, true, true, null, 40_000L).contains("waiting for a fresh"))
        val fresh = TravelLocation(40.1, -74.1, 5f, 40_000L)
        assertTrue(TravelReceptionStatus.text(true, true, true, true, true, fresh, 40_000L).contains("using a fresh"))
        assertTrue(TravelReceptionStatus.text(true, true, true, true, true, fresh, 80_001L).contains("waiting for a fresh"))
        assertTrue(TravelReceptionStatus.text(true, false, false, false, false, null, 40_000L, true).contains("removing saved phone coordinates"))
    }
}
