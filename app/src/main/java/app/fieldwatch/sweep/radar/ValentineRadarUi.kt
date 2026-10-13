package app.fieldwatch.sweep.radar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fieldwatch.sweep.SweepAmber
import app.fieldwatch.sweep.SweepCream
import app.fieldwatch.sweep.SweepInk
import app.fieldwatch.sweep.SweepLine
import app.fieldwatch.sweep.SweepMuted
import app.fieldwatch.sweep.SweepPale
import app.fieldwatch.sweep.SweepTeal
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ValentineRadarScreen(
    state: ValentineRadarState,
    phoneStatus: String,
    phoneSupported: Boolean,
    blockedBySweep: Boolean,
    actionError: String?,
    onBack: () -> Unit,
    onDiscover: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onSettings: () -> Unit,
) {
    var sample by rememberSaveable { mutableStateOf(false) }
    var info by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    LaunchedEffect(state.status) {
        if (state.status !in listOf(RadarConnectionStatus.DISCONNECTED, RadarConnectionStatus.ERROR)) sample = false
    }
    val connected = state.status == RadarConnectionStatus.CONNECTED
    val busy = state.status in listOf(RadarConnectionStatus.DISCOVERING, RadarConnectionStatus.CONNECTING, RadarConnectionStatus.SUBSCRIBING)
    val canDiscover = phoneSupported && !blockedBySweep && !connected && !busy
    val canConnect = phoneSupported && !blockedBySweep && !connected && state.status !in listOf(RadarConnectionStatus.CONNECTING, RadarConnectionStatus.SUBSCRIBING)
    val display = if (sample) sampleDisplay else state.display.takeIf { connected }
    val alerts = if (sample) sampleAlerts else state.alerts.takeIf { connected }.orEmpty()
    BackHandler { onBack() }

    Scaffold(
        containerColor = SweepCream,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.ArrowBack, "Back to SWEEP") }
                Text("Valentine", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { info = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Info, "Accessory compatibility and privacy") }
            }
        },
    ) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item(key = "radar_intro") {
                Column(Modifier.fillMaxWidth().background(SweepInk, RoundedCornerShape(25.dp)).padding(23.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.BluetoothConnected, null, tint = Color(0xFFB5EACD), modifier = Modifier.size(20.dp))
                        Text("EXTERNAL DETECTOR", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB5EACD))
                    }
                    Text("Your detector.\nA clearer view.", style = MaterialTheme.typography.headlineLarge, color = Color.White)
                    Text("Read the alerts reported by your compatible Valentine One detector over its supported Bluetooth connection.", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFC4D6CF))
                    Text("Radar detection comes from the external hardware. This phone displays the data it receives.", style = MaterialTheme.typography.bodySmall, color = Color(0xFFC4D6CF))
                }
            }
            if (sample) item(key = "radar_sample_banner") {
                RadarNotice("SAMPLE · SYNTHETIC ALERTS", "These example values illustrate the display. No detector connection or radar detection is demonstrated.", Color(0xFFF1E8D3))
            }
            if (blockedBySweep) item(key = "radar_scan_gate") {
                RadarNotice("RF scan in progress", "End the RF scan before connecting a radar accessory.", Color(0xFFF1E8D3))
            }
            item(key = "radar_status") {
                ConnectionCard(state, phoneStatus, now)
            }
            (actionError ?: state.error)?.let { message -> item(key = "radar_error") {
                RadarNotice(if (connected) "Accessory note" else "Connection needs attention", message, MaterialTheme.colorScheme.errorContainer)
            } }
            item(key = "radar_controls") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { sample = false; onDiscover() }, enabled = canDiscover, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(15.dp)) {
                        if (state.status == RadarConnectionStatus.DISCOVERING) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.BluetoothSearching, null, Modifier.size(19.dp))
                        Spacer(Modifier.width(8.dp)); Text(if (state.status == RadarConnectionStatus.DISCOVERING) "Discovering…" else "Discover")
                    }
                    if (connected || busy) OutlinedButton(onClick = { sample = false; onDisconnect() }, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(15.dp)) {
                        Text(if (state.status == RadarConnectionStatus.DISCOVERING) "Stop discovery" else "Disconnect")
                    }
                    else OutlinedButton(onClick = onSettings, modifier = Modifier.heightIn(min = 52.dp), shape = RoundedCornerShape(15.dp)) { Text("Settings") }
                }
            }
            if (state.devices.isNotEmpty()) item(key = "radar_device_heading") { Text("Discovered accessories", style = MaterialTheme.typography.titleLarge) }
            items(state.devices, key = { "accessory_${it.id}" }) { device ->
                AccessoryCard(device, state, canConnect) { sample = false; onConnect(device.id) }
            }
            if (state.devices.isEmpty() && !connected && !busy && !sample) item(key = "radar_device_empty") {
                RadarNotice("Ready when your hardware is", "Power on a compatible Valentine detector and its Bluetooth connection, then tap Discover. Discovering an accessory does not connect to it.")
            }
            if (display != null) item(key = "radar_display") { DetectorDisplay(display, now, sample) }
            if (alerts.isNotEmpty()) {
                item(key = "radar_alert_heading") { Text(if (sample) "Sample radar signals" else "Reported radar signals", style = MaterialTheme.typography.titleLarge) }
                items(alerts, key = { "radar_alert_${it.index}" }) { alert -> AlertCard(alert, now, sample) }
            } else if (connected) item(key = "radar_waiting") {
                RadarNotice(if (display == null) "Connected · waiting for detector data" else "No detailed radar signals reported", "A Bluetooth connection is separate from a radar alert. A quiet detector display cannot establish that the road is free of radar or other hazards.")
            }
            if (!connected && !busy) item(key = "radar_sample_button") {
                OutlinedButton(onClick = { sample = !sample }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(15.dp)) {
                    Icon(if (sample) Icons.Outlined.Close else Icons.Outlined.Science, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text(if (sample) "Exit sample" else "View sample alerts")
                }
            }
            item(key = "radar_permission_note") {
                RadarNotice("Bluetooth permissions", "Discovery in this build requires location access and location services. Android 12 and later also require nearby-device access. Permission is requested when you choose Discover or Connect; this screen does not capture your phone’s GPS position.")
            }
            item(key = "radar_footer") {
                Text("Reported arrows refer to the detector’s front, side and rear. Strength is device data, not distance. Alert viewing ends when you leave this screen; returning requires your next connection action.", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
            }
        }
    }
    if (info) {
        val context = LocalContext.current
        val licenseText = remember(context) {
            runCatching { context.assets.open("licenses/valentine-mit.txt").bufferedReader().use { it.readText() } }
                .getOrDefault("The MITValentine license notice could not be loaded from this installation.")
        }
        AlertDialog(
        onDismissRequest = { info = false },
        title = { Text("An accessory display") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Text("This feature connects to supported Valentine Bluetooth hardware and reads manufacturer-protocol alert data. You choose discovery, a specific accessory, and disconnection.")
                Text("Bands, arrows, signal bars and frequencies come from the detector. Arrows indicate direction relative to its orientation; they are not GPS locations. Front/rear strength values are raw 0–255 readings and are not dBm or distance.")
                Text("The connection is scoped to this screen. No detector settings are changed. Hardware compatibility and live alert reception have not been tested on a physical detector.")
                Text("Valentine One is a trademark of Valentine Research. SWEEP is an independent integration.", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
                Text("Protocol reference: MITValentine", style = MaterialTheme.typography.titleSmall)
                Text("The MITValentine copyright and permission notice are reproduced below.", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
                Text(licenseText, style = MaterialTheme.typography.bodySmall, color = SweepMuted)
            }
        },
        confirmButton = { TextButton(onClick = { info = false }) { Text("Got it") } },
        )
    }
}

