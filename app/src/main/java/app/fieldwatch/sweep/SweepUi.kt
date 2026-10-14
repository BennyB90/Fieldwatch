package app.fieldwatch.sweep

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fieldwatch.domain.Fleet
import app.fieldwatch.domain.MatchRule
import app.fieldwatch.domain.SignatureClass
import app.fieldwatch.domain.Rssi
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private enum class SweepTab(val label: String, val icon: ImageVector) {
    SCAN("Scan", Icons.Outlined.WifiTethering),
    SESSIONS("Sessions", Icons.Outlined.History),
    CATALOG("Catalog", Icons.Outlined.GridView),
    LISTEN("Listen", Icons.Outlined.Headphones),
    RADAR("Radar", Icons.Outlined.Radar),
    TRAVEL("Travel", Icons.Outlined.Route),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SweepRoot(
    current: SweepSession?,
    history: List<SweepSession>,
    coverage: SweepCoverage,
    catalog: List<Fleet>,
    starting: Boolean,
    error: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDelete: (String) -> Unit,
    onDeleteAll: () -> Unit,
    onShare: (SweepSession, Boolean, Boolean) -> Unit,
    onDemo: () -> Unit,
    onClearDemo: () -> Unit,
    onSettings: () -> Unit,
    onMap: (SweepPin) -> Unit,
    radioEnabled: Boolean = false,
    onRadio: () -> Unit = {},
    onRadar: () -> Unit = {},
    onTravel: () -> Unit = {},
    networkEnabled: Boolean = false,
    onNetwork: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableStateOf(SweepTab.SCAN) }
    var selectedSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedFindingId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedFleetId by rememberSaveable { mutableStateOf<String?>(null) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var shareSession by remember { mutableStateOf<SweepSession?>(null) }
    var deleteSession by remember { mutableStateOf<SweepSession?>(null) }
    var clearAll by rememberSaveable { mutableStateOf(false) }
    var comparing by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val tabState = rememberSaveableStateHolder()
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }

    val selectedSession = selectedSessionId?.let { id -> history.find { it.id == id } ?: current?.takeIf { it.id == id } }
    val findingSession = selectedSession ?: current
    val finding = findingSession?.findings?.find { it.id == selectedFindingId }
    val fleet = catalog.find { it.id == selectedFleetId }
    val inDetail = finding != null || selectedSession != null || fleet != null || comparing

    fun back() {
        when {
            selectedFindingId != null -> selectedFindingId = null
            selectedSessionId != null -> selectedSessionId = null
            selectedFleetId != null -> selectedFleetId = null
            comparing -> comparing = false
            tab != SweepTab.SCAN -> tab = SweepTab.SCAN
        }
    }
    BackHandler(enabled = inDetail || tab != SweepTab.SCAN) { back() }

    Scaffold(
        containerColor = SweepCream,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (inDetail) {
                    IconButton(onClick = { back() }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
                    }
                    Text(if (finding != null) "Signal details" else if (fleet != null) "Catalog entry" else if (comparing) "Compare room scans" else "Session report", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                } else {
                    Box(Modifier.size(36.dp).background(SweepInk, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.WifiTethering, contentDescription = null, tint = Color(0xFF9FE2C8), modifier = Modifier.size(22.dp))
                    }
                    Text("SWEEP", fontWeight = FontWeight.ExtraBold, fontSize = 23.sp, letterSpacing = 2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp).weight(1f))
                    if (!networkEnabled) TinyTag(if (radioEnabled) "LOCAL SCANS" else "ON DEVICE", SweepPale, SweepInk)
                }
                if (networkEnabled && !inDetail) IconButton(onClick = onNetwork, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Lan, contentDescription = "Local network discovery", tint = SweepMuted)
                }
                IconButton(onClick = { showInfo = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Info, contentDescription = "About, privacy and scanning limits", tint = SweepMuted)
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFFFFFEF9), tonalElevation = 0.dp) {
                SweepTab.entries.filter { radioEnabled || it != SweepTab.LISTEN }.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = {
                            if (item == SweepTab.LISTEN) onRadio()
                            else if (item == SweepTab.RADAR) onRadar()
                            else if (item == SweepTab.TRAVEL) onTravel()
                            else { tab = item; comparing = false; selectedSessionId = null; selectedFindingId = null; selectedFleetId = null }
                        },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = SweepInk, selectedTextColor = SweepInk, indicatorColor = SweepPale, unselectedIconColor = SweepMuted, unselectedTextColor = SweepMuted),
                    )
                }
            }
        },
    ) { inset ->
        Box(Modifier.padding(inset).fillMaxSize()) {
            when {
                finding != null -> FindingDetail(finding, findingSession!!.demo, now, onMap)
                selectedSession != null -> SessionReport(selectedSession, now, { selectedFindingId = it.id }, { shareSession = selectedSession }, { deleteSession = selectedSession })
                fleet != null -> CatalogDetail(fleet)
                comparing -> SweepComparisonScreen(history) { session, selected -> selectedSessionId = session.id; selectedFindingId = selected.id }
                else -> tabState.SaveableStateProvider(tab.name) {
                    when (tab) {
                        SweepTab.SCAN -> ScanScreen(current, coverage, starting, error, now, onStart, onStop, { selectedFindingId = it.id }, { shareSession = it }, onDemo, onClearDemo, onSettings)
                        SweepTab.SESSIONS -> SessionsScreen(history, now, { selectedSessionId = it.id }, { clearAll = true }, { comparing = true })
                        SweepTab.CATALOG -> CatalogScreen(catalog, { selectedFleetId = it.id })
                        SweepTab.LISTEN -> Unit // Listen is an external route owned by the radio activity.
                        SweepTab.RADAR -> Unit
                        SweepTab.TRAVEL -> Unit
                    }
                }
            }
        }
    }

    if (showInfo) InfoDialog(radioEnabled) { showInfo = false }
    shareSession?.let { session -> ShareDialog(session, { shareSession = null }) { identifiers, coordinates -> onShare(session, identifiers, coordinates); shareSession = null } }
    deleteSession?.let { session ->
        AlertDialog(
            onDismissRequest = { deleteSession = null },
            title = { Text("Delete this session?") },
            text = { Text("This removes the report from this phone. Copies you have shared remain in other apps.") },
            confirmButton = { TextButton(onClick = { onDelete(session.id); deleteSession = null; selectedSessionId = null; selectedFindingId = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteSession = null }) { Text("Keep session") } },
        )
    }
    if (clearAll) AlertDialog(
        onDismissRequest = { clearAll = false },
        title = { Text("Clear all local sessions?") },
        text = { Text("All ${history.size} saved session reports will be removed from this phone. Shared copies are unaffected.") },
        confirmButton = { TextButton(onClick = { onDeleteAll(); clearAll = false; selectedSessionId = null; selectedFindingId = null }) { Text("Clear all", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { clearAll = false }) { Text("Cancel") } },
    )
}

