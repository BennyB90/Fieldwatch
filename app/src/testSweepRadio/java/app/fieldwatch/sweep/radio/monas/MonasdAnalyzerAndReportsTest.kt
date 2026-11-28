package app.fieldwatch.sweep.radio.monas

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test

class MonasdAnalyzerAndReportsTest {
    private val demo = MonasDemo.snapshot()
    private val live = demo.copy(sample = false)
    private fun two(offset: Double): MonasSnapshot {
        val a = live.census.devices[0].copy(kind = "RPA", crystalOffsetPpm = MonasSigma(0.0, .6), modulationIndex = MonasSigma(.45, .03))
        val b = live.census.devices[1].copy(kind = "nrpa", crystalOffsetPpm = MonasSigma(offset, .8), modulationIndex = MonasSigma(.55, .04))
        return live.copy(census = live.census.copy(devices = listOf(a, b), total = 2))
    }

    @Test fun classificationUsesCfoEvenWhenModulationSeparatesAndIncludesExactBoundaries() {
        for ((offset, verdict) in listOf(5.0 to SeparationVerdict.DISTINCT_OSCILLATORS, 4.999 to SeparationVerdict.MODERATE_SEPARATION,
            2.0 to SeparationVerdict.MODERATE_SEPARATION, 1.999 to SeparationVerdict.OVERLAPPING_CLUSTER)) {
            val s = two(offset)
            val result = MonasdAnalyzer.pair(s, 1, 0)!!
            assertEquals(offset, result.zCfo, 1e-12)
            assertEquals(hypot(offset, 2.0), result.zCombined!!, 1e-12)
            assertEquals(verdict, result.verdict)
            assertEquals(MonasdAnalyzer.pair(s, 0, 1), result)
            val counts = MonasdAnalyzer.counts(s)
            assertEquals(2, counts.rotatingRows); assertEquals(1, counts.evaluated)
            assertEquals(if (offset >= 5) 1 else 0, counts.rotatingDistinct)
            assertEquals(if (offset < 2) 1 else 0, counts.rotatingOverlapping)
        }
    }

    @Test fun staleAbsentAndExpiredReferenceRefuseAllPairsAndStableLossQualifiesCounts() {
        val s = two(6.0)
        val ref = s.provenance.copy(tier = MonasReferenceTier.REFERENCED, referenceAgeSec = 900)
        assertNotNull(MonasdAnalyzer.pair(s.copy(provenance = ref), 0, 1))
        val blocked = listOf(s.copy(health = s.health.copy(stale = true)), s.copy(health = s.health.copy(streaming = false)),
            s.copy(census = s.census.copy(testimony = s.census.testimony.copy(stale = true))),
            s.copy(census = s.census.copy(testimony = s.census.testimony.copy(absent = "offline"))),
            s.copy(provenance = ref.copy(referenceAgeSec = 901)), s.copy(provenance = ref.copy(referenceAgeSec = null)),
            s.copy(provenance = s.provenance.copy(expired = true)), s.copy(provenance = ref.copy(tier = MonasReferenceTier.UNKNOWN)))
        blocked.forEach {
            assertNull(MonasdAnalyzer.pair(it, 0, 1)); assertEquals(0, MonasdAnalyzer.counts(it).evaluated)
            assertTrue(MonasReports.text(it).contains("unavailable"))
        }
        val loss = s.copy(ble = s.ble.copy(testimony = s.ble.testimony.copy(feedLoss = true)))
        assertNotNull(MonasdAnalyzer.pair(loss, 0, 1))
        assertTrue("lower bounds" in MonasReports.text(loss))
    }

    @Test fun sameRunRequiredAndDiscontinuityStaysLatchedThroughLaterIdenticalRefreshes() {
        val before = live.copy(provenance = live.provenance.copy(sessionSeconds = 361))
        val after = before.copy(provenance = before.provenance.copy(sessionSeconds = 1))
        assertEquals(MonasReferenceTier.RELATIVE, after.provenance.tier)
        assertNull(MonasdAnalyzer.continuity(before, before))
        for (changed in listOf(after, before.copy(health = before.health.copy(radio = "Another radio")),
            before.copy(health = before.health.copy(sampleRateHz = 2e6)), before.copy(health = before.health.copy(view = "Other")),
            before.copy(ble = before.ble.copy(testimony = before.ble.testimony.copy(stale = true))),
            before.copy(census = before.census.copy(testimony = before.census.testimony.copy(feedLoss = true))),
            before.copy(provenance = before.provenance.copy(mode = "SURVEY")),
            before.copy(provenance = before.provenance.copy(addressesMode = "oui")))) {
            val reason = MonasdAnalyzer.continuity(before, changed)
            assertNotNull(reason)
            val latched = changed.copy(continuityIssue = reason)
            assertNull(MonasdAnalyzer.pair(latched, 0, 1))
            assertEquals(reason, MonasdAnalyzer.continuity(latched, changed))
        }
    }