@Composable
private fun ConnectionCard(state: ValentineRadarState, phoneStatus: String, now: Long) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(19.dp)).border(1.dp, SweepLine, RoundedCornerShape(19.dp)).padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RadarValue("Phone Bluetooth", phoneStatus)
        HorizontalDivider(color = SweepLine)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(if (state.status == RadarConnectionStatus.CONNECTED) Icons.Outlined.BluetoothConnected else Icons.Outlined.Bluetooth, null, tint = SweepTeal, modifier = Modifier.size(23.dp))
            Column(Modifier.weight(1f)) {
                Text("Accessory connection", style = MaterialTheme.typography.labelMedium, color = SweepMuted)
                Text(state.status.label(), style = MaterialTheme.typography.titleMedium)
                state.selectedDevice?.let { Text(it.name, style = MaterialTheme.typography.bodySmall, color = SweepMuted) }
            }
        }
        state.lastPacketAt?.takeIf { state.status == RadarConnectionStatus.CONNECTED }?.let { received ->
            Text("Last accessory message: ${radarAge(now, received)} (as of now)", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
        }
    }
}

@Composable
private fun AccessoryCard(device: RadarAccessory, state: ValentineRadarState, canConnect: Boolean, onConnect: () -> Unit) {
    val selected = state.selectedDevice?.id == device.id
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(17.dp)).border(1.dp, if (selected) SweepTeal else SweepLine, RoundedCornerShape(17.dp)).padding(15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Outlined.Router, null, tint = SweepTeal, modifier = Modifier.size(25.dp))
        Column(Modifier.weight(1f)) {
            Text(device.name, style = MaterialTheme.typography.titleSmall)
            Text(device.rssi?.let { "Bluetooth signal $it dBm" } ?: "Supported accessory candidate", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
        }
        if (selected && state.status == RadarConnectionStatus.CONNECTED) Text("Connected", style = MaterialTheme.typography.labelMedium, color = SweepTeal)
        else TextButton(onClick = onConnect, enabled = canConnect, modifier = Modifier.heightIn(min = 48.dp)) { Text("Connect") }
    }
}