@Composable
private fun ScanScreen(
    session: SweepSession?, coverage: SweepCoverage, starting: Boolean, error: String?, now: Long,
    onStart: () -> Unit, onStop: () -> Unit, onFinding: (SweepFinding) -> Unit,
    onShare: (SweepSession) -> Unit, onDemo: () -> Unit, onClearDemo: () -> Unit, onSettings: () -> Unit,
) {
    val active = session?.state == SweepSessionState.ACTIVE && !session.demo
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 22.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Spacer(Modifier.height(2.dp))
        if (session?.demo == true) {
            Notice(Icons.Outlined.Science, "Sample session", "These example signals demonstrate the interface. They are not observations from your phone.", Color(0xFFF1E8D3))
        }
        ScanHero(session, starting, now, onStart, onStop, onClearDemo)
        if (error != null) Notice(Icons.Outlined.WarningAmber, "Scan needs attention", error, MaterialTheme.colorScheme.errorContainer)
        if (session?.error != null && session.error != error) Notice(Icons.Outlined.WarningAmber, "Session note", session.error, MaterialTheme.colorScheme.errorContainer)
        RadioCoverage(if (session != null && !active) session.coverage else coverage, onSettings, sample = session?.demo == true)
        if (session == null) {
            SectionHeading("A little awareness goes a long way.")
            Text("Look for nearby tracker broadcasts, camera indicators, Remote ID drones and other matching signals.", style = MaterialTheme.typography.bodyLarge, color = SweepMuted)
            CategoryGuide()
            PermissionNote()
            OutlinedButton(onClick = onDemo, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Outlined.Science, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("View sample session")
            }
        } else {
            Counts(session.findings)
            if (!active) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { onShare(session) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Outlined.IosShare, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (session.demo) "Share sample" else "Share report")
                    }
                    if (session.demo) TextButton(onClick = onClearDemo, modifier = Modifier.heightIn(min = 48.dp)) { Text("Exit sample") }
                }
            }
            FindingsList(session.findings, now, session.demo, onFinding)
            ScanLimits()
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun ScanHero(session: SweepSession?, starting: Boolean, now: Long, onStart: () -> Unit, onStop: () -> Unit, onClearDemo: () -> Unit) {
    val active = session?.state == SweepSessionState.ACTIVE && !session.demo
    val inkLight = Color(0xFFC4D6CF)
    Column(
        Modifier.fillMaxWidth().background(SweepInk, RoundedCornerShape(26.dp)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(17.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(if (active) Color(0xFFADF1CD) else inkLight, CircleShape))
                Text(
                    when { session?.demo == true -> "SAMPLE REPORT"; starting -> "PREPARING RADIOS"; active -> "SESSION IN PROGRESS"; session?.state == SweepSessionState.INTERRUPTED -> "SESSION INTERRUPTED"; session != null -> "SESSION COMPLETE"; else -> "YOUR POCKET RF SCANNER" },
                    style = MaterialTheme.typography.labelSmall, color = inkLight,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Icon(Icons.Outlined.WifiTethering, null, tint = Color(0xFF9ADBBE), modifier = Modifier.size(23.dp))
        }
        if (session == null) {
            Text("Know what’s\nbroadcasting.", color = Color.White, style = MaterialTheme.typography.headlineLarge)
            Text("A focused look at nearby wireless signals.\nPrivate by design. On your terms.", color = inkLight, style = MaterialTheme.typography.bodyMedium)
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(session.findings.size.toString(), fontSize = 64.sp, lineHeight = 67.sp, fontWeight = FontWeight.Light, letterSpacing = (-3).sp, color = Color(0xFFB4F0CD))
                Column(Modifier.padding(start = 15.dp, bottom = 6.dp)) {
                    Text(if (session.findings.size == 1) "observed radio" else "observed radios", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(if (session.demo) "Illustrative data only" else "Observed in this session", color = inkLight, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (session.demo) "SAMPLE DURATION" else if (active) "ELAPSED" else "SESSION DURATION", style = MaterialTheme.typography.labelSmall, color = inkLight)
                Text(sessionDuration(session, now), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
        }
        Button(
            onClick = if (active) onStop else { { if (session?.demo == true) onClearDemo(); onStart() } },
            enabled = !starting,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC3F1D4), contentColor = SweepInk, disabledContainerColor = Color(0xFF426760), disabledContentColor = Color.White),
            shape = RoundedCornerShape(16.dp),
        ) {
            if (starting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
            else Icon(if (active) Icons.Outlined.StopCircle else Icons.Outlined.WifiTethering, null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(if (starting) "Preparing scan…" else if (active) "End session" else if (session == null) "Start a scan" else "Start new session")
        }
        if (session == null) Text("On-demand scans · Results stay on this phone", color = inkLight, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RadioCoverage(coverage: SweepCoverage, onSettings: () -> Unit, sample: Boolean) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp)).border(1.dp, SweepLine, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (sample) "Sample radio coverage" else "Radio coverage", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = onSettings, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text("Settings", style = MaterialTheme.typography.labelMedium) }
        }
        RadioRow(Icons.Outlined.Bluetooth, "Bluetooth LE", coverage.bleReady, coverage.bleSupported, coverage.bleStatus, coverage.bleSamples)
        HorizontalDivider(color = SweepLine)
        RadioRow(Icons.Outlined.Wifi, "Wi-Fi", coverage.wifiReady, coverage.wifiSupported, coverage.wifiStatus, coverage.wifiSamples)
        HorizontalDivider(color = SweepLine)
        RadioRow(Icons.Outlined.Air, "Drone Wi-Fi NAN", coverage.wifiNanRunning, coverage.wifiNanSupported, coverage.wifiNanStatus, coverage.wifiNanSamples)
        if (coverage.droppedSamples > 0) Text("${coverage.droppedSamples} radio samples were dropped. Coverage is incomplete.", style = MaterialTheme.typography.bodySmall, color = SweepAmber)
        Text(
            "Wi-Fi vendor fields: ${if (coverage.wifiIeAvailable) "available" else "unavailable"}. Extended Bluetooth: ${when (coverage.bleExtendedSupported) { true -> "phone reports support"; false -> "unsupported"; null -> "capability unknown" }}. Wi-Fi NAN: ${if (coverage.wifiNanAvailable) "available" else "unavailable"}.",
            style = MaterialTheme.typography.bodySmall, color = SweepMuted,
        )
        if (!sample) Text("Hardware capability does not establish reception of every Remote ID broadcast mode.", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
    }
}

@Composable
private fun RadioRow(icon: ImageVector, title: String, ready: Boolean, supported: Boolean, status: String, samples: Long) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = if (ready) SweepTeal else SweepMuted, modifier = Modifier.size(23.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(status, style = MaterialTheme.typography.bodySmall, color = if (ready) SweepMuted else SweepAmber)
        }
        Column(horizontalAlignment = Alignment.End) {
            TinyTag(if (ready) "READY" else if (supported) "PARTIAL" else "UNAVAILABLE", if (ready) SweepPale else Color(0xFFF3E9D5), if (ready) SweepTeal else SweepAmber)
            if (samples > 0) Text("$samples samples", style = MaterialTheme.typography.bodySmall, color = SweepMuted, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun CategoryGuide() {
    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        listOf(
            Triple(SweepCategory.TRACKER, "Trackers", "Bluetooth tag broadcasts"),
            Triple(SweepCategory.CAMERA, "Camera indicators", "Names and radio signatures"),
            Triple(SweepCategory.DRONE, "Remote ID drones", "Broadcast IDs and reported positions"),
            Triple(SweepCategory.OTHER, "Other signals", "A broad catalog of device signatures"),
        ).forEach { (category, name, description) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CategoryGlyph(category)
                Column { Text(name, style = MaterialTheme.typography.titleSmall); Text(description, style = MaterialTheme.typography.bodySmall, color = SweepMuted) }
            }
        }
    }
}

@Composable
private fun Counts(findings: List<SweepFinding>) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        SweepCategory.entries.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                pair.forEach { category ->
                    Row(Modifier.weight(1f).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).border(1.dp, SweepLine, RoundedCornerShape(16.dp)).padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(category.icon(), null, tint = category.color(), modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text(category.shortLabel(), style = MaterialTheme.typography.labelMedium, color = SweepMuted)
                            Text(findings.count { it.category == category }.toString(), style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FindingsList(findings: List<SweepFinding>, now: Long, demo: Boolean, onFinding: (SweepFinding) -> Unit) {
    var filter by rememberSaveable { mutableStateOf<SweepCategory?>(null) }
    val filtered = findings.filter { filter == null || it.category == filter }.sortedByDescending { if (Rssi.measured(it.rssi)) it.rssi else Int.MIN_VALUE }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionHeading("Observed signals", Modifier.weight(1f))
            Text("${findings.size} TOTAL", style = MaterialTheme.typography.labelSmall, color = SweepMuted)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") })
            SweepCategory.entries.forEach { category -> FilterChip(selected = filter == category, onClick = { filter = category }, label = { Text(category.shortLabel()) }) }
        }
        if (filtered.isEmpty()) {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(19.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.WifiTetheringOff, null, tint = SweepMuted, modifier = Modifier.size(28.dp))
                Text("No matching signals observed", style = MaterialTheme.typography.titleMedium)
                Text(if (filter == null) "Some devices stay silent or use signals this phone cannot receive. This result does not establish that a room is free of cameras, microphones or trackers." else "No ${filter!!.shortLabel().lowercase(Locale.US)} match is present in this session. Try the All filter to review other observations.", style = MaterialTheme.typography.bodyMedium, color = SweepMuted)
            }
        } else {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(19.dp)).border(1.dp, SweepLine, RoundedCornerShape(19.dp))) {
                filtered.forEachIndexed { index, finding ->
                    FindingRow(finding, now, demo, { onFinding(finding) })
                    if (index < filtered.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = SweepLine)
                }
            }
        }
        if (findings.isNotEmpty()) Text(if (demo) "Sample signal strength is shown in dBm; it does not provide distance or direction." else "Observation ages are relative to the phone’s current clock. Signal strength is shown in dBm; it does not provide distance or direction.", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
    }
}

@Composable
private fun FindingRow(finding: SweepFinding, now: Long, demo: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 17.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CategoryGlyph(finding.category)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(finding.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${finding.transport} · ${if (demo) "sample" else age(now, finding.lastSeenAt)}", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
            if (finding.dronePins.isNotEmpty()) Text("${finding.dronePins.size} reported ${if (finding.dronePins.size == 1) "position" else "positions"}", style = MaterialTheme.typography.bodySmall, color = SweepTeal)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(if (Rssi.measured(finding.rssi)) "${finding.rssi}" else "—", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleSmall)
            Text(if (Rssi.measured(finding.rssi)) "dBm" else "unavailable", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = SweepMuted, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun FindingDetail(finding: SweepFinding, demo: Boolean, now: Long, onMap: (SweepPin) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (demo) TinyTag("SAMPLE SIGNAL", Color(0xFFF1E8D3), SweepAmber)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(15.dp)) {
            CategoryGlyph(finding.category, large = true)
            Column(Modifier.weight(1f)) {
                Text(finding.name, style = MaterialTheme.typography.headlineMedium)
                Text("${finding.category.longLabel()} · ${finding.transport}", style = MaterialTheme.typography.bodyMedium, color = SweepMuted, modifier = Modifier.padding(top = 5.dp))
            }
        }
        Notice(Icons.Outlined.ManageSearch, "What this observation means", finding.interpretation)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Metric("LATEST SIGNAL", if (Rssi.measured(finding.rssi)) "${finding.rssi} dBm" else "Unavailable", Modifier.weight(1f))
            Metric("OBSERVATIONS", finding.observations.toString(), Modifier.weight(1f))
        }
        SweepSignalChart(finding, demo)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeading("Observation record")
            KeyValue("First seen", dateTime(finding.firstSeenAt))
            KeyValue("Last seen", dateTime(finding.lastSeenAt))
            KeyValue(if (demo) "Observation age" else "Observation age (as of now)", if (demo) "Sample timestamp" else age(now, finding.lastSeenAt))
            if (finding.identifier.isNotBlank()) KeyValue("Radio identifier", finding.identifier, mono = true)
            if (finding.randomizedAddress) Text("This address is randomized. One physical device may appear under different radio identifiers.", style = MaterialTheme.typography.bodySmall, color = SweepAmber)
            Text("Received strength varies with walls, antenna orientation and interference. It cannot locate the device.", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeading("Why it matched")
            if (finding.signatureNames.isNotEmpty()) Text(finding.signatureNames.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            if (finding.evidence.isEmpty()) Text("No additional decoded fields are available for this observation.", style = MaterialTheme.typography.bodyMedium, color = SweepMuted)
            finding.evidence.forEach { KeyValue(it.label, it.value, mono = false) }
        }
        if (finding.droneId != null) KeyValue("Remote ID", finding.droneId, mono = true)
        if (finding.dronePins.isNotEmpty()) DronePositions(finding.dronePins, demo, now, onMap)
        if (finding.category == SweepCategory.DRONE && finding.dronePins.isEmpty()) Notice(Icons.Outlined.LocationOff, "No reported coordinates", "This radio observation did not include a usable position. No aircraft or operator location can be inferred.")
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeading("What to check next")
            val steps = finding.nextSteps.ifEmpty { listOf("Repeat a scan and compare the observed signal with devices you know are nearby.", "Use a physical inspection to verify a device’s identity and purpose.") }
            steps.forEachIndexed { index, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(25.dp).background(SweepPale, CircleShape), contentAlignment = Alignment.Center) { Text((index + 1).toString(), style = MaterialTheme.typography.labelMedium) }
                    Text(step, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        }
        ScanLimits()
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SessionsScreen(history: List<SweepSession>, now: Long, onSession: (SweepSession) -> Unit, onClear: () -> Unit, onCompare: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Your scan journal.", style = MaterialTheme.typography.headlineLarge)
        Text("Reports stay on this phone until you choose to share them.", style = MaterialTheme.typography.bodyLarge, color = SweepMuted)
        OutlinedButton(onClick = onCompare, enabled = history.count { !it.demo && it.state == SweepSessionState.COMPLETE && it.endedAt != null } >= 2, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.CompareArrows, null); Spacer(Modifier.width(8.dp)); Text("Compare two room scans")
        }
        if (history.count { !it.demo && it.state == SweepSessionState.COMPLETE && it.endedAt != null } < 2) Text("Complete two real scans to compare newly observed signals. Sample sessions are excluded.", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
        if (history.isEmpty()) {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(23.dp)).padding(26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.History, null, tint = SweepTeal, modifier = Modifier.size(36.dp))
                Text("A fresh start", style = MaterialTheme.typography.titleLarge)
                Text("End a scan to save its observations here. Sample sessions are kept separate from your scan history.", style = MaterialTheme.typography.bodyMedium, color = SweepMuted)
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${history.size} LOCAL ${if (history.size == 1) "SESSION" else "SESSIONS"}", style = MaterialTheme.typography.labelSmall, color = SweepMuted, modifier = Modifier.weight(1f))
                TextButton(onClick = onClear) { Text("Clear all", color = MaterialTheme.colorScheme.error) }
            }
            history.sortedByDescending { it.startedAt }.forEach { session ->
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(19.dp)).border(1.dp, SweepLine, RoundedCornerShape(19.dp)).clickable { onSession(session) }.padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(dateTime(session.startedAt), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Icon(Icons.Outlined.ChevronRight, null, tint = SweepMuted, modifier = Modifier.size(22.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TinyTag(if (session.state == SweepSessionState.INTERRUPTED) "INTERRUPTED" else "COMPLETE", if (session.state == SweepSessionState.INTERRUPTED) Color(0xFFF3E9D5) else SweepPale, if (session.state == SweepSessionState.INTERRUPTED) SweepAmber else SweepTeal)
                        Text(sessionDuration(session, now), style = MaterialTheme.typography.bodySmall, color = SweepMuted)
                    }
                    Text("${session.findings.size} observed ${if (session.findings.size == 1) "radio" else "radios"}", style = MaterialTheme.typography.titleLarge)
                    val counts = SweepCategory.entries.mapNotNull { category -> session.findings.count { it.category == category }.takeIf { it > 0 }?.let { "$it ${category.shortLabel().lowercase(Locale.US)}" } }
                    Text(counts.ifEmpty { listOf("No matches observed") }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = SweepMuted)
                }
            }
        }
        Notice(Icons.Outlined.Lock, "Local by default", "No sign-in or cloud upload is needed to scan. Sharing a report or opening an external map sends the information you select to another app.")
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun SessionReport(session: SweepSession, now: Long, onFinding: (SweepFinding) -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        TinyTag(if (session.demo) "SAMPLE REPORT" else if (session.state == SweepSessionState.INTERRUPTED) "INTERRUPTED SESSION" else "LOCAL REPORT", if (session.demo) Color(0xFFF1E8D3) else SweepPale, SweepInk)
        Text(dateTime(session.startedAt), style = MaterialTheme.typography.headlineMedium)
        Text("${sessionDuration(session, now)} · ${session.findings.size} observed radios", style = MaterialTheme.typography.bodyLarge, color = SweepMuted)
        Counts(session.findings)
        if (session.error != null) Notice(Icons.Outlined.WarningAmber, "Session note", session.error, MaterialTheme.colorScheme.errorContainer)
        Button(onClick = onShare, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(15.dp)) { Icon(Icons.Outlined.IosShare, null, Modifier.size(19.dp)); Spacer(Modifier.width(9.dp)); Text("Share report") }
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp)).padding(17.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            SectionHeading("Capture context")
            KeyValue("Phone", session.phone.ifBlank { "Not recorded" })
            KeyValue("Android", session.androidVersion.ifBlank { "Not recorded" })
            KeyValue("Catalog version", session.catalogVersion.toString())
            KeyValue("Bluetooth LE", session.coverage.bleStatus)
            KeyValue("Wi-Fi", session.coverage.wifiStatus)
            KeyValue("Drone Wi-Fi NAN", "${session.coverage.wifiNanStatus} · ${session.coverage.wifiNanSamples} samples")
            KeyValue("Radio samples", "${session.coverage.bleSamples} BLE · ${session.coverage.wifiSamples} Wi-Fi")
            if (session.coverage.droppedSamples > 0) KeyValue("Dropped samples", session.coverage.droppedSamples.toString())
            if (session.startBattery != null && session.endBattery != null) KeyValue("Battery", "${session.startBattery}% → ${session.endBattery}%")
        }
        FindingsList(session.findings, now, session.demo, onFinding)
        ScanLimits()
        if (!session.demo) TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(8.dp)); Text("Delete session", color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun CatalogScreen(catalog: List<Fleet>, onFleet: (Fleet) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedClass by rememberSaveable { mutableStateOf<SignatureClass?>(null) }
    val classes = catalog.groupingBy { it.kind }.eachCount().entries.sortedBy { it.key.label() }
    val filtered = remember(catalog, query, selectedClass) {
        catalog.filter { (selectedClass == null || it.kind == selectedClass) && (query.isBlank() || "${it.name} ${it.notes} ${it.kind.label()}".contains(query, ignoreCase = true)) }.sortedBy { it.name.lowercase(Locale.US) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
        item(key = "catalog_intro") {
            Column(verticalArrangement = Arrangement.spacedBy(17.dp)) {
                Text("A field guide\nto signals.", style = MaterialTheme.typography.headlineLarge)
                Text("${catalog.size} signatures · ${classes.size} device classes", style = MaterialTheme.typography.titleMedium, color = SweepTeal)
                Text("The catalog describes matching rules. A listed device has not necessarily been tested on your phone, and a match does not verify a physical device.", style = MaterialTheme.typography.bodyMedium, color = SweepMuted)
            }
        }
        item(key = "catalog_search") {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, placeholder = { Text("Search names, classes or notes") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = if (query.isNotBlank()) { { IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Clear search") } } } else null,
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            )
        }
        item(key = "catalog_classes") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                FilterChip(selected = selectedClass == null, onClick = { selectedClass = null }, label = { Text("All ${catalog.size}") })
                classes.forEach { (kind, count) -> FilterChip(selected = selectedClass == kind, onClick = { selectedClass = kind }, label = { Text("${kind.label()} $count") }) }
            }
        }
        item(key = "catalog_count") { Text("${filtered.size} ${if (filtered.size == 1) "ENTRY" else "ENTRIES"}", style = MaterialTheme.typography.labelSmall, color = SweepMuted) }
        if (filtered.isEmpty()) item(key = "catalog_empty") { Text("No catalog entries match this search.", style = MaterialTheme.typography.bodyLarge) }
        items(filtered, key = { "entry_${it.id}" }) { fleet ->
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(15.dp)).border(1.dp, SweepLine, RoundedCornerShape(15.dp)).clickable { onFleet(fleet) }.padding(15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(38.dp).background(SweepPale, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Tune, null, tint = SweepTeal, modifier = Modifier.size(20.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(fleet.name, style = MaterialTheme.typography.titleSmall)
                    Text("${fleet.kind.label()} · ${fleet.rules.size} ${if (fleet.rules.size == 1) "rule" else "rules"}", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
                }
                Icon(Icons.Outlined.ChevronRight, null, tint = SweepMuted, modifier = Modifier.size(20.dp))
            }
        }
        item(key = "catalog_attribution") { Text("Detection catalog and scan engine adapted from Fieldwatch by OffGridPete. MIT attribution preserved.", style = MaterialTheme.typography.bodySmall, color = SweepMuted) }
        item(key = "catalog_footer") { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun CatalogDetail(fleet: Fleet) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        TinyTag(fleet.kind.label().uppercase(Locale.US), SweepPale, SweepTeal)
        Text(fleet.name, style = MaterialTheme.typography.headlineLarge)
        Notice(Icons.Outlined.Rule, "Rule-based indicator", "A signature match is evidence of a radio pattern. It does not establish who owns the device, its physical identity or its purpose.")
        if (fleet.notes.isNotBlank()) { SectionHeading("Catalog notes"); Text(fleet.notes, style = MaterialTheme.typography.bodyLarge) }
        if (fleet.attentionNote.isNotBlank()) Notice(Icons.Outlined.Info, "Additional context", fleet.attentionNote, Color(0xFFF3E9D5))
        SectionHeading("Matching rules")
        Text(if (fleet.matchAny) "A match may satisfy any enabled rule below." else "A match must satisfy all enabled rules below.", style = MaterialTheme.typography.bodyMedium, color = SweepMuted)
        fleet.rules.forEachIndexed { index, rule ->
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${index + 1}. ${rule.kind.name.replace('_', ' ').lowercase(Locale.US).replaceFirstChar { it.uppercase() }}", style = MaterialTheme.typography.titleSmall)
                Text(rule.description(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                if (!rule.enabled) TinyTag("DISABLED RULE", Color(0xFFF3E9D5), SweepAmber)
            }
        }
        if (fleet.rules.isEmpty()) Text("No explicit radio rules are defined in this entry.", style = MaterialTheme.typography.bodyMedium, color = SweepMuted)
        if (fleet.minPeers > 0) KeyValue("Peer requirement", "${fleet.minPeers} peers within ${fleet.peerWindowSec}s")
        Text("Hardware validation is separate from catalog coverage. Only measured detection-spike results can establish detection rates.", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun DronePositions(pins: List<SweepPin>, demo: Boolean, now: Long, onMap: (SweepPin) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        SectionHeading("Reported positions")
        Text("Offline coordinate plot · north is up", style = MaterialTheme.typography.bodySmall, color = SweepTeal)
        Text("These coordinates came from the broadcast. Aircraft, control station, takeoff and fixed operator points have different meanings; an operator point may not be the pilot’s current location.", style = MaterialTheme.typography.bodyMedium, color = SweepMuted)
        GeographicPlot(pins)
        pins.forEach { pin ->
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).border(1.dp, SweepLine, RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(23.dp).background(pin.kind.color(), CircleShape), contentAlignment = Alignment.Center) { Text(pin.kind.symbol(), color = Color.White, style = MaterialTheme.typography.labelMedium) }
                    Text(pin.kind.label(), style = MaterialTheme.typography.titleSmall)
                }
                Text("${coordinate(pin.latitude)}, ${coordinate(pin.longitude)}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                Text(if (pin.observedAt == null) "Coordinate decoding time not available" else if (demo) "Sample coordinate time: ${dateTime(pin.observedAt)}" else "Coordinate age (as of now): ${age(now, pin.observedAt)}\nDecoded ${dateTime(pin.observedAt)}", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
                OutlinedButton(onClick = { onMap(pin) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Outlined.OpenInNew, null, Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)); Text("Open in external map")
                }
            }
        }
        Text("Opening a map shares the selected coordinate with the map app. This plot uses no online tiles and does not use your phone’s GPS.", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
    }
}

@Composable
private fun GeographicPlot(pins: List<SweepPin>) {
    val reference = pins.first().longitude
    val longitude = pins.map { pin -> reference + ((((pin.longitude - reference) + 540.0) % 360.0) - 180.0) }
    val minLon = longitude.minOrNull() ?: reference
    val maxLon = longitude.maxOrNull() ?: reference
    val minLat = pins.minOf { it.latitude }
    val maxLat = pins.maxOf { it.latitude }
    val separate = abs(maxLon - minLon) > 0.000001 || abs(maxLat - minLat) > 0.000001
    Column(Modifier.fillMaxWidth().background(Color(0xFFEDF1E8), RoundedCornerShape(19.dp)).border(1.dp, SweepLine, RoundedCornerShape(19.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("N ↑", style = MaterialTheme.typography.labelMedium, color = SweepMuted)
            Text(if (separate) "REPORTED COORDINATE EXTENT" else "ONE REPORTED LOCATION", style = MaterialTheme.typography.labelSmall, color = SweepMuted)
        }
        Canvas(Modifier.fillMaxWidth().height(175.dp).semantics { contentDescription = "Offline plot of ${pins.size} reported positions, north is up. Coordinates and point types are listed below." }) {
            val margin = 22.dp.toPx()
            val width = size.width - margin * 2
            val height = size.height - margin * 2
            repeat(5) { i ->
                val t = i / 4f
                drawLine(SweepLine, Offset(margin + width * t, margin), Offset(margin + width * t, margin + height), 1.dp.toPx())
                drawLine(SweepLine, Offset(margin, margin + height * t), Offset(margin + width, margin + height * t), 1.dp.toPx())
            }
            // Only received coordinates determine the plot. No user position or RF direction is inferred.
            pins.forEachIndexed { index, pin ->
                val x = if (abs(maxLon - minLon) < 0.000001) 0.5f else ((longitude[index] - minLon) / (maxLon - minLon)).toFloat()
                val y = if (abs(maxLat - minLat) < 0.000001) 0.5f else (1.0 - (pin.latitude - minLat) / (maxLat - minLat)).toFloat()
                val point = Offset(margin + width * x, margin + height * y)
                drawCircle(Color.White, 13.dp.toPx(), point)
                drawCircle(pin.kind.color(), 10.dp.toPx(), point)
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 11.sp.toPx(); textAlign = android.graphics.Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD }
                drawContext.canvas.nativeCanvas.drawText(pin.kind.symbol(), point.x, point.y - (paint.ascent() + paint.descent()) / 2, paint)
            }
        }
        Text("Latitude ${coordinate(minLat)} … ${coordinate(maxLat)}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = SweepMuted)
        Text(if (maxLon > 180 || minLon < -180) "Longitude extent crosses the date line" else "Longitude ${coordinate(minLon)} … ${coordinate(maxLon)}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = SweepMuted)
        Text("Axes fit the received points. This is not a street map or a distance estimate.", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
    }
}

@Composable
private fun ShareDialog(session: SweepSession, onDismiss: () -> Unit, onShare: (Boolean, Boolean) -> Unit) {
    var identifiers by remember(session.id) { mutableStateOf(false) }
    var coordinates by remember(session.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (session.demo) "Share sample report" else "Share session report") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The report includes catalog labels, matching evidence and session context. Broadcast names, identifiers and coordinates are excluded by default.")
                ShareOption("Include names & identifiers", "Advertised names, radio identifiers and drone IDs", identifiers) { identifiers = it }
                ShareOption("Include reported coordinates", "Broadcast aircraft and operator-related points", coordinates) { coordinates = it }
                Text("The receiving app gets the report you select. Review where you share it.", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
            }
        },
        confirmButton = { TextButton(onClick = { onShare(identifiers, coordinates) }) { Text("Choose sharing app") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ShareOption(title: String, explanation: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(explanation, style = MaterialTheme.typography.bodySmall, color = SweepMuted) }
    }
}

@Composable
private fun InfoDialog(radioEnabled: Boolean, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("A clearer view of the signals.") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("SWEEP is an on-demand wireless scanner for Android 10 and later. Detection and reports run on your phone.")
                if (radioEnabled) {
                    Text("Optional internet audio", style = MaterialTheme.typography.titleMedium)
                    Text("Listen plays authorized streams and recorded calls from a compatible Rdio server. Listening uses the internet; the chosen provider and any stream host see your connection, including your IP address. You can revoke listening consent in Listen. RF scans and saved scan reports stay local.")
                }
                Text("Radar accessory", style = MaterialTheme.typography.titleMedium)
                Text("Radar connects to a compatible external Valentine detector when you choose. The detector senses radar; the phone displays its reported alerts. End an RF scan before connecting. The accessory disconnects when you leave its screen. Hardware compatibility has not yet been tested.")
                Text("Scanning limits", style = MaterialTheme.typography.titleMedium)
                Text("Wi-Fi and Bluetooth are only part of the radio environment. Silent, wired, cellular and unsupported devices can be invisible. Hidden microphones cannot generally be found with a phone radio scan. A match is an indicator, and an empty scan cannot certify a safe room.")
                Text("Permissions & privacy", style = MaterialTheme.typography.titleMedium)
                Text("Android requires location access and location services for Wi-Fi scanning. Nearby-device permission is also required on newer versions. Room scans do not retain the phone’s GPS position. Travel mode records location only if you explicitly enable location correlation before starting it. Reports remain local until you share them. Opening a map passes the selected broadcast coordinate to the external map app.")
                Text("Travel observations", style = MaterialTheme.typography.titleMedium)
                Text("Travel is a separately enabled foreground mode for repeated tracker broadcasts. It never starts automatically, and Android interruptions can create gaps. Repeated sightings do not establish stalking. Changed addresses are not linked into a claimed physical identity. End travel mode before using a room scan, network discovery or radar accessory.")
                if (radioEnabled) {
                    Text("Local network discovery", style = MaterialTheme.typography.titleMedium)
                    Text("The network button requests advertised services on your authorized Wi-Fi network while its screen is visible. Live scoped discovery requires Android 13+. Results can be cached and do not list every client, confirm recording or inspect encrypted storage. Router exports are separately labeled reports. No VPN traffic capture is included.")
                }
                Text("Drones & reported positions", style = MaterialTheme.typography.titleMedium)
                Text("Only supported Remote ID broadcasts can be decoded. A takeoff point, fixed operator location or control-station coordinate is not a guaranteed current pilot location. Broadcast values can be incomplete, stale or misleading.")
                Text("Attribution", style = MaterialTheme.typography.titleMedium)
                Text("Built on Fieldwatch by OffGridPete, licensed under the MIT License. The scan engine, signature engine and device catalog retain their original attribution. Fieldwatch: https://github.com/OffGridPete/Fieldwatch")
                Text("MIT License\n\nCopyright (c) 2026 Off Grid Pete LLC\n\nPermission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the \"Software\"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:\n\nThe above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.\n\nTHE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
    )
}

@Composable
private fun PermissionNote() {
    Notice(Icons.Outlined.Lock, "A note about permissions", "Android requires location access and location services to scan Wi-Fi, plus nearby-device access on newer phones. Room scans do not retain your phone’s GPS position. Travel location correlation is a separate opt-in choice.")
}

@Composable
private fun ScanLimits() {
    Notice(Icons.Outlined.Info, "Signals are clues", "A radio match does not verify a hidden device. Silent cameras, wired microphones and unsupported broadcasts can be invisible. An empty scan cannot certify a safe room.")
}

@Composable
private fun Notice(icon: ImageVector, title: String, text: String, background: Color = Color(0xFFEBEEE4)) {
    Row(Modifier.fillMaxWidth().background(background, RoundedCornerShape(17.dp)).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Icon(icon, null, tint = SweepInk, modifier = Modifier.padding(top = 1.dp).size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall, color = SweepMuted)
        }
    }
}

@Composable
private fun CategoryGlyph(category: SweepCategory, large: Boolean = false) {
    Box(Modifier.size(if (large) 58.dp else 41.dp).background(category.color().copy(alpha = 0.10f), RoundedCornerShape(if (large) 18.dp else 13.dp)), contentAlignment = Alignment.Center) {
        Icon(category.icon(), null, tint = category.color(), modifier = Modifier.size(if (large) 28.dp else 21.dp))
    }
}

@Composable
private fun TinyTag(text: String, background: Color, foreground: Color) {
    Text(text, modifier = Modifier.background(background, RoundedCornerShape(7.dp)).padding(horizontal = 8.dp, vertical = 5.dp), color = foreground, style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun SectionHeading(text: String, modifier: Modifier = Modifier) { Text(text, style = MaterialTheme.typography.titleLarge, modifier = modifier) }

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).border(1.dp, SweepLine, RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = SweepMuted)
        Text(value, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun KeyValue(label: String, value: String, mono: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = SweepMuted)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = if (mono) FontFamily.Monospace else FontFamily.SansSerif)
    }
}

private fun SweepCategory.shortLabel(): String = when (this) { SweepCategory.TRACKER -> "Trackers"; SweepCategory.CAMERA -> "Cameras"; SweepCategory.DRONE -> "Drones"; SweepCategory.OTHER -> "Other" }
private fun SweepCategory.longLabel(): String = when (this) { SweepCategory.TRACKER -> "Tracker indicator"; SweepCategory.CAMERA -> "Camera indicator"; SweepCategory.DRONE -> "Drone broadcast"; SweepCategory.OTHER -> "Other radio signal" }
private fun SweepCategory.icon(): ImageVector = when (this) { SweepCategory.TRACKER -> Icons.Outlined.Sell; SweepCategory.CAMERA -> Icons.Outlined.Videocam; SweepCategory.DRONE -> Icons.Outlined.Flight; SweepCategory.OTHER -> Icons.Outlined.Sensors }
private fun SweepCategory.color(): Color = when (this) { SweepCategory.TRACKER -> SweepTeal; SweepCategory.CAMERA -> Color(0xFF916029); SweepCategory.DRONE -> Color(0xFF547C94); SweepCategory.OTHER -> Color(0xFF80738D) }
private fun SweepPinKind.label(): String = when (this) { SweepPinKind.AIRCRAFT -> "Reported aircraft"; SweepPinKind.CONTROL_STATION -> "Reported control station"; SweepPinKind.TAKEOFF -> "Reported takeoff point"; SweepPinKind.FIXED_OPERATOR -> "Reported fixed operator point"; SweepPinKind.UNSPECIFIED_OPERATOR -> "Reported operator point (type unknown)" }
private fun SweepPinKind.symbol(): String = when (this) { SweepPinKind.AIRCRAFT -> "A"; SweepPinKind.CONTROL_STATION -> "C"; SweepPinKind.TAKEOFF -> "T"; SweepPinKind.FIXED_OPERATOR -> "F"; SweepPinKind.UNSPECIFIED_OPERATOR -> "O" }
private fun SweepPinKind.color(): Color = when (this) { SweepPinKind.AIRCRAFT -> SweepTeal; SweepPinKind.CONTROL_STATION -> Color(0xFF547C94); SweepPinKind.TAKEOFF -> Color(0xFF976520); SweepPinKind.FIXED_OPERATOR -> Color(0xFF80738D); SweepPinKind.UNSPECIFIED_OPERATOR -> SweepMuted }
private fun MatchRule.description(): String = listOfNotNull(text.takeIf { it.isNotBlank() }, dataPrefixHex.takeIf { it.isNotBlank() }?.let { "data: $it" }, companyId.takeIf { it != 0 }?.let { "company ID: $it" }, radio?.let { "radio: ${it.name}" }).joinToString("\n").ifBlank { "Radio property rule" }
private fun dateTime(time: Long): String = SimpleDateFormat("MMM d, yyyy · h:mm:ss a", Locale.getDefault()).format(Date(time))
private fun coordinate(value: Double): String = String.format(Locale.US, "%.6f", value)
private fun duration(millis: Long): String { val seconds = (millis.coerceAtLeast(0) / 1000); return if (seconds >= 3600) String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60) else String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60) }
private fun sessionDuration(session: SweepSession, now: Long): String = when {
    session.state == SweepSessionState.ACTIVE -> duration(now - session.startedAt)
    session.endedAt != null -> duration(session.endedAt - session.startedAt)
    else -> "Duration unknown"
}
private fun age(now: Long, then: Long): String { val seconds = ((now - then).coerceAtLeast(0) / 1000); return when { then > now -> "timestamp ahead of phone clock"; seconds < 2 -> "just now"; seconds < 60 -> "${seconds}s ago"; seconds < 3600 -> "${seconds / 60}m ago"; seconds < 86400 -> "${seconds / 3600}h ago"; else -> "${seconds / 86400}d ago" } }
