package app.fieldwatch.sweep.radio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.fieldwatch.sweep.SweepAmber
import app.fieldwatch.sweep.SweepCream
import app.fieldwatch.sweep.SweepInk
import app.fieldwatch.sweep.SweepLine
import app.fieldwatch.sweep.SweepMuted
import app.fieldwatch.sweep.SweepPale
import app.fieldwatch.sweep.SweepTeal
import java.net.URI
import java.util.Locale
import java.util.UUID

/** Optional internet audio UI. RF scanning remains a separate, local feature. */
@Composable
fun ScannerRadioScreen(
    feeds: List<ScannerFeed>,
    playback: RadioPlaybackState,
    networkConsent: Boolean,
    error: String?,
    loading: Boolean,
    onBack: () -> Unit,
    onSave: (ScannerFeed) -> Unit,
    onDelete: (String) -> Unit,
    onFavorite: (String) -> Unit,
    onPlay: (String) -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onConsent: (Boolean) -> Unit,
    onDeleteAll: () -> Unit,
    onRdio: () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ScannerFeed?>(null) }
    var clearAll by rememberSaveable { mutableStateOf(false) }
    var revoke by rememberSaveable { mutableStateOf(false) }
    var consentDialog by rememberSaveable { mutableStateOf(false) }
    // A process/activity recovery must not replay a previously pending Play request.
    var pendingFeedId by remember { mutableStateOf<String?>(null) }
    val playingFeed = feeds.find { it.id == playback.feedId }
    val filtered = remember(feeds, query, favoritesOnly) {
        feeds.filter { (!favoritesOnly || it.favorite) && (query.isBlank() || "${it.name} ${it.region} ${it.category}".contains(query, ignoreCase = true)) }
            .sortedWith(compareByDescending<ScannerFeed> { it.favorite }.thenBy { it.name.lowercase(Locale.US) })
    }
    // Consent is persisted by the activity. Wait for that state before requesting playback.
    LaunchedEffect(networkConsent, pendingFeedId) {
        val id = pendingFeedId
        if (networkConsent && id != null) { pendingFeedId = null; onPlay(id) }
    }
    BackHandler { onBack() }

    fun play(feed: ScannerFeed) {
        if (networkConsent) onPlay(feed.id)
        else { pendingFeedId = feed.id; consentDialog = true }
    }

    Scaffold(
        containerColor = SweepCream,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.ArrowBack, "Back to RF scanner") }
                Text("Listen", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text("INTERNET AUDIO", style = MaterialTheme.typography.labelSmall, color = SweepTeal, modifier = Modifier.background(SweepPale, RoundedCornerShape(7.dp)).padding(8.dp))
            }
        },
        bottomBar = {
            if (playback.feedId != null || playback.status != RadioPlaybackStatus.IDLE) {
                PlaybackPanel(playingFeed, playback, onPause, onStop, { playingFeed?.let { play(it) } })
            }
        },
    ) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item(key = "radio_intro") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Streams\nyou choose.", style = MaterialTheme.typography.headlineLarge)
                    Text("Add an audio stream from a provider who authorizes you to listen. Your saved feed links stay on this phone.", style = MaterialTheme.typography.bodyLarge, color = SweepMuted)
                    Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Add a feed")
                    }
                }
            }
            item(key = "radio_consent") {
                Column(Modifier.fillMaxWidth().background(if (networkConsent) SweepPale else Color(0xFFEBEEE4), RoundedCornerShape(18.dp)).padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Icon(if (networkConsent) Icons.Outlined.Public else Icons.Outlined.PrivacyTip, null, tint = SweepTeal, modifier = Modifier.size(21.dp))
                        Text(if (networkConsent) "Internet audio allowed" else "Choose before connecting", style = MaterialTheme.typography.titleSmall)
                    }
                    Text("Listening uses the internet. The selected stream provider and its hosts receive your IP address and connection details. RF scans and scan reports stay local.", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
                    TextButton(onClick = { if (networkConsent) revoke = true else consentDialog = true }, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (networkConsent) "Revoke listening consent" else "Allow internet audio")
                    }
                }
            }
            item(key = "rdio_connection") {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp)).padding(17.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Rdio Scanner", style = MaterialTheme.typography.titleMedium)
                    Text("Browse and play recorded calls from your operator's Rdio server with the SWEEP HTTP extension installed.", style = MaterialTheme.typography.bodyMedium, color = SweepMuted)
                    OutlinedButton(onClick = onRdio, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(13.dp)) {
                        Icon(Icons.Outlined.Radio, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("Connect to Rdio")
                    }
                }
            }
            error?.let { message -> item(key = "radio_error") { RadioError(safeMessage(message)) } }
            item(key = "radio_search") {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Search name, region or category") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = if (query.isNotBlank()) { { IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Clear search") } } } else null,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                )
            }
            item(key = "radio_list_heading") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${feeds.size} SAVED ${if (feeds.size == 1) "FEED" else "FEEDS"}", style = MaterialTheme.typography.labelSmall, color = SweepMuted, modifier = Modifier.weight(1f))
                    FilterChip(selected = favoritesOnly, onClick = { favoritesOnly = !favoritesOnly }, label = { Text("Favorites") }, leadingIcon = { Icon(Icons.Outlined.StarOutline, null, Modifier.size(16.dp)) })
                }
            }
            if (loading) item(key = "radio_loading") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(21.dp), strokeWidth = 2.dp)
                    Text("Loading saved feeds…", style = MaterialTheme.typography.bodyMedium, color = SweepMuted)
                }
            }
            if (!loading && feeds.isEmpty()) item(key = "radio_empty") {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(22.dp)).padding(23.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Headphones, null, tint = SweepTeal, modifier = Modifier.size(35.dp))
                    Text("Your first feed starts here.", style = MaterialTheme.typography.titleLarge)
                    Text("Add an authorized HTTPS audio-stream link. There is no built-in feed directory. This feature streams internet audio; it does not tune the phone to police or emergency-service radio frequencies.", style = MaterialTheme.typography.bodyMedium, color = SweepMuted)
                }
            } else if (!loading && filtered.isEmpty()) item(key = "radio_no_results") {
                Text("No saved feeds match these filters.", style = MaterialTheme.typography.bodyLarge, color = SweepMuted)
            }
            items(filtered, key = { "feed_${it.id}" }) { feed ->
                FeedCard(feed, playback, onFavorite = { onFavorite(feed.id) }, onDelete = { deleting = feed }, onPlay = { play(feed) }, onPause = onPause, onStop = onStop)
            }
            if (feeds.isNotEmpty()) item(key = "radio_clear") {
                TextButton(onClick = { clearAll = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("Clear saved feeds", color = MaterialTheme.colorScheme.error)
                }
            }
            item(key = "radio_limits") {
                Text("Stream availability and delay depend on the provider. Broadcast audio can be delayed, interrupted or unavailable.", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
            }
        }
    }

    if (adding) AddFeedDialog({ adding = false }) { feed -> onSave(feed); adding = false }
    if (consentDialog) AlertDialog(
        onDismissRequest = { consentDialog = false; pendingFeedId = null },
        title = { Text("Allow internet audio?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                pendingFeedId?.let { id -> feeds.find { it.id == id } }?.let { feed ->
                    Text("Selected feed: ${feed.name}", style = MaterialTheme.typography.titleSmall)
                    providerHost(feed)?.let { Text("Stream host: $it", style = MaterialTheme.typography.bodySmall, color = SweepMuted) }
                }
                Text("Playing a feed connects over the internet. The provider and any stream hosts receive your IP address and connection details and may retain logs. Mobile data charges may apply.")
                Text("This choice allows internet audio until you revoke it in Listen. RF scans remain local.", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
            }
        },
        confirmButton = { TextButton(onClick = { consentDialog = false; onConsent(true) }) { Text("Allow audio") } },
        dismissButton = { TextButton(onClick = { consentDialog = false; pendingFeedId = null }) { Text("Cancel") } },
    )
    if (revoke) AlertDialog(
        onDismissRequest = { revoke = false },
        title = { Text("Revoke listening consent?") },
        text = { Text("Audio playback will stop. Your saved feed links remain on this phone. You can allow internet audio again when you choose to listen.") },
        confirmButton = { TextButton(onClick = { pendingFeedId = null; onStop(); onConsent(false); revoke = false }) { Text("Stop & revoke") } },
        dismissButton = { TextButton(onClick = { revoke = false }) { Text("Cancel") } },
    )
    deleting?.let { feed -> AlertDialog(
        onDismissRequest = { deleting = null },
        title = { Text("Delete this feed?") },
        text = { Text("“${feed.name}” will be removed from the saved links on this phone.") },
        confirmButton = { TextButton(onClick = { if (playback.feedId == feed.id) onStop(); onDelete(feed.id); deleting = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep feed") } },
    ) }
    if (clearAll) AlertDialog(
        onDismissRequest = { clearAll = false },
        title = { Text("Clear all saved feeds?") },
        text = { Text("This removes all ${feeds.size} saved links from this phone and stops playback.") },
        confirmButton = { TextButton(onClick = { pendingFeedId = null; onStop(); onDeleteAll(); clearAll = false }) { Text("Clear all", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { clearAll = false }) { Text("Cancel") } },
    )
}

@Composable
private fun FeedCard(feed: ScannerFeed, playback: RadioPlaybackState, onFavorite: () -> Unit, onDelete: () -> Unit, onPlay: () -> Unit, onPause: () -> Unit, onStop: () -> Unit) {
    val selected = playback.feedId == feed.id
    val playing = selected && playback.status == RadioPlaybackStatus.PLAYING
    val buffering = selected && playback.status == RadioPlaybackStatus.BUFFERING
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(19.dp)).border(1.dp, if (selected) SweepTeal else SweepLine, RoundedCornerShape(19.dp)).padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Box(Modifier.size(40.dp).background(SweepPale, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Headphones, null, tint = SweepTeal, modifier = Modifier.size(22.dp)) }
            Column(Modifier.weight(1f)) {
                Text(feed.name, style = MaterialTheme.typography.titleMedium)
                Text(listOf(feed.region, feed.category).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = SweepMuted)
            }
            IconButton(onClick = onFavorite, modifier = Modifier.size(48.dp)) {
                Icon(if (feed.favorite) Icons.Outlined.Star else Icons.Outlined.StarOutline, if (feed.favorite) "Remove ${feed.name} from favorites" else "Favorite ${feed.name}", tint = if (feed.favorite) SweepAmber else SweepMuted)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (selected) playback.status.label() else "Saved audio stream", style = MaterialTheme.typography.bodySmall, color = if (selected) SweepTeal else SweepMuted, modifier = Modifier.weight(1f))
            IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.DeleteOutline, "Delete ${feed.name}", tint = SweepMuted, modifier = Modifier.size(21.dp)) }
            FilledTonalButton(onClick = if (playing) onPause else if (buffering) onStop else onPlay, shape = RoundedCornerShape(13.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                if (buffering) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                else Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp)); Text(if (playing) "Pause" else if (buffering) "Stop" else if (selected && playback.status == RadioPlaybackStatus.ERROR) "Retry" else "Play")
            }
        }
    }
}

