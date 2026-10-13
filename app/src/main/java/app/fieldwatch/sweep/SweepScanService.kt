package app.fieldwatch.sweep

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.net.Uri
import androidx.core.app.NotificationCompat
import app.fieldwatch.R
import app.fieldwatch.domain.Observation
import app.fieldwatch.domain.ScanIntensity
import app.fieldwatch.radio.BleRadio
import app.fieldwatch.radio.WifiRadio
import app.fieldwatch.radio.WifiNanRadio
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

/** Session service around the reused receive/parse wrappers; no inherited network or GPS features. */
class SweepScanService : Service() {
    private val coordinator get() = (application as SweepApplication).coordinator
    private val serviceInstanceId = UUID.randomUUID().toString()
    private var sessionId: String? = null
    private val ending = AtomicBoolean(false)
    private val exceptionHandler = CoroutineExceptionHandler { _, _ -> end(interrupted = true) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + exceptionHandler)
    private val inbound = Channel<Observation>(512, BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { sessionId?.let { coordinator.noteDropped(it, serviceInstanceId) } })
    private var wifi: WifiRadio? = null
    private var ble: BleRadio? = null
    private var nan: WifiNanRadio? = null
    private var pump: Job? = null
    private var ticker: Job? = null
    private var active = false
    private var lastSaveAt = 0L
    private var startedElapsed = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sessionId = coordinator.attachPendingService(serviceInstanceId, ::requestEnd)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestedId = intent?.getStringExtra(EXTRA_SESSION_ID)
        if (sessionId == null && requestedId != null &&
            coordinator.attachRequestedService(requestedId, serviceInstanceId, ::requestEnd)) sessionId = requestedId
        val ownedId = sessionId
        if (ownedId == null || requestedId != ownedId) {
            // A late command from an expired request must not stop a new pending owner.
            if (ownedId == null && !coordinator.hasServiceRequest()) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) { end(); return START_NOT_STICKY }
        if (active) return START_NOT_STICKY
        if (ending.get() || !coordinator.attachRequestedService(ownedId, serviceInstanceId, ::requestEnd)) {
            stopSelf(startId); return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Scan sessions", NotificationManager.IMPORTANCE_LOW))
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (_: Exception) {
            coordinator.finishSession(ownedId, serviceInstanceId, true, "Android could not run the scanner. Check permissions and reopen SWEEP.")
            stopSelf(); return START_NOT_STICKY
        }
        if (!coordinator.scanStarted(ownedId, serviceInstanceId)) { stopSelf(); return START_NOT_STICKY }
        active = true
        startedElapsed = SystemClock.elapsedRealtime()
        pump = scope.launch(Dispatchers.Default) {
            val batch = ArrayList<Observation>(80)
            for (first in inbound) {
                batch.clear(); batch.add(first)
                while (batch.size < 80) batch.add(inbound.tryReceive().getOrNull() ?: break)
                coordinator.record(ownedId, serviceInstanceId, batch)
            }
        }
        ticker = scope.launch {
            while (isActive && !ending.get()) {
                val health = coordinator.refreshCoverage(ownedId, serviceInstanceId)
                if (health == null) { end(true); break }
                if (health.wifiReady) {
                    if (wifi == null) wifi = WifiRadio(this@SweepScanService, ::offer, { _, _ -> })
                    wifi?.start()
                    wifi?.requestScan(40_000L, unthrottled = false)
                } else wifi?.stop()
                if (nan == null) nan = WifiNanRadio(this@SweepScanService, ::offer) { status -> coordinator.reportNanStatus(ownedId, serviceInstanceId, status) }
                if (health.wifiReady && health.wifiNanAvailable) nan?.start() else nan?.stop()
                nan?.status()?.let { coordinator.reportNanStatus(ownedId, serviceInstanceId, it) }
                if (health.bleReady) {
                    if (ble == null) ble = BleRadio(this@SweepScanService, ::offer, {
                        coordinator.reportServiceError(ownedId, serviceInstanceId, "Bluetooth scanning is waiting for Android. Wi-Fi may still work.")
                    })
                    if (ble?.needsRestart() == true) {
                        if (ble?.isRunning() == true) { ble?.stop(); delay(ble?.restartBackoffMs() ?: 1_200L) }
                        ble?.start(ScanIntensity.BALANCED)
                    }
                } else ble?.stop()
                coordinator.publish(
                    ownedId, serviceInstanceId,
                    wifiStatus = if (!health.wifiReady) health.wifiStatus else wifi?.throttleHint()?.ifBlank { "Observing Wi-Fi access points" },
                    bleStatus = if (!health.bleReady) health.bleStatus else if (ble?.isRunning() == true) "Observing Bluetooth advertisements" else "Bluetooth waiting for Android",
                )
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification())
                val elapsed = SystemClock.elapsedRealtime()
                if (elapsed - lastSaveAt >= 5_000L) { coordinator.checkpoint(ownedId, serviceInstanceId); lastSaveAt = elapsed }
                if (elapsed - startedElapsed >= MAX_SESSION_MS) { end(); break }
                delay(1_000L)
            }
        }
        return START_NOT_STICKY
    }

    private fun offer(observation: Observation) {
        if (ending.get()) return
        if (inbound.trySend(observation).isFailure) sessionId?.let { coordinator.noteDropped(it, serviceInstanceId) }
    }

    private fun requestEnd(interrupted: Boolean) { scope.launch { end(interrupted) } }

    private fun end(interrupted: Boolean = false) {
        if (!ending.compareAndSet(false, true)) return
        ticker?.cancel()
        runCatching { wifi?.stop() }; runCatching { ble?.stop() }; runCatching { nan?.stop() }
        inbound.close()
        val ownedId = sessionId
        scope.launch {
            pump?.join()
            if (ownedId != null) {
                coordinator.publish(ownedId, serviceInstanceId)
                coordinator.finishSession(ownedId, serviceInstanceId, interrupted,
                    if (interrupted) "Android interrupted scanning. Review the recorded coverage before interpreting results." else null)
            }
            stopSelf()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) { end(); super.onTaskRemoved(rootIntent) }

    override fun onDestroy() {
        ticker?.cancel(); runCatching { wifi?.close() }; runCatching { ble?.stop() }; runCatching { nan?.close() }
        inbound.cancel(); scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
        sessionId?.let { coordinator.serviceDestroyed(it, serviceInstanceId) }
    }

    private fun notification(): Notification {
        val launch = PendingIntent.getActivity(this, 0, Intent(this, SweepActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, SweepScanService::class.java).setAction(ACTION_STOP)
            .setData(Uri.parse("sweep:session/$sessionId")).putExtra(EXTRA_SESSION_ID, sessionId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val session = coordinator.current.value?.takeIf { it.id == sessionId }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_fieldwatch)
            .setContentTitle("SWEEP is scanning")
            .setContentText("${session?.findings?.size ?: 0} radio observations · results stay on this phone")
            .setContentIntent(launch).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, "End session", stop).setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE).build()
    }

    companion object {
        const val ACTION_STOP = "app.fieldwatch.sweep.STOP"
        const val EXTRA_SESSION_ID = "app.fieldwatch.sweep.SESSION_ID"
        private const val CHANNEL = "sweep-scans"
        private const val NOTIFICATION_ID = 407
        private const val MAX_SESSION_MS = 20 * 60_000L
    }
}
