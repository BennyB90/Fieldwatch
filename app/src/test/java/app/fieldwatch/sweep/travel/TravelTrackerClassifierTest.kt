package app.fieldwatch.sweep.travel

import app.fieldwatch.domain.*
import org.junit.Assert.*
import org.junit.Test

class TravelTrackerClassifierTest {
    private fun observation(name: String = "", uuid: String? = null, records: List<MfgRecord> = emptyList()) = Observation(
        kind = RadioKind.BLE, mac = "AA:BB:CC:DD:EE:01", name = name, rssi = -55, channel = 0,
        frequencyMhz = 2402, hiddenSsid = false, serviceUuids = listOfNotNull(uuid),
        manufacturerId = records.firstOrNull()?.companyId, manufacturerDataHex = records.firstOrNull()?.dataHex.orEmpty(),
        rawHex = "", extras = "", at = 1_000, facts = RadioFacts(mfgRecords = records),
    )

    @Test fun nameOnlyAndSamsungCompanyAreNotTrackerEvidence() {
        assertNull(TravelTrackerClassifier.label(observation(name = "Tile AirTag")))
        assertNull(TravelTrackerClassifier.label(observation(records = listOf(MfgRecord(0x0075, "010203")))))
        assertNull(TravelTrackerClassifier.label(observation(uuid = "FEAA")))
    }

    @Test fun explicitItemFinderServicesQualifyAcrossUuidAliases() {
        assertNotNull(TravelTrackerClassifier.label(observation(uuid = "FD5A")))
        assertEquals("Tile item-finder service", TravelTrackerClassifier.label(observation(uuid = "0000FEED-0000-1000-8000-00805F9B34FB")))
        assertNull(TravelTrackerClassifier.label(observation(uuid = "0000FEED-ABCD-1000-8000-00805F9B34FB")))
        assertNull(TravelTrackerClassifier.label(observation(uuid = "FD5A").copy(kind = RadioKind.WIFI)))
    }

    @Test fun appleContinuitySuppressesGenericFindMyGuess() {
        assertNull(TravelTrackerClassifier.label(observation(records = listOf(MfgRecord(0x004C, "10AABB"), MfgRecord(0x004C, "12AABB")))))
        assertEquals("Apple Find My signal", TravelTrackerClassifier.label(observation(records = listOf(MfgRecord(0x004C, "12AABB")))))
    }

    @Test fun phoneContinuityAndFindMyInDifferentPacketsAreNotLinkedAsTrackerEvidence() {
        val qualifier = TravelTrackerQualifier()
        assertNull(qualifier.label(observation(records = listOf(MfgRecord(0x004C, "10AABB")))))
        assertNull(qualifier.label(observation(records = listOf(MfgRecord(0x004C, "12AABB")))))
        assertTrue(qualifier.isPhone("AA:BB:CC:DD:EE:01"))
        // A new address has independent evidence; no physical identity stitching.
        assertNotNull(qualifier.label(observation(records = listOf(MfgRecord(0x004C, "12AABB"))).copy(mac = "AA:BB:CC:DD:EE:02")))
    }

    @Test fun currentPhoneEvidenceClearsPriorGenericAssessmentWhenPhoneCacheIsSaturated() {
        val qualifier = TravelTrackerQualifier()
        val generic = observation(records = listOf(MfgRecord(0x004C, "12AABB")))
        val label = qualifier.label(generic)!!
        var session = TravelSession("one", 1_000L, 1_000L)
        listOf(0, 5, 10, 15).forEach { minute ->
            session = TravelSamples.record(session, generic.mac, label,
                TravelSample(1_000L + minute * 60_000L, 1_000L + minute * 60_000L, -60), emptySet())
        }
        assertEquals(1, session.assessments.size)
        val continuity = observation(records = listOf(MfgRecord(0x004C, "10AABB")))
        repeat(1_024) { index ->
            qualifier.label(continuity.copy(mac = "02:00:00:00:%02X:%02X".format(index / 256, index % 256)))
        }
        assertNull(qualifier.label(continuity))
        assertFalse(qualifier.isPhone(continuity.mac))
        val cleaned = TravelPhoneEvidence.removeGenericRow(session, continuity, qualifier)
        assertTrue(cleaned.trackers.isEmpty())
        assertTrue(cleaned.assessments.isEmpty())
    }
}
