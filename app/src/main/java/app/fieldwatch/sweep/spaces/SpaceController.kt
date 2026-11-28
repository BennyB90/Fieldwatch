package app.fieldwatch.sweep.spaces

import app.fieldwatch.sweep.SweepPersistenceQueue
import app.fieldwatch.sweep.SweepSession
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class SpacesState(
    val spaces: List<SavedSpace> = emptyList(),
    val loaded: Boolean = false,
    val readError: String? = null,
)

/** Uses saved scans only. The existing Room Scan entry point owns every live radio operation. */
internal class SpaceController(
    private val store: SpaceStore,
    private val exports: SpacePdfExporter,
    private val sessions: () -> List<SweepSession>,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val queue = SweepPersistenceQueue(scope)
    private val mutableState = MutableStateFlow(SpacesState())
    val state = mutableState.asStateFlow()

    fun initialize() = ordered {
        try {
            exports.prune()
            publish(store.load())
        } catch (failure: Exception) {
            mutableState.value = SpacesState(readError = failure.message ?: "Cannot open Saved Spaces.")
            throw failure
        }
    }

    fun create(name: String, kind: SpaceKind) = ordered {
        val at = now()
        val space = SavedSpace(UUID.randomUUID().toString(), name(name), kind, at, at)
        publish(store.update { listOf(space) + it })
        space.id
    }

    fun rename(id: String, name: String) = ordered {
        edit(id) { it.copy(name = name(name)) }
    }

    fun delete(id: String) = ordered {
        exports.clear(id)
        publish(store.update { spaces -> spaces.filterNot { it.id == id } })
    }

    fun clear() = ordered {
        exports.clear()
        store.clear()
        publish(emptyList())
    }

    fun setBaseline(id: String, sessionId: String) = ordered {
        val baseline = SpaceAnalyzer.baseline(session(sessionId))
        edit(id) { it.copy(baseline = baseline) }
    }

    fun setThermalBaseline(id: String, itemId: String, reading: SpaceThermalCheck) = ordered {
        edit(id) { space ->
            require(SpaceChecklists.forKind(space.kind).any { it.id == itemId }) { "Unknown checklist item." }
            requireLive(reading)
            space.copy(thermalBaselines = space.thermalBaselines + (itemId to SpaceThermalBaseline(
                reading.ambientDeciC, reading.peakDeciC, reading.deltaDeciC, reading.capturedAt)))
        }
    }

    private fun requireLive(reading: SpaceThermalCheck) {
        require(!reading.demo && reading.capturedAt > 0) { "Only a measured USB thermal frame can be attached." }
        require(reading.ambientDeciC in -2732..7509 && reading.peakDeciC in reading.ambientDeciC..7509 &&
            reading.deltaDeciC in 0..10240 && kotlin.math.abs(reading.peakDeciC - reading.ambientDeciC - reading.deltaDeciC) <= 1) {
            "Invalid measured thermal values."
        }
    }

    fun markExpected(id: String, sessionId: String, key: String) = ordered {
        val scan = session(sessionId)
        edit(id) { space ->
            val finding = requireNotNull(SpaceAnalyzer.diff(space, scan).comparable[key]) {
                "Choose a uniquely observed BLE or Wi-Fi address."
            }
            if (space.expectedDevices.any { it.key == key }) space else
                space.copy(expectedDevices = space.expectedDevices + SpaceAnalyzer.expected(finding, now()))
        }
    }

    fun removeExpected(id: String, key: String) = ordered {
        edit(id) { space -> space.copy(expectedDevices = space.expectedDevices.filterNot { it.key == key }) }
    }

    fun saveInspection(
        reviewed: SavedSpace, scanId: String, inspectionId: String, startedAt: Long,
        checklist: List<SpaceChecklistItem>,
    ) = ordered {
        edit(reviewed.id) { space ->
            if (space.inspections.any { it.id == inspectionId }) return@edit space
            require(space.baseline == reviewed.baseline && space.expectedDevices == reviewed.expectedDevices &&
                space.thermalBaselines == reviewed.thermalBaselines) {
                "The baseline or expected list changed. Review the updated comparison before saving."
            }
            val scan = session(scanId)
            val diff = SpaceAnalyzer.diff(space, scan)
            val completedAt = now()
            require(startedAt in 1..completedAt) { "Check the phone's clock before saving this inspection." }
            val template = SpaceChecklists.forKind(space.kind)
            require(checklist.map { it.id } == template.map { it.id } && checklist.none { it.status == ChecklistStatus.UNCHECKED }) {
                "Choose an outcome for every checklist item, including N/A for skipped items."
            }
            val checked = template.zip(checklist) { item, entry ->
                val thermal = entry.thermal?.let { reading ->
                    requireLive(reading)
                    require(reading.capturedAt in startedAt..completedAt) { "Capture the thermal check during this inspection." }
                    reading.copy(deviceLabel = SpaceLimits.clean(reading.deviceLabel, SpaceLimits.RADIO_TEXT),
                        manufacturer = SpaceLimits.clean(reading.manufacturer, SpaceLimits.RADIO_TEXT),
                        serialNumber = SpaceLimits.clean(reading.serialNumber, SpaceLimits.RADIO_TEXT),
                        baselineDeltaDiffDeciC = reading.changeFrom(space.thermalBaselines[item.id]))
                }
                item.copy(status = entry.status, note = SpaceLimits.clean(entry.note, SpaceLimits.NOTE), thermal = thermal)
            }
            val radioNames = scan.findings.asSequence()
                .flatMap { sequenceOf(it.name, it.identifier, it.droneId.orEmpty()) }
                .map { SpaceLimits.clean(it, SpaceLimits.RADIO_TEXT) } +
                space.baseline?.devices.orEmpty().asSequence().flatMap { sequenceOf(it.name, it.identifier) } +
                space.expectedDevices.asSequence().map { it.label }
            val usbIdentifiers = checked.asSequence().mapNotNull { it.thermal }
                .flatMap { sequenceOf(it.deviceLabel, it.manufacturer, it.serialNumber) }
            val inspection = SpaceInspection(
                id = inspectionId, spaceId = space.id, spaceName = space.name, spaceKind = space.kind,
                redactedSpaceName = SpaceReports.redactSpaceName(space.name, radioNames, usbIdentifiers),
                createdAt = startedAt, completedAt = completedAt, sessionId = scan.id,
                sessionStartedAt = scan.startedAt, sessionEndedAt = requireNotNull(scan.endedAt),
                coverage = SpaceAnalyzer.coverage(scan.coverage), diffSummary = SpaceAnalyzer.summary(space, diff, checked),
                thermalCoverageRecorded = true,
                checklist = checked, reportSha256 = "", archiveSha256 = "",
            )
            val archived = inspection.copy(
                reportSha256 = SpaceReports.report(inspection).sha256,
                archiveSha256 = SpaceReports.report(inspection, includeIdentifiers = true, includeCoordinates = true).sha256,
            )
            space.copy(inspections = (listOf(archived) + space.inspections).take(SpaceLimits.INSPECTIONS))
        }
    }

    fun export(spaceId: String, inspectionId: String, format: SpaceExportFormat, identifiers: Boolean, coordinates: Boolean): Deferred<File> = ordered {
        val log = store.load().find { it.id == spaceId }?.inspections?.find { it.id == inspectionId }
            ?: throw IllegalStateException("This inspection is no longer saved.")
        val original = SpaceReports.report(log)
        check(SpaceReports.integrityValid(log, original)) { "The saved inspection does not match its recorded digest." }
        val report = if (identifiers || coordinates) SpaceReports.report(log, identifiers, coordinates) else original
        exports.export(spaceId, report, format)
    }

    private fun session(id: String) = requireNotNull(sessions().firstOrNull { it.id == id && SpaceAnalyzer.eligible(it) }) {
        "The completed Room Scan is no longer available. Select another scan."
    }

    private fun name(input: String) = SpaceLimits.clean(input, SpaceLimits.NAME).also {
        require(it.isNotBlank()) { "Enter a space name." }
    }

    private fun edit(id: String, change: (SavedSpace) -> SavedSpace) {
        publish(store.update { spaces ->
            require(spaces.any { it.id == id }) { "This space is no longer saved." }
            spaces.map { space ->
                if (space.id != id) space else change(space).let { next ->
                    if (next == space) space else next.copy(updatedAt = maxOf(space.updatedAt, now()))
                }
            }
        })
    }

    private fun publish(spaces: List<SavedSpace>) { mutableState.value = SpacesState(spaces, loaded = true) }

    private fun <T> ordered(block: () -> T): Deferred<T> {
        val result = CompletableDeferred<T>()
        queue.enqueue(onFailure = { failure ->
            if (failure is SpaceHistoryUnreadable) mutableState.value = SpacesState(readError = failure.message)
            result.completeExceptionally(failure)
        }) { result.complete(block()) }
        return result
    }
}
