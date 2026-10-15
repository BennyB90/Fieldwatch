package app.fieldwatch.sweep.radio.rdio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.Log
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.fieldwatch.sweep.SweepTheme
import app.fieldwatch.sweep.radio.ScannerRadioApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An explicitly connected, foreground-only listener. PINs and audio never go to disk. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RdioScannerActivity : ComponentActivity() {
    private val repository get() = (application as ScannerRadioApplication).radio
    private val state = MutableStateFlow(RdioScreenState())
    private var client: RdioHttpClient? = null
    private var player: ExoPlayer? = null
    private var request: Job? = null
    private var generation = 0L
    private var foreground = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.setLogLevel(Log.LOG_LEVEL_OFF)
        lifecycleScope.launch {
            repository.networkConsent.collect { allowed -> if (!allowed) disconnect() }
        }
        setContent {
            val current by state.collectAsStateWithLifecycle()
            val consent by repository.networkConsent.collectAsStateWithLifecycle()
            val loading by repository.loading.collectAsStateWithLifecycle()
            SweepTheme {
                RdioScannerScreen(
                    state = current, networkConsent = consent, loading = loading, onBack = ::finish,
                    onServer = { state.value = state.value.copy(server = it) },
                    onPin = { state.value = state.value.copy(pin = it) },
                    onConnect = ::connect, onRefresh = ::refresh, onDisconnect = ::disconnect,
                    onPlay = ::playCall, onStop = ::stopPlayback,
                    onPause = { player?.pause() }, onResume = { if (allowed()) player?.play() },
                    onConsent = { if (!it) disconnect(); repository.setConsent(it) },
                )
            }
        }
    }

    override fun onStart() { super.onStart(); foreground = true }
    override fun onStop() {
        foreground = false
        disconnect()
        super.onStop()
    }

    private fun allowed() = foreground && !repository.loading.value && repository.networkConsent.value

    private fun connect() {
        if (!allowed()) return
        val server = state.value.server
        val pin = state.value.pin
        disconnect(clearPin = false)
        runCatching { RdioHttpClient(server, pin, ::allowed) }.onSuccess { next ->
            client = next
            fetch {
                val config = next.config()
                val calls = next.calls()
                val update: (RdioScreenState) -> RdioScreenState = { old -> old.copy(config = config, calls = calls.calls, connected = true) }
                update
            }
        }.onFailure { showError(it) }
    }

    private fun refresh() {
        val current = client ?: return
        if (!allowed()) return
        stopAudio()
        fetch {
            val calls = current.calls()
            val update: (RdioScreenState) -> RdioScreenState = { old -> old.copy(calls = calls.calls, connected = true) }
            update
        }
    }

    private fun fetch(work: () -> ((RdioScreenState) -> RdioScreenState)) {
        request?.cancel()
        val ticket = ++generation
        state.value = state.value.copy(busy = true, error = null)
        request = lifecycleScope.launch {
            try {
                val update = withContext(Dispatchers.IO) { work() }
                if (ticket == generation && allowed()) state.value = update(state.value).copy(busy = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Exception) { if (ticket == generation && foreground) showError(problem) }
        }
    }

    private fun playCall(id: Long) {
        val current = client ?: return
        if (!allowed() || state.value.busy || state.value.calls.none { it.id == id }) return
        stopAudio()
        request?.cancel()
        val ticket = ++generation
        state.value = state.value.copy(busy = true, error = null)
        request = lifecycleScope.launch {
            try {
                val audio = withContext(Dispatchers.IO) { current.audio(id) }
                if (ticket != generation || !allowed()) return@launch
                val nextPlayer = ExoPlayer.Builder(this@RdioScannerActivity)
                    .setMediaSourceFactory(DefaultMediaSourceFactory { ByteArrayDataSource(audio.bytes) })
                    .build()
                player = nextPlayer
                nextPlayer.addListener(object : Player.Listener {
                    override fun onEvents(source: Player, events: Player.Events) {
                        if (player !== nextPlayer) return
                        state.value = state.value.copy(playing = source.isPlaying, buffering = source.playbackState == Player.STATE_BUFFERING)
                        if (source.playbackState == Player.STATE_ENDED) stopAudio()
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        if (player === nextPlayer) {
                            stopAudio()
                            state.value = state.value.copy(error = "This call could not be played. Check the server's audio format.")
                        }
                    }
                })
                state.value = state.value.copy(busy = false, selectedCallId = id)
                nextPlayer.setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
                nextPlayer.setHandleAudioBecomingNoisy(true)
                nextPlayer.setMediaItem(MediaItem.Builder().setUri("memory://rdio/call/$id").setMimeType(audio.mimeType).build())
                nextPlayer.prepare()
                nextPlayer.play()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Exception) { if (ticket == generation && foreground) showError(problem) }
        }
    }

    private fun stopAudio() {
        val previous = player
        player = null
        previous?.release()
        state.value = state.value.copy(selectedCallId = null, playing = false, buffering = false)
    }

    private fun stopPlayback() {
        ++generation
        request?.cancel(); request = null
        client?.cancelRequests()
        stopAudio()
        state.value = state.value.copy(busy = false)
    }

    private fun disconnect(clearPin: Boolean = true) {
        ++generation
        request?.cancel(); request = null
        client?.close(); client = null
        stopAudio()
        state.value = state.value.copy(pin = if (clearPin) "" else state.value.pin, connected = false,
            config = null, calls = emptyList(), busy = false, error = null, connectionIntent = state.value.connectionIntent + 1)
    }

    private fun showError(problem: Throwable) {
        state.value = state.value.copy(busy = false, error = when (problem) {
            is RdioException -> problem.message ?: "The Rdio server did not accept this request."
            is IllegalArgumentException -> "Enter a valid HTTPS server address and listener access code."
            else -> "The Rdio server could not be reached. Check the address, extension and access code."
        })
    }
}

data class RdioScreenState(
    val server: String = "", val pin: String = "", val connected: Boolean = false,
    val config: RdioConfig? = null, val calls: List<RdioCall> = emptyList(),
    val busy: Boolean = false, val selectedCallId: Long? = null,
    val playing: Boolean = false, val buffering: Boolean = false, val error: String? = null,
    val connectionIntent: Long = 0,
)
