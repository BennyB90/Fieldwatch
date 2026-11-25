package app.fieldwatch.sweep

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.fieldwatch.BuildConfig
import app.fieldwatch.sweep.travel.TravelSession
import app.fieldwatch.sweep.travel.TravelSessionState
import java.time.Instant
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class BatteryReading(val percent: Int, val elapsedMs: Long, val boot: Int, val plugged: Boolean)

@Serializable
internal data class BatteryCondition(
    val travelId: String? = null, val lowPower: Boolean = false, val gps: Boolean = false, val playback: Boolean = false,
) {
    val label: String get() = when {
        travelId != null -> "Travel · ${if (lowPower) "low power" else "balanced"} · GPS ${if (gps) "on" else "off"}"
        playback -> "Radio playback"
        else -> "Idle baseline"
    }
}

@Serializable
internal data class BatteryTrial(val start: BatteryReading, val condition: BatteryCondition) {
    fun finish(
        end: BatteryReading,
        current: BatteryCondition,
        completedAt: Long? = null,
        travelSession: TravelSession? = null,
    ): BatteryResult {
        require(!start.plugged && !end.plugged) { "Power was connected. Repeat the run unplugged." }
        require(start.boot == end.boot && end.elapsedMs > start.elapsedMs) { "The phone restarted or the timer was interrupted. Repeat this run." }
        require(condition == current) { "Travel or its settings changed. Repeat this run with one configuration." }
        require(end.percent <= start.percent) { "The reported battery level increased. Repeat this run." }
        val trackers = travelSession?.takeIf { condition.travelId != null && it.id == condition.travelId }?.trackers
        val trackerCount = trackers?.size
        val sightingCount = trackers?.sumOf { it.samples.size }
        val gpsSightings = if (condition.gps && trackers != null) {
            trackers.sumOf { tracker -> tracker.samples.count { it.location != null } }
        } else null
        return BatteryResult(
            label = condition.label,
            startPercent = start.percent,
            endPercent = end.percent,
            durationMs = end.elapsedMs - start.elapsedMs,
            completedAt = completedAt,
            trackerCount = trackerCount,
            sightingCount = sightingCount,
            gpsSightingCount = gpsSightings,
        )
    }
}

@Serializable
internal data class BatteryResult(
    val label: String,
    val startPercent: Int,
    val endPercent: Int,
    val durationMs: Long,
    val completedAt: Long? = null,
    val trackerCount: Int? = null,
    val sightingCount: Int? = null,
    val gpsSightingCount: Int? = null,
) {
    val pointsPerHour: Double
        get() = if (durationMs <= 0L) 0.0 else (startPercent - endPercent) * 3_600_000.0 / durationMs
}

@Serializable
internal data class BatteryTestLog(val active: BatteryTrial? = null, val results: List<BatteryResult> = emptyList())

/** Privacy-safe summary formatter. Omits handset identity by default and never includes MACs or coordinates. */
internal object BatteryTrialReports {
    fun text(
        results: List<BatteryResult>,
        includeDevice: Boolean = false,
        deviceModel: String? = null,
        androidRelease: String? = null,
        sdkInt: Int? = null,
    ): String {
        require(results.isNotEmpty()) { "Complete at least one battery measurement before sharing." }
        val safeModel = if (includeDevice) sanitizeDevice(deviceModel) else "Withheld"
        val safeAndroid = if (includeDevice) sanitizeAndroid(androidRelease, sdkInt) else "Withheld"
        return buildString {
            appendLine("SWEEP — HANDSET BATTERY TRIAL SUMMARY")
            appendLine("Device: $safeModel")
            appendLine("Android: $safeAndroid")
            appendLine("Completed runs: ${results.size}")
            appendLine()
            results.forEachIndexed { index, result ->
                val minutes = result.durationMs.coerceAtLeast(0L) / 60_000.0
                val drop = result.startPercent - result.endPercent
                appendLine(
                    String.format(
                        Locale.US,
                        "%d. %s — %d%% -> %d%% (%d pt drop) in %.1f min · %.2f points/hour",
                        index + 1,
                        sanitizeLine(result.label, 80),
                        result.startPercent,
                        result.endPercent,
                        drop,
                        minutes,
                        result.pointsPerHour,
                    ),
                )
                if (result.sightingCount != null && result.trackerCount != null) {
                    val gpsNote = if (result.gpsSightingCount != null) {
                        " (${result.gpsSightingCount} of ${result.sightingCount} with usable GPS fix)"
                    } else ""
                    appendLine("   Travel sightings: ${result.sightingCount} spaced observations across ${result.trackerCount} tracker signal(s)$gpsNote")
                }
                result.completedAt?.takeIf { it > 0L }?.let { timestamp ->
                    runCatching { Instant.ofEpochMilli(timestamp).toString() }.getOrNull()?.let { iso ->
                        appendLine("   Recorded: $iso")
                    }
                }
            }
            appendLine()
            appendLine("Method & limits: whole-phone battery percentage endpoints over elapsed time including deep sleep, not isolated per-app power draw. Android rounds battery percentage to whole integers, so runs shorter than 60 minutes can be noisy. Power state and active mode are checked at the start and finish endpoints only; radio identifiers, stream URLs, and GPS coordinates are never included.")
            append("Reports stay on this device until you choose to share them.")
        }
    }

    private fun sanitizeDevice(raw: String?): String =
        sanitizeLine(raw.orEmpty(), 80).ifEmpty { "Unknown" }

