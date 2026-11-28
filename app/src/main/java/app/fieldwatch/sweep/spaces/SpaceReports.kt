package app.fieldwatch.sweep.spaces

import app.fieldwatch.domain.PayloadLocation
import app.fieldwatch.domain.Rssi
import app.fieldwatch.sweep.SweepCoverage
import app.fieldwatch.sweep.SweepMapper
import app.fieldwatch.sweep.SweepReports
import app.fieldwatch.sweep.spectrum.SpectrumPreset
import app.fieldwatch.sweep.spectrum.deciDbm
import app.fieldwatch.sweep.spectrum.formatMhz
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
        val spaceName = if (includeIdentifiers) log.spaceName else {
            val usb = log.spectrumChecks.asSequence().flatMap { sequenceOf(it.deviceLabel, it.manufacturer, it.serialNumber) } +
                log.checklist.asSequence().mapNotNull { it.thermal }.flatMap { sequenceOf(it.deviceLabel, it.manufacturer, it.serialNumber) }
            mac.replace(redactSpaceName(log.redactedSpaceName, emptySequence(), usb), "[address withheld]")
        }
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
                addAll(diff.warnings.filterNot { it == SpaceWarning.THERMAL_DELTA || it == SpaceWarning.SPECTRUM_SPIKE }.map { it.message })
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
            if (log.thermalCoverageRecorded || log.checklist.any { it.thermal != null }) {
                val template = SpaceChecklists.forKind(log.spaceKind).associateBy { it.id }
                add(SpaceReportSection("USB thermal inspection", buildList {
                    add("Measured checklist items: ${log.checklist.count { it.thermal != null }} of ${log.checklist.size}.")
                    for (item in log.checklist) {
                        val t = item.thermal ?: continue
                        add("${template.getValue(item.id).title} · ${time(t.capturedAt)}")
                        add("Frame median ${deciC(t.ambientDeciC)} °C · peak ${deciC(t.peakDeciC)} °C · ΔT ${deciC(t.deltaDeciC)} °C")
                        add(t.baselineDeltaDiffDeciC?.let { "Change in ΔT from item baseline: ${deciC(it)} °C" }
                            ?: "Item thermal baseline unavailable.")
                        if (includeIdentifiers) {
                            if (t.deviceLabel.isNotBlank()) add("USB accessory: ${freeText(t.deviceLabel)}")
                            if (t.manufacturer.isNotBlank()) add("USB manufacturer: ${freeText(t.manufacturer)}")
                            if (t.serialNumber.isNotBlank()) add("USB serial: ${freeText(t.serialNumber)}")
                        }
                    }
                    if (SpaceWarning.THERMAL_DELTA in diff.warnings) add(SpaceWarning.THERMAL_DELTA.message)
                    add("Surface warmth can have ordinary causes. Inspect the item physically to identify it.")
                }))
            }
            if (log.spectrumCoverageRecorded || log.spectrumChecks.isNotEmpty()) {
                add(SpaceReportSection("USB wideband spectrum", buildList {
                    add(if (log.spectrumCoverageRecorded) "Bands measured: ${log.spectrumChecks.size} of ${SpectrumPreset.entries.size}."
                        else "Attached band checks: ${log.spectrumChecks.size}. Overall band coverage unrecorded.")
                    for (s in log.spectrumChecks) {
                        val preset = requireNotNull(SpectrumPreset.byId(s.presetId))
                        add("${preset.title} · ${formatMhz(s.startHz)}–${formatMhz(s.stopHz)} MHz · ${time(s.capturedAt)}")
                        add("Band median ${deciDbm(s.medianDeciDbm)} dBm · peak ${deciDbm(s.peakDeciDbm)} dBm at ${formatMhz(s.peakHz)} MHz")
                        add("Peak above band median: ${deciDbm(s.prominenceDeciDb)} dB")
                        if (s.baselinePeakDiffDeciDb != null && s.baselineProminenceDiffDeciDb != null) {
                            add("Change from saved band baseline: peak ${deciDbm(s.baselinePeakDiffDeciDb)} dB · prominence ${deciDbm(s.baselineProminenceDiffDeciDb)} dB")
                        } else add("Earlier baseline unavailable for this band.")
                        add(preset.guidance)
                        if (includeIdentifiers) {
                            if (s.modelLabel.isNotEmpty()) add("Analyzer model: ${freeText(s.modelLabel)}")
                            if (s.deviceLabel.isNotEmpty()) add("USB product: ${freeText(s.deviceLabel)}")
                            if (s.manufacturer.isNotEmpty()) add("USB manufacturer: ${freeText(s.manufacturer)}")
                            if (s.serialNumber.isNotEmpty()) add("USB serial: ${freeText(s.serialNumber)}")
                        }
                    }
                    if (SpaceWarning.SPECTRUM_SPIKE in diff.warnings) add(SpaceWarning.SPECTRUM_SPIKE.message)
                    if (log.spectrumChecks.isNotEmpty()) {
                        // The private inspection UUID blinds the digest so exports cannot link USB identities across visits.
                        val fields = log.spectrumChecks.map { listOf(it.presetId, it.modelLabel, it.deviceLabel, it.manufacturer, it.serialNumber) }
                        val commitment = sha256(Json.encodeToString(listOf(listOf(log.id)) + fields))
                        add("USB metadata integrity (SHA-256): $commitment")
                    }
                    add("Peaks measure received energy. Identifying a source requires physical inspection. Physical USB-CDC reception awaits bench verification.")
                }))
            }
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
        return SpaceReport(payload, sha256(Json.encodeToString(payload)))
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return buildString(64) { bytes.forEach { append(hex[it.toInt() ushr 4 and 15]); append(hex[it.toInt() and 15]) } }
    }

    fun integrityValid(log: SpaceInspection, redactedReport: SpaceReport = report(log)): Boolean {
        if (redactedReport.sha256 != log.reportSha256) return false
        if (log.spectrumChecks.any { it.demo }) return false
        if (log.archiveSha256.isEmpty() && (log.thermalCoverageRecorded || log.checklist.any { it.thermal != null } ||
                log.spectrumCoverageRecorded || log.spectrumChecks.isNotEmpty())) return false
        if (log.archiveSha256.isNotEmpty() && report(log, includeIdentifiers = true, includeCoordinates = true).sha256 != log.archiveSha256) {
            return false
        }
        return true
    }

    private val formatChars = Regex("\\p{Cf}")

    /** A user-entered space label may repeat a name heard on the radio. Check even rows beyond the archive detail limit. */
    fun redactSpaceName(name: String, radioNames: Sequence<String>, usbIdentifiers: Sequence<String> = emptySequence()): String {
        val cleanName = SpaceLimits.clean(name, SpaceLimits.NAME)
        val compactName = formatChars.replace(name, "").trim()
        val usbMatched = usbIdentifiers.flatMap { raw ->
            sequenceOf(SpaceLimits.clean(raw, SpaceLimits.RADIO_TEXT), SpaceLimits.clean(formatChars.replace(raw, ""), SpaceLimits.RADIO_TEXT))
        }.any { token ->
            if (token.isBlank()) false
            else if (token.length >= 4) cleanName.contains(token, ignoreCase = true) || compactName.contains(token, ignoreCase = true)
            else {
                val boundary = Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(token)}(?![\\p{L}\\p{N}])")
                cleanName.equals(token, ignoreCase = true) || compactName.equals(token, ignoreCase = true) ||
                    boundary.containsMatchIn(cleanName) || boundary.containsMatchIn(compactName)
            }
        }
        if (usbMatched) return "[space name withheld]"
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
