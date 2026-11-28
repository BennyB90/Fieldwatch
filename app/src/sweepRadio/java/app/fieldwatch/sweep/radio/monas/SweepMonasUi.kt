// SPDX-License-Identifier: MIT
package app.fieldwatch.sweep.radio.monas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs

/** The text field retains only bullets. Apply each keyboard edit to the wipeable buffer. */
internal class MonasTokenDraft {
    private var chars = charArrayOf()
    var masked by mutableStateOf(TextFieldValue())
        private set
    var invalid by mutableStateOf(false)
        private set

    fun edit(next: TextFieldValue) {
        if (next.text.length > 256) { invalid = true; return }
        val nextSel = TextRange(next.selection.min.coerceIn(0, next.text.length), next.selection.max.coerceIn(0, next.text.length))
        if (next.text == masked.text) {
            masked = masked.copy(selection = nextSel)
            return
        }
        val curSel = TextRange(masked.selection.min.coerceIn(0, masked.text.length), masked.selection.max.coerceIn(0, masked.text.length))
        val prefix = minOf(curSel.min, nextSel.min, masked.text.commonPrefixWith(next.text).length)
        val suffix = minOf(chars.size - prefix, next.text.length - nextSel.max,
            masked.text.commonSuffixWith(next.text).length).coerceAtLeast(0)
        val updated = CharArray(next.text.length)
        chars.copyInto(updated, endIndex = prefix)
        next.text.toCharArray(updated, prefix, prefix, next.text.length - suffix)
        chars.copyInto(updated, updated.size - suffix, chars.size - suffix)
        if (!validToken(updated)) { updated.fill('\u0000'); invalid = true; return }
        chars.fill('\u0000')
        chars = updated
        invalid = false
        masked = TextFieldValue("•".repeat(chars.size), nextSel)
    }

    fun take(): CharArray {
        val token = chars
        chars = charArrayOf()
        masked = TextFieldValue("", TextRange.Zero)
        invalid = false
        return token
    }

    fun clear() { take().fill('\u0000') }
}

