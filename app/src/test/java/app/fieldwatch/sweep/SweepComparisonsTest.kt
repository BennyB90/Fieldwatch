package app.fieldwatch.sweep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SweepComparisonsTest {
    @Test fun partitionsActualAddressesIntoNewNotReobservedAndSharedObservations() {
        val result = compare(listOf(finding("10:20:30:40:50:01"), finding("10:20:30:40:50:02")),
            listOf(finding("10:20:30:40:50:02"), finding("10:20:30:40:50:03")))
        assertTrue(result.eligible)
        assertEquals("10:20:30:40:50:03", result.newlyObserved.single().finding.identifier)
        assertEquals("10:20:30:40:50:01", result.notReobserved.single().finding.identifier)
        assertEquals("10:20:30:40:50:02", result.shared.single().current.identifier)
        assertTrue(result.unresolved.isEmpty())
    }

    @Test fun sameNameSignatureOrRemoteIdCannotMergeDifferentRadioAddresses() {
        val older = finding("10:20:30:40:50:01").copy(name = "AirTag", signatureNames = listOf("AirTag"), droneId = "SAME-UAS")
        val newer = older.copy(id = "new-radio", identifier = "10:20:30:40:50:02")
        val result = compare(listOf(older), listOf(newer))
        assertEquals(1, result.newlyObserved.size)
        assertEquals(1, result.notReobserved.size)
        assertTrue(result.shared.isEmpty())
    }

    @Test fun normalizesAddressFormatAndTransportCaseWithoutMatchingAcrossTransports() {
        val original = finding("10:20:30:40:50:01")
        val normalized = compare(listOf(original), listOf(original.copy(id = "changed-id", identifier = "102030405001", transport = "ble")))
        assertEquals(1, normalized.shared.size)
        val differentTransport = compare(listOf(original), listOf(original.copy(transport = "WIFI")))
        assertTrue(differentTransport.shared.isEmpty())
        assertEquals(1, differentTransport.newlyObserved.size)
        assertEquals(1, differentTransport.notReobserved.size)
    }

    @Test fun legacyIdsOnlyMatchWhenTheyEncodeAnActualValidatedRadioAddress() {
        val older = finding("10:20:30:40:50:01").copy(identifier = "")
        val result = compare(listOf(older), listOf(finding("10:20:30:40:50:01")))
        assertEquals(1, result.shared.size)
        val arbitrary = compare(listOf(older.copy(id = "tracking-device")), emptyList())
        assertEquals(1, arbitrary.unresolved.size)
        assertTrue(arbitrary.notReobserved.isEmpty())
    }

    @Test fun rotatingAddressesAreNotJoinedAndBothRowsCarryIdentityUncertainty() {
        val older = finding("02:20:30:40:50:01").copy(randomizedAddress = true)
        val newer = older.copy(id = "rotated", identifier = "02:20:30:40:50:02")
        val result = compare(listOf(older), listOf(newer))
        assertTrue(result.shared.isEmpty())
        assertTrue(result.newlyObserved.single().identityNote.contains("may have changed"))
        assertTrue(result.notReobserved.single().identityNote.contains("unverified"))
        val same = compare(listOf(older), listOf(older))
        assertTrue(same.shared.single().identityNote.contains("may change or be reused"))
    }

    @Test fun sessionScopedNanIdentifiersAreUnresolvedRatherThanNewMissingOrPersistent() {
        val nan = finding("nan:first:1").copy(id = "WIFI:nan:first:1", transport = "WIFI")
        val result = compare(listOf(nan), listOf(nan.copy(id = "WIFI:nan:second:1", identifier = "nan:second:1")))
        assertEquals(2, result.unresolved.size)
        assertTrue(result.newlyObserved.isEmpty())
        assertTrue(result.notReobserved.isEmpty())
        assertTrue(result.shared.isEmpty())
    }

    @Test fun duplicateAddressOnEitherSideMakesBothSidesAmbiguous() {
        val original = finding("10:20:30:40:50:01")
        val duplicate = original.copy(id = "duplicate")
        for (result in listOf(compare(listOf(original, duplicate), listOf(original)),
            compare(listOf(original), listOf(original, duplicate)))) {
            assertEquals(3, result.unresolved.size)
            assertTrue(result.shared.isEmpty())
            assertTrue(result.newlyObserved.isEmpty())
            assertTrue(result.notReobserved.isEmpty())
        }
    }

    @Test fun noFreshTransportCoverageDoesNotTurnAComparisonIntoEvidenceOfAbsence() {
        val baseline = session("before", 1_000, listOf(finding("10:20:30:40:50:01")))
        val current = session("after", 2_000, emptyList()).copy(coverage = coverage.copy(bleSamples = 0, bleReady = false))
        val result = SweepComparisons.compare(baseline, current)
        assertTrue(result.eligible)
        assertFalse(result.notReobserved.single().coverageComparable)
        assertFalse(result.coverage.single { it.transport == "BLE" }.comparable)
        assertTrue(result.limitations.any { it.startsWith("BLE:") && it.contains("absence") })
        assertTrue(result.limitations.any { it.contains("does not mean removed") })
    }

    @Test fun evidenceAndCatalogChangesAreVisibleWithoutCreatingAnotherDevice() {
        val original = finding("10:20:30:40:50:01").copy(signatureNames = listOf("Wearable"))
        val changed = original.copy(category = SweepCategory.CAMERA, signatureNames = listOf("Camera"))
        val result = SweepComparisons.compare(session("before", 1_000, listOf(original)),
            session("after", 2_000, listOf(changed)).copy(catalogVersion = 96))
        assertEquals(1, result.shared.size)
        assertFalse(result.shared.single().evidenceCompatible)
        assertTrue(result.newlyObserved.isEmpty())
        assertTrue(result.limitations.any { it.contains("classification") })
    }

    @Test fun droppedObservationsAndDifferentReceiverFeaturesRemainExplicitLimitations() {
        val baseline = session("before", 1_000, emptyList())
        val current = session("after", 2_000, emptyList()).copy(phone = "Different phone",
            coverage = coverage.copy(droppedSamples = 2, wifiIeAvailable = true, bleExtendedSupported = true,
                wifiNanSupported = true, wifiNanSamples = 3))
        val result = SweepComparisons.compare(baseline, current)
        assertTrue(result.limitations.any { it.contains("dropped observations") })
        assertTrue(result.limitations.any { it.contains("feature coverage differed") })
        assertTrue(result.limitations.any { it.contains("not directly calibrated") })
        assertFalse(result.coverage.single { it.transport == "WIFI_NAN" }.comparable)
    }

    @Test fun differentDurationsAndRecordedScanErrorsDoNotProduceCompleteCoverageClaims() {
        val before = session("before", 1_000, emptyList())
        val after = session("after", 2_000, emptyList()).copy(endedAt = 2_600, error = "internal scanner error")
        val result = SweepComparisons.compare(before, after)
        assertTrue(result.eligible)
        assertTrue(result.limitations.any { it.contains("different durations") })
        assertTrue(result.limitations.any { it.contains("scan error") })
        assertTrue(result.limitations.none { it.contains("internal scanner error") })
    }

    @Test fun onlyDistinctExplicitCompletedRealSessionsWithKnownTimesQualify() {
        val before = session("before", 1_000, emptyList())
        val after = session("after", 2_000, emptyList())
        val rejected = listOf(
            SweepComparisons.compare(before, before),
            SweepComparisons.compare(before, after.copy(demo = true)),
            SweepComparisons.compare(before, after.copy(state = SweepSessionState.ACTIVE)),
            SweepComparisons.compare(before, after.copy(state = SweepSessionState.INTERRUPTED)),
            SweepComparisons.compare(before, after.copy(endedAt = null)),
            SweepComparisons.compare(before, after.copy(endedAt = 1_900)),
            SweepComparisons.compare(after, before),
        )
        rejected.forEach { assertFalse(it.eligible); assertTrue(!it.unavailableReason.isNullOrBlank()) }
    }

    @Test fun invalidPlaceholdersAndTimesCannotBecomeComparableDeviceIdentities() {
        val invalid = listOf(finding("00:00:00:00:00:00"), finding("FF:FF:FF:FF:FF:FF"),
            finding("not an address"), finding("10:20:30:40:50:01").copy(observations = 0))
        val result = compare(invalid, emptyList())
        assertEquals(4, result.unresolved.size)
        assertTrue(result.notReobserved.isEmpty())
        val timeResult = SweepComparisons.compare(session("before", 1_000, listOf(finding("10:20:30:40:50:01")))
            .copy(findings = listOf(finding("10:20:30:40:50:01").copy(firstSeenAt = 500))), session("after", 2_000, emptyList()))
        assertEquals(1, timeResult.unresolved.size)
    }

    private val coverage = SweepCoverage(wifiSupported = true, bleSupported = true,
        wifiReady = true, bleReady = true, wifiSamples = 2, bleSamples = 5)

    private fun compare(before: List<SweepFinding>, after: List<SweepFinding>) =
        SweepComparisons.compare(session("before", 1_000, before), session("after", 2_000, after))

    private fun session(id: String, start: Long, findings: List<SweepFinding>) =
        SweepSession(id, start, start + 900, SweepSessionState.COMPLETE,
            findings = findings.map { it.copy(firstSeenAt = start + 100, lastSeenAt = start + 500) }, coverage = coverage)

    private fun finding(address: String) = SweepFinding("BLE:$address", "Observed radio", SweepCategory.OTHER,
        "BLE", -60, 1_100, 1_500, 2, identifier = address)
}
