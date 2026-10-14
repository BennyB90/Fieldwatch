package app.fieldwatch.sweep.network

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fieldwatch.sweep.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SweepNetworkScreen(
    current: NetworkSession?, history: List<NetworkSession>, availability: NetworkAvailability,
    initializing: Boolean, busy: Boolean, error: String?, onBack: () -> Unit,
    onStart: () -> Unit, onStop: () -> Unit, onImport: () -> Unit,
    onShare: (NetworkSession, Boolean) -> Unit, onDelete: (String) -> Unit, onClear: () -> Unit,
    onSettings: () -> Unit, onClearError: () -> Unit,
    onRetryHistory: () -> Unit,
    onRetryCleanup: () -> Unit,
) {
    var selectedSnapshotId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedEndpointId by rememberSaveable { mutableStateOf<String?>(null) }
    var startConsent by rememberSaveable { mutableStateOf(false) }
    var importConsent by rememberSaveable { mutableStateOf(false) }
    var sharing by remember { mutableStateOf<NetworkSession?>(null) }
    var deleting by remember { mutableStateOf<NetworkSession?>(null) }
    var clearAll by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val routes = rememberSaveableStateHolder()
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val snapshot = selectedSnapshotId?.let { id -> current?.takeIf { it.id == id } ?: history.find { it.id == id } }
    val detail = snapshot?.endpoints?.find { it.id == selectedEndpointId }
    fun back() {
        when { selectedEndpointId != null -> selectedEndpointId = null; selectedSnapshotId != null -> selectedSnapshotId = null; else -> onBack() }
    }
    BackHandler { back() }

    Scaffold(
        containerColor = SweepCream,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { back() }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.ArrowBack, "Back") }
                Text(if (detail != null) "Record evidence" else if (snapshot != null) "Network snapshot" else "Network", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (snapshot == null) Text("LOCAL REVIEW", style = MaterialTheme.typography.labelSmall, color = SweepTeal, modifier = Modifier.background(SweepPale, RoundedCornerShape(7.dp)).padding(8.dp))
            }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            when {
                detail != null -> EndpointDetail(detail, snapshot!!, now)
                snapshot != null -> routes.SaveableStateProvider("snapshot_${snapshot.id}") {
                    SnapshotDetail(snapshot, now, { selectedEndpointId = it.id }, { sharing = snapshot }, { deleting = snapshot })
                }
                else -> routes.SaveableStateProvider("network_home") {
                    NetworkHome(current, history, availability, initializing, busy, error, now,
                        onStart = { startConsent = true }, onStop = onStop, onImport = { importConsent = true },
                        onSnapshot = { selectedSnapshotId = it.id; selectedEndpointId = null },
                        onEndpoint = { session, endpoint -> selectedSnapshotId = session.id; selectedEndpointId = endpoint.id },
                        onSettings = onSettings, onClearError = onClearError, onRetryHistory = onRetryHistory,
                        onRetryCleanup = onRetryCleanup, onClear = { clearAll = true })
                }
            }
        }
    }
    if (startConsent) AuthorizedActionDialog(
        title = "Discover on this Wi-Fi?",
        explanation = "SWEEP will request advertised services on the connected Wi-Fi network for up to 60 seconds. Android can return cached results. Discovery ends when you leave this screen; it does not restart by itself.",
        confirmation = "I own, manage, or have permission to inspect this network.", action = "Start discovery",
        onDismiss = { startConsent = false }, onConfirm = { startConsent = false; onStart() },
    )
    if (importConsent) AuthorizedActionDialog(
        title = "Import a router inventory",
        explanation = "Choose an authorized JSON inventory in SWEEP schema version 1. Required fields: schemaVersion, capturedAt (epoch milliseconds), and devices. Optional device fields are hostname, addresses (literal IP addresses), macAddress, reportedIdentifier, current and lastSeenAt. Files are limited to 1 MiB; credentials and unknown fields are not accepted.",
        confirmation = "I am authorized to use and inspect this inventory.", action = "Choose JSON file",
        onDismiss = { importConsent = false }, onConfirm = { importConsent = false; onImport() },
    )
    sharing?.let { report -> NetworkShareDialog(report, { sharing = null }) { identifiers -> onShare(report, identifiers); sharing = null } }
    deleting?.let { report -> AlertDialog(
        onDismissRequest = { deleting = null }, title = { Text("Delete this snapshot?") },
        text = { Text("Removes this saved network snapshot from this phone. Any copies you shared remain with their recipients.") },
        confirmButton = { TextButton(onClick = { onDelete(report.id); deleting = null; selectedSnapshotId = null; selectedEndpointId = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep snapshot") } },
    ) }
    if (clearAll) AlertDialog(
        onDismissRequest = { clearAll = false }, title = { Text("Clear saved network snapshots?") },
        text = { Text("This removes network history stored on this phone, including any snapshots that could not be loaded.") },
        confirmButton = { TextButton(onClick = { onClear(); clearAll = false; selectedSnapshotId = null; selectedEndpointId = null }) { Text("Clear all", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { clearAll = false }) { Text("Cancel") } },
    )
}

@Composable
private fun NetworkHome(
    current: NetworkSession?, history: List<NetworkSession>, availability: NetworkAvailability,
    initializing: Boolean, busy: Boolean, error: String?, now: Long,
    onStart: () -> Unit, onStop: () -> Unit, onImport: () -> Unit, onSnapshot: (NetworkSession) -> Unit,
    onEndpoint: (NetworkSession, NetworkEndpoint) -> Unit, onSettings: () -> Unit, onClearError: () -> Unit,
    onRetryHistory: () -> Unit, onRetryCleanup: () -> Unit, onClear: () -> Unit,
) {
    val active = current?.state == NetworkSessionState.ACTIVE
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item(key = "network_hero") {
            Column(Modifier.fillMaxWidth().background(SweepInk, RoundedCornerShape(25.dp)).padding(23.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Router, null, tint = Color(0xFFB5EACD), modifier = Modifier.size(21.dp))
                    Text(if (active) "DISCOVERY IN PROGRESS" else "WI-FI SERVICE DISCOVERY", style = MaterialTheme.typography.labelSmall, color = Color(0xFFC4D6CF))
                }
                if (current == null) {
                    Text("Your Wi-Fi,\nin context.", style = MaterialTheme.typography.headlineLarge, color = Color.White)
                    Text("Review the services devices advertise, or an inventory you import from your router.", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFC4D6CF))
                } else {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(current.endpoints.size.toString(), fontSize = 60.sp, fontWeight = FontWeight.Light, color = Color(0xFFB5EACD))
                        Text(if (current.source == NetworkSource.ROUTER_IMPORT) "imported records" else "service records", style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.weight(1f).padding(bottom = 8.dp))
                    }
                    Text(current.source.label(), style = MaterialTheme.typography.bodySmall, color = Color(0xFFC4D6CF))
                    if (active) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("TIME LEFT · 60s MAXIMUM", style = MaterialTheme.typography.labelSmall, color = Color(0xFFC4D6CF))
                        Text(networkDuration((60_000L - (now - current.startedAt)).coerceIn(0, 60_000)), style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, color = Color.White)
                    } else Text(if (current.state == NetworkSessionState.INTERRUPTED) "Discovery interrupted" else "Snapshot complete", style = MaterialTheme.typography.labelMedium, color = Color(0xFFC4D6CF))
                }
                Button(onClick = if (active) onStop else onStart, enabled = active || (availability.ready && !busy && !initializing), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC3F1D4), contentColor = SweepInk, disabledContainerColor = Color(0xFF426760), disabledContentColor = Color(0xFFC4D6CF))) {
                    Icon(if (active) Icons.Outlined.StopCircle else Icons.Outlined.Search, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text(if (active) "End discovery" else "Start 60-second discovery")
                }
                Text("On demand · Ends when you leave · Snapshots stay local", style = MaterialTheme.typography.bodySmall, color = Color(0xFFC4D6CF))
            }
        }
        item(key = "network_availability") {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp)).border(1.dp, SweepLine, RoundedCornerShape(18.dp)).padding(17.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Connected-network availability", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onSettings) { Text("Wi-Fi settings", style = MaterialTheme.typography.labelMedium) }
                }
                Text(availability.message, style = MaterialTheme.typography.bodyMedium, color = if (availability.ready) SweepMuted else SweepAmber)
                if (!availability.ready && !initializing && !busy && availability.message.contains("history", ignoreCase = true)) {
                    TextButton(onClick = onRetryHistory) { Text("Retry local history") }
                }
                if (!busy && availability.message.contains("cleanup", ignoreCase = true)) {
                    TextButton(onClick = onRetryCleanup) { Text("Retry Android cleanup") }
                }
                Text("Scoped live discovery requires Android 13 or later. Saved snapshots and authorized router imports remain available on Android 10 and later.", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
            }
        }
        if (initializing || busy) item(key = "network_busy") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                CircularProgressIndicator(Modifier.size(21.dp), strokeWidth = 2.dp)
                Text(if (initializing) "Loading local snapshots…" else "Preparing network evidence…", style = MaterialTheme.typography.bodyMedium, color = SweepMuted)
            }
        }
        error?.let { text -> item(key = "network_error") {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                NetworkNotice("Network note", text, MaterialTheme.colorScheme.errorContainer)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (text.contains("history", ignoreCase = true) || text.contains("sav", ignoreCase = true)) TextButton(onClick = onRetryHistory, enabled = !busy && !initializing) { Text("Retry local history") }
                    TextButton(onClick = onClearError) { Text("Dismiss note") }
                }
            }
        } }
        current?.let { session ->
            item(key = "network_coverage") { CoverageCard(session.coverage, session.source) }
            if (session.endpoints.isNotEmpty()) {
                item(key = "network_current_heading") { Text(if (session.source == NetworkSource.ROUTER_IMPORT) "Imported inventory records" else "Advertised-service records", style = MaterialTheme.typography.titleLarge) }
                items(session.endpoints, key = { "current_${it.id}" }) { endpoint -> EndpointRow(endpoint) { onEndpoint(session, endpoint) } }
            } else item(key = "network_current_empty") {
                NetworkNotice("No service records received", "Service discovery is partial. Cached results, isolated clients, different subnets and devices that advertise no supported service can leave no records. This cannot prove that the network is empty or free of recording devices.")
            }
            if (!active) item(key = "network_current_report") {
                OutlinedButton(onClick = { onSnapshot(session) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(15.dp)) { Icon(Icons.Outlined.Description, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Review snapshot & share") }
            }
        }
        item(key = "network_import") {
            OutlinedButton(onClick = onImport, enabled = !active && !busy && !initializing, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(15.dp)) {
                Icon(Icons.Outlined.FileUpload, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("Import router JSON inventory")
            }
        }
        item(key = "network_history_heading") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Saved snapshots", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onClear, enabled = !active && !busy && !initializing) { Text("Clear all", color = MaterialTheme.colorScheme.error) }
            }
        }
        if (history.isEmpty() && !initializing) item(key = "network_history_empty") {
            Text("Completed discovery sessions and imported inventories appear here. Each snapshot keeps its source and coverage context.", style = MaterialTheme.typography.bodyMedium, color = SweepMuted)
        }
        items(history.sortedByDescending { it.startedAt }, key = { "saved_${it.id}" }) { session -> SnapshotRow(session) { onSnapshot(session) } }
        item(key = "network_limits") { NetworkLimitsNotice() }
    }
}

