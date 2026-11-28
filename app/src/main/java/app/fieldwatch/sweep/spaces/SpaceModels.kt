package app.fieldwatch.sweep.spaces

import app.fieldwatch.sweep.SweepCategory
import app.fieldwatch.sweep.SweepCoverage
import app.fieldwatch.sweep.SweepPin
import kotlinx.serialization.Serializable

@Serializable
enum class SpaceKind(val label: String) {
    ROOM("Room"), RENTAL_UNIT("Rental unit"), OFFICE("Office"), VEHICLE("Vehicle")
}

@Serializable
data class SpaceBaselineDevice(
    val key: String,
    val transport: String,
    val category: SweepCategory,
    val signatureNames: List<String>,
    val randomizedAddress: Boolean,
    val identifier: String,
    val name: String,
    val lastRssi: Int,
    val capturedAt: Long,
)

@Serializable
data class SpaceBaseline(
    val sourceSessionId: String,
    val startedAt: Long,
    val endedAt: Long,
    val coverage: SweepCoverage,
    val devices: List<SpaceBaselineDevice>,
    val ambiguousKeys: Set<String>,
    val unresolvedCount: Int,
    val scanReportedError: Boolean,
)

@Serializable
data class SpaceExpectedDevice(
    val key: String,
    val transport: String,
    val category: SweepCategory,
    val label: String,
    val addedAt: Long,
    val randomizedAddress: Boolean,
)

@Serializable
enum class ChecklistStatus(val label: String) {
    UNCHECKED("Unchecked"), CHECKED_CLEAR("Checked clear"), NEEDS_FOLLOW_UP("Needs follow-up"), NOT_APPLICABLE("N/A")
}

@Serializable
data class SpaceChecklistItem(
    val id: String,
    val title: String,
    val guidance: String,
    val status: ChecklistStatus = ChecklistStatus.UNCHECKED,
    val note: String = "",
    val thermal: SpaceThermalCheck? = null,
)

@Serializable
data class SpaceThermalBaseline(val ambientDeciC: Int, val peakDeciC: Int, val deltaDeciC: Int, val capturedAt: Long)

@Serializable
data class SpaceThermalCheck(
    val ambientDeciC: Int,
    val peakDeciC: Int,
    val deltaDeciC: Int,
    val capturedAt: Long,
    val baselineDeltaDiffDeciC: Int? = null,
    val deviceLabel: String = "",
    val demo: Boolean = false,
    val manufacturer: String = "",
    val serialNumber: String = "",
)

internal fun deciC(value: Int) = java.lang.String.format(java.util.Locale.ROOT, "%.1f", value / 10.0)

internal const val THERMAL_WARNING_DECI_C = 20

internal fun SpaceThermalCheck.changeFrom(baseline: SpaceThermalBaseline?): Int? =
    baseline?.takeIf { it.capturedAt <= capturedAt }?.let { deltaDeciC - it.deltaDeciC }

object SpaceChecklists {
    fun forKind(kind: SpaceKind): List<SpaceChecklistItem> = if (kind == SpaceKind.VEHICLE) listOf(
        SpaceChecklistItem("obd", "OBD-II port", "With the vehicle parked, look for an unfamiliar plug or adapter at the diagnostic port."),
        SpaceChecklistItem("seats", "Under seats", "Look around accessible seat rails and pockets for unfamiliar items or wiring."),
        SpaceChecklistItem("trunk", "Trunk and spare-tire well", "Check the cargo area and accessible storage compartments."),
        SpaceChecklistItem("wheels", "Wheel wells and bumpers", "From a safe standing position, look for unfamiliar attachments on visible surfaces."),
        SpaceChecklistItem("dash", "Dash, headliner and mirror", "Look over visible trim, accessories and mirror mounts."),
        SpaceChecklistItem("console", "Glove box and center console", "Review accessible compartments, chargers and loose accessories."),
    ) else listOf(
        SpaceChecklistItem("detectors", "Smoke detectors", "Visually check the housing and nearby fittings. Leave life-safety equipment in place."),
        SpaceChecklistItem("clocks", "Alarm clocks and radios", "Look over displays, grilles and unfamiliar openings."),
        SpaceChecklistItem("chargers", "USB wall chargers and outlets", "Inspect visible adapters and faceplates without opening electrical fittings."),
        SpaceChecklistItem("mirrors", "Mirrors", "Look over frames, mounts and nearby objects from normal viewing positions."),
        SpaceChecklistItem("vents", "HVAC vents", "Visually check accessible grilles for unfamiliar objects."),
        SpaceChecklistItem("router", "Routers and modems", "Compare visible equipment, attachments and cables with the expected setup."),
        SpaceChecklistItem("decor", "Lamps and picture frames", "Inspect visible fixtures, frames and objects facing occupied areas."),
        SpaceChecklistItem("shelves", "Shelves and loose accessories", "Look over visible objects on shelves, bedside tables and desks."),
    )
}