@Composable
internal fun SweepMonasUi(
    state: MonasScreenState, consent: Boolean, loading: Boolean, server: String, pin: String, draft: MonasTokenDraft,
    onBack: () -> Unit, onServer: (String) -> Unit, onPin: (String) -> Unit, onConsent: (Boolean) -> Unit,
    onConnect: () -> Unit, onDisconnect: () -> Unit, onRefresh: () -> Unit, onAuto: (Boolean) -> Unit,
    onDemo: () -> Unit, onShare: (MonasSnapshot, Boolean) -> Unit,
) {
    val snapshot = state.snapshot
    var first by remember(snapshot, state.revision) { mutableStateOf<Int?>(null) }
    var second by remember(snapshot, state.revision) { mutableStateOf<Int?>(null) }
    var sharing by remember(snapshot, state.revision) { mutableStateOf(false) }
    val pick: (Int) -> Unit = { index ->
        if (index in snapshot?.census?.devices.orEmpty().indices) {
            onAuto(false)
            if (first == null || second != null || first == index) {
                first = index
                second = null
            } else second = index
        }
    }
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onBack) { Text("Back") }
                    Text("Monas SDR", style = MaterialTheme.typography.headlineMedium)
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Receiver connection", style = MaterialTheme.typography.titleLarge)
                        if (!state.connected) {
                            Text("Your receiver sees this phone’s IP address and the optional bearer token.")
                            Toggle("Allow Radio network access", consent, !loading, onConsent)
                            OutlinedTextField(server, onServer, Modifier.fillMaxWidth(), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                label = { Text("HTTPS server URL") })
                            OutlinedTextField(draft.masked, draft::edit, Modifier.fillMaxWidth(), singleLine = true,
                                isError = draft.invalid, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                                label = { Text("Bearer token (optional)") })
                            if (draft.invalid) Text("Use up to 256 printable ASCII characters.")
                            OutlinedTextField(pin, onPin, Modifier.fillMaxWidth(), singleLine = true,
                                label = { Text("SHA-256 SPKI pin (optional)") })
                            Button(onConnect, enabled = consent && !loading && server.isNotBlank() && !draft.invalid) {
                                Text("Authorize this server and connect")
                            }
                            if (snapshot?.sample != true) OutlinedButton(onDemo) { Text("Preview synthetic census") }
                            else TextButton(onDisconnect) { Text("Close sample") }
                        } else {
                            Text(server)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onRefresh, enabled = !state.busy) { Text(if (state.busy) "Reading…" else "Refresh") }
                                OutlinedButton(onDisconnect) { Text("Disconnect") }
                            }
                            Toggle("Auto-poll (2 s)", state.auto, snapshot?.continuityIssue == null, onAuto)
                            Toggle("Allow Radio network access", consent, !loading, onConsent)
                        }
                    }
                }
            }
            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (snapshot != null) {
                item {
                    val badge = when { snapshot.sample -> MonasDemo.TITLE; snapshot.stale -> "[STALE]"; else -> "[LIVE] at last refresh" }
                    Text(badge, style = MaterialTheme.typography.titleMedium)
                    Text("Clock · ${snapshot.provenance.tier}" + (snapshot.provenance.referenceAgeSec?.let { " · $it s old" } ?: ""))
                    Text((if (snapshot.feedLoss) "[FEED LOSS] · " else "") + "lost_samples = ${snapshot.health.lostSamples ?: "unknown"}")
                    if (snapshot.masked) Text("addresses = masked")
                    MonasdAnalyzer.unavailable(snapshot)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text("Census · ${snapshot.census.devices.size} of ${snapshot.census.total} address rows")
                    state.counts?.let { Text("${it.rotatingRows} rotating rows · ${it.rotatingDistinct} rotating pairs ≥5σ · ${it.rotatingOverlapping} rotating pairs <2σ") }
                    Text("Packet page · ${snapshot.ble.packets.size} of ${snapshot.ble.total} · ${snapshot.ble.packets.count { it.crcOk }} passed CRC")
                }
                item {
                    Text("Crystal offset × modulation index", style = MaterialTheme.typography.titleMedium)
                    CrystalPlot(snapshot, first, second, pick)
                }
                item {
                    Text("Pairwise σ separability", style = MaterialTheme.typography.titleMedium)
                    val firstRow = first?.let { snapshot.census.devices.getOrNull(it) }
                    val secondRow = second?.let { snapshot.census.devices.getOrNull(it) }
                    when {
                        firstRow == null -> Text("Tap two points or address rows from this snapshot.")
                        secondRow == null -> Text("Selected ${firstRow.address}. Tap a second row.")
                        else -> {
                            Text("${firstRow.address}  /  ${secondRow.address}")
                            val result = MonasdAnalyzer.pair(snapshot, first!!, second!!)
                            if (result == null) Text(MonasdAnalyzer.unavailable(snapshot)
                                ?: "This pair needs distinct rows, overlapping observation times and positive CFO uncertainties.")
                            else {
                                Text("${result.verdict.label} · CFO ${fmt(result.zCfo)}σ", style = MaterialTheme.typography.titleSmall)
                                result.zCombined?.let { Text("Combined CFO and modulation distance · ${fmt(it)}") }
                                Text(result.explanation)
                            }
                        }
                    }
                    OutlinedButton({ onAuto(false); sharing = true }, enabled = !state.busy) { Text("Share summary") }
                }
                itemsIndexed(snapshot.census.devices) { i, d ->
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { pick(i) }.padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text((if (i == first || i == second) "✓ " else "") + "${d.kind} · ${d.address}")
                        d.crystalOffsetPpm?.let { Text("${it.value} ± ${it.sigma} ppm") }
                        d.modulationIndex?.let { Text("h = ${it.value} ± ${it.sigma}") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    if (sharing && snapshot != null) {
        var identifiers by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { sharing = false }, title = { Text("Share summary") },
            text = { Column {
                Text(if (snapshot.sample) MonasDemo.TITLE else "The default report shares aggregate observations.")
                Toggle("Include identifiers & numerical crystal offsets", identifiers, true) { identifiers = it }
            } },
            confirmButton = { TextButton({ sharing = false; onShare(snapshot, identifiers) }) { Text("Share") } },
            dismissButton = { TextButton({ sharing = false }) { Text("Cancel") } })
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, change, enabled = enabled, modifier = Modifier.semantics { contentDescription = label })
    }
}

