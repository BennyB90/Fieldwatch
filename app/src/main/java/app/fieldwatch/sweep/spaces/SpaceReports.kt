package app.fieldwatch.sweep.spaces

import app.fieldwatch.domain.PayloadLocation
import app.fieldwatch.domain.Rssi
import app.fieldwatch.sweep.SweepCoverage
import app.fieldwatch.sweep.SweepMapper
import app.fieldwatch.sweep.SweepReports
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class SpaceReportSection(val heading: String, val lines: List<String>)

@Serializable
internal data class SpaceReportPayload(val version: Int, val title: String, val sections: List<SpaceReportSection>)

@Serializable
internal data class SpaceReport(val payload: SpaceReportPayload, val sha256: String) {
    fun text() = buildString {
        append(payload.title)
        payload.sections.forEach { section ->
            append("\n\n").append(section.heading)
            section.lines.forEach { append('\n').append(it) }
        }
        append("\n\nSHA-256 (canonical JSON payload, UTF-8)\n").append(sha256)
    }

    fun json(): String = Json.encodeToString(this)
}

/** Only allowlisted fields enter the canonical payload. PDF, JSON and text use the same report. */
internal object SpaceReports {
    const val DISCLAIMER = "Timestamped inspection log only. Does not certify a safe room or rule out offline/SD-card recorders, wired cameras, or unsupported radios."
    private val mac = Regex("(?i)(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}|(?<![0-9a-f])[0-9a-f]{12}(?![0-9a-f])")
    private val hex = "0123456789abcdef"

    fun report(log: SpaceInspection, includeIdentifiers: Boolean = false, includeCoordinates: Boolean = false): SpaceReport {
        fun freeText(value: String) = SweepReports.exportedText(value, includeCoordinates)
        val diff = log.diffSummary
        val groups = listOf("New since baseline" to diff.newFindings, "Expected / Baseline matched" to diff.expectedFindings,
            "Not reobserved from baseline" to diff.notReobservedBaseline, "Unresolved observations" to diff.unresolvedFindings)
        val spaceName = if (includeIdentifiers) log.spaceName else mac.replace(log.redactedSpaceName, "[address withheld]")
        val sections = buildList {
            add(SpaceReportSection("Inspection", buildList {
                add("Space: ${freeText(spaceName)}")
                add("Space type: ${log.spaceKind.label}")
                add("Inspection started: ${time(log.createdAt)}")
                add("Inspection completed: ${time(log.completedAt)}")
                add("Inspection duration: ${log.completedAt - log.createdAt} ms")
                add("Identifiers and notes: ${if (includeIdentifiers) "included by request" else "withheld"}")
                add("Coordinates: ${if (includeCoordinates) "included by request" else "withheld"}")
            }))
            add(SpaceReportSection("Wireless coverage", buildList {
                add("Room Scan: ${time(log.sessionStartedAt)} to ${time(log.sessionEndedAt)}")
                add("Room Scan duration: ${log.sessionEndedAt - log.sessionStartedAt} ms")
                addAll(coverage(log.coverage))
                if (diff.baselineStartedAt != null && diff.baselineEndedAt != null) {
                    add("Known-good baseline: ${time(diff.baselineStartedAt)} to ${time(diff.baselineEndedAt)}")
                    diff.baselineCoverage?.let { addAll(coverage(it).map { line -> "Baseline $line" }) }
                } else add("Known-good baseline: not set")
                add("Authorized LAN review: not included in this inspection.")
            }))
            add(SpaceReportSection("Wireless comparison", buildList {
                groups.forEach { (label, group) -> add("$label: ${group.count}") }
                add("Unresolved baseline records: ${diff.baselineUnresolved}")
                add("A match is an advertised address on the same transport. Addresses can rotate or be reused.")
                add("New observations can be pre-existing transmitters. Unobserved transmitters may still be present.")
                addAll(diff.warnings.map { it.message })
            }))
            val skipped = log.checklist.count { it.status == ChecklistStatus.NOT_APPLICABLE }
            val followUp = log.checklist.count { it.status == ChecklistStatus.NEEDS_FOLLOW_UP }
            val clear = log.checklist.count { it.status == ChecklistStatus.CHECKED_CLEAR }
            add(SpaceReportSection("Guided physical checklist", buildList {
                add("Human-reviewed: ${clear + followUp} of ${log.checklist.size}. Checked clear: $clear. Needs follow-up: $followUp. N/A: $skipped.")
                val template = SpaceChecklists.forKind(log.spaceKind).associateBy { it.id }
                log.checklist.forEach { item ->
                    val title = template.getValue(item.id).title
                    add("$title - ${item.status.label}")
                    if (includeIdentifiers && item.note.isNotBlank()) add("Note: ${freeText(item.note)}")
                }
            }))
            add(SpaceReportSection("Capability limits", listOf(DISCLAIMER,
                "Access-point scanning can miss Wi-Fi clients. RF observations do not establish recording activity or physical location.")))
            groups.filter { it.second.count > 0 }.forEach { (label, group) ->
                add(SpaceReportSection(label, buildList {
                    add("Archived detail: ${group.details.size} of ${group.count} observations.")
                    group.details.forEachIndexed { index, obs ->
                        val signal = if (Rssi.measured(obs.rssi)) "${obs.rssi} dBm" else "signal unavailable"
                        val radio = obs.transport.takeIf { it in setOf("WIFI", "BLE", "WIFI NAN") } ?: "Unknown radio"
                        add("${index + 1}. $radio / ${SweepMapper.categoryLabel(obs.category)} / $signal" +
                            if (obs.randomizedAddress) " / address may rotate" else "")
                        if (includeIdentifiers) {
                            if (obs.name.isNotBlank()) add("Advertised name: ${freeText(obs.name)}")
                            if (obs.identifier.isNotBlank()) add("Identifier: ${freeText(obs.identifier)}")
                        }
                        if (includeCoordinates) obs.pins.filter { PayloadLocation.validCoord(it.latitude, it.longitude) }.forEach { pin ->
                            add("${SweepReports.pinLabel(pin.kind)}: ${pin.latitude}, ${pin.longitude} (observed: ${pin.observedAt?.let(::time) ?: "unknown"})")
                        }
                    }
                }))
            }
        }
        val payload = SpaceReportPayload(1, "Timestamped Inspection / Turnover Log", sections)
        val bytes = MessageDigest.getInstance("SHA-256").digest(Json.encodeToString(payload).toByteArray(Charsets.UTF_8))
        val digest = buildString(64) { bytes.forEach { append(hex[it.toInt() ushr 4 and 15]); append(hex[it.toInt() and 15]) } }
        return SpaceReport(payload, digest)
    }

