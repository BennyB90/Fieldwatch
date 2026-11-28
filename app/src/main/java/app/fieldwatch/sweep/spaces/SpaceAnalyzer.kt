package app.fieldwatch.sweep.spaces

import app.fieldwatch.domain.PayloadLocation
import app.fieldwatch.domain.Rssi
import app.fieldwatch.sweep.SweepCategory
import app.fieldwatch.sweep.SweepComparisonSide
import app.fieldwatch.sweep.SweepComparisons
import app.fieldwatch.sweep.SweepCoverage
import app.fieldwatch.sweep.SweepFinding
import app.fieldwatch.sweep.SweepSession
import app.fieldwatch.sweep.SweepUnresolvedObservation
import java.util.Locale

data class SpaceDiff(
    val newFindings: List<SweepFinding>,
    val expectedFindings: List<SweepFinding>,
    val notReobservedBaseline: List<SpaceBaselineDevice>,
    val unresolvedFindings: List<SweepFinding>,
    val warnings: List<SpaceWarning>,
    val comparable: Map<String, SweepFinding>,
)

object SpaceAnalyzer {
    fun eligible(session: SweepSession) =
        !session.demo && session.id.isNotBlank() && SweepComparisons.completed(session)

    fun canInspect(space: SavedSpace, session: SweepSession): Boolean {
        val baseline = space.baseline
        return eligible(session) && (baseline == null ||
            (session.id != baseline.sourceSessionId && session.startedAt >= baseline.endedAt))
    }

    fun baseline(session: SweepSession): SpaceBaseline {
        require(eligible(session)) { "Select a completed, measured Room Scan with recorded start and end times." }
        val unresolved = mutableListOf<SweepUnresolvedObservation>()
        val index = SweepComparisons.indexed(session, SweepComparisonSide.BASELINE, unresolved)
        require(index.findings.size + index.ambiguousKeys.size <= SpaceLimits.BASELINE) {
            "This scan exceeds the ${SpaceLimits.BASELINE}-address baseline limit. Choose a smaller scan."
        }
        return SpaceBaseline(session.id, session.startedAt, requireNotNull(session.endedAt), coverage(session.coverage),
            index.findings.map { (key, finding) ->
                SpaceBaselineDevice(key, key.substringBefore(':'), finding.category,
                    signatures(finding),
                    SweepComparisons.randomized(finding), key.substringAfter(':'), radioText(finding.name),
                    finding.rssi, finding.lastSeenAt)
            }, index.ambiguousKeys, unresolved.size, !session.error.isNullOrBlank())
    }

    fun diff(space: SavedSpace, session: SweepSession): SpaceDiff {
        require(canInspect(space, session)) { "Choose a completed, measured Room Scan taken after the baseline." }
        val baseline = space.baseline
        val unresolved = mutableListOf<SweepUnresolvedObservation>()
        val index = SweepComparisons.indexed(session, SweepComparisonSide.CURRENT, unresolved)
        val ambiguous = baseline?.ambiguousKeys.orEmpty()
        val candidates = index.findings.filterKeys { it !in ambiguous }
        val known = space.expectedDevices.mapTo(mutableSetOf()) { it.key }
        baseline?.devices?.mapTo(known) { it.key }
        val (matched, fresh) = candidates.entries.partition { it.key in known }
        val missing = baseline?.devices.orEmpty().filter { it.key !in index.findings && it.key !in index.ambiguousKeys }
        val unknown = unresolved.map { it.finding } + index.findings.filterKeys { it in ambiguous }.values
        val unfamiliar = fresh.map { it.value }
        val currentByKey = matched.associate { it.key to it.value }
        val baselineChanged = baseline?.devices.orEmpty().any { earlier ->
            val current = currentByKey[earlier.key] ?: return@any false
            earlier.category != current.category || earlier.signatureNames != signatures(current)
        }
        val expectedChanged = space.expectedDevices.any { expected ->
            val current = currentByKey[expected.key] ?: return@any false
            expected.category != current.category
        }
        val changedEvidence = baselineChanged || expectedChanged
        return SpaceDiff(if (baseline == null) emptyList() else unfamiliar.sortedWith(priority),
            matched.map { it.value }.sortedWith(priority), missing.sortedBy { it.key },
            (if (baseline == null) unknown + unfamiliar else unknown).sortedWith(priority),
            warnings(space, session) + if (changedEvidence) listOf(SpaceWarning.EVIDENCE) else emptyList(), candidates)
    }

    fun expected(finding: SweepFinding, at: Long): SpaceExpectedDevice {
        val key = requireNotNull(SweepComparisons.key(finding)) { "This observation has no comparable radio address." }
        return SpaceExpectedDevice(key, key.substringBefore(':'), finding.category, radioText(finding.name), at,
            SweepComparisons.randomized(finding))
    }