@Composable
private fun DetectorDisplay(display: RadarDisplay, now: Long, sample: Boolean) {
    Column(Modifier.fillMaxWidth().background(SweepInk, RoundedCornerShape(23.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(if (sample) "SAMPLE DETECTOR DISPLAY" else "RECEIVED DETECTOR DISPLAY", style = MaterialTheme.typography.labelSmall, color = Color(0xFFC4D6CF))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Text(display.bogeyText.ifBlank { "—" }, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 64.sp, color = Color(0xFFB5EACD))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (display.bogeyText.isBlank()) "Blank display" else "Display character", style = MaterialTheme.typography.bodySmall, color = Color(0xFFC4D6CF))
                Text(if (display.bands.isEmpty()) "No band indicator reported" else display.bands.joinToString(" · ") { it.label() }, style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text("${display.signalBars} / 8 signal LEDs", style = MaterialTheme.typography.bodySmall, color = Color(0xFFC4D6CF))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(8) { index -> Box(Modifier.weight(1f).height(12.dp).background(if (index < display.signalBars.coerceIn(0, 8)) Color(0xFFB5EACD) else Color(0xFF44635A), RoundedCornerShape(3.dp))) }
        }
        DirectionIndicators(display.directions, dark = true)
        Text(if (display.legacy) "Legacy display packet · status indicators unavailable" else "${if (display.systemStatus) "Detector active-search indicator: on" else "Detector active-search indicator: off"} · Soft indicator: ${if (display.muted) "on" else "off"}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFC4D6CF))
        Text(if (sample) "Synthetic values · no detector packet received" else "Received ${radarAge(now, display.receivedAt)} (as of now)\n${radarTime(display.receivedAt)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFC4D6CF))
    }
}

@Composable
private fun AlertCard(alert: RadarAlert, now: Long, sample: Boolean) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(19.dp)).border(1.dp, SweepLine, RoundedCornerShape(19.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(alert.bands.joinToString(" · ") { it.label() }.ifBlank { "Band not reported" }, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (alert.priority) Text("DETECTOR PRIORITY", style = MaterialTheme.typography.labelSmall, color = SweepAmber)
        }
        RadarValue("Reported frequency", "${alert.frequencyMhz} MHz", mono = true)
        DirectionIndicators(alert.directions)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RadarValue("Front strength", "${alert.frontStrength} / 255", Modifier.weight(1f), mono = true)
            RadarValue("Rear strength", "${alert.rearStrength} / 255", Modifier.weight(1f), mono = true)
        }
        Text("Strength readings are raw, unitless detector values. They do not estimate distance.", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
        Text(if (sample) "Synthetic radar-signal example" else "Received ${radarAge(now, alert.receivedAt)} (as of now) · ${radarTime(alert.receivedAt)}", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
    }
}

@Composable
private fun DirectionIndicators(directions: List<RadarDirection>, dark: Boolean = false) {
    val textColor = if (dark) Color(0xFFC4D6CF) else SweepMuted
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("DETECTOR-REPORTED DIRECTION", style = MaterialTheme.typography.labelSmall, color = textColor)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            RadarDirection.entries.forEach { direction ->
                val selected = direction in directions
                val bg = if (selected) (if (dark) Color(0xFFB5EACD) else SweepPale) else (if (dark) Color(0xFF36554A) else Color(0xFFECEEE7))
                val fg = if (selected) SweepInk else textColor
                Row(Modifier.weight(1f).background(bg, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(when (direction) { RadarDirection.FRONT -> Icons.Outlined.ArrowUpward; RadarDirection.SIDE -> Icons.Outlined.SwapHoriz; RadarDirection.REAR -> Icons.Outlined.ArrowDownward }, null, tint = fg, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp)); Text(direction.name.lowercase(Locale.US).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelMedium, color = fg)
                }
            }
        }
        if (directions.isEmpty()) Text("No direction indicator was reported.", style = MaterialTheme.typography.bodySmall, color = textColor)
    }
}

@Composable
private fun RadarValue(label: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = SweepMuted)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = if (mono) FontFamily.Monospace else FontFamily.SansSerif)
    }
}