    fun integrityValid(log: SpaceInspection, redactedReport: SpaceReport = report(log)): Boolean {
        if (redactedReport.sha256 != log.reportSha256) return false
        if (log.archiveSha256.isNotEmpty() && report(log, includeIdentifiers = true, includeCoordinates = true).sha256 != log.archiveSha256) {
            return false
        }
        return true
    }

    private val formatChars = Regex("\\p{Cf}")

    /** A user-entered space label may repeat a name heard on the radio. Check even rows beyond the archive detail limit. */
    fun redactSpaceName(name: String, radioNames: Sequence<String>): String {
        val cleanName = SpaceLimits.clean(name, SpaceLimits.NAME)
        val compactName = formatChars.replace(name, "").trim()
        val genericLabels = app.fieldwatch.sweep.SweepCategory.entries.map { SweepMapper.categoryLabel(it) }.toSet()
        val matched = radioNames.flatMap { raw ->
            sequenceOf(SpaceLimits.clean(raw, SpaceLimits.RADIO_TEXT), SpaceLimits.clean(formatChars.replace(raw, ""), SpaceLimits.RADIO_TEXT))
        }.any { token ->
            token.isNotBlank() &&
                token !in genericLabels &&
                (cleanName.equals(token, ignoreCase = true) || compactName.equals(token, ignoreCase = true) ||
                    (token.length >= 4 && (cleanName.contains(token, ignoreCase = true) || compactName.contains(token, ignoreCase = true))))
        }
        return if (matched) "[space name withheld]" else name
    }

    private fun coverage(c: SweepCoverage) = listOf(
        "Wi-Fi AP: ${readiness(c.wifiSupported, c.wifiReady)}, ${c.wifiSamples} samples",
        "BLE: ${readiness(c.bleSupported, c.bleReady)}, ${c.bleSamples} samples",
        "Wi-Fi Aware: ${readiness(c.wifiNanSupported, c.wifiNanAvailable)}, ${c.wifiNanSamples} samples",
        "Dropped samples: ${c.droppedSamples}",
    )

    private fun readiness(supported: Boolean, ready: Boolean) = when {
        !supported -> "unsupported or not checked"
        ready -> "ready"
        else -> "unavailable"
    }

    private fun time(epochMillis: Long) = Instant.ofEpochMilli(epochMillis).toString()
}
