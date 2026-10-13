package app.fieldwatch.sweep

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fieldwatch.sweep.travel.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SweepTravelActivity : ComponentActivity() {
    private val travel get() = (application as SweepApplication).travelController
    private var pendingLocation: Boolean? = null
    private var uiError by mutableStateOf<String?>(null)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val location = pendingLocation
        pendingLocation = null
        if (location != null && !isFinishing && !isDestroyed) {
            if (requiredPermissions().all(::hasPermission)) travel.start(location)
            else uiError = "Travel needs Bluetooth, precise location access for Android scanning, and notification access. GPS correlation remains a separate choice."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val current by travel.current.collectAsStateWithLifecycle()
            val history by travel.history.collectAsStateWithLifecycle()
            val starting by travel.starting.collectAsStateWithLifecycle()
            val clearing by travel.clearing.collectAsStateWithLifecycle()
            val locationErasePending by travel.locationErasurePending.collectAsStateWithLifecycle()
            val ignored by travel.ignoredAddresses.collectAsStateWithLifecycle()
            val error by travel.error.collectAsStateWithLifecycle()
            SweepTheme {
                TravelScreen(current, history, starting, clearing, locationErasePending, ignored, uiError ?: error,
                    onBack = ::finish, onStart = ::requestStart, onStop = travel::stop,
                    onDeleteAll = travel::deleteAll, onDisableLocation = travel::disableLocation,
                    onIgnored = travel::setIgnored, onShare = ::share, onSettings = ::openSettings)
            }
        }
    }

    private fun hasPermission(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    private fun requiredPermissions() = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }
    private fun requestStart(location: Boolean) {
        uiError = null
        if (requiredPermissions().any { !hasPermission(it) }) {
            pendingLocation = location
            permissions.launch(requiredPermissions().toTypedArray())
        } else travel.start(location)
    }
    private fun openSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            .onFailure { uiError = "Open Android settings to review permissions, Bluetooth and Location." }
    }
    private fun share(session: TravelSession) {
        // Only a user-selected report. No temporary file contains phone coordinates.
        val available = travel.history.value.find { it.id == session.id } ?: travel.current.value?.takeIf { it.id == session.id }
        if (available == null) { uiError = "This travel report was cleared."; return }
        runCatching {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, TravelReports.text(available))
                .putExtra(Intent.EXTRA_SUBJECT, "SWEEP travel observation report"), "Share redacted travel report"))
        }.onFailure { uiError = "No sharing app is available." }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TravelScreen(
    current: TravelSession?, history: List<TravelSession>, starting: Boolean, clearing: Boolean, locationErasePending: Boolean,
    ignored: Set<String>, error: String?, onBack: () -> Unit, onStart: (Boolean) -> Unit,
    onStop: () -> Unit, onDeleteAll: () -> Unit, onDisableLocation: () -> Unit,
    onIgnored: (String, Boolean) -> Unit, onShare: (TravelSession) -> Unit, onSettings: () -> Unit,
) {
    var locationConsent by remember { mutableStateOf(false) }
    var clearDialog by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000L) } }
    val active = current?.state == TravelSessionState.ACTIVE
    val selected = selectedId?.let { id -> history.find { it.id == id } } ?: current
    val clock = remember { SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()) }
    Scaffold(containerColor = SweepCream, topBar = {
        TopAppBar(title = { Text("Travel observations") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Back to SWEEP") }
        })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Repeated sightings, explained.", style = MaterialTheme.typography.headlineMedium)
            Text("This optional mode observes supported tracker broadcasts while its foreground service runs. Android can interrupt reception. A repeated sighting is not proof of unwanted tracking.", color = SweepMuted)
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            if (!active) {
                Row(Modifier.fillMaxWidth().clickable { locationConsent = !locationConsent }) {
                    Checkbox(checked = locationConsent, onCheckedChange = { locationConsent = it })
                    Column(Modifier.weight(1f)) {
                        Text("Also compare my phone's locations", style = MaterialTheme.typography.titleSmall)
                        Text("Off by default. Records GPS fixes locally for this mode to compare sightings at different places. It does not locate a tracker.", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
                    }
                }
                Button(onClick = { selectedId = null; onStart(locationConsent) }, enabled = !starting && !clearing, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (starting) "Starting…" else "Start travel mode") }
            } else {
                Text(current!!.status, style = MaterialTheme.typography.titleSmall)
                Text("${((now - current.startedAt).coerceAtLeast(0) / 60_000)} minutes elapsed · location correlation ${if (current.locationConsent) "enabled" else "off"}", style = MaterialTheme.typography.bodySmall)
                Button(onClick = onStop, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Stop travel mode") }
            }
            TextButton(onClick = onSettings) { Text("Android permissions and settings") }
            if (current?.locationConsent == true || history.any { it.locationConsent } || locationErasePending) {
                OutlinedButton(onClick = { locationConsent = false; onDisableLocation() }, enabled = !clearing, modifier = Modifier.fillMaxWidth()) { Text(if (locationErasePending) "Retry erasing saved phone locations" else "Stop location correlation and erase saved locations") }
                if (locationErasePending) Text("Location correlation is stopped. Erasure of saved phone locations is not confirmed yet; retry if an error is shown.", style = MaterialTheme.typography.bodySmall, color = SweepAmber)
            }
            selected?.let { session ->
                HorizontalDivider()
                Text(if (session.state == TravelSessionState.ACTIVE) "Current observations" else "Saved travel report", style = MaterialTheme.typography.titleMedium)
                Text("${clock.format(Date(session.startedAt))} · ${session.state.name.lowercase(Locale.getDefault())}", style = MaterialTheme.typography.bodySmall)
                session.error?.let { Text(it, color = SweepAmber) }
                if (session.droppedSamples > 0) Text("${session.droppedSamples} samples were dropped. Reception evidence is incomplete.", style = MaterialTheme.typography.bodySmall, color = SweepAmber)
                if (session.trackers.isEmpty()) Text("No eligible tracker broadcasts recorded. This does not rule out trackers, cellular trackers or silent devices.", color = SweepMuted)
                session.trackers.forEach { tracker ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(tracker.label, style = MaterialTheme.typography.titleSmall)
                        Text(tracker.address, style = MaterialTheme.typography.bodySmall)
                        Text("${tracker.samples.size} spaced observations", style = MaterialTheme.typography.bodySmall)
                        session.assessments.find { it.address == tracker.address }?.let { Text(it.explanation, color = SweepAmber) }
                        if (tracker.randomizedAddress) Text("Address may change. Different addresses are not linked into a physical identity.", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
                        TextButton(onClick = { onIgnored(tracker.address, true) }) { Text("Ignore this familiar address") }
                    }
                }
                TextButton(onClick = { onShare(session) }) { Text("Share report without identifiers or coordinates") }
            }
            if (ignored.isNotEmpty()) {
                Text("Familiar addresses", style = MaterialTheme.typography.titleMedium)
                ignored.sorted().forEach { address -> TextButton(onClick = { onIgnored(address, false) }) { Text("Observe $address again") } }
                Text("Ignoring an address does not ignore future rotated addresses.", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
            }
            if (history.isNotEmpty()) {
                Text("Saved travel sessions", style = MaterialTheme.typography.titleMedium)
                history.forEach { session -> OutlinedButton(onClick = { selectedId = session.id }, modifier = Modifier.fillMaxWidth()) { Text("${clock.format(Date(session.startedAt))} · ${session.trackers.size} tracker signals") } }
            }
            Text("Up to four hours per run. Local history is limited to eight sessions and seven days. Closing SWEEP from recents or an Android interruption stops the mode; it never resumes automatically.", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
            TextButton(onClick = { clearDialog = true }, enabled = !clearing) { Text(if (clearing) "Clearing…" else "Clear all travel observations", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (clearDialog) AlertDialog(onDismissRequest = { clearDialog = false },
        title = { Text("Clear travel observations?") }, text = { Text("Stops travel mode and removes local sightings, phone locations and familiar addresses. Shared copies stay in the receiving apps.") },
        confirmButton = { TextButton(onClick = { clearDialog = false; selectedId = null; locationConsent = false; onDeleteAll() }) { Text("Clear all") } },
        dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("Cancel") } })
}