    @Test fun disjointWindowsDuplicateRowsAndInvalidSigmasAreIneligible() {
        val s = two(6.0)
        val a = s.census.devices.first()
        val b = s.census.devices.last()
        fun paired(row: MonasCensusDevice) = MonasdAnalyzer.pair(s.copy(census = s.census.copy(devices = listOf(a, row))), 0, 1)
        for (bad in listOf(b.copy(firstSeenSec = 50, lastSeenSec = 60), b.copy(firstSeenSec = null), b.copy(address = a.address),
            b.copy(crystalOffsetPpm = null), b.copy(crystalOffsetPpm = MonasSigma(1.0, 0.0)),
            b.copy(crystalOffsetPpm = MonasSigma(1.0, Double.POSITIVE_INFINITY)), b.copy(crystalOffsetPpm = MonasSigma(Double.NaN, 1.0)))) {
            assertNull(paired(bad))
        }
        assertNull(paired(b.copy(modulationIndex = MonasSigma(.1, 0.0)))!!.zCombined)
        assertNull(MonasdAnalyzer.pair(s, 0, 0)); assertNull(MonasdAnalyzer.pair(s, 0, 2))
    }

    @Test fun defaultReportIsAnAggregateAllowlistEvenWhenServerSuppliesHostileLabels() {
        val address = "46:09:22:31:A8:7B"
        val endpoint = "https://operator-private.invalid:9443"
        val serial = "SERIAL-9A7312"
        val a = live.census.devices.first().copy(address = address, name = "Beacon-name-secret", company = "Private-company",
            crystalOffsetPpm = MonasSigma(-12.345678, .02345678), modulationIndex = MonasSigma(.48765432, .00076543))
        val rows = listOf(a) + live.census.devices.drop(1)
        val s = live.copy(census = live.census.copy(devices = rows), health = live.health.copy(radio = endpoint),
            radios = listOf(MonasRadioInfo(serial, null, serial, true, null)),
            provenance = live.provenance.copy(mode = endpoint),
            ble = live.ble.copy(packets = live.ble.packets.map { it.copy(address = serial, cfoPpm = MonasSigma(31.234, .12345)) }))
        val safe = MonasReports.text(s)
        for (secret in rows.map { it.address } + rows.mapNotNull { it.name } + rows.mapNotNull { it.company } +
            listOf(address, endpoint, serial, "-12.345678", "0.02345678", "0.48765432", "0.00076543", "31.234", "0.12345", "ppm", "kHz", "mod_index")) {
            assertFalse(secret, safe.contains(secret))
        }
        listOf("Observed address rows: 5", "Rotating address rows: 3", "≥5σ distinct-oscillator pairs: 7", "<2σ overlapping pairs: 3",
            "CRC pass: 5", "Mean packet SNR:", "stale=false", "feed_loss=false", "lost_samples=0",
            "RELATIVE", "Overlap never proves shared identity", "Rotating addresses are never linked").forEach { assertTrue(it, it in safe) }
        val private = MonasReports.text(s, includeIdentifiers = true)
        listOf(address, "-12.345678 ± 0.02345678 ppm", "0.48765432 ± 7.6543E-4", "Private-company", "Beacon-name-secret")
            .forEach { assertTrue(it, it in private) }
        assertFalse(MonasReports.text(s.copy(continuityIssue = endpoint)).contains(endpoint))
        assertTrue(MonasReports.text(demo).startsWith(MonasDemo.TITLE))
    }

    @Test fun tokenEditorKeepsOnlyBulletsInComposeAndSupportsMiddleEdits() {
        val draft = MonasTokenDraft()
        draft.edit(TextFieldValue("secret", TextRange(6)))
        assertEquals("••••••", draft.masked.text)
        draft.edit(draft.masked.copy(selection = TextRange(1, 4)))
        draft.edit(TextFieldValue("•ab••", TextRange(3)))
        draft.edit(TextFieldValue("•ab••\n", TextRange(6)))
        assertTrue(draft.invalid)
        draft.edit(TextFieldValue("•ab••😀", TextRange(7)))
        assertTrue(draft.invalid)
        draft.edit(TextFieldValue("•ab••", TextRange(0, 999)))
        assertArrayEquals("sabet".toCharArray(), draft.take())
        assertTrue(draft.masked.text.isEmpty())
    }
}
