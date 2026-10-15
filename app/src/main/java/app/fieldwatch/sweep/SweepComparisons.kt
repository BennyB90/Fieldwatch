package app.fieldwatch.sweep

import java.util.Locale

data class SweepComparisonEntry(
    val finding: SweepFinding,
    val coverageComparable: Boolean,
    val identityNote: String,
)

data class SweepSharedObservation(
    val baseline: SweepFinding,
    val current: SweepFinding,
    /** Same consumer category and matched-signature names, not verified physical identity. */
    val evidenceCompatible: Boolean,
    val coverageComparable: Boolean,
    val identityNote: String,
)

enum class SweepComparisonSide { BASELINE, CURRENT }

data class SweepUnresolvedObservation(
    val finding: SweepFinding,
    val side: SweepComparisonSide,
    val reason: String,
)

data class SweepTransportComparison(
    val transport: String,
    val baselineSamples: Long,
    val currentSamples: Long,
    val comparable: Boolean,
)

data class SweepSessionComparison(
    val baselineSessionId: String,
    val currentSessionId: String,
    val eligible: Boolean,
    val unavailableReason: String? = null,
    val newlyObserved: List<SweepComparisonEntry> = emptyList(),
    val notReobserved: List<SweepComparisonEntry> = emptyList(),
    val shared: List<SweepSharedObservation> = emptyList(),
    /** Invalid, ambiguous or session-scoped identities must not masquerade as new devices. */
    val unresolved: List<SweepUnresolvedObservation> = emptyList(),
    val coverage: List<SweepTransportComparison> = emptyList(),
    val limitations: List<String> = emptyList(),
)

/**
 * Compare two explicitly selected saved sessions. The caller supplies the saved
 * instances: this helper neither selects sessions nor infers that they describe
 * the same room. No disappearance, surveillance, movement or safe-room verdict.
 */
object SweepComparisons {
    fun compare(baseline: SweepSession, current: SweepSession): SweepSessionComparison {
        val rejection = when {
            baseline.demo || current.demo -> "Synthetic demonstrations cannot be compared with saved scans."
            baseline.id.isBlank() || current.id.isBlank() || baseline.id == current.id -> "Choose two distinct saved sessions."
            !completed(baseline) || !completed(current) -> "Both saved sessions must be complete with known start and end times."
            current.startedAt < baseline.startedAt -> "Choose an earlier session as the baseline."
            else -> null
        }
        if (rejection != null) return SweepSessionComparison(baseline.id, current.id, false, rejection)

        val coverage = listOf(
            transportCoverage("BLE", baseline.coverage.bleSamples, current.coverage.bleSamples,
                baseline.coverage.bleSupported, current.coverage.bleSupported),
            transportCoverage("WIFI", baseline.coverage.wifiSamples, current.coverage.wifiSamples,
                baseline.coverage.wifiSupported, current.coverage.wifiSupported),
            transportCoverage("WIFI_NAN", baseline.coverage.wifiNanSamples, current.coverage.wifiNanSamples,
                baseline.coverage.wifiNanSupported, current.coverage.wifiNanSupported),
        )
        val unresolved = mutableListOf<SweepUnresolvedObservation>()
        val beforeIndex = indexed(baseline, SweepComparisonSide.BASELINE, unresolved)
        val afterIndex = indexed(current, SweepComparisonSide.CURRENT, unresolved)
        val ambiguous = beforeIndex.ambiguousKeys + afterIndex.ambiguousKeys
        fun resolved(index: ComparisonIndex, side: SweepComparisonSide) = index.findings.filter { (key, finding) ->
            if (key in ambiguous) {
                unresolved += SweepUnresolvedObservation(finding, side,
                    "This address has ambiguous records in the other session; identity cannot be compared.")
                false
            } else true
        }
        val before = resolved(beforeIndex, SweepComparisonSide.BASELINE)
        val after = resolved(afterIndex, SweepComparisonSide.CURRENT)
        fun comparable(finding: SweepFinding) = coverage.firstOrNull {
            it.transport == transport(finding)
        }?.comparable == true
        fun entry(finding: SweepFinding) = SweepComparisonEntry(finding, comparable(finding), identityNote(finding))
        val shared = after.keys.intersect(before.keys).sorted().map { key ->
            val older = before.getValue(key)
            val newer = after.getValue(key)
            SweepSharedObservation(
                baseline = older,
                current = newer,
                evidenceCompatible = older.category == newer.category && signatures(older) == signatures(newer),
                coverageComparable = comparable(newer),
                identityNote = if (randomized(older) || randomized(newer)) {
                    "Same advertised address and transport; the address may change or be reused. Physical identity is unverified."
                } else "Same advertised address and transport; physical identity is unverified.",
            )
        }
        val limitations = buildList {
            add("This compares observed radio addresses in two selected scans, not a complete inventory or a room safety assessment.")
            add("Newly observed does not mean newly installed. Not reobserved does not mean removed, absent or inactive.")
            add("Addresses may rotate or be reused. Matching names, catalog signatures and Remote ID values do not establish physical identity.")
            coverage.filterNot { it.comparable }.forEach {
                add("${it.transport}: fresh samples were not recorded with supported reception in both sessions; absence comparisons are limited.")
            }
            if (baseline.coverage.wifiSamples > 0 || current.coverage.wifiSamples > 0) {
                add("Wi-Fi scans are throttled and can miss access points; scan age and coverage affect the comparison.")
            }
            if (baseline.coverage.droppedSamples > 0 || current.coverage.droppedSamples > 0) {
                add("One or both sessions dropped observations; not all received data was retained.")
            }
            if (!baseline.error.isNullOrBlank() || !current.error.isNullOrBlank()) {
                add("One or both sessions recorded a scan error; coverage may be incomplete.")
            }
            if (baseline.endedAt!! - baseline.startedAt != current.endedAt!! - current.startedAt) {
                add("The sessions have different durations; detection opportunities differ.")
            }
            if (baseline.catalogVersion != current.catalogVersion || shared.any { !it.evidenceCompatible }) {
                add("Catalog or matched evidence changed; a different classification does not establish a different physical device.")
            }
            if (baseline.coverage.wifiIeAvailable != current.coverage.wifiIeAvailable ||
                baseline.coverage.bleExtendedSupported != current.coverage.bleExtendedSupported ||
                baseline.coverage.wifiNanSupported != current.coverage.wifiNanSupported ||
                baseline.coverage.wifiNanAvailable != current.coverage.wifiNanAvailable) {
                add("Radio feature coverage differed between sessions; some advertised evidence may not be comparable.")
            }
            if (baseline.phone != current.phone || baseline.androidVersion != current.androidVersion) {
                add("The recorded phone or Android version differs; received signal strengths are not directly calibrated between devices.")
            }
            if (unresolved.isNotEmpty()) {
                add("Some observations lack a unique comparable identity and are listed separately rather than labeled new or missing.")
            }
        }
        return SweepSessionComparison(
            baselineSessionId = baseline.id,
            currentSessionId = current.id,
            eligible = true,
            newlyObserved = after.filterKeys { it !in before }.values.map(::entry),
            notReobserved = before.filterKeys { it !in after }.values.map(::entry),
            shared = shared,
            unresolved = unresolved,
            coverage = coverage,
            limitations = limitations,
        )
    }

