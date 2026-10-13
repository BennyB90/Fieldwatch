package app.fieldwatch.sweep

import app.fieldwatch.domain.DefaultCatalog
import app.fieldwatch.domain.PayloadLocation
import app.fieldwatch.domain.Rssi
import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** Explicit export boundary. Stored and displayed findings can contain personal radio data. */
object SweepReports {
    private val format = Json { prettyPrint = true; encodeDefaults = true }
    private val stockNames by lazy { DefaultCatalog.fleets().filter { it.builtIn }.map { it.name }.toSet() }
    private val evidenceKinds = mapOf(
        "Matched signature" to "Catalog signature matched.",
        "Advertised name rule" to "Advertised name matched a catalog pattern; the name is withheld.",
        "Catalog prefix rule" to "Address or vendor information matched a catalog prefix.",
        "Address prefix rule" to "Address matched a catalog prefix; the address is withheld.",
        "Wi-Fi vendor rule" to "Wi-Fi vendor information matched a catalog pattern.",
        "Bluetooth service rule" to "Advertised Bluetooth service matched a catalog pattern.",
        "Bluetooth payload rule" to "Bluetooth service data matched a catalog pattern.",
        "Manufacturer rule" to "Advertised company ID matched a catalog rule; a shared ID is not unique identity.",
        "Manufacturer payload rule" to "Manufacturer data matched a catalog pattern.",
        "Radio type rule" to "Radio type matched a catalog rule.",
        "Hidden network rule" to "Wi-Fi network did not advertise its name.",
        "Cluster signature" to "Upstream peer-group signature matched; peers are not independently verified in this report.",
        "Remote ID" to "OpenDroneID message decoded from advertised radio data.",
        "Radio observation" to "Radio observed without a catalog signature match.",
        "Synthetic sample" to "Demo data only; no physical device was detected.",
    )
    private val decimalNumber = Regex("(?<![A-Za-z0-9])[-+]?\\d{1,3}\\.\\d+(?![A-Za-z0-9])")
    private val coordinatePair = Regex("(?<![A-Za-z0-9])[-+]?\\d{1,3}(?:\\.\\d+)?\\s*[,;/]\\s*[-+]?\\d{1,3}(?:\\.\\d+)?(?![A-Za-z0-9])")

    fun json(session: SweepSession, includeIdentifiers: Boolean = false, includeCoordinates: Boolean = false): String {
        val fields = format.encodeToJsonElement(redacted(session, includeIdentifiers, includeCoordinates)).jsonObject.toMutableMap()
        fields["reportLabel"] = JsonPrimitive(if (session.demo) "SYNTHETIC SAMPLE REPORT — DEMO, NO SCAN PERFORMED" else "SWEEP scan session report")
        fields["identifiersIncluded"] = JsonPrimitive(includeIdentifiers)
        fields["coordinatesIncluded"] = JsonPrimitive(includeCoordinates)
        fields["durationMillis"] = durationMillis(session)?.let { JsonPrimitive(it) } ?: JsonNull
        fields["limits"] = JsonPrimitive("Signal strength does not measure distance. Broadcast identity and positions can be inaccurate or spoofed. A quiet scan cannot certify a safe room or rule out silent cameras, microphones, or unsupported radios.")
        return format.encodeToString(JsonObject(fields))
    }