@Composable
private fun PlaybackPanel(feed: ScannerFeed?, playback: RadioPlaybackState, onPause: () -> Unit, onStop: () -> Unit, onPlay: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(SweepInk).navigationBarsPadding().padding(horizontal = 22.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            if (playback.status == RadioPlaybackStatus.BUFFERING) CircularProgressIndicator(Modifier.size(17.dp), color = Color(0xFFC3F1D4), strokeWidth = 2.dp)
            else Icon(Icons.Outlined.GraphicEq, null, tint = Color(0xFFC3F1D4), modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(feed?.name ?: "Stream playback", style = MaterialTheme.typography.titleSmall, color = Color.White)
                Text(playback.status.label(), style = MaterialTheme.typography.bodySmall, color = Color(0xFFBAD2C8))
            }
            if (playback.status != RadioPlaybackStatus.BUFFERING && feed != null) IconButton(onClick = if (playback.status == RadioPlaybackStatus.PLAYING) onPause else onPlay, modifier = Modifier.size(48.dp)) {
                Icon(if (playback.status == RadioPlaybackStatus.PLAYING) Icons.Outlined.PauseCircle else Icons.Outlined.PlayCircle, if (playback.status == RadioPlaybackStatus.PLAYING) "Pause audio" else "Play audio", tint = Color(0xFFC3F1D4), modifier = Modifier.size(31.dp))
            }
            IconButton(onClick = onStop, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.StopCircle, "Stop audio", tint = Color.White, modifier = Modifier.size(28.dp)) }
        }
        if (playback.status == RadioPlaybackStatus.ERROR || playback.error != null) Text("Audio could not be played. Check the feed link, provider availability and connection.", style = MaterialTheme.typography.bodySmall, color = Color(0xFFF1D4CC))
    }
}

