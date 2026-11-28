package app.fieldwatch.sweep.spaces

import app.fieldwatch.sweep.*

internal const val ADDRESS = "10:20:30:40:50:60"
internal const val SSID = "Private Beacon"
internal const val LAT = 34.123456
internal const val LON = -118.654321
internal const val SECRET_NOTE = "Contact Casey at 555-0123"

internal fun finding(address: String = ADDRESS, at: Long = 2100L, transport: String = "WIFI") = SweepFinding(
    id = "$transport:$address", name = SSID, category = SweepCategory.CAMERA, transport = transport,
    rssi = -65, firstSeenAt = at, lastSeenAt = at, observations = 2, identifier = address,
    signatureNames = listOf("secret custom signature"), dronePins = listOf(SweepPin(SweepPinKind.AIRCRAFT, LAT, LON)),
)

internal fun scan(id: String = "current", at: Long = 2000L, findings: List<SweepFinding> = listOf(finding(at = at + 100))) =
    SweepSession(id, at, at + 400, SweepSessionState.COMPLETE, findings,
        SweepCoverage(wifiSupported = true, wifiReady = true, wifiSamples = 7, bleSupported = true, bleReady = true,
            bleSamples = 9, wifiStatus = "private wifi status", bleStatus = "private ble status"), phone = "secret-phone")

internal fun space() = SavedSpace("room", "Unit 4B", SpaceKind.RENTAL_UNIT, 500, 500)
internal fun checked(kind: SpaceKind = SpaceKind.RENTAL_UNIT) = SpaceChecklists.forKind(kind).mapIndexed { index, entry ->
    if (index == 0) entry.copy(status = ChecklistStatus.NEEDS_FOLLOW_UP, note = "$SECRET_NOTE $LAT, $LON")
    else entry.copy(status = if (index == 1) ChecklistStatus.NOT_APPLICABLE else ChecklistStatus.CHECKED_CLEAR)
}

internal fun spectrumCheck() = SpaceSpectrumCheck("subghz_315_433", 300_000_000, 450_000_000, -920,
    -760, 433_920_001L, 160, 3300, modelLabel = "tinySA Basic", deviceLabel = "Private Analyzer",
    manufacturer = "Pocket Instruments", serialNumber = "SN-RF-001")

internal fun inspection(): SpaceInspection {
    val room = space().copy(baseline = SpaceAnalyzer.baseline(scan("baseline", 1000, emptyList())))
    val current = scan(findings = listOf(finding().copy(name = "$SSID $LAT, $LON")))
    val summary = SpaceAnalyzer.summary(room, SpaceAnalyzer.diff(room, current))
    val draft = SpaceInspection("log", room.id, room.name, room.name, room.kind, 3000, 4000, current.id,
        current.startedAt, current.endedAt!!, current.coverage, summary, checked(), "")
    return draft.copy(reportSha256 = SpaceReports.report(draft).sha256)
}