    fun summary(space: SavedSpace, diff: SpaceDiff, checklist: List<SpaceChecklistItem> = emptyList(),
        spectrumChecks: List<SpaceSpectrumCheck> = emptyList()): SpaceDiffSummary {
        fun group(findings: List<SweepFinding>) = SpaceFindingGroup(findings.size,
            findings.take(SpaceLimits.REPORT_ROWS_PER_GROUP).map { f ->
                val transport = when (f.transport.trim().uppercase(Locale.ROOT)) {
                    "WIFI" -> "WIFI"
                    "BLE" -> "BLE"
                    "WIFI NAN", "WIFI_NAN" -> "WIFI NAN"
                    else -> "Unknown radio"
                }
                SpaceObservation(transport, f.category, radioText(f.name),
                    SweepComparisons.key(f)?.substringAfter(':') ?: radioText(f.identifier),
                    f.rssi, SweepComparisons.randomized(f), f.dronePins.filter {
                        PayloadLocation.validCoord(it.latitude, it.longitude)
                    }.take(5))
            })
        val baseline = space.baseline
        return SpaceDiffSummary(baseline?.startedAt, baseline?.endedAt, baseline?.coverage, baseline?.unresolvedCount ?: 0,
            group(diff.newFindings), group(diff.expectedFindings),
            SpaceFindingGroup(diff.notReobservedBaseline.size,
                diff.notReobservedBaseline.take(SpaceLimits.REPORT_ROWS_PER_GROUP).map { f ->
                    SpaceObservation(f.transport, f.category, f.name, f.identifier, f.lastRssi, f.randomizedAddress)
                }), group(diff.unresolvedFindings), diff.warnings + listOfNotNull(
                    SpaceWarning.THERMAL_DELTA.takeIf { checklist.any { item ->
                        (item.thermal?.baselineDeltaDiffDeciC ?: 0) >= THERMAL_WARNING_DECI_C
                    } }, SpaceWarning.SPECTRUM_SPIKE.takeIf { spectrumChecks.any(SpaceSpectrumCheck::spike) }))
    }

    // Free-form OS messages are unnecessary for a private inspection log. Keep measured fields.
    internal fun coverage(c: SweepCoverage) = c.copy(wifiStatus = "", bleStatus = "", wifiNanStatus = "")

    private fun warnings(space: SavedSpace, session: SweepSession): List<SpaceWarning> = buildList {
        val baseline = space.baseline
        val b = baseline?.coverage
        val c = session.coverage
        if (baseline == null) add(SpaceWarning.NO_BASELINE)
        if (session.findings.any(SweepComparisons::randomized) || baseline?.devices.orEmpty().any { it.randomizedAddress } ||
            space.expectedDevices.any { it.randomizedAddress }) add(SpaceWarning.ROTATING)
        if (listOfNotNull(b, c).any { !it.wifiSupported || !it.wifiReady || it.wifiSamples <= 0 }) add(SpaceWarning.WIFI)
        if (listOfNotNull(b, c).any { !it.bleSupported || !it.bleReady || it.bleSamples <= 0 }) add(SpaceWarning.BLE)
        if (b != null && (b.wifiIeAvailable != c.wifiIeAvailable || b.wifiNanSupported != c.wifiNanSupported ||
                b.wifiNanAvailable != c.wifiNanAvailable || b.bleExtendedSupported != c.bleExtendedSupported)) add(SpaceWarning.FEATURE)
        if (c.droppedSamples > 0 || (b?.droppedSamples ?: 0) > 0) add(SpaceWarning.DROPPED)
        if (!session.error.isNullOrBlank() || baseline?.scanReportedError == true) add(SpaceWarning.ERROR)
        if (baseline != null && baseline.endedAt - baseline.startedAt != requireNotNull(session.endedAt) - session.startedAt) add(SpaceWarning.DURATION)
        if ((baseline?.unresolvedCount ?: 0) > 0) add(SpaceWarning.BASELINE_UNRESOLVED)
    }

    private fun radioText(value: String) = SpaceLimits.clean(value, SpaceLimits.RADIO_TEXT)
    private fun signatures(finding: SweepFinding) =
        finding.signatureNames.take(16).map(::radioText).distinct().sorted()
    private val priority = compareBy<SweepFinding> { it.category == SweepCategory.OTHER }
        .thenByDescending { if (Rssi.measured(it.rssi)) it.rssi else Int.MIN_VALUE }
        .thenBy { SweepComparisons.key(it).orEmpty() }.thenBy { it.id }.thenBy { it.name }
}