@Composable
private fun AddFeedDialog(onDismiss: () -> Unit, onSave: (ScannerFeed) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var region by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("Public safety") }
    var url by rememberSaveable { mutableStateOf("") }
    var authorized by rememberSaveable { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add an authorized feed") },
        text = {
            Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Use a direct HTTPS audio-stream link from a provider who allows you to listen. A normal webpage link may not contain playable audio.", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
                OutlinedTextField(name, { name = it; validationError = null }, label = { Text("Feed name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                OutlinedTextField(region, { region = it; validationError = null }, label = { Text("Region (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                OutlinedTextField(category, { category = it; validationError = null }, label = { Text("Category") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                OutlinedTextField(url, { url = it; validationError = null }, label = { Text("HTTPS audio-stream URL") }, placeholder = { Text("https://…") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), shape = RoundedCornerShape(12.dp))
                Row(Modifier.fillMaxWidth().clickable { authorized = !authorized; validationError = null }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(authorized, { authorized = it; validationError = null })
                    Text("The stream operator authorizes me to listen to this feed.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
                validationError?.let { RadioError(it) }
                Text("Saving a link does not connect to the provider. Playback requires your separate internet-audio consent.", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!authorized) { validationError = "Confirm that the stream operator authorizes you to listen." }
                else {
                    try {
                        val feed = ScannerFeedValidator.validate(ScannerFeed(id = UUID.randomUUID().toString(), name = name, url = url, region = region, category = category))
                        onSave(feed)
                    } catch (failure: IllegalArgumentException) {
                        validationError = safeMessage(failure.message ?: "Check the feed name and HTTPS audio-stream link.")
                    }
                }
            }) { Text("Save feed") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RadioError(message: String) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(14.dp)).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(19.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
    }
}

private fun RadioPlaybackStatus.label(): String = when (this) {
    RadioPlaybackStatus.IDLE -> "Stopped"
    RadioPlaybackStatus.BUFFERING -> "Buffering audio"
    RadioPlaybackStatus.PLAYING -> "Playing internet audio"
    RadioPlaybackStatus.PAUSED -> "Paused"
    RadioPlaybackStatus.ERROR -> "Unable to play audio"
}
private fun providerHost(feed: ScannerFeed): String? = runCatching { URI(feed.url).host }.getOrNull()?.takeIf { it.isNotBlank() }
private fun safeMessage(message: String): String = message.replace(Regex("[a-z][a-z0-9+.-]*://\\S+", RegexOption.IGNORE_CASE), "[stream link]").take(260)