    private fun sanitizeAndroid(release: String?, sdkInt: Int?): String {
        val cleanRelease = Regex("^\\d{1,2}(?:\\.\\d{1,2}){0,2}").find(release.orEmpty().trim())?.value
        val apiPart = sdkInt?.takeIf { it in 29..99 }?.let { "API $it" }
        return listOfNotNull(cleanRelease, apiPart).joinToString(" · ").ifEmpty { "Unknown" }
    }

    private fun sanitizeLine(raw: String, maxLength: Int): String =
        raw.replace(Regex("[\\p{Cc}\\p{Cf}]"), " ").trim().replace(Regex("\\s+"), " ").take(maxLength)
}

/** UI-triggered endpoint readings. No polling, service, or wake lock during the measurement. */
internal class HandsetBatteryTest(private val app: SweepApplication) {
    private val prefs = app.getSharedPreferences("handset-battery-tests", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val stored = runCatching {
        prefs.getString("log", null)?.let { json.decodeFromString<BatteryTestLog>(it) } ?: BatteryTestLog()
    }
    private var storageCorrupt = stored.isFailure
    var log by mutableStateOf(stored.getOrDefault(BatteryTestLog()))
        private set
    var message by mutableStateOf(if (stored.isFailure) CORRUPT_STORAGE_MESSAGE else "")
        private set

    fun start(playback: Boolean) {
        if (log.active != null) return
        if (storageCorrupt) {
            message = CORRUPT_STORAGE_MESSAGE
            return
        }
        runCatching {
            val start = reading()
            require(!start.plugged) { "Unplug the phone before starting a battery test." }
            save(log.copy(active = BatteryTrial(start, condition(playback, finishing = false))))
        }.onFailure { message = it.message ?: "The battery test could not start." }
    }

    fun finish() {
        val trial = log.active ?: return
        val end = runCatching { reading() }.getOrElse {
            message = it.message ?: "The battery reading is unavailable. Try again."
            return
        }
        // Keep the active trial intact if the user accidentally tapped Finish right after starting.
        if (!end.plugged && end.boot == trial.start.boot && end.elapsedMs > trial.start.elapsedMs &&
            end.elapsedMs - trial.start.elapsedMs < MIN_DURATION_MS) {
            message = "Let the run continue for at least one minute (60 minutes recommended) before finishing, or choose Discard."
            return
        }
        val travel = app.travelController.current.value
        val result = runCatching {
            val nowCondition = condition(trial.condition.playback, finishing = true)
            trial.finish(end, nowCondition, completedAt = System.currentTimeMillis(), travelSession = travel)
        }.getOrElse {
            save(log.copy(active = null))
            message = it.message ?: "The test conditions changed. Repeat this run."
            return
        }
        save(BatteryTestLog(results = (listOf(result) + log.results).take(MAX_RESULTS)))
    }

    fun discard() {
        if (storageCorrupt) { message = CORRUPT_STORAGE_MESSAGE; return }
        save(log.copy(active = null))
    }

    fun clearResults() {
        storageCorrupt = false
        save(log.copy(active = if (storageCorrupt) null else log.active, results = emptyList()))
    }

    fun reportText(includeDevice: Boolean): String = BatteryTrialReports.text(
        results = log.results,
        includeDevice = includeDevice,
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
        androidRelease = Build.VERSION.RELEASE,
        sdkInt = Build.VERSION.SDK_INT,
    )

    private fun save(updated: BatteryTestLog) {
        val committed = prefs.edit().putString("log", json.encodeToString(updated)).commit()
        if (!committed) {
            message = "The battery measurement could not be saved to phone storage."
            return
        }
        log = updated
        message = ""
    }

    private fun condition(playback: Boolean, finishing: Boolean): BatteryCondition {
        val travel = app.travelController.current.value
        if (travel?.state == TravelSessionState.ACTIVE) {
            require(!app.travelController.starting.value) { "Wait for Travel to start before taking a reading." }
            require(!app.isAudioPlaying) { "Stop radio playback before measuring Travel battery use." }
            return BatteryCondition(travel.id, travel.lowPowerBluetooth, travel.locationConsent)
        }
        require(!app.radioGate.held()) { "Wait for the other scan to stop before measuring idle or audio playback." }
        val wantsPlayback = playback && BuildConfig.SWEEP_RADIO_ENABLED
        if (wantsPlayback) {
            require(app.isAudioPlaying) {
                if (finishing) "Audio playback stopped before the final reading. Repeat this run while audio stays playing."
                else "Start an authorized stream or Rdio call before measuring radio playback."
            }
            return BatteryCondition(playback = true)
        }
        require(!app.isAudioPlaying) {
            if (finishing) "Radio playback was active during an idle baseline. Repeat this run with audio stopped."
            else "Stop radio playback before starting an idle baseline, or label the run as radio playback."
        }
        return BatteryCondition(playback = false)
    }

    private fun reading(): BatteryReading {
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val boot = Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)
        require(scale > 0 && level in 0..scale && plugged >= 0 && boot >= 0) { "Android could not supply a battery level, power state, or boot count." }
        return BatteryReading((100.0 * level / scale).roundToInt(), SystemClock.elapsedRealtime(), boot, plugged != 0)
    }

    companion object {
        internal const val MIN_DURATION_MS = 60_000L
        internal const val MAX_RESULTS = 12
        private const val CORRUPT_STORAGE_MESSAGE = "Saved battery measurements could not be read. Clear measurements explicitly to reset them."
    }
}
