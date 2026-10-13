package app.fieldwatch.sweep.radio

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.fieldwatch.sweep.SweepTheme
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow

class ScannerRadioActivity : ComponentActivity() {
    private val repository get() = (application as ScannerRadioApplication).radio
    private val playback = MutableStateFlow(RadioPlaybackState())
    private val connectionError = MutableStateFlow<String?>(null)
    private val connected = MutableStateFlow(false)
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { updatePlayback() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val saved by repository.preferences.collectAsStateWithLifecycle()
            val allowed by repository.networkConsent.collectAsStateWithLifecycle()
            val loading by repository.loading.collectAsStateWithLifecycle()
            val error by repository.error.collectAsStateWithLifecycle()
            val connection by connectionError.collectAsStateWithLifecycle()
            val playerConnected by connected.collectAsStateWithLifecycle()
            val playing by playback.collectAsStateWithLifecycle()
            SweepTheme {
                ScannerRadioScreen(
                    feeds = saved.feeds, playback = playing, networkConsent = allowed, error = error ?: connection,
                    loading = loading || (!playerConnected && connection == null), onBack = ::finish,
                    onSave = { feed ->
                        if (controller?.currentMediaItem?.mediaId == feed.id) stopPlayback()
                        repository.save(feed)
                    },
                    onDelete = { id -> if (controller?.currentMediaItem?.mediaId == id) stopPlayback(); repository.delete(id) },
                    onFavorite = repository::favorite, onPlay = ::playFeed,
                    onPause = { controller?.pause() }, onStop = ::stopPlayback,
                    onConsent = { allowedNow -> if (!allowedNow) stopPlayback(); repository.setConsent(allowedNow) },
                    onDeleteAll = { stopPlayback(); repository.deleteAll() },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val pending = MediaController.Builder(this, SessionToken(this, ComponentName(this, ScannerRadioService::class.java))).buildAsync()
        future = pending
        pending.addListener({
            if (future !== pending || isFinishing || isDestroyed) return@addListener
            runCatching {
                controller = pending.get().also { it.addListener(listener) }
                connected.value = true
                connectionError.value = null
                updatePlayback()
            }.onFailure { connectionError.value = "The audio player could not connect. Reopen Listen to try again." }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        controller?.removeListener(listener)
        controller = null
        connected.value = false
        future?.let { MediaController.releaseFuture(it) }; future = null
        super.onStop()
    }

    private fun playFeed(id: String) {
        if (!repository.networkConsent.value || repository.loading.value || repository.preferences.value.feeds.none { it.id == id }) return
        val player = controller ?: run {
            connectionError.value = "The audio player is connecting. Reopen Listen if it does not become ready."
            return
        }
        connectionError.value = null
        if (player.currentMediaItem?.mediaId != id || player.playerError != null || player.playbackState == Player.STATE_ENDED) {
            player.setMediaItem(MediaItem.Builder().setMediaId(id).build())
            player.prepare()
        } else if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    private fun stopPlayback() { controller?.stop(); controller?.clearMediaItems(); updatePlayback() }

    private fun updatePlayback() {
        val player = controller ?: return
        val problem = player.playerError
        playback.value = RadioPlaybackState(
            status = when {
                problem != null -> RadioPlaybackStatus.ERROR
                player.currentMediaItem == null || player.playbackState == Player.STATE_ENDED || player.playbackState == Player.STATE_IDLE -> RadioPlaybackStatus.IDLE
                player.playbackState == Player.STATE_BUFFERING -> RadioPlaybackStatus.BUFFERING
                player.isPlaying -> RadioPlaybackStatus.PLAYING
                else -> RadioPlaybackStatus.PAUSED
            },
            feedId = player.currentMediaItem?.mediaId,
            error = problem?.let { playbackError(it.errorCode) },
        )
    }

    private fun playbackError(code: Int): String = when (code) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "The feed could not be reached. Check your connection and try again."
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The provider did not make this feed available. Check its address and access requirements."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "This address did not provide supported audio. Use a direct HTTPS MP3, AAC or HLS stream."
        else -> "Playback stopped. Check the provider's stream and try again."
    }
}
