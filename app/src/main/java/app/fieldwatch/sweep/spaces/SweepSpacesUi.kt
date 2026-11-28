package app.fieldwatch.sweep.spaces

import android.app.Activity
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.fieldwatch.sweep.SweepComparisons
import app.fieldwatch.sweep.SweepFinding
import app.fieldwatch.sweep.SweepMapper
import app.fieldwatch.sweep.SweepSession
import app.fieldwatch.sweep.thermal.SweepThermalActivity
import app.fieldwatch.sweep.spectrum.SpectrumPreset
import app.fieldwatch.sweep.spectrum.deciDbm
import app.fieldwatch.sweep.spectrum.formatMhz
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private enum class Route(val title: String) {
    SPACES("Saved Spaces"), SPACE("Space"), BASELINE("Choose baseline"), SCAN("Attach Room Scan"),
    INSPECT("Space inspection"), LOG("Inspection log")
}

private enum class Dialog { CREATE, RENAME, DELETE, CLEAR, DISCARD }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SweepSpacesUi(
    state: SpacesState, history: List<SweepSession>, controller: SpaceController,
    onRoomScan: () -> Unit, onShare: (File, SpaceExportFormat) -> Unit,
) {
    var route by rememberSaveable { mutableStateOf(Route.SPACES) }
    var dialog by rememberSaveable { mutableStateOf<Dialog?>(null) }
    var spaceId by rememberSaveable { mutableStateOf("") }
    var scanId by rememberSaveable { mutableStateOf("") }
    var inspectionId by rememberSaveable { mutableStateOf("") }
    var startedAt by rememberSaveable { mutableLongStateOf(0L) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun runAction(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "The operation could not be completed." }
            finally { busy = false }
        }
    }
    val space = state.spaces.find { it.id == spaceId }
    val scans = remember(history) { history.filter(SpaceAnalyzer::eligible) }
    val scan = scans.find { it.id == scanId }
    val log = space?.inspections?.find { it.id == inspectionId }
    val context = LocalContext.current
    var pendingItem by rememberSaveable { mutableStateOf<String?>(null) }
    var draftJson by rememberSaveable(inspectionId) { mutableStateOf("{}") }
    val thermalDraft = remember(draftJson) { Json.decodeFromString<Map<String, SpaceThermalCheck>>(draftJson) }
    val thermalLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val itemId = pendingItem
        pendingItem = null
        val reading = if (result.resultCode == Activity.RESULT_OK) SweepThermalActivity.reading(result.data) else null
        if (itemId != null && reading != null) {
            when (route) {
                Route.SPACE -> runAction { controller.setThermalBaseline(spaceId, itemId, reading).await() }
                Route.INSPECT -> if (reading.capturedAt >= startedAt) {
                    error = null
                    draftJson = Json.encodeToString(thermalDraft + (itemId to reading))
                } else error = "Capture the thermal check during this inspection."
                else -> Unit
            }
        }
    }
    var spectrumJson by rememberSaveable(inspectionId) { mutableStateOf("{}") }
    val spectrumDraft = remember(spectrumJson) { Json.decodeFromString<Map<String, SpaceSpectrumCheck>>(spectrumJson) }
    val spectrumLauncher = rememberLauncherForActivityResult(remember { CaptureSpectrumBand() }) { reading ->
        if (reading != null) when (route) {
            Route.SPACE -> runAction { controller.setSpectrumBaseline(spaceId, reading).await() }
            Route.INSPECT -> runCatching {
                reading.requireLive()
                require(reading.capturedAt >= startedAt) { "Capture the wideband sweep during this inspection." }
            }.onSuccess {
                error = null
                spectrumJson = Json.encodeToString(spectrumDraft + (reading.presetId to reading))
            }.onFailure { failure ->
                error = failure.message ?: "Invalid wideband spectrum sweep."
            }
            else -> Unit
        }
    }
    fun measure(item: SpaceChecklistItem) {
        if (busy || pendingItem != null) return
        pendingItem = item.id
        thermalLauncher.launch(SweepThermalActivity.captureIntent(context, item.title))
    }
    fun back() {
        if (busy) return
        error = null
        when (route) {
            Route.SPACES -> onRoomScan()
            Route.SPACE -> route = Route.SPACES
            Route.INSPECT -> dialog = Dialog.DISCARD
            else -> route = Route.SPACE
        }
    }
    var backOffset by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = route != Route.SPACES || busy) { gesture ->
        try {
            gesture.collect { event ->
                val direction = if (event.swipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f
                if (!busy) backOffset = event.progress * direction
            }
            back()
        } finally {
            backOffset = 0f
        }
    }
    LaunchedEffect(state.loaded, space, log, route) {
        if (state.loaded && route != Route.SPACES && space == null) route = Route.SPACES
        else if (state.loaded && route == Route.LOG && log == null) route = Route.SPACE
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(title = { Text(route.title) }, navigationIcon = {
                IconButton(onClick = ::back, enabled = !busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            }, windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).graphicsLayer {
            translationX = size.width * 0.08f * backOffset
        }) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.takeIf { it != state.readError }?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 18.dp))
            }
            when {
                state.readError != null -> SpaceList {
                    item { Text(state.readError) }
                    item { OutlinedButton({ runAction { controller.initialize().await() } }) { Text("Retry reading") } }
                    item { TextButton({ dialog = Dialog.CLEAR }) { Text("Clear Saved Spaces") } }
                }
                !state.loaded -> LinearProgressIndicator(Modifier.fillMaxWidth())
                route == Route.SPACES -> SpaceList {
                    item { Button({ dialog = Dialog.CREATE }, enabled = !busy && state.spaces.size < SpaceLimits.SPACES) { Text("Create space") } }
                    if (state.spaces.isEmpty()) item { Text("Save a room, rental unit, office or vehicle to compare repeat inspections.") }
                    else item { Text("${state.spaces.size} of ${SpaceLimits.SPACES} spaces") }
                    items(state.spaces, key = { it.id }) { saved ->
                        SelectRow(saved.name, "${saved.kind.label} · ${saved.inspections.size} inspection logs") {
                            spaceId = saved.id
                            route = Route.SPACE
                        }
                    }
                }
                space == null -> Unit
                route == Route.SPACE -> SpaceDetails(space, onBaseline = { route = Route.BASELINE }, onInspect = { route = Route.SCAN },
                    onThermal = ::measure, onSpectrum = { spectrumLauncher.launch(it) },
                    onRename = { dialog = Dialog.RENAME }, onDelete = { dialog = Dialog.DELETE },
                    onRemove = { runAction { controller.removeExpected(space.id, it).await() } },
                    onLog = { inspectionId = it; route = Route.LOG })
                route == Route.BASELINE || route == Route.SCAN -> {
                    val isBaseline = route == Route.BASELINE
                    val options = if (isBaseline) scans else scans.filter { SpaceAnalyzer.canInspect(space, it) }
                    SpaceList {
                        item { Text(if (isBaseline) "Use a completed Room Scan from a known-good visit to ${space.name}."
                            else "Choose a completed Room Scan from this visit to ${space.name}.") }
                        if (options.isEmpty()) item {
                            Text(if (isBaseline || space.baseline == null) "Complete a measured Room Scan to continue."
                                else "Complete a new Room Scan after the baseline to continue.")
                        }
                        items(options, key = { it.id }) { selected ->
                            SelectRow(date(selected.startedAt), "${selected.findings.size} observations · ${(selected.endedAt!! - selected.startedAt) / 1000} s") {
                                if (isBaseline) runAction { controller.setBaseline(space.id, selected.id).await(); route = Route.SPACE }
                                else {
                                    scanId = selected.id
                                    startedAt = System.currentTimeMillis()
                                    inspectionId = UUID.randomUUID().toString()
                                    route = Route.INSPECT
                                }
                            }
                        }
                        item { OutlinedButton(onRoomScan) { Text("Go to Room Scan") } }
                    }
                }
                route == Route.INSPECT && (scan == null || !SpaceAnalyzer.canInspect(space, scan)) -> SpaceList {
                    item { Text("The attached scan is no longer available for this baseline.") }
                    item { Button({ route = Route.SCAN }) { Text("Choose another scan") } }
                }
                route == Route.INSPECT && scan != null -> key(inspectionId) {
                    InspectSpace(space, scan, busy,
                        thermal = thermalDraft, onThermal = ::measure,
                        spectrum = spectrumDraft, onSpectrum = { spectrumLauncher.launch(it) },
                        onRemoveSpectrum = { spectrumJson = Json.encodeToString(spectrumDraft - it) },
                        onRemoveThermal = { draftJson = Json.encodeToString(thermalDraft - it) },
                        onExpected = { key, remove -> runAction {
                            if (remove) controller.removeExpected(space.id, key).await()
                            else controller.markExpected(space.id, scan.id, key).await()
                        } },
                        onSave = { checklist -> runAction {
                            controller.saveInspection(space, scan.id, inspectionId, startedAt, checklist, spectrumDraft.values.toList()).await()
                            route = Route.LOG
                        } })
                }
                route == Route.LOG && log != null -> key(log.id) {
                    ArchivedLog(log, busy) { format, identifiers, coordinates -> runAction {
                        val file = controller.export(space.id, log.id, format, identifiers, coordinates).await()
                        onShare(file, format)
                    } }
                }
            }
        }
    }
    when (dialog) {
        Dialog.CREATE, Dialog.RENAME -> NameDialog(if (dialog == Dialog.RENAME) space else null, { dialog = null }) { name, kind ->
            val creating = dialog == Dialog.CREATE
            dialog = null
            runAction {
                if (creating) { spaceId = controller.create(name, kind).await(); route = Route.SPACE }
                else controller.rename(spaceId, name).await()
            }
        }
        Dialog.DELETE, Dialog.CLEAR, Dialog.DISCARD -> {
            val pending = dialog
            AlertDialog(onDismissRequest = { dialog = null }, title = { Text(when (pending) {
                Dialog.DELETE -> "Delete ${space?.name}?"
                Dialog.CLEAR -> "Clear all Saved Spaces?"
                else -> "Discard this inspection?"
            }) }, text = { Text(when (pending) {
                Dialog.DELETE -> "Deletes this space, its baseline, expected devices, archived inspections and local share files."
                Dialog.CLEAR -> "Deletes all Saved Spaces and their local inspection shares, including unreadable data."
                else -> "Your unsaved checklist will be discarded. Changes to expected devices are already saved."
            }) }, confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    when (pending) {
                        Dialog.DELETE -> runAction { controller.delete(spaceId).await(); route = Route.SPACES }
                        Dialog.CLEAR -> runAction { controller.clear().await(); route = Route.SPACES }
                        else -> route = Route.SPACE
                    }
                }) { Text(when (pending) { Dialog.DELETE -> "Delete"; Dialog.CLEAR -> "Clear all"; else -> "Discard" }) }
            }, dismissButton = { TextButton({ dialog = null }) { Text("Cancel") } })
        }
        null -> Unit
    }
}