@Composable
private fun CoverageCard(coverage: NetworkCoverage, source: NetworkSource) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp)).border(1.dp, SweepLine, RoundedCornerShape(18.dp)).padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (source == NetworkSource.ROUTER_IMPORT) "Inventory source" else "Discovery coverage", style = MaterialTheme.typography.titleSmall)
        if (source == NetworkSource.ROUTER_IMPORT) {
            Text("Records supplied by an imported document. No live network scan was performed for this snapshot.", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
        } else {
            Text(coverage.status, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                NetworkValue("Resolved results", coverage.resolvedServices.toString(), Modifier.weight(1f))
                NetworkValue("Unresolved results", coverage.unresolvedServices.toString(), Modifier.weight(1f))
            }
            NetworkValue("Wi-Fi network scope", if (coverage.scopedToNetwork) "Scoped to the selected Wi-Fi network" else "Scope not established")
            NetworkValue("Service types started", if (coverage.typesStarted.isEmpty()) "None" else coverage.typesStarted.joinToString(" · "))
            if (coverage.typesFailed.isNotEmpty()) NetworkValue("Service types unavailable", coverage.typesFailed.joinToString(" · "))
            if (coverage.droppedServices > 0) Text("${coverage.droppedServices} results were not attributed or retained. Coverage is partial.", style = MaterialTheme.typography.bodySmall, color = SweepAmber)
            coverage.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SweepAmber) }
        }
    }
}