private fun fmt(v: Double) = String.format(Locale.ROOT, "%.4g", v)

private data class PlotPoint(val index: Int, val x: MonasSigma, val y: MonasSigma, val rotating: Boolean)
private data class Axis(val low: Double, val high: Double) {
    fun fraction(v: Double) = if (low == high) 0.5f else ((v - low) / (high - low)).toFloat().coerceIn(0f, 1f)
}

@Composable
private fun CrystalPlot(s: MonasSnapshot, first: Int?, second: Int?, pick: (Int) -> Unit) {
    val points = remember(s) {
        s.census.devices.mapIndexedNotNull { i, d ->
            val x = d.crystalOffsetPpm ?: return@mapIndexedNotNull null
            val y = d.modulationIndex ?: return@mapIndexedNotNull null
            if (listOf(x, y).any { !it.value.isFinite() || !it.sigma.isFinite() || it.sigma < 0 ||
                    abs(it.value) > Double.MAX_VALUE / 8 || it.sigma > Double.MAX_VALUE / 8 }) null
            else PlotPoint(i, x, y, d.rotating)
        }
    }
    if (points.isEmpty()) { Text("No plottable two-dimensional readings."); return }
    val x = Axis(points.minOf { it.x.value - 2 * it.x.sigma }, points.maxOf { it.x.value + 2 * it.x.sigma })
    val y = Axis(points.minOf { it.y.value - 2 * it.y.sigma }, points.maxOf { it.y.value + 2 * it.y.sigma })
    val ink = MaterialTheme.colorScheme.primary
    val selected = MaterialTheme.colorScheme.tertiary
    val background = MaterialTheme.colorScheme.surfaceVariant
    val latestPick by rememberUpdatedState(pick)
    Text("Modulation index (h) · ${fmt(y.high)}")
    Canvas(Modifier.fillMaxWidth().height(180.dp).background(background).padding(10.dp)
        .semantics { contentDescription = "Crystal offsets and modulation indices with two-sigma error bars. Selectable rows follow." }
        .pointerInput(points) {
            detectTapGestures { tap ->
                fun distance(p: PlotPoint) = (Offset(x.fraction(p.x.value) * size.width, (1f - y.fraction(p.y.value)) * size.height) - tap).getDistance()
                points.minByOrNull(::distance)?.takeIf { distance(it) <= 24.dp.toPx() }?.let { latestPick(it.index) }
            }
        }) {
        fun xy(a: Double, b: Double) = Offset(x.fraction(a) * size.width, (1f - y.fraction(b)) * size.height)
        for (p in points) {
            val center = xy(p.x.value, p.y.value)
            val color = if (p.index == first || p.index == second) selected else ink
            drawLine(color, xy(p.x.value - 2 * p.x.sigma, p.y.value), xy(p.x.value + 2 * p.x.sigma, p.y.value), 1.dp.toPx())
            drawLine(color, xy(p.x.value, p.y.value - 2 * p.y.sigma), xy(p.x.value, p.y.value + 2 * p.y.sigma), 1.dp.toPx())
            if (p.rotating) {
                drawCircle(background, 4.dp.toPx(), center)
                drawCircle(color, 4.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
            } else drawCircle(color, 4.dp.toPx(), center)
            if (p.index == first || p.index == second) drawCircle(selected, 7.dp.toPx(), center, style = Stroke(1.dp.toPx()))
        }
    }
    Text("${fmt(y.low)} h")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(fmt(x.low)); Text("Crystal offset (ppm)"); Text(fmt(x.high))
    }
    Text("±2σ bars · solid = fixed · ring = rotating", style = MaterialTheme.typography.bodySmall)
}