    fun text(session: SweepSession, includeIdentifiers: Boolean = false, includeCoordinates: Boolean = false): String {
        val safe = redacted(session, includeIdentifiers, includeCoordinates)
        return buildString {
            appendLine(if (safe.demo) "SWEEP — SYNTHETIC SAMPLE REPORT (DEMO)" else "SWEEP — SCAN SESSION REPORT")
            if (safe.demo) appendLine("Synthetic sample data only. No radios were detected by this sample.")
            appendLine("State: ${safe.state.name.lowercase()}")
            appendLine("Started: ${time(safe.startedAt)}")
            appendLine("Ended: ${safe.endedAt?.let { time(it) } ?: "not recorded"}")
            val duration = durationMillis(safe)
            appendLine(if (duration == null) {
                "Duration: unavailable; actual start-to-end duration could not be established."
            } else {
                "Duration: ${duration / 1_000.0} seconds (recorded start to end)"
            })
            if (includeIdentifiers) appendLine("Session identifier: ${safe.id}")
            appendLine("Catalog version: ${safe.catalogVersion}")
            appendLine("Findings: ${safe.findings.size}")
            SweepCategory.entries.forEach { category ->
                appendLine("${SweepMapper.categoryLabel(category)}: ${safe.findings.count { it.category == category }}")
            }
            appendLine("Coverage: Wi-Fi ${safe.coverage.wifiStatus}; Bluetooth ${safe.coverage.bleStatus}")
            appendLine("Samples: Wi-Fi AP ${safe.coverage.wifiSamples}; Wi-Fi NAN ${safe.coverage.wifiNanSamples}; Bluetooth ${safe.coverage.bleSamples}; dropped ${safe.coverage.droppedSamples}")
            appendLine("Wi-Fi NAN discovery: ${safe.coverage.wifiNanStatus}")
            appendLine("Wi-Fi vendor information: ${if (safe.coverage.wifiIeAvailable) "available" else "unavailable"}; Wi-Fi NAN: ${if (safe.coverage.wifiNanAvailable) "available" else "unavailable"}")
            appendLine("Extended Bluetooth advertisements: ${when (safe.coverage.bleExtendedSupported) { true -> "supported"; false -> "unsupported"; null -> "unknown" }}")
            if (safe.startBattery != null && safe.endBattery != null) {
                appendLine("Battery: ${safe.startBattery}% at start; ${safe.endBattery}% at end")
            }
            safe.error?.let { appendLine(it) }
            appendLine("Identifiers and advertised names: ${if (includeIdentifiers) "included by request" else "withheld"}")
            appendLine("Coordinates: ${if (includeCoordinates) "included by request" else "withheld"}")
            safe.findings.forEachIndexed { index, finding ->
                appendLine()
                appendLine("${index + 1}. ${finding.name} — ${SweepMapper.categoryLabel(finding.category)}")
                appendLine("${finding.transport} • ${if (Rssi.measured(finding.rssi)) "${finding.rssi} dBm" else "signal unavailable"} • ${finding.observations} observations")
                if (finding.signalSamples.isNotEmpty()) appendLine("Signal history: ${finding.signalSamples.size} recorded measurements; strength is not distance.")
                appendLine("Observed: ${time(finding.firstSeenAt)} to ${time(finding.lastSeenAt)}")
                if (finding.signatureNames.isNotEmpty()) appendLine("Catalog: ${finding.signatureNames.joinToString(", ")}")
                if (includeIdentifiers) {
                    if (finding.identifier.isNotBlank()) appendLine("Radio address: ${finding.identifier}")
                    finding.droneId?.let { appendLine("UAS identifier: $it") }
                }
                if (finding.randomizedAddress) appendLine("Address may rotate; observation count is not a count of physical devices.")
                finding.evidence.forEach { appendLine("Evidence — ${it.label}: ${it.value}") }
                finding.dronePins.forEach { pin ->
                    appendLine("${pinLabel(pin.kind)}: ${pin.latitude}, ${pin.longitude}; coordinate observation time ${pin.observedAt?.let { time(it) } ?: "unknown"}")
                }
                appendLine(finding.interpretation)
                finding.nextSteps.forEach { appendLine("• $it") }
            }
            appendLine()
            appendLine("Limits: signal strength does not measure distance. Broadcast identity and positions can be inaccurate or spoofed. A quiet scan cannot certify a safe room or rule out silent cameras, microphones, or unsupported radios.")
            append("Reports stay on this device until you choose to share them.")
        }
    }