@Composable
private fun RadarNotice(title: String, text: String, background: Color = Color(0xFFEBEEE4)) {
    Row(Modifier.fillMaxWidth().background(background, RoundedCornerShape(17.dp)).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.Info, null, tint = SweepInk, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall, color = SweepMuted)
        }
    }
}

private fun RadarConnectionStatus.label(): String = when (this) {
    RadarConnectionStatus.DISCONNECTED -> "Not connected"
    RadarConnectionStatus.DISCOVERING -> "Discovering nearby accessories"
    RadarConnectionStatus.CONNECTING -> "Connecting to selected accessory"
    RadarConnectionStatus.SUBSCRIBING -> "Setting up alert messages"
    RadarConnectionStatus.CONNECTED -> "Connected to accessory"
    RadarConnectionStatus.ERROR -> "Connection unavailable"
}
private fun RadarBand.label(): String = when (this) { RadarBand.X -> "X band"; RadarBand.K -> "K band"; RadarBand.KA -> "Ka band"; RadarBand.KU -> "Ku band"; RadarBand.LASER -> "Laser" }
private fun radarAge(now: Long, received: Long): String {
    val seconds = ((now - received).coerceAtLeast(0) / 1000)
    return when { received > now -> "timestamp ahead of phone clock"; seconds < 2 -> "just now"; seconds < 60 -> "${seconds}s ago"; seconds < 3600 -> "${seconds / 60}m ago"; else -> "${seconds / 3600}h ago" }
}
private fun radarTime(time: Long): String = SimpleDateFormat("MMM d · h:mm:ss a", Locale.getDefault()).format(Date(time))
// Synthetic protocol-shaped examples stay in the UI and never enter the BLE controller.
private val sampleDisplay = RadarDisplay("2", 5, listOf(RadarBand.KA), listOf(RadarDirection.FRONT), 0, muted = false, systemStatus = true)
private val sampleAlerts = listOf(
    RadarAlert(1, 2, 34710, 180, 25, listOf(RadarBand.KA), listOf(RadarDirection.FRONT), priority = true, receivedAt = 0),
    RadarAlert(2, 2, 24150, 40, 75, listOf(RadarBand.K), listOf(RadarDirection.REAR), priority = false, receivedAt = 0),
)
