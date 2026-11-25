package app.fieldwatch.sweep

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun SweepComparisonScreen(history: List<SweepSession>, onFinding: (SweepSession, SweepFinding) -> Unit) {
    val eligible = history.filter { !it.demo && it.state == SweepSessionState.COMPLETE && it.endedAt != null }.sortedBy { it.startedAt }
    var baselineId by rememberSaveable { mutableStateOf(eligible.dropLast(1).lastOrNull()?.id) }
    var currentId by rememberSaveable { mutableStateOf(eligible.lastOrNull()?.id) }
    val baseline = eligible.find { it.id == baselineId }
    val current = eligible.find { it.id == currentId }
    val result = remember(baseline, current) { if (baseline != null && current != null) SweepComparisons.compare(baseline, current) else null }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("What changed between scans?", style = MaterialTheme.typography.headlineMedium)
        Text("Choose two scans of the space you want to compare. Newly observed does not mean newly installed; not reobserved does not mean removed.", color = SweepMuted)
        SessionChoice("Earlier baseline", eligible, baselineId) { baselineId = it }
        SessionChoice("Later comparison", eligible, currentId) { currentId = it }
        if (result == null) {
            Text("Choose two saved completed scans. Deleted, interrupted and sample sessions cannot be compared.")
        } else if (!result.eligible) {
            Text(result.unavailableReason ?: "These scans cannot be compared.", color = SweepAmber)
        } else {
            Text("Recorded coverage", style = MaterialTheme.typography.titleMedium)
            result.coverage.forEach { row ->
                val label = when (row.transport) { "BLE" -> "Bluetooth"; "WIFI" -> "Wi-Fi access points"; else -> "Drone Wi-Fi NAN" }
                Text("$label: ${row.baselineSamples} → ${row.currentSamples} samples${if (row.comparable) "" else " · limited comparison"}", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
            }
            ComparisonGroup("Newly observed", result.newlyObserved, current!!, onFinding)
            ComparisonGroup("Not reobserved", result.notReobserved, baseline!!, onFinding)
            Text("Observed in both (${result.shared.size})", style = MaterialTheme.typography.titleMedium)
            if (result.shared.isEmpty()) Text("No comparable advertised addresses were observed in both scans.", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
            result.shared.forEach { pair ->
                Column(Modifier.fillMaxWidth().clickable { onFinding(current, pair.current) }.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(pair.current.name, style = MaterialTheme.typography.titleSmall)
                    Text("${pair.current.transport} · ${pair.current.identifier}", style = MaterialTheme.typography.bodySmall)
                    Text(pair.identityNote, style = MaterialTheme.typography.bodySmall, color = SweepMuted)
                    if (!pair.evidenceCompatible) Text("The matched evidence or category changed. Review both reports before interpreting this.", style = MaterialTheme.typography.bodySmall, color = SweepAmber)
                    if (!pair.coverageComparable) Text("Reception coverage differs or is incomplete.", style = MaterialTheme.typography.bodySmall, color = SweepAmber)
                }
            }
            if (result.unresolved.isNotEmpty()) {
                Text("Identity could not be compared (${result.unresolved.size})", style = MaterialTheme.typography.titleMedium)
                result.unresolved.forEach { row ->
                    val source = if (row.side == SweepComparisonSide.BASELINE) baseline else current
                    Column(Modifier.fillMaxWidth().clickable { onFinding(source, row.finding) }.padding(vertical = 6.dp)) {
                        Text("${row.finding.name} · ${if (row.side == SweepComparisonSide.BASELINE) "baseline" else "later scan"}", style = MaterialTheme.typography.titleSmall)
                        Text(row.reason, style = MaterialTheme.typography.bodySmall, color = SweepMuted)
                    }
                }
            }
            HorizontalDivider()
            Text("Interpretation limits", style = MaterialTheme.typography.titleMedium)
            result.limitations.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = SweepMuted) }
        }
    }
}

@Composable
private fun SessionChoice(label: String, sessions: List<SweepSession>, selectedId: String?, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val clock = remember { SimpleDateFormat("MMM d, yyyy HH:mm:ss", Locale.getDefault()) }
    fun title(session: SweepSession) = "${clock.format(Date(session.startedAt))} · ${session.findings.size} ${if (session.findings.size == 1) "signal" else "signals"}"
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = sessions.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(sessions.find { it.id == selectedId }?.let(::title) ?: "Choose a saved scan")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                sessions.forEach { session -> DropdownMenuItem(text = { Text(title(session)) }, onClick = { onSelect(session.id); expanded = false }) }
            }
        }
    }
}

@Composable
private fun ComparisonGroup(title: String, entries: List<SweepComparisonEntry>, session: SweepSession, onFinding: (SweepSession, SweepFinding) -> Unit) {
    Text("$title (${entries.size})", style = MaterialTheme.typography.titleMedium)
    if (entries.isEmpty()) Text("No comparable addresses in this group.", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
    entries.forEach { entry ->
        Column(Modifier.fillMaxWidth().clickable { onFinding(session, entry.finding) }.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.finding.name, style = MaterialTheme.typography.titleSmall)
            Text("${entry.finding.transport} · ${entry.finding.identifier}", style = MaterialTheme.typography.bodySmall)
            Text(entry.identityNote, style = MaterialTheme.typography.bodySmall, color = SweepMuted)
            if (!entry.coverageComparable) Text("The other scan has limited reception evidence; a missed observation is possible.", style = MaterialTheme.typography.bodySmall, color = SweepAmber)
        }
    }
}
