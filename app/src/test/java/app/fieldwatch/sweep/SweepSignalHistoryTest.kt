package app.fieldwatch.sweep

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SweepSignalHistoryTest {
    private val json = Json { encodeDefaults = true }

    @Test fun freshMeasuredObservationKeepsItsActualTimestampAndRssi() {
        val result = SweepSignalHistory.record(finding(), null, 1_234, -71)
        assertEquals(listOf(SweepSignalSample(1_234, -71)), result.signalSamples)
        assertEquals(8_000L, result.lastSeenAt)
    }

    @Test fun unavailableRawRssiDoesNotReuseTheMappersStickyValue() {
        val result = SweepSignalHistory.record(finding().copy(rssi = -50), null, 1_234, 127)
        assertTrue(result.signalSamples.isEmpty())
    }

    @Test fun cachedObservationsCannotCreateNewSignalSamples() {
        val previous = finding().copy(signalSamples = listOf(SweepSignalSample(1_100, -60)))
        val result = SweepSignalHistory.record(finding(), previous, 1_500, -40, fresh = false)
        assertEquals(previous.signalSamples, result.signalSamples)
    }

    @Test fun duplicateAndConflictingTimestampRetainTheFirstMeasuredValue() {
        val samples = listOf(SweepSignalSample(1_100, -60))
        assertEquals(samples, SweepSignalHistory.append(samples, 1_100, -60))
        assertEquals(samples, SweepSignalHistory.append(samples, 1_100, -40))
    }

    @Test fun outOfOrderDeliveryKeepsSortedRealMeasurements() {
        val result = SweepSignalHistory.append(listOf(SweepSignalSample(1_300, -50)), 1_100, -70)
        assertEquals(listOf(SweepSignalSample(1_100, -70), SweepSignalSample(1_300, -50)), result)
    }

    @Test fun capacityRetainsTheNewestTimestampsNotTheLatestDelivery() {
        val samples = (1L..200L).map { SweepSignalSample(it, -60) }.reversed()
        val bounded = SweepSignalHistory.bounded(samples)
        assertEquals(SweepSignalHistory.MAX_SAMPLES, bounded.size)
        assertEquals(137L, bounded.first().observedAt)
        assertEquals(200L, bounded.last().observedAt)
        assertEquals(bounded, SweepSignalHistory.append(bounded, 1, -20))
    }

    @Test fun invalidTimesAndUnavailableOrOutOfRangeStrengthAreRejected() {
        val samples = listOf(SweepSignalSample(0, -60), SweepSignalSample(-1, -60),
            SweepSignalSample(1, 127), SweepSignalSample(2, -128), SweepSignalSample(3, 128))
        assertTrue(SweepSignalHistory.bounded(samples).isEmpty())
        // Preserve the upstream measured-value convention; NAN uses 127, not zero.
        assertEquals(listOf(SweepSignalSample(4, 0)), SweepSignalHistory.append(emptyList(), 4, 0))
    }

    @Test fun findingTransportOrIdentityChangesCannotInheritAnotherHistory() {
        val previous = finding().copy(signalSamples = listOf(SweepSignalSample(1_100, -60)))
        for (next in listOf(finding().copy(id = "different"), finding().copy(transport = "WIFI"),
            finding().copy(identifier = "10:20:30:40:50:99"))) {
            assertEquals(listOf(SweepSignalSample(1_500, -40)),
                SweepSignalHistory.record(next, previous, 1_500, -40).signalSamples)
        }
    }

    @Test fun olderJsonLoadsWithEmptyHistoryAndBackwardCompatibleNanCoverageDefaults() {
        val fields = json.parseToJsonElement(json.encodeToString(finding())).jsonObject.toMutableMap()
        fields.remove("signalSamples")
        val restored = json.decodeFromJsonElement(SweepFinding.serializer(), JsonObject(fields))
        assertTrue(restored.signalSamples.isEmpty())
        val coverage = json.decodeFromString<SweepCoverage>("{}")
        assertEquals(SweepCoverage(), coverage)
        assertEquals("Not checked", coverage.wifiNanStatus)
        assertEquals(0L, coverage.wifiNanSamples)
    }

    @Test fun propertySerializerBoundsBothSavingAndLoadingOversizedHistories() {
        val samples = (1L..200L).map { SweepSignalSample(it, -60) }.reversed()
        val restored = json.decodeFromString<SweepFinding>(json.encodeToString(finding().copy(signalSamples = samples)))
        assertEquals(SweepSignalHistory.bounded(samples), restored.signalSamples)

        // Bypass the property serializer to model an externally oversized stored row.
        val fields = json.parseToJsonElement(json.encodeToString(finding())).jsonObject.toMutableMap()
        fields["signalSamples"] = json.parseToJsonElement(json.encodeToString(samples + SweepSignalSample(0, 127)))
        val loaded = json.decodeFromJsonElement(SweepFinding.serializer(), JsonObject(fields))
        assertEquals(SweepSignalHistory.bounded(samples), loaded.signalSamples)
    }

    @Test fun serializedSessionRoundTripPreservesSampleTimestampsAndUnavailableHistory() {
        val session = SweepSession("saved", 1_000, 2_000, SweepSessionState.COMPLETE,
            findings = listOf(finding().copy(signalSamples = listOf(SweepSignalSample(1_234, -71))), finding().copy(id = "old")))
        val restored = json.decodeFromString<SweepSession>(json.encodeToString(session))
        assertEquals(session, restored)
        assertTrue(restored.findings.last().signalSamples.isEmpty())
    }

    private fun finding() = SweepFinding("BLE:10:20:30:40:50:60", "Observed radio", SweepCategory.OTHER,
        "BLE", -60, 1_000, 8_000, 2, identifier = "10:20:30:40:50:60")
}