    private fun redacted(session: SweepSession, identifiers: Boolean, coordinates: Boolean): SweepSession = session.copy(
        id = if (identifiers) exportedText(session.id, coordinates) else "session",
        // Free-form metadata and error strings can themselves contain SSIDs, addresses or GPS.
        phone = "Withheld",
        androidVersion = session.androidVersion.takeIf { it.matches(Regex("\\d{1,2}(?:\\.\\d{1,2})?")) } ?: "Unknown",
        error = session.error?.let { "Session reported an error; details remain on the device." },
        coverage = session.coverage.copy(
            wifiStatus = coverageStatus(session.coverage.wifiSupported, session.coverage.wifiReady, session.coverage.wifiSamples),
            bleStatus = coverageStatus(session.coverage.bleSupported, session.coverage.bleReady, session.coverage.bleSamples),
            wifiNanStatus = if (!session.coverage.wifiNanSupported) "unsupported or not checked" else if (session.coverage.wifiNanSamples == 0L) "supported; no samples received" else "supported; samples received",
        ),
        findings = session.findings.mapIndexed { index, finding ->
            val products = finding.signatureNames.filter { it in stockNames }.distinct()
            val pins = finding.dronePins.filter { PayloadLocation.validCoord(it.latitude, it.longitude) }
            val categoricalEvidence = finding.evidence.mapNotNull { evidence ->
                evidenceKinds[evidence.label]?.let { SweepEvidence(evidence.label, it) }
            }.distinct().toMutableList()
            if (pins.isNotEmpty()) categoricalEvidence += SweepEvidence("Broadcast positions", pins.map { pinLabel(it.kind) }.distinct().joinToString(", ") + "; coordinate age may be unknown.")
            finding.copy(
                id = "finding-${index + 1}",
                name = if (identifiers) exportedText(finding.name, coordinates) else products.firstOrNull() ?: SweepMapper.categoryLabel(finding.category),
                transport = finding.transport.takeIf { it in setOf("BLE", "WIFI", "WIFI NAN", "WIFI_NAN") } ?: "Unknown radio",
                signatureNames = products,
                evidence = categoricalEvidence,
                identifier = if (identifiers) exportedText(finding.identifier, coordinates) else "",
                droneId = if (identifiers) finding.droneId?.let { exportedText(it, coordinates) } else null,
                dronePins = if (coordinates) pins else emptyList(),
                interpretation = SweepMapper.interpretation(finding.category),
                nextSteps = SweepMapper.nextSteps(finding.category),
            )
        },
    )

    private fun exportedText(value: String, coordinates: Boolean): String {
        val singleLine = value.replace(Regex("[\\p{Cc}\\p{Cf}]"), " ").take(256)
        return if (coordinates) singleLine else decimalNumber.replace(coordinatePair.replace(singleLine, "[coordinates withheld]"), "[number withheld]")
    }

    private fun coverageStatus(supported: Boolean, ready: Boolean, samples: Long): String = when {
        !supported -> "unsupported or not checked"
        !ready -> "not ready"
        samples == 0L -> "ready; no samples received"
        else -> "ready; samples received"
    }

    private fun time(at: Long): String = runCatching { Instant.ofEpochMilli(at).toString() }.getOrElse { "unknown" }

    private fun durationMillis(session: SweepSession): Long? {
        val end = session.endedAt ?: return null
        if (end < session.startedAt) return null
        return runCatching { Math.subtractExact(end, session.startedAt) }.getOrNull()
    }

    internal fun pinLabel(kind: SweepPinKind): String = when (kind) {
        SweepPinKind.AIRCRAFT -> "Aircraft position"
        SweepPinKind.CONTROL_STATION -> "Control station position"
        SweepPinKind.TAKEOFF -> "Takeoff point"
        SweepPinKind.FIXED_OPERATOR -> "Fixed operator position"
        SweepPinKind.UNSPECIFIED_OPERATOR -> "Operator position (type unspecified)"
    }
}
