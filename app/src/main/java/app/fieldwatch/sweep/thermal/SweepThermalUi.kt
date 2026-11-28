package app.fieldwatch.sweep.thermal

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private data class Display(val frame: ThermalFrame, val capture: ThermalCapture?, val image: ImageBitmap, val rendering: ThermalRendering)
private fun degrees(value: Float) = String.format(Locale.getDefault(), "%.1f °C", value)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SweepThermalUi(usb: ThermalUsbController, item: String?, back: () -> Unit,
    onConnect: (Int) -> Unit, onAppSettings: () -> Unit,
    attach: (ThermalFrame, ThermalCapture) -> Boolean) {
    val state by usb.state.collectAsStateWithLifecycle()
    var demo by rememberSaveable { mutableStateOf(false) }
    var palette by rememberSaveable { mutableStateOf(ThermalPalette.DELTA_SPOTLIGHT) }
    var threshold by rememberSaveable { mutableFloatStateOf(ThermalSpotlight.DEFAULT_THRESHOLD) }
    var rotation by rememberSaveable { mutableIntStateOf(0) }
    var spotX by rememberSaveable(rotation, demo) { mutableIntStateOf(-1) }
    var spotY by rememberSaveable(rotation, demo) { mutableIntStateOf(-1) }
    var target by remember { mutableStateOf<ThermalDevice?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var display by remember(demo, rotation, palette, threshold) { mutableStateOf<Display?>(null) }
    LaunchedEffect(demo, rotation, palette, threshold, usb) {
        val source = if (demo) kotlinx.coroutines.flow.flowOf(null) else usb.state.map { it.capture }.distinctUntilChanged()
        source.collect { capture ->
            display = withContext(Dispatchers.Default) {
                val frame = if (demo) ThermalDemo.frame(rotation) else capture?.let {
                    ThermalFrameParser.parse(it.bytes, it.capturedAt, rotation)
                }
                frame?.let {
                    val rendering = ThermalSpotlight.render(it, palette, threshold)
                    val bitmap = Bitmap.createBitmap(rendering.argb, it.width, it.height, Bitmap.Config.ARGB_8888)
                    Display(it, capture, bitmap.asImageBitmap(), rendering)
                }
            }
        }
    }
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing, topBar = {
        TopAppBar(title = { Text("Thermal inspection") }, navigationIcon = {
            IconButton(back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
        }, windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
            if (demo) Text(ThermalDemo.LABEL, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            else Text(state.status)
            if (!demo && (state.status == ThermalUsbController.CAMERA_REQUIRED || state.status == ThermalUsbController.DENIED)) {
                OutlinedButton(onAppSettings) { Text("Open app settings") }
            }
            val shown = display?.takeIf { demo || (it.capture != null && it.capture.generation == state.capture?.generation) }
            if (shown != null) {
                val f = shown.frame
                Text(if (f.radiometric) "Radiometric · tap to inspect a point" else "Luminance only · temperatures unavailable")
                Box(Modifier.fillMaxWidth().aspectRatio(f.width.toFloat() / f.height)
                    .pointerInput(f.width, f.height) {
                        detectTapGestures { at ->
                            spotX = (at.x * f.width / size.width).toInt().coerceIn(0, f.width - 1)
                            spotY = (at.y * f.height / size.height).toInt().coerceIn(0, f.height - 1)
                        }
                    }) {
                    Image(shown.image, if (f.demo) ThermalDemo.LABEL else "USB thermal frame",
                        Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    if (spotX >= 0 && spotY >= 0) Canvas(Modifier.fillMaxSize()) {
                        val center = Offset((spotX + .5f) * size.width / f.width, (spotY + .5f) * size.height / f.height)
                        drawCircle(Color.Black, 5.dp.toPx(), center)
                        drawCircle(Color.White, 3.dp.toPx(), center)
                    }
                }
                if (f.ambientMedianC != null && f.minC != null && f.maxC != null) {
                    Text("Frame median ${degrees(f.ambientMedianC)} · min ${degrees(f.minC)} · peak ${degrees(f.maxC)}")
                    Text("Peak above median  ${degrees(requireNotNull(f.maxDeltaC))}", style = MaterialTheme.typography.titleMedium)
                    if (spotX >= 0) Text(f.spotC(spotX, spotY)?.let {
                        "Point ${degrees(it)} · ΔT ${degrees(requireNotNull(f.spotDeltaC(spotX, spotY)))}"
                    } ?: "No valid temperature at this point.")
                    if (palette == ThermalPalette.DELTA_SPOTLIGHT) Text("Compact warm regions ${shown.rendering.compactClusters} · broad surfaces ${shown.rendering.broadSurfaces}")
                } else if (f.radiometric) Text("No valid temperature samples in this frame.")
                if (item != null && !demo && shown.capture != null && f.ambientMedianC != null) {
                    Button({ if (!attach(f, shown.capture)) error = "Wait for a fresh live measurement." }) { Text("Attach this frame’s readings") }
                }
            }
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
            if (demo || state.selected != null) {
                var menu by remember { mutableStateOf(false) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        OutlinedButton({ menu = true }) { Text(palette.label) }
                        DropdownMenu(menu, { menu = false }) { ThermalPalette.entries.forEach {
                            DropdownMenuItem({ Text(it.label) }, { palette = it; menu = false })
                        } }
                    }
                    TextButton({ rotation = (rotation + 90) % 360 }) { Text("Rotate · $rotation°") }
                }
                if (palette == ThermalPalette.DELTA_SPOTLIGHT && (demo || shown?.frame?.radiometric == true)) {
                    Text("Highlight above median +${degrees(threshold)}")
                    Slider(threshold, { threshold = it }, valueRange = ThermalSpotlight.MIN_THRESHOLD..ThermalSpotlight.MAX_THRESHOLD, steps = 12)
                }
                if (!demo) Text("Calibration frames skipped ${state.skipped} · dropped ${state.dropped} (corrupt ${state.corrupt})")
                OutlinedButton({ demo = false; usb.disconnect(); error = null }) { Text(if (demo) "Close sample" else "Disconnect") }
            } else {
                Button({ usb.discover(); error = null }) { Text("Discover USB accessories") }
                state.devices.forEach { device -> OutlinedButton({ target = device }) { Text("Connect · ${device.label}") } }
                TextButton({ error = null; demo = true }) { Text("View synthetic sample") }
            }
            Text("Aim at one checklist item. The frame median estimates scene ambient. Charging, sunlight and ordinary operation can produce warm surfaces. Inspect them physically.")
            Text("Thermal uses USB only. Room and Travel scans can continue. Leaving Network ends live discovery.")
            Text("Physical USB-C reception on the ordered Topdon TC001 Max awaits hardware testing.", style = MaterialTheme.typography.bodySmall)
        }
    }
    target?.let { device -> AlertDialog(onDismissRequest = { target = null }, title = { Text("Use Tiny1-C / P2 profile?") },
        text = { Text("Choose this profile only for a compatible thermal accessory. Android requires Camera permission before granting USB access to USB Video Class (UVC) thermal accessories; SWEEP uses USB only and never opens the phone's built-in cameras.") },
        confirmButton = { TextButton({ val id = device.id; target = null; error = null; onConnect(id) }) { Text("Connect") } },
        dismissButton = { TextButton({ target = null }) { Text("Cancel") } }) }
}
