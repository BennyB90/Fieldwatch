package app.fieldwatch.sweep.spaces

import app.fieldwatch.sweep.*
import org.junit.Assert.*
import org.junit.Test

class SpaceAnalyzerTest {
    @Test fun baselineAndInspectionRequireCompletedMeasuredTimeOrderedScans() {
        val first = scan("base", 1000)
        listOf(first.copy(demo = true), first.copy(endedAt = null), first.copy(endedAt = 999),
            first.copy(state = SweepSessionState.ACTIVE), first.copy(state = SweepSessionState.INTERRUPTED)).forEach {
            assertFalse(SpaceAnalyzer.eligible(it))
            assertThrows(IllegalArgumentException::class.java) { SpaceAnalyzer.baseline(it) }
        }
        val room = space().copy(baseline = SpaceAnalyzer.baseline(first))
        assertFalse(SpaceAnalyzer.canInspect(room, first))
        assertFalse(SpaceAnalyzer.canInspect(room, scan(at = 1200)))
        assertTrue(SpaceAnalyzer.canInspect(room, scan(at = 1400)))
    }

    @Test fun normalizesOnlyExactTransportAndAddressAndPrioritizesIndicators() {
        val baseline = SpaceAnalyzer.baseline(scan("base", 1000, listOf(
            finding("aa-bb-cc-dd-ee-11", 1100), finding(ADDRESS, 1100))))
        val known = finding("02:00:00:00:00:01")
        val room = space().copy(baseline = baseline, expectedDevices = listOf(SpaceAnalyzer.expected(known, 1)))
        val rotated = known.copy(identifier = "02:00:00:00:00:02", rssi = -75)
        val other = finding("AA:BB:CC:DD:EE:11", transport = "BLE").copy(category = SweepCategory.OTHER, rssi = -30)
        val shared = finding("AABBCCDDEE11")
        val diff = SpaceAnalyzer.diff(room, scan(findings = listOf(other, known, shared, rotated)))
        assertEquals(listOf(rotated, other), diff.newFindings)
        assertEquals(setOf(known, shared), diff.expectedFindings.toSet())
        assertEquals(listOf(ADDRESS), diff.notReobservedBaseline.map { it.identifier })
        assertTrue(SpaceWarning.ROTATING in diff.warnings)
    }

    @Test fun collisionsStaleAndSessionScopedRecordsRemainUnresolved() {
        val a = "10:00:00:00:00:01"
        val b = "10:00:00:00:00:02"
        val base = scan("base", 1000, listOf(finding(a, 1100), finding(a, 1100), finding(b, 1100)))
        val current = scan(findings = listOf(finding(a), finding(b), finding(b), finding("unknown"),
            finding("FF:FF:FF:FF:FF:FF"), finding(transport = "WIFI NAN"), finding(at = 1000)))
        val room = space().copy(baseline = SpaceAnalyzer.baseline(base))
        val diff = SpaceAnalyzer.diff(room, current)
        assertTrue(diff.newFindings.isEmpty() && diff.expectedFindings.isEmpty() && diff.notReobservedBaseline.isEmpty())
        assertEquals(7, diff.unresolvedFindings.size)
        assertTrue(diff.comparable.isEmpty())
        assertEquals(2, SpaceAnalyzer.summary(room, diff).baselineUnresolved)
    }

    @Test fun noBaselineKeepsUnfamiliarOutOfNewAndAllowsUniqueExpected() {
        val familiar = finding()
        val room = space().copy(expectedDevices = listOf(SpaceAnalyzer.expected(familiar, 1)))
        val fresh = finding("10:00:00:00:00:01")
        val diff = SpaceAnalyzer.diff(room, scan(findings = listOf(familiar, fresh)))
        assertEquals(listOf(fresh), diff.unresolvedFindings)
        assertTrue(diff.newFindings.isEmpty())
        assertEquals(listOf(familiar), diff.expectedFindings)
        assertEquals(2, diff.comparable.size)
        assertTrue(SpaceWarning.NO_BASELINE in diff.warnings)
    }

    @Test fun retainsCountsBeyondArchivedDetailAndSurfacesCoverageAndEvidenceChanges() {
        val base = scan("base", 1000).copy(coverage = SweepCoverage(droppedSamples = 5), error = "private error")
        val room = space().copy(baseline = SpaceAnalyzer.baseline(base))
        val others = (1..60).map { finding("10:00:00:00:00:%02X".format(it)) }
        val current = scan(findings = others + finding().copy(category = SweepCategory.TRACKER)).copy(endedAt = 2600)
        val summary = SpaceAnalyzer.summary(room, SpaceAnalyzer.diff(room, current))
        assertEquals(60, summary.newFindings.count)
        assertEquals(SpaceLimits.REPORT_ROWS_PER_GROUP, summary.newFindings.details.size)
        assertTrue(summary.warnings.containsAll(listOf(SpaceWarning.BLE, SpaceWarning.WIFI, SpaceWarning.DURATION,
            SpaceWarning.DROPPED, SpaceWarning.ERROR, SpaceWarning.EVIDENCE)))
    }

    @Test fun oversizedBaselineIsRejectedWithoutPartialClassification() {
        val devices = (0..SpaceLimits.BASELINE).map { finding("10:00:00:00:%02X:%02X".format(it / 256, it % 256)) }
        assertThrows(IllegalArgumentException::class.java) { SpaceAnalyzer.baseline(scan(findings = devices)) }
    }

    @Test fun categoryChangeOnExpectedDeviceEmitsEvidenceWarning() {
        val benign = finding("10:00:00:00:00:99").copy(category = SweepCategory.OTHER)
        val room = space().copy(expectedDevices = listOf(SpaceAnalyzer.expected(benign, 1)))
        val escalated = benign.copy(category = SweepCategory.CAMERA)
        val diff = SpaceAnalyzer.diff(room, scan(findings = listOf(escalated)))
        assertEquals(listOf(escalated), diff.expectedFindings)
        assertTrue(SpaceWarning.EVIDENCE in diff.warnings)
    }
}
