package app.fieldwatch.sweep.radio

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Internet audio only: no radio tuning, recording, transcription or access to RF findings. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ScannerRadioService : MediaSessionService() {
    private val repository get() = (application as ScannerRadioApplication).radio
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaSession? = null
    private var player: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()
        // Prevent HTTP error diagnostics from putting private feed tokens into logs.
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
        val http = DefaultHttpDataSource.Factory().setUserAgent("SWEEP-Radio/alpha.1")
            .setConnectTimeoutMs(12_000).setReadTimeoutMs(20_000).setAllowCrossProtocolRedirects(false)
        val audio = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL).build()
        player = audio
        val launch = PendingIntent.getActivity(this, 409, Intent(this, ScannerRadioActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, audio).setSessionActivity(launch)
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
                    if (!repository.networkConsent.value || repository.loading.value || mediaItems.size != 1) return denied()
                    val approved = mediaItems.map { item ->
                        val feed = repository.preferences.value.feeds.find { it.id == item.mediaId } ?: return denied()
                        val checked = runCatching { ScannerFeedValidator.validate(feed) }.getOrNull() ?: return denied()
                        // Callers supply only a saved ID. Never accept an arbitrary controller URI.
                        MediaItem.Builder().setMediaId(checked.id).setUri(checked.url)
                            .setMediaMetadata(MediaMetadata.Builder().setTitle(checked.name).setArtist(checked.region.ifBlank { "Public safety radio" }).build()).build()
                    }
                    return Futures.immediateFuture(approved)
                }
                private fun denied(): ListenableFuture<List<MediaItem>> = Futures.immediateFailedFuture(IllegalStateException("Choose an authorized saved feed and enable Internet listening first."))
            }).build()
        scope.launch {
            combine(repository.networkConsent, repository.preferences) { allowed, saved -> allowed to saved }.collect { (allowed, saved) ->
                val invalid = (0 until audio.mediaItemCount).any { index ->
                    val item = audio.getMediaItemAt(index)
                    saved.feeds.none { it.id == item.mediaId && it.url == item.localConfiguration?.uri?.toString() }
                }
                if (!allowed || invalid) {
                    audio.stop(); audio.clearMediaItems()
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        session?.takeIf { controllerInfo.packageName == packageName || controllerInfo.isTrusted }

    override fun onTaskRemoved(rootIntent: Intent?) { pauseAllPlayersAndStopSelf() }

    override fun onDestroy() {
        scope.cancel()
        player?.stop(); player?.release(); player = null
        session?.release(); session = null
        super.onDestroy()
    }
}
