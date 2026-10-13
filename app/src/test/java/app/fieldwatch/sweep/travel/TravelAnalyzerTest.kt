package app.fieldwatch.sweep.travel

import org.junit.Assert.*
import org.junit.Test

class TravelAnalyzerTest {
    private val address = "AA:BB:CC:DD:EE:01"
    private val started = 1_000_000L
    private val elapsed = 10_000L
    private fun sample(minutes: Int, location: TravelLocation? = null) = TravelSample(started + minutes * 60_000L, elapsed + minutes * 60_000L, -60, location)
    private fun session(consent: Boolean = false) = TravelSession("trip", started, elapsed, locationConsent = consent)
    private fun record(session: TravelSession, minutes: Int, fix: TravelLocation? = null, ignored: Set<String> = emptySet()) =
        TravelSamples.record(session, address, "Item-finder signal", sample(minutes, fix), ignored)

    @Test fun rapidPacketsAndDuplicatesAreNotRepeatedEvidence() {
        var trip = session()
        repeat(100) { trip = TravelSamples.record(trip, address, "Item-finder signal", TravelSample(started + it, elapsed + it, -60), emptySet()) }
        assertEquals(1, trip.trackers.single().samples.size)
        assertTrue(trip.assessments.isEmpty())
    }

    @Test fun timeOnlyObservationsDoNotClaimMovement() {
        var trip = session()
        listOf(0, 5, 10, 15).forEach { trip = record(trip, it) }
        val assessment = trip.assessments.single()
        assertEquals(TravelEvidenceKind.REPEATED_OBSERVATIONS, assessment.kind)
        assertEquals(4, assessment.observations)
        assertEquals(0, assessment.distinctPhoneLocations)
        assertTrue(assessment.explanation.contains("Movement and locations are unverified"))
        assertFalse(assessment.explanation.contains("moving with you"))
        assertFalse(assessment.explanation.contains("following you"))
    }

    @Test fun accurateStationaryObservationsRemainTimeOnly() {
        var trip = session(true)
        listOf(0, 5, 10, 15).forEach {
            trip = record(trip, it, TravelLocation(40.0, -74.0, 10f, elapsed + it * 60_000L))
        }
        assertEquals(TravelEvidenceKind.REPEATED_OBSERVATIONS, trip.assessments.single().kind)
        assertEquals(1, trip.assessments.single().distinctPhoneLocations)
    }

    @Test fun freshSeparatedPhoneLocationsAreLabeledAsObservations() {
        var trip = session(true)
        listOf(0, 5, 10, 15).forEachIndexed { index, minute ->
            trip = record(trip, minute, TravelLocation(40.0 + index * 0.004, -74.0, 10f, elapsed + minute * 60_000L))
        }
        assertEquals(TravelEvidenceKind.DISTINCT_PHONE_LOCATIONS, trip.assessments.single().kind)
        assertEquals(4, trip.assessments.single().distinctPhoneLocations)
        assertTrue(trip.assessments.single().explanation.contains("does not prove unwanted tracking"))
    }

    @Test fun staleFutureAndInaccurateLocationsAreNeitherSavedNorCorrelated() {
        var trip = session(true)
        val fixes = listOf(
            TravelLocation(40.0, -74.0, 10f, elapsed - 60_000),
            TravelLocation(40.1, -74.0, 10f, elapsed + 5 * 60_000 + 1),
            TravelLocation(40.2, -74.0, 100f, elapsed + 10 * 60_000),
            TravelLocation(Double.NaN, -74.0, 10f, elapsed + 15 * 60_000),
        )
        listOf(0, 5, 10, 15).forEachIndexed { index, minute -> trip = record(trip, minute, fixes[index]) }
        assertTrue(trip.trackers.single().samples.all { it.location == null })
        assertEquals(TravelEvidenceKind.REPEATED_OBSERVATIONS, trip.assessments.single().kind)
    }

    @Test fun coordinatesRequireSeparateConsentEvenIfCallerSuppliesThem() {
        val trip = record(session(false), 0, TravelLocation(40.0, -74.0, 5f, elapsed))
        assertNull(trip.trackers.single().samples.single().location)
    }

    @Test fun gpsJitterWithinUncertaintyDoesNotCreateDistinctLocations() {
        var trip = session(true)
        listOf(0, 5, 10, 15).forEachIndexed { index, minute ->
            trip = record(trip, minute, TravelLocation(40.0 + index * 0.0001, -74.0, 50f, elapsed + minute * 60_000L))
        }
        assertEquals(1, trip.assessments.single().distinctPhoneLocations)
    }

    @Test fun familiarIgnoredAddressNeverProducesSamplesOrNotice() {
        var trip = session()
        listOf(0, 5, 10, 15).forEach { trip = record(trip, it, ignored = setOf(address)) }
        assertTrue(trip.trackers.isEmpty())
        assertTrue(trip.assessments.isEmpty())
    }

    @Test fun changedRandomizedAddressesAreNotLinked() {
        var trip = session()
        listOf(0, 5, 10, 15).forEachIndexed { index, minute ->
            trip = TravelSamples.record(trip, "AA:BB:CC:DD:EE:0${index + 1}", "Same protocol signal", sample(minute), emptySet())
        }
        assertEquals(4, trip.trackers.size)
        assertTrue(trip.assessments.isEmpty())
    }

    @Test fun shortIntervalsLongGapAndOldSessionDoNotQualify() {
        var trip = session()
        listOf(0, 5, 10, 30).forEach { trip = record(trip, it) }
        assertTrue(trip.assessments.isEmpty())
        val old = TravelSample(started - 1, elapsed - 1, -60)
        assertEquals(session(), TravelSamples.record(session(), address, "Item finder", old, emptySet()))
        assertEquals(trip, TravelSamples.record(trip, address, "Item finder", sample(5), emptySet()))
    }

    @Test fun defaultShareOmitsIdentifiersCoordinatesAndExactTimes() {
        val trip = record(session(true), 0, TravelLocation(40.123456, -74.123456, 5f, elapsed))
        val public = TravelReports.text(trip)
        assertFalse(public.contains(address))
        assertFalse(public.contains("40.123456"))
        assertFalse(public.contains("-74.123456"))
        assertFalse(public.contains(started.toString()))
        val explicit = TravelReports.text(trip, includeIdentifiers = true, includeLocations = true)
        assertTrue(explicit.contains(address))
        assertTrue(explicit.contains("40.123456"))
    }
}