@Serializable
enum class SpaceWarning(val message: String) {
    NO_BASELINE("No known-good baseline was attached. Unfamiliar observations have no baseline for comparison."),
    ROTATING("Randomized or locally administered addresses can rotate or be reused. Physical identity is unverified."),
    WIFI("Wi-Fi coverage is limited. Supported reception, readiness and fresh samples affect this comparison."),
    BLE("BLE coverage is limited. Supported reception, readiness and fresh samples affect this comparison."),
    FEATURE("Radio capabilities or Wi-Fi Aware availability differed between scans."),
    EVIDENCE("A matched address had different catalog evidence. Its physical identity is unverified."),
    DROPPED("At least one scan dropped samples. Some received observations were not retained."),
    ERROR("At least one scan recorded an error. Coverage may be incomplete."),
    DURATION("Scan durations differed. The opportunities to receive advertisements differed."),
    BASELINE_UNRESOLVED("Some baseline records had no unique comparable identity."),
    THERMAL_DELTA("A fixture’s peak above the frame median rose by at least 2.0 °C from its thermal baseline. Check the fixture for ordinary causes such as charging or sunlight."),
}

@Serializable
data class SpaceObservation(
    val transport: String,
    val category: SweepCategory,
    val name: String,
    val identifier: String,
    val rssi: Int,
    val randomizedAddress: Boolean,
    val pins: List<SweepPin> = emptyList(),
)

@Serializable
data class SpaceFindingGroup(val count: Int, val details: List<SpaceObservation>)

@Serializable
data class SpaceDiffSummary(
    val baselineStartedAt: Long?,
    val baselineEndedAt: Long?,
    val baselineCoverage: SweepCoverage?,
    val baselineUnresolved: Int,
    val newFindings: SpaceFindingGroup,
    val expectedFindings: SpaceFindingGroup,
    val notReobservedBaseline: SpaceFindingGroup,
    val unresolvedFindings: SpaceFindingGroup,
    val warnings: List<SpaceWarning>,
)

@Serializable
data class SpaceInspection(
    val id: String,
    val spaceId: String,
    val spaceName: String,
    val redactedSpaceName: String,
    val spaceKind: SpaceKind,
    val createdAt: Long,
    val completedAt: Long,
    val sessionId: String?,
    val sessionStartedAt: Long,
    val sessionEndedAt: Long,
    val coverage: SweepCoverage,
    val diffSummary: SpaceDiffSummary,
    val checklist: List<SpaceChecklistItem>,
    val reportSha256: String,
    val archiveSha256: String = "",
    val thermalCoverageRecorded: Boolean = false,
)

@Serializable
data class SavedSpace(
    val id: String,
    val name: String,
    val kind: SpaceKind,
    val createdAt: Long,
    val updatedAt: Long,
    val baseline: SpaceBaseline? = null,
    val expectedDevices: List<SpaceExpectedDevice> = emptyList(),
    val inspections: List<SpaceInspection> = emptyList(),
    val thermalBaselines: Map<String, SpaceThermalBaseline> = emptyMap(),
)

internal object SpaceLimits {
    const val SPACES = 25
    const val BASELINE = 500
    const val EXPECTED = 250
    const val INSPECTIONS = 30
    const val REPORT_ROWS_PER_GROUP = 24
    const val NOTE = 256
    const val NAME = 80
    const val RADIO_TEXT = 128

    private val controls = Regex("[\\p{Cc}\\p{Cf}]")
    fun clean(value: String, maximum: Int): String =
        controls.replace(value, " ").trim().take(maximum).trimEnd().dropLastWhile { it.isHighSurrogate() }
}
