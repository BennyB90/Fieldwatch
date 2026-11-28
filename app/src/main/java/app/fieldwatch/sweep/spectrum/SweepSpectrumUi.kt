package app.fieldwatch.sweep.spectrum

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fieldwatch.sweep.SweepAmber
import app.fieldwatch.sweep.SweepCream
import app.fieldwatch.sweep.SweepInk
import app.fieldwatch.sweep.SweepTeal
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun SweepSpectrumUi(usb: TinySaUsbController, target: SpectrumPreset?, back: () -> Unit, attach: (SpectrumSweep) -> Unit) {
    val state by usb.state.collectAsStateWithLifecycle()
    var preset by rememberSaveable { mutableStateOf(target ?: SpectrumPreset.REMOTES) }
    var threshold by rememberSaveable { mutableIntStateOf(120) }
    var preview by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<TinySaDevice?>(null) }
    val model = state.model
    val supported = model != null && preset.stopHz <= model.maxHz
    val sweep = if (preview) remember(preset) { SpectrumDemo.sweep(preset) } else state.sweep?.takeIf { it.presetId == preset.id }
    val text = MaterialTheme.typography
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing, topBar = {
        TopAppBar(title = { Text("Wideband spectrum") }, navigationIcon = {
            IconButton(back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
        }, windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState())
            .padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("USB analyzer", style = text.titleLarge)
                    Text(state.status)
                    if (state.selected == null) {
                        OutlinedButton({ usb.discover() }) { Text("Discover USB devices") }
                        state.devices.forEach { device ->
                            TextButton({
                                preview = false
                                if (device.primary) usb.connect(device.id) else confirm = device
                            }) { Text(if (device.primary) "${device.label} · tinySA / STM32 CDC" else "${device.label} · other CDC adapter") }
                        }
                    } else {
                        Text(state.selected!!.label, style = text.bodySmall)
                        if (state.ready && state.detected == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TinySaModel.entries.forEach { choice ->
                                FilterChip(model == choice, { usb.chooseModel(choice) }, { Text(choice.label) }, enabled = !state.scanning)
                            }
                        }
                        OutlinedButton({ usb.disconnect() }) { Text("Disconnect USB") }
                    }
                }
            }
            Text(if (target == null) "Sweep band" else "Measure ${target.title}", style = text.titleLarge)
            if (target == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SpectrumPreset.entries.forEach { choice ->
                    FilterChip(preset == choice, { preset = choice }, { Text(choice.title) },
                        enabled = !state.scanning && (preview || model == null || choice.stopHz <= model.maxHz))
                }
            }
            Text("${formatMhz(preset.startHz)}–${formatMhz(preset.stopHz)} MHz · ${preset.defaultPoints} points")
            Text(preset.guidance)
            if (preset.ultraOnly && model == TinySaModel.TINYSA_BASIC) Text("This band requires tinySA Ultra.", color = SweepAmber)
            if (preset.stopHz > 800_000_000L && model == TinySaModel.TINYSA_ULTRA) {
                Text("Above 800 MHz on tinySA Ultra, ensure CONFIG → MORE → ENABLE ULTRA is enabled on the analyzer.", style = text.bodySmall)
            }
            Text("Highlight peaks ≥ +${deciDbm(threshold)} dB above the median", style = text.titleSmall)
            Slider(threshold.toFloat(), { threshold = it.roundToInt() }, valueRange = 60f..240f,
                modifier = Modifier.semantics { contentDescription = "Peak prominence threshold in tenths of a decibel" })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ preview = false; usb.scan(preset, false, threshold) }, enabled = supported && !state.scanning) { Text("Single sweep") }
                if (state.continuous) OutlinedButton(usb::finishSweep) { Text("Finish current sweep") }
                else OutlinedButton({ preview = false; usb.scan(preset, true, threshold) }, enabled = supported && !state.scanning) {
                    Text("Continuous sweeps")
                }
            }
            if (sweep != null) {
                Text(if (sweep.demo) "SAMPLE · SYNTHETIC SPECTRUM" else "USB measurement · ${sweep.model.label}", style = text.labelLarge)
                SpectrumTrace(sweep, threshold)
                if (target != null && !sweep.demo) {
                    val attachable = sweep.canAttachToSpace()
                    Button({ attach(sweep) }, enabled = !state.scanning && attachable) { Text("Use measured sweep") }
                    if (!attachable) {
                        Text("Measured level is outside the Saved Space range (-174.0 to +40.0 dBm). Check analyzer external gain/attenuation settings.",
                            style = text.bodySmall, color = SweepAmber)
                    }
                }
            }
            TextButton({ preview = !preview }, enabled = !state.scanning) {
                Text(if (preview) "Close synthetic preview" else "SAMPLE · SYNTHETIC SPECTRUM")
            }
            Text("A peak measures received energy. Keyfobs, smart meters, phones, routers and appliances can all produce spikes. Inspect physically to identify a source.",
                style = text.bodyMedium)
            Text("Physical USB-CDC reception on tinySA and tinySA Ultra awaits bench verification.", style = text.bodySmall)
        }
    }
    confirm?.let { device ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Connect to ${device.label}?") },
            text = { Text("This serial interface has a different USB ID. Confirm it belongs to your tinySA before sending analyzer commands.") },
            confirmButton = { TextButton({ confirm = null; preview = false; usb.connect(device.id) }) { Text("Connect analyzer") } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } })
    }
}

