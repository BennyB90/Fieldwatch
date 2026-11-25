package app.fieldwatch.sweep

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fieldwatch.BuildConfig
import app.fieldwatch.sweep.travel.TravelSessionState
import java.util.Locale

@Composable
internal fun BatteryTestDialog(onClose: () -> Unit, onShare: (String) -> Unit = {}) {
    val app = LocalContext.current.applicationContext as SweepApplication
    val meter = app.batteryTest
    val travel by app.travelController.current.collectAsStateWithLifecycle()
    val active = meter.log.active
    var playback by rememberSaveable { mutableStateOf(false) }
    var includeDevice by rememberSaveable { mutableStateOf(false) }
    var confirmed by rememberSaveable(active?.start?.elapsedMs) { mutableStateOf(false) }
    var confirmClear by remember(meter.log.results) { mutableStateOf(false) }

    AlertDialog(onDismissRequest = onClose, title = { Text("Battery test") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (active == null) {
                Text("Unplug and lock the phone between readings. Aim for an hour per run.")
                if (travel?.state == TravelSessionState.ACTIVE) {
                    Text(BatteryCondition(travel?.id, travel?.lowPowerBluetooth == true, travel?.locationConsent == true).label)
                } else {
                    Text("For an idle baseline, stop scans and audio first. To measure Travel, start it before the first reading.")
                    if (BuildConfig.SWEEP_RADIO_ENABLED) {
                        Row(
                            Modifier.fillMaxWidth().clickable { playback = !playback },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = playback, onCheckedChange = { playback = it })
                            Text("Label this run as radio playback", modifier = Modifier.weight(1f))
                        }
                    }
                }
                Button(onClick = { meter.start(playback) }) { Text("Start measurement") }
            } else {
                Text("${active.condition.label} · started at ${active.start.percent}%")
                Text("Return here before stopping the mode to take the final reading.")
                Row(
                    Modifier.fillMaxWidth().clickable { confirmed = !confirmed },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
                    Text("The phone stayed unplugged and in the same mode.", modifier = Modifier.weight(1f))
                }
                Button(enabled = confirmed, onClick = { meter.finish() }) { Text("Finish measurement") }
                TextButton(onClick = { meter.discard() }) { Text("Discard this run") }
            }
            if (meter.message.isNotEmpty()) Text(meter.message, color = MaterialTheme.colorScheme.error)
            if (meter.log.results.isNotEmpty()) {
                HorizontalDivider()
                Text("Recent measurements", style = MaterialTheme.typography.titleMedium)
                meter.log.results.forEach { result ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(result.label, style = MaterialTheme.typography.titleSmall)
                        Text(String.format(Locale.getDefault(), "%d%% → %d%% in %.1f min · %.1f points/hour",
                            result.startPercent, result.endPercent, result.durationMs / 60_000.0, result.pointsPerHour))
                        if (result.sightingCount != null && result.trackerCount != null) {
                            val gpsSuffix = result.gpsSightingCount?.let { " · $it with GPS fix" }.orEmpty()
                            Text(
                                "${result.sightingCount} sightings (${result.trackerCount} tracker${if (result.trackerCount == 1) "" else "s"}$gpsSuffix)",
                                style = MaterialTheme.typography.bodySmall,
                                color = SweepMuted,
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clickable { includeDevice = !includeDevice },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = includeDevice, onCheckedChange = { includeDevice = it })
                    Column(Modifier.weight(1f)) {
                        Text("Include phone model & Android version", style = MaterialTheme.typography.bodyMedium)
                        Text("Off by default. Radio addresses and GPS coordinates are never included.", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
                    }
                }
                OutlinedButton(
                    onClick = { onShare(meter.reportText(includeDevice)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Share measurements")
                }
                if (confirmClear) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { meter.clearResults(); confirmClear = false }) {
                            Text("Confirm clear", color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
                    }
                } else {
                    TextButton(onClick = { confirmClear = true }) { Text("Clear measurements") }
                }
            } else if (meter.message.isNotEmpty()) {
                TextButton(onClick = { meter.clearResults() }) { Text("Clear damaged measurements") }
            }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Close") } })
}