@Composable
private fun SnapshotRow(session: NetworkSession, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp)).border(1.dp, SweepLine, RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(17.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(session.source.label(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.ChevronRight, null, tint = SweepMuted, modifier = Modifier.size(22.dp))
        }
        Text("${session.endpoints.size} records · ${networkTime(session.startedAt)}", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
        if (session.source == NetworkSource.ROUTER_IMPORT) Text("Inventory timestamp: ${session.capturedAt?.let(::networkTime) ?: "not supplied"} (reported)", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
        else if (session.state == NetworkSessionState.INTERRUPTED) Text("Interrupted discovery", style = MaterialTheme.typography.labelMedium, color = SweepAmber)
    }
}

@Composable
private fun SnapshotDetail(session: NetworkSession, now: Long, onEndpoint: (NetworkEndpoint) -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(session.source.label(), style = MaterialTheme.typography.headlineMedium)
                Text("${session.endpoints.size} ${if (session.source == NetworkSource.ROUTER_IMPORT) "imported" else "service"} records", style = MaterialTheme.typography.bodyLarge, color = SweepMuted)
                NetworkValue(if (session.source == NetworkSource.ROUTER_IMPORT) "Imported on this phone" else "Discovery started", networkTime(session.startedAt))
                if (session.source == NetworkSource.ROUTER_IMPORT) {
                    NetworkValue("Inventory captured at (reported)", session.capturedAt?.let(::networkTime) ?: "Not supplied")
                    NetworkValue("Reported network label", session.networkLabel ?: "Not supplied")
                } else {
                    NetworkValue("Discovery duration", if (session.state == NetworkSessionState.ACTIVE) networkDuration(now - session.startedAt) else session.endedAt?.let { networkDuration(it - session.startedAt) } ?: "Duration unknown")
                    session.endedAt?.let { NetworkValue("Discovery ended", networkTime(it)) }
                    session.networkLabel?.let { NetworkValue("Session network label", it) }
                }
            }
        }
        item { CoverageCard(session.coverage, session.source) }
        if (session.state != NetworkSessionState.ACTIVE) item {
            Button(onClick = onShare, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(15.dp)) { Icon(Icons.Outlined.IosShare, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("Share snapshot summary") }
        }
        item { Text(if (session.source == NetworkSource.ROUTER_IMPORT) "Reported inventory evidence" else "Received service evidence", style = MaterialTheme.typography.titleLarge) }
        if (session.endpoints.isEmpty()) item { Text("This snapshot contains no service records. Review its coverage before drawing conclusions.", style = MaterialTheme.typography.bodyMedium, color = SweepMuted) }
        items(session.endpoints, key = { it.id }) { endpoint -> EndpointRow(endpoint) { onEndpoint(endpoint) } }
        item { NetworkLimitsNotice() }
        if (session.state != NetworkSessionState.ACTIVE) item {
            TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("Delete snapshot", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun EndpointRow(endpoint: NetworkEndpoint, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(17.dp)).border(1.dp, SweepLine, RoundedCornerShape(17.dp)).clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).background(SweepPale, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(if (endpoint.source == NetworkSource.ROUTER_IMPORT) Icons.Outlined.Description else Icons.Outlined.Lan, null, tint = SweepTeal, modifier = Modifier.size(22.dp)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(endpoint.instanceName.ifBlank { endpoint.hostnames.firstOrNull() ?: if (endpoint.source == NetworkSource.ROUTER_IMPORT) "Imported inventory record" else "Advertised service" }, style = MaterialTheme.typography.titleSmall)
            Text(if (endpoint.source == NetworkSource.ROUTER_IMPORT) "Router-reported record" else endpoint.serviceType, style = MaterialTheme.typography.bodySmall, color = SweepMuted)
            Text(if (endpoint.source == NetworkSource.ROUTER_IMPORT) "Imported evidence · tap to review source" else if (endpoint.addresses.isEmpty()) "Resolution not available" else "${endpoint.addresses.size} resolved ${if (endpoint.addresses.size == 1) "address" else "addresses"}", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = SweepMuted, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun EndpointDetail(endpoint: NetworkEndpoint, session: NetworkSession, now: Long) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(endpoint.instanceName.ifBlank { endpoint.hostnames.firstOrNull() ?: "Network record" }, style = MaterialTheme.typography.headlineMedium)
        NetworkNotice(if (endpoint.source == NetworkSource.ROUTER_IMPORT) "Imported · router-reported evidence" else "Received · Wi-Fi service evidence", if (endpoint.source == NetworkSource.ROUTER_IMPORT) "These values came from a document, not a live observation by this phone. A reported current flag or timestamp has not been independently checked." else "Android may return cached discovery and resolution results. Receipt timestamps do not establish a fresh on-air advertisement or confirm that a client is currently online.")
        NetworkValue("Source", endpoint.source.label())
        if (endpoint.source == NetworkSource.NSD) {
            NetworkValue("Advertised service type", endpoint.serviceType, mono = true)
            NetworkValue("Advertised instance name", endpoint.instanceName.ifBlank { "Not supplied" })
            NetworkValue("First NSD result received", endpoint.firstSeenAt?.let(::networkTime) ?: "Not recorded")
            NetworkValue("Last NSD result received", endpoint.lastSeenAt?.let(::networkTime) ?: "Not recorded")
            endpoint.lastSeenAt?.let { NetworkValue("Result receipt age (as of now)", networkAge(now, it)) }
        } else {
            NetworkValue("Inventory captured at (reported)", session.capturedAt?.let(::networkTime) ?: "Not supplied")
            NetworkValue("Record imported on this phone", endpoint.importedAt?.let(::networkTime) ?: "Not recorded")
            NetworkValue("Router-reported current flag", when (endpoint.reportedCurrent) { true -> "Yes (reported)"; false -> "No (reported)"; null -> "Not supplied" })
            NetworkValue("Router-reported last seen", endpoint.reportedLastSeenAt?.let(::networkTime) ?: "Not supplied")
            endpoint.reportedMacAddress?.let { NetworkValue("Router-reported MAC address", it, mono = true) }
            endpoint.reportedIdentifier?.let { NetworkValue("Router-reported identifier", it, mono = true) }
        }
        NetworkValue(if (endpoint.source == NetworkSource.ROUTER_IMPORT) "Reported hostnames" else "Resolved hostnames", endpoint.hostnames.ifEmpty { listOf("Not supplied") }.joinToString("\n"), mono = true)
        NetworkValue(if (endpoint.source == NetworkSource.ROUTER_IMPORT) "Reported addresses" else "Resolved addresses", endpoint.addresses.ifEmpty { listOf("Not available") }.joinToString("\n"), mono = true)
        if (endpoint.source == NetworkSource.NSD) NetworkValue("Resolved port", endpoint.port?.toString() ?: "Not available", mono = true)
        if (endpoint.txt.isNotEmpty()) {
            Text("Reported TXT attributes", style = MaterialTheme.typography.titleLarge)
            endpoint.txt.forEach { (key, value) -> NetworkValue(key, value, mono = true) }
        }
        NetworkNotice("What this can tell you", "A service record describes metadata advertised or reported about a network endpoint. Several records can belong to one device. Names and protocols do not prove a camera, microphone or recorder is present.")
        NetworkLimitsNotice()
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AuthorizedActionDialog(title: String, explanation: String, confirmation: String, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var checked by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 390.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                Text(explanation, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth().clickable { checked = !checked }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked, { checked = it }); Text(confirmation, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = checked) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NetworkShareDialog(session: NetworkSession, onDismiss: () -> Unit, onShare: (Boolean) -> Unit) {
    var identifiers by remember(session.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Share snapshot summary") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("The default summary includes counts, source and coverage. Names, addresses and identifying evidence are excluded.")
                Row(Modifier.fillMaxWidth().clickable { identifiers = !identifiers }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(identifiers, { identifiers = it })
                    Column(Modifier.weight(1f)) {
                        Text("Include identifying evidence", style = MaterialTheme.typography.titleSmall)
                        Text("Network labels, names, hostnames, IP addresses, ports, reported MACs/identifiers and TXT fields when present.", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
                    }
                }
                Text("The sharing app receives the summary you choose. Review the recipient before sending it.", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
            }
        },
        confirmButton = { TextButton(onClick = { onShare(identifiers) }) { Text("Choose sharing app") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NetworkLimitsNotice() {
    NetworkNotice("A partial view of advertised services", "This is service discovery, not a census of every network client. Android can return cached results. Isolated devices, other subnets and clients that do not advertise supported services can be absent. A record or an empty result does not prove that recording devices are present or absent.")
}

@Composable
private fun NetworkNotice(title: String, text: String, background: Color = Color(0xFFEBEEE4)) {
    Row(Modifier.fillMaxWidth().background(background, RoundedCornerShape(17.dp)).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.Info, null, tint = SweepInk, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(text, style = MaterialTheme.typography.bodySmall, color = SweepMuted) }
    }
}

@Composable
private fun NetworkValue(label: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = SweepMuted)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = if (mono) FontFamily.Monospace else FontFamily.SansSerif)
    }
}
private fun NetworkSource.label(): String = when (this) { NetworkSource.NSD -> "Wi-Fi service discovery"; NetworkSource.ROUTER_IMPORT -> "Router inventory · imported" }
private fun networkTime(time: Long): String = SimpleDateFormat("MMM d, yyyy · h:mm:ss a", Locale.getDefault()).format(Date(time))
private fun networkDuration(millis: Long): String { val seconds = millis.coerceAtLeast(0) / 1000; return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60) }
private fun networkAge(now: Long, time: Long): String { val seconds = (now - time).coerceAtLeast(0) / 1000; return when { time > now -> "timestamp ahead of phone clock"; seconds < 2 -> "just now"; seconds < 60 -> "${seconds}s ago"; seconds < 3600 -> "${seconds / 60}m ago"; seconds < 86400 -> "${seconds / 3600}h ago"; else -> "${seconds / 86400}d ago" } }
