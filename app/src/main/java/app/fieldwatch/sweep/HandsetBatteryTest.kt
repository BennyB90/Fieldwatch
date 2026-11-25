package app.fieldwatch.sweep

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.fieldwatch.sweep.travel.TravelSessionState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.math.roundToInt

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
    fun finish(end: BatteryReading, current: BatteryCondition): BatteryResult {
        require(!start.plugged && !end.plugged) { "Power was connected. Repeat the run unplugged." }
        require(start.boot == end.boot && end.elapsedMs > start.elapsedMs) { "The phone restarted or the timer was interrupted. Repeat this run." }
        require(condition == current) { "Travel or its settings changed. Repeat this run with one configuration." }
        require(end.percent <= start.percent) { "The reported battery level increased. Repeat this run." }
        return BatteryResult(condition.label, start.percent, end.percent, end.elapsedMs - start.elapsedMs)
    }
}

@Serializable
internal data class BatteryResult(val label: String, val startPercent: Int, val endPercent: Int, val durationMs: Long) {
    val pointsPerHour get() = (startPercent - endPercent) * 3_600_000.0 / durationMs
}

@Serializable
internal data class BatteryTestLog(val active: BatteryTrial? = null, val results: List<BatteryResult> = emptyList())

/** UI-triggered endpoint readings. No polling, service, or wake lock during the measurement. */
internal class HandsetBatteryTest(private val app: SweepApplication) {
    private val prefs = app.getSharedPreferences("handset-battery-tests", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val stored = runCatching {
        prefs.getString("log", null)?.let { json.decodeFromString<BatteryTestLog>(it) } ?: BatteryTestLog()
    }
    var log by mutableStateOf(stored.getOrDefault(BatteryTestLog()))
        private set
    var message by mutableStateOf(if (stored.isFailure) "The saved battery measurements could not be read." else "")
        private set

    fun start(playback: Boolean) {
        if (log.active != null) return
        runCatching {
            val start = reading()
            require(!start.plugged) { "Unplug the phone before starting a battery test." }
            save(log.copy(active = BatteryTrial(start, condition(playback))))
        }.onFailure { message = it.message ?: "The battery test could not start." }
    }

    fun finish() {
        val trial = log.active ?: return
        val end = runCatching { reading() }.getOrElse {
            message = it.message ?: "The battery reading is unavailable. Try again."
            return
        }
        val result = runCatching { trial.finish(end, condition(trial.condition.playback)) }.getOrElse {
            save(log.copy(active = null))
            message = it.message ?: "The test conditions changed. Repeat this run."
            return
        }
        save(BatteryTestLog(results = (listOf(result) + log.results).take(12)))
    }

    fun discard() = save(log.copy(active = null))
    fun clearResults() = save(log.copy(results = emptyList()))

    private fun save(updated: BatteryTestLog) {
        prefs.edit().putString("log", json.encodeToString(updated)).apply()
        log = updated
        message = ""
    }

    private fun condition(playback: Boolean): BatteryCondition {
        val travel = app.travelController.current.value
        if (travel?.state == TravelSessionState.ACTIVE) {
            require(!app.travelController.starting.value) { "Wait for Travel to start before taking a reading." }
            return BatteryCondition(travel.id, travel.lowPowerBluetooth, travel.locationConsent)
        }
        require(!app.radioGate.held()) { "Wait for the other scan to stop before measuring idle or audio playback." }
        return BatteryCondition(playback = playback)
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
}