@Composable
private fun SpaceList(content: LazyListScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
private fun Heading(title: String) { Text(title, style = MaterialTheme.typography.titleMedium) }

@Composable
private fun SelectRow(title: String, detail: String, select: () -> Unit) {
    OutlinedCard(onClick = select, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Heading(title)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun date(value: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(value))

@Composable
private fun NameDialog(space: SavedSpace?, close: () -> Unit, save: (String, SpaceKind) -> Unit) {
    var name by rememberSaveable { mutableStateOf(space?.name.orEmpty()) }
    var kind by rememberSaveable { mutableStateOf(space?.kind ?: SpaceKind.ROOM) }
    AlertDialog(onDismissRequest = close, title = { Text(if (space == null) "Create space" else "Rename space") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it.take(SpaceLimits.NAME) }, label = { Text("Space name") }, singleLine = true)
                Text("The space name appears on shared inspection logs.", style = MaterialTheme.typography.bodySmall)
                if (space == null) SpaceKind.entries.forEach { item ->
                    Row(Modifier.fillMaxWidth().selectable(kind == item, role = Role.RadioButton) { kind = item }
                        .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(kind == item, null)
                        Text(item.label, Modifier.padding(start = 10.dp))
                    }
                }
            }
        }, confirmButton = { TextButton({ save(name, kind) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(close) { Text("Cancel") } })
}

@Composable
private fun SpaceDetails(
    space: SavedSpace, onBaseline: () -> Unit, onInspect: () -> Unit, onRename: () -> Unit,
    onDelete: () -> Unit, onRemove: (String) -> Unit, onLog: (String) -> Unit,
    onThermal: (SpaceChecklistItem) -> Unit, onSpectrum: (SpectrumPreset) -> Unit,
) {
    var expectedOpen by rememberSaveable { mutableStateOf(false) }
    var thermalOpen by rememberSaveable { mutableStateOf(false) }
    SpaceList {
        item {
            Text(space.name, style = MaterialTheme.typography.headlineMedium)
            Text(space.kind.label)
        }
        item { Button(onInspect) { Text("Start inspection") } }
        item {
            Heading("Known-good baseline")
            Text(space.baseline?.let { "${date(it.startedAt)} · ${it.devices.size} comparable addresses · ${it.unresolvedCount} unresolved" }
                ?: "No baseline set")
            OutlinedButton(onBaseline) { Text(if (space.baseline == null) "Set baseline" else "Update baseline") }
        }
        item {
            Heading("USB spectrum baselines")
            SpectrumBandPicker("Measure band baseline", onSpectrum)
            space.spectrumBaselines.values.forEach { band ->
                Text("${SpectrumPreset.byId(band.presetId)?.title} · ${date(band.capturedAt)}")
                Text("Median ${deciDbm(band.medianDeciDbm)} dBm · peak ${deciDbm(band.peakDeciDbm)} dBm at ${formatMhz(band.peakHz)} MHz · Δ ${deciDbm(band.prominenceDeciDb)} dB",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        item { OutlinedButton({ thermalOpen = !thermalOpen }) {
            Text("Thermal baselines (${space.thermalBaselines.size})")
        } }
        if (thermalOpen) items(SpaceChecklists.forKind(space.kind), key = { "thermal-${it.id}" }) { row ->
            val baseline = space.thermalBaselines[row.id]
            SelectRow(row.title, baseline?.let { "ΔT ${deciC(it.deltaDeciC)} °C · ${date(it.capturedAt)} · measure again" }
                ?: "Measure item baseline") { onThermal(row) }
        }
        item { OutlinedButton({ expectedOpen = !expectedOpen }) {
            Text("${if (expectedOpen) "Hide" else "Show"} expected devices (${space.expectedDevices.size})")
        } }
        if (expectedOpen) items(space.expectedDevices, key = { it.key }) { device ->
            Text(device.label.ifBlank { device.transport })
            Text(device.key, style = MaterialTheme.typography.bodySmall)
            TextButton({ onRemove(device.key) }) { Text("Remove expected") }
        }
        item { Heading("Inspection logs"); Text("This space retains the latest ${SpaceLimits.INSPECTIONS} saved inspections.") }
        items(space.inspections, key = { it.id }) { log ->
            SelectRow(date(log.completedAt), "${log.diffSummary.newFindings.count} new · ${log.checklist.count { it.status == ChecklistStatus.NEEDS_FOLLOW_UP }} need follow-up") { onLog(log.id) }
        }
        item {
            Row { TextButton(onRename) { Text("Rename space") }; TextButton(onDelete) { Text("Delete space") } }
        }
    }
}

@Composable
private fun SpectrumBandPicker(label: String, select: (SpectrumPreset) -> Unit, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }, enabled = enabled) { Text(label) }
        DropdownMenu(open, { open = false }) {
            SpectrumPreset.entries.forEach { preset ->
                DropdownMenuItem({ Text(preset.title) }, { open = false; select(preset) })
            }
        }
    }
}

@Composable
private fun InspectSpace(
    space: SavedSpace, scan: SweepSession, busy: Boolean, onExpected: (String, Boolean) -> Unit,
    thermal: Map<String, SpaceThermalCheck>, onThermal: (SpaceChecklistItem) -> Unit, onRemoveThermal: (String) -> Unit,
    spectrum: Map<String, SpaceSpectrumCheck>, onSpectrum: (SpectrumPreset) -> Unit, onRemoveSpectrum: (String) -> Unit,
    onSave: (List<SpaceChecklistItem>) -> Unit,
) {
    val diff = remember(space.baseline, space.expectedDevices, scan) { SpaceAnalyzer.diff(space, scan) }
    val template = remember(space.kind) { SpaceChecklists.forKind(space.kind) }
    var statuses by rememberSaveable { mutableStateOf(template.map { it.status.name }) }
    var notes by rememberSaveable { mutableStateOf(template.map { "" }) }
    var expectedOpen by rememberSaveable { mutableStateOf(false) }
    val expectedKeys = remember(space.expectedDevices) { space.expectedDevices.mapTo(mutableSetOf()) { it.key } }
    fun LazyListScope.findings(list: List<SweepFinding>) {
        items(list) { finding ->
            val key = SweepComparisons.key(finding)
            Column {
                Text(finding.name.ifBlank { SweepMapper.categoryLabel(finding.category) }, style = MaterialTheme.typography.titleSmall)
                Text(listOfNotNull(finding.transport, key?.substringAfter(':')).joinToString(" · "))
                if (key != null && diff.comparable[key] === finding) {
                    val expected = key in expectedKeys
                    TextButton({ onExpected(key, expected) }, enabled = !busy) {
                        Text(if (expected) "Remove expected" else "Mark expected")
                    }
                }
            }
        }
    }
    SpaceList {
        item {
            Heading(space.name)
            Text("Room Scan · ${date(scan.startedAt)}")
            Text("Wi-Fi AP ${scan.coverage.wifiSamples} · BLE ${scan.coverage.bleSamples} · Wi-Fi Aware ${scan.coverage.wifiNanSamples} samples")
            if (space.baseline == null) Text(SpaceWarning.NO_BASELINE.message)
        }
        item { Heading("New since baseline (${diff.newFindings.size})") }
        findings(diff.newFindings)
        item { OutlinedButton({ expectedOpen = !expectedOpen }) {
            Text("${if (expectedOpen) "Hide" else "Show"} expected / baseline matched (${diff.expectedFindings.size})")
        } }
        if (expectedOpen) findings(diff.expectedFindings)
        item { Heading("Not reobserved from baseline (${diff.notReobservedBaseline.size})") }
        items(diff.notReobservedBaseline) { device ->
            Text("${device.name.ifBlank { SweepMapper.categoryLabel(device.category) }} · ${device.key}")
        }
        item { Heading("Unresolved (${diff.unresolvedFindings.size})") }
        findings(diff.unresolvedFindings)
        item {
            Heading("Comparison limits")
            Text("A transmitter can be missed on a scan. An address match does not establish physical identity.")
            if ((space.baseline?.unresolvedCount ?: 0) > 0) Text("Unresolved baseline records: ${space.baseline?.unresolvedCount}")
            diff.warnings.filterNot { it == SpaceWarning.NO_BASELINE }.forEach { Text(it.message) }
        }
        item {
            Heading("USB wideband spectrum")
            SpectrumBandPicker("Measure band check", onSpectrum, enabled = !busy)
        }
        items(spectrum.values.toList(), key = { it.presetId }) { reading ->
            val check = reading.comparedWith(space.spectrumBaselines[reading.presetId])
            Heading(requireNotNull(SpectrumPreset.byId(check.presetId)).title)
            Text("Median ${deciDbm(check.medianDeciDbm)} dBm · peak ${deciDbm(check.peakDeciDbm)} dBm at ${formatMhz(check.peakHz)} MHz · Δ ${deciDbm(check.prominenceDeciDb)} dB")
            if (check.baselinePeakDiffDeciDb != null && check.baselineProminenceDiffDeciDb != null) {
                Text("Change from baseline · peak ${deciDbm(check.baselinePeakDiffDeciDb)} dB · prominence ${deciDbm(check.baselineProminenceDiffDeciDb)} dB")
                if (check.spike()) Text(SpaceWarning.SPECTRUM_SPIKE.message)
            } else Text("Earlier band baseline unavailable.")
            TextButton({ onRemoveSpectrum(check.presetId) }, enabled = !busy) { Text("Remove band check") }
        }
        item { Heading("Guided physical checklist") }
        items(template.size) { index ->
            val item = template[index]
            val status = ChecklistStatus.valueOf(statuses[index])
            var choosing by remember { mutableStateOf(false) }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Heading(item.title)
                    Text(item.guidance)
                    val reading = thermal[item.id]
                    if (reading != null) {
                        Text("Measured ΔT ${deciC(reading.deltaDeciC)} °C")
                        reading.changeFrom(space.thermalBaselines[item.id])?.let { difference ->
                            Text("Change from baseline ${deciC(difference)} °C")
                            if (difference >= THERMAL_WARNING_DECI_C) Text(SpaceWarning.THERMAL_DELTA.message)
                        }
                        TextButton({ onRemoveThermal(item.id) }, enabled = !busy) { Text("Remove thermal check") }
                    }
                    OutlinedButton({ onThermal(item) }, enabled = !busy) {
                        Text(if (reading == null) "Measure USB thermal check" else "Measure again")
                    }
                    Box {
                        OutlinedButton({ choosing = true }) { Text(status.label) }
                        DropdownMenu(choosing, { choosing = false }) {
                            ChecklistStatus.entries.filterNot { it == ChecklistStatus.UNCHECKED }.forEach { choice ->
                                DropdownMenuItem(text = { Text(choice.label) }, onClick = {
                                    statuses = ArrayList(statuses).apply { set(index, choice.name) }
                                    if (choice != ChecklistStatus.NEEDS_FOLLOW_UP) notes = ArrayList(notes).apply { set(index, "") }
                                    choosing = false
                                })
                            }
                        }
                    }
                    if (status == ChecklistStatus.NEEDS_FOLLOW_UP) OutlinedTextField(
                        notes[index], { notes = ArrayList(notes).apply { set(index, it.take(SpaceLimits.NOTE)) } },
                        label = { Text("Follow-up note (optional)") }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        item {
            val completed = statuses.count { it != ChecklistStatus.UNCHECKED.name }
            Text("$completed of ${template.size} outcomes recorded")
            Button(onClick = {
                onSave(template.mapIndexed { index, item -> item.copy(status = ChecklistStatus.valueOf(statuses[index]),
                    note = notes[index], thermal = thermal[item.id]) })
            }, enabled = !busy && completed == template.size) { Text("Save timestamped inspection") }
        }
    }
}

@Composable
private fun ArchivedLog(
    log: SpaceInspection, busy: Boolean, export: (SpaceExportFormat, Boolean, Boolean) -> Unit,
) {
    var identifiers by rememberSaveable { mutableStateOf(false) }
    var coordinates by rememberSaveable { mutableStateOf(false) }
    val original = remember(log) { SpaceReports.report(log) }
    if (!SpaceReports.integrityValid(log, original)) {
        Text("This inspection differs from its recorded SHA-256 digest. Sharing is unavailable.", Modifier.padding(18.dp))
        return
    }
    val report = remember(log, identifiers, coordinates) {
        if (identifiers || coordinates) SpaceReports.report(log, identifiers, coordinates) else original
    }
    val clipboard = LocalClipboardManager.current
    SpaceList {
        item {
            Text(report.payload.title, style = MaterialTheme.typography.headlineMedium)
            Text(date(log.completedAt))
            Text(SpaceReports.DISCLAIMER)
        }
        item {
            Heading("Export inspection")
            Toggle("Include identifiers, radio and USB device details, and notes", identifiers) { identifiers = it }
            Toggle("Include coordinates", coordinates) { coordinates = it }
            Text("SHA-256 of this export payload", style = MaterialTheme.typography.labelLarge)
            Text(report.sha256, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            TextButton({ clipboard.setText(AnnotatedString(report.sha256)) }) { Text("Copy SHA-256") }
            Button({ export(SpaceExportFormat.PDF, identifiers, coordinates) }, enabled = !busy) { Text("Share inspection PDF") }
            Row {
                TextButton({ export(SpaceExportFormat.TEXT, identifiers, coordinates) }, enabled = !busy) { Text("Share text") }
                TextButton({ export(SpaceExportFormat.JSON, identifiers, coordinates) }, enabled = !busy) { Text("Share JSON") }
            }
        }
        items(report.payload.sections) { section ->
            Heading(section.heading)
            section.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = change)
        .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, null)
        Text(label, Modifier.padding(start = 10.dp))
    }
}