@Composable
private fun SpectrumTrace(sweep: SpectrumSweep, threshold: Int) {
    val samples = sweep.samplesDeciDbm
    var marker by remember(sweep.presetId, sweep.demo) { mutableIntStateOf(-1) }
    val clusters = remember(sweep, threshold) {
        TinySaProtocol.clusters(samples, sweep.startHz, sweep.stopHz, sweep.medianDeciDbm, threshold)
    }
    val low = samples.min() - 20
    val high = maxOf(sweep.peakDeciDbm, sweep.medianDeciDbm + threshold) + 20
    Text("Peak ${deciDbm(sweep.peakDeciDbm)} dBm at ${formatMhz(sweep.peakHz)} MHz · +${deciDbm(sweep.prominenceDeciDb)} dB above median",
        style = MaterialTheme.typography.titleMedium)
    Canvas(Modifier.fillMaxWidth().height(172.dp).background(SweepCream)
        .semantics { contentDescription = "Spectrum trace with ${clusters.size} highlighted peak regions" }
        .pointerInput(samples.size) {
            detectTapGestures { marker = (it.x / size.width * samples.lastIndex).roundToInt().coerceIn(samples.indices) }
        }) {
        fun x(i: Int) = size.width * i / samples.lastIndex
        fun y(level: Int) = size.height * (high - level) / (high - low)
        fun bandX(hz: Long) = (size.width * ((hz - sweep.startHz).toDouble() / (sweep.stopHz - sweep.startHz))).toFloat()
        val step = size.width / samples.lastIndex
        for (cluster in clusters) {
            val left = (bandX(cluster.startHz) - step / 2).coerceAtLeast(0f)
            val right = (bandX(cluster.endHz) + step / 2).coerceAtMost(size.width)
            drawRect(SweepAmber.copy(alpha = 0.2f), Offset(left, 0f), Size(right - left, size.height))
        }
        fun line(level: Int, color: androidx.compose.ui.graphics.Color) =
            drawLine(color, Offset(0f, y(level)), Offset(size.width, y(level)), 1.dp.toPx())
        line(sweep.medianDeciDbm, SweepInk)
        line(sweep.medianDeciDbm + threshold, SweepAmber)
        for (i in 1..samples.lastIndex) drawLine(SweepTeal, Offset(x(i - 1), y(samples[i - 1])), Offset(x(i), y(samples[i])), 1.5.dp.toPx())
        if (marker in samples.indices) {
            drawLine(SweepInk, Offset(x(marker), 0f), Offset(x(marker), size.height))
            drawCircle(SweepInk, 4.dp.toPx(), Offset(x(marker), y(samples[marker])))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${formatMhz(sweep.startHz)} MHz")
        Text("${formatMhz(sweep.stopHz)} MHz")
    }
    Text("Median ${deciDbm(sweep.medianDeciDbm)} dBm · threshold ${deciDbm(sweep.medianDeciDbm + threshold)} dBm · ${clusters.size} highlighted regions",
        style = MaterialTheme.typography.bodySmall)
    if (marker in samples.indices) {
        val hz = frequencyAt(sweep.startHz, sweep.stopHz, marker, samples.size)
        Text("Marker ${formatMhz(hz)} MHz · ${deciDbm(samples[marker])} dBm · Δ ${deciDbm(samples[marker] - sweep.medianDeciDbm)} dB")
    } else Text("Tap the trace to inspect a frequency.", style = MaterialTheme.typography.bodySmall)
}
