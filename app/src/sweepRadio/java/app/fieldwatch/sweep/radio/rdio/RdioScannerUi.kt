package app.fieldwatch.sweep.radio.rdio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.fieldwatch.sweep.*
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun RdioScannerScreen(
    state: RdioScreenState, networkConsent: Boolean, loading: Boolean,
    onBack: () -> Unit, onServer: (String) -> Unit, onPin: (String) -> Unit,
    onConnect: () -> Unit, onRefresh: () -> Unit, onDisconnect: () -> Unit,
    onPlay: (Long) -> Unit, onStop: () -> Unit, onPause: () -> Unit, onResume: () -> Unit,
    onConsent: (Boolean) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var consentDialog by remember { mutableStateOf(false) }
    var pendingConnect by remember { mutableStateOf<Long?>(null) }
    val labels = remember(state.config) { state.config?.systems.orEmpty().associateBy { it.id } }
    fun callLabel(call: RdioCall): String {
        val system = labels[call.system]
        val group = system?.talkgroups?.find { it.id == call.talkgroup }
        return listOf(system?.label?.ifBlank { "System ${call.system}" } ?: "System ${call.system}",
            group?.label?.ifBlank { group.name.ifBlank { "Channel ${call.talkgroup}" } } ?: "Channel ${call.talkgroup}").joinToString(" · ")
    }
    val calls = state.calls.filter { query.isBlank() || callLabel(it).contains(query, true) }
    LaunchedEffect(networkConsent, state.connectionIntent) {
        if (pendingConnect != null && pendingConnect != state.connectionIntent) pendingConnect = null
        if (networkConsent && pendingConnect == state.connectionIntent) { pendingConnect = null; onConnect() }
    }
    BackHandler { onBack() }
    Scaffold(
        containerColor = SweepCream,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.ArrowBack, "Back to Listen") }
                Text("Rdio Scanner", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text("RECORDED CALLS", style = MaterialTheme.typography.labelSmall, color = SweepTeal)
            }
        },
    ) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).imePadding(), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Your radio server.\nYour choice.", style = MaterialTheme.typography.headlineLarge)
                    Text("Connect to an operator-authorized Rdio server with the SWEEP HTTP extension. A stock Rdio server needs that extension before this screen can play calls.", color = SweepMuted, style = MaterialTheme.typography.bodyLarge)
                }
            }
            item {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp)).padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(state.server, { if (it.length <= 2048) onServer(it) }, label = { Text("HTTPS server address") },
                        placeholder = { Text("https://radio.example.com") }, enabled = !state.connected && !state.busy,
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(state.pin, { if (it.length <= 128) onPin(it) }, label = { Text("Listener access code (if required)") },
                        enabled = !state.connected && !state.busy, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                    Text("The address and code stay in this open screen. The code is cleared when you disconnect or leave. Calls play one at a time while this screen is open.", color = SweepMuted, style = MaterialTheme.typography.bodySmall)
                    if (state.connected || state.busy) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = onRefresh, enabled = !state.busy && networkConsent, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Refresh calls") }
                            OutlinedButton(onClick = { pendingConnect = null; onDisconnect() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Disconnect") }
                        }
                    } else Button(onClick = { if (networkConsent) onConnect() else { pendingConnect = state.connectionIntent; consentDialog = true } },
                        enabled = !loading && state.server.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Connect") }
                    if (state.busy) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Requesting from your server…", color = SweepMuted)
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().background(SweepPale, RoundedCornerShape(18.dp)).padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (networkConsent) "Internet listening allowed" else "Internet listening is off", style = MaterialTheme.typography.titleSmall)
                    Text("Connecting shares your IP address and listener code with the selected server. RF findings and phone location are not sent. This uses the same listening consent as saved streams.", color = SweepMuted, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        if (networkConsent) { pendingConnect = null; onConsent(false) }
                        else consentDialog = true
                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (networkConsent) "Disconnect & revoke consent" else "Allow internet listening") }
                }
            }
            state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) } }
            if (state.connected) {
                item {
                    OutlinedTextField(query, { query = it.take(200) }, singleLine = true, label = { Text("Filter system or channel") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth())
                }
                item { Text("${state.calls.size} RECENT CALLS · REFRESH TO UPDATE", color = SweepMuted, style = MaterialTheme.typography.labelSmall) }
                if (calls.isEmpty()) item { Text(if (state.calls.isEmpty()) "No accessible calls were returned by this server." else "No calls match this filter.", color = SweepMuted) }
                items(calls, key = { it.id }) { call ->
                    val selected = state.selectedCallId == call.id
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(callLabel(call), style = MaterialTheme.typography.titleMedium)
                        Text(callTime(call.dateTime), color = SweepMuted, style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(if (selected) when { state.buffering -> "Buffering…"; state.playing -> "Playing call"; else -> "Paused" } else "Recorded call", color = SweepMuted, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            if (selected) IconButton(onClick = onStop) { Icon(Icons.Outlined.Stop, "Stop call") }
                            FilledTonalButton(onClick = { if (selected) { if (state.playing) onPause() else onResume() } else onPlay(call.id) },
                                enabled = !state.busy && networkConsent, modifier = Modifier.heightIn(min = 48.dp)) {
                                Icon(if (selected && state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null)
                                Text(if (selected && state.playing) "Pause" else "Play")
                            }
                        }
                    }
                }
            }
            item { Text("Audio comes from the server's scanner recordings. Coverage, delays and available channels depend on its receiver and access rules.", style = MaterialTheme.typography.bodySmall, color = SweepMuted) }
        }
    }
    if (consentDialog) AlertDialog(
        onDismissRequest = { consentDialog = false; pendingConnect = null },
        title = { Text("Allow internet listening?") },
        text = { Text("Rdio requests connect to the server you enter. It receives your IP address and listener code and may keep connection logs. Mobile data charges may apply. This consent also allows the saved streams in Listen until revoked.") },
        confirmButton = { TextButton(onClick = { consentDialog = false; onConsent(true) }) { Text("Allow listening") } },
        dismissButton = { TextButton(onClick = { consentDialog = false; pendingConnect = null }) { Text("Cancel") } },
    )
}

private fun callTime(value: String): String = runCatching {
    OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm:ss a"))
}.getOrDefault(value.take(80))