    private fun completed(session: SweepSession): Boolean = session.state == SweepSessionState.COMPLETE &&
        session.startedAt > 0 && session.endedAt?.let { it >= session.startedAt } == true

    private fun transportCoverage(kind: String, older: Long, newer: Long, olderSupported: Boolean, newerSupported: Boolean) =
        SweepTransportComparison(kind, older.coerceAtLeast(0), newer.coerceAtLeast(0),
            olderSupported && newerSupported && older > 0 && newer > 0)

    private data class ComparisonIndex(val findings: Map<String, SweepFinding>, val ambiguousKeys: Set<String>)

    private fun indexed(
        session: SweepSession,
        side: SweepComparisonSide,
        unresolved: MutableList<SweepUnresolvedObservation>,
    ): ComparisonIndex {
        val end = session.endedAt!!
        val candidates = session.findings.groupBy { finding ->
            if (finding.observations <= 0 || finding.firstSeenAt !in session.startedAt..end ||
                finding.lastSeenAt !in session.startedAt..end) null else key(finding)
        }
        val result = linkedMapOf<String, SweepFinding>()
        val ambiguous = mutableSetOf<String>()
        candidates.toSortedMap(compareBy<String?> { it ?: "" }).forEach { (key, findings) ->
            if (key == null || findings.size != 1) {
                if (key != null) ambiguous += key
                findings.forEach { unresolved += SweepUnresolvedObservation(it, side,
                    if (key == null) "No comparable advertised address or measured observation within this saved session."
                    else "Multiple records use this advertised address; identity is ambiguous.") }
            } else result[key] = findings.single()
        }
        return ComparisonIndex(result, ambiguous)
    }

    private fun key(finding: SweepFinding): String? {
        val kind = transport(finding)
        if (kind != "BLE" && kind != "WIFI") return null
        val address = advertisedAddress(finding) ?: return null
        return "$kind:$address"
    }

    private fun advertisedAddress(finding: SweepFinding): String? {
        val kind = transport(finding)
        val raw = finding.identifier.ifBlank {
            finding.id.takeIf { it.substringBefore(':').equals(kind, ignoreCase = true) }?.substringAfter(':').orEmpty()
        }
        return address(raw)
    }

    private fun address(raw: String): String? {
        val text = raw.trim().uppercase(Locale.ROOT)
        if (!ADDRESS_PATTERN.matches(text)) return null
        val hex = text.replace(":", "").replace("-", "")
        if (hex == "000000000000" || hex == "FFFFFFFFFFFF") return null
        return hex.chunked(2).joinToString(":")
    }

    private fun transport(finding: SweepFinding) = finding.transport.trim().uppercase(Locale.ROOT)
    private fun signatures(finding: SweepFinding) = finding.signatureNames.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
    private fun randomized(finding: SweepFinding): Boolean = finding.randomizedAddress ||
        advertisedAddress(finding)?.substringBefore(':')?.toIntOrNull(16)?.let { (it and 0x02) != 0 } == true

    private fun identityNote(finding: SweepFinding) = if (randomized(finding)) {
        "This address may have changed since the other scan or be reused; physical identity is unverified."
    } else "An advertised radio address, not a verified physical device."

    private val ADDRESS_PATTERN = Regex("(?:[0-9A-F]{2}[:-]){5}[0-9A-F]{2}|[0-9A-F]{12}")
}
