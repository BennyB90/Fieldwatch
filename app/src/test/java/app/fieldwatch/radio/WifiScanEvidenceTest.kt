package app.fieldwatch.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiScanEvidenceTest {
    @Test fun successfulScanDoesNotMakePreSessionCacheFresh() {
        val evidence = WifiScanEvidence(10_000_000L)
        assertNull(evidence.observationTimeMs("AP", 9_999_999L, 12_000_000L, 100_000L))
        assertEquals(99_000L, evidence.observationTimeMs("AP", 11_000_000L, 12_000_000L, 100_000L))
    }

    @Test fun repeatedDeliveryDoesNotRefreshAgeOrBecomeAnotherHit() {
        val evidence = WifiScanEvidence(10_000_000L)
        assertEquals(99_000L, evidence.observationTimeMs("AP", 11_000_000L, 12_000_000L, 100_000L))
        assertNull(evidence.observationTimeMs("AP", 11_000_000L, 15_000_000L, 103_000L))
        assertNull(evidence.observationTimeMs("AP", 10_500_000L, 15_000_000L, 103_000L))
        assertEquals(102_000L, evidence.observationTimeMs("AP", 14_000_000L, 15_000_000L, 103_000L))
    }

    @Test fun invalidTimestampsCannotContaminateTheNextValidSample() {
        val evidence = WifiScanEvidence(10_000_000L)
        assertNull(evidence.observationTimeMs("AP", 0L, 12_000_000L, 100_000L))
        assertNull(evidence.observationTimeMs("AP", 13_000_000L, 12_000_000L, 100_000L))
        assertEquals(99_000L, evidence.observationTimeMs("AP", 11_000_000L, 12_000_000L, 100_000L))
    }

    @Test fun sampleTrackingIsPerApAndResetsBetweenSessions() {
        val evidence = WifiScanEvidence(10_000_000L)
        assertEquals(99_000L, evidence.observationTimeMs("A", 11_000_000L, 12_000_000L, 100_000L))
        assertEquals(99_000L, evidence.observationTimeMs("B", 11_000_000L, 12_000_000L, 100_000L))
        val nextSession = WifiScanEvidence(13_000_000L)
        assertNull(nextSession.observationTimeMs("A", 11_000_000L, 14_000_000L, 102_000L))
    }
}
