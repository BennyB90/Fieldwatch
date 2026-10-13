package app.fieldwatch.sweep.travel

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import app.fieldwatch.R
import app.fieldwatch.domain.Observation
import app.fieldwatch.domain.ScanIntensity
import app.fieldwatch.radio.BleRadio
import app.fieldwatch.sweep.SweepTravelActivity
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Separately enabled foreground Bluetooth observer. It never resumes from persisted state. */
class TravelObservationService : Service() {
    private val controller get() = (application as TravelModeHost).travelController
    private val instanceId = UUID.randomUUID().toString()
    private var sessionId: String? = null
    private val ending = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, _ -> end(true, "Android interrupted Travel observation.") })
    private data class Inbound(val observation: Observation, val elapsedMs: Long, val fix: TravelLocation?)
    private val inbound = Channel<Inbound>(256, BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { sessionId?.let { controller.noteDropped(it, instanceId) } })
    private var ble: BleRadio? = null
    private var ticker: Job? = null
    private var pump: Job? = null
    private var active = false
    private var locating = false
    private var gpsAvailable = false
    @Volatile private var lastFix: TravelLocation? = null
    private val notified = HashMap<String, TravelEvidenceKind>()
    private val locations get() = getSystemService(LOCATION_SERVICE) as LocationManager

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (ending.get() || !controller.locationAllowed()) { lastFix = null; return }
            val fix = TravelLocation(location.latitude, location.longitude,
                if (location.hasAccuracy()) location.accuracy else Float.NaN, location.elapsedRealtimeNanos / 1_000_000L)
            lastFix = TravelAnalyzer.validLocation(fix, SystemClock.elapsedRealtime())
        }
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) { lastFix = null }
        @Deprecated("Legacy LocationListener callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        sessionId = controller.attachPending(instanceId) { interrupted -> scope.launch { end(interrupted) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestedId = intent?.getStringExtra(EXTRA_SESSION_ID)
        if (sessionId == null && requestedId != null && controller.attach(requestedId, instanceId) { interrupted -> scope.launch { end(interrupted) } }) sessionId = requestedId
        val id = sessionId
        if (id == null || id != requestedId) {
            if (id == null && !controller.hasServiceRequest()) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) { end(); return START_NOT_STICKY }
        if (active || ending.get()) return START_NOT_STICKY
        val available = controller.availability()
        if (!available.ready) { end(true, "Travel stopped: ${available.message}"); return START_NOT_STICKY }
        try {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Travel observation", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Repeated tracker observations", NotificationManager.IMPORTANCE_DEFAULT))
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                (if (controller.locationAllowed()) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            startForeground(NOTIFICATION_ID, notification(), types)
        } catch (_: Exception) {
            end(true, "Android could not start the Travel foreground service. Start again explicitly after checking permissions.")
            return START_NOT_STICKY
        }
        if (!controller.started(id, instanceId)) { end(true); return START_NOT_STICKY }
        active = true
        ble = BleRadio(this, { observation ->
            if (!ending.get()) {
                // A delayed callback's receipt time is not a new tracker sighting.
                val measuredAt = observation.elapsedRealtimeMs
                if (measuredAt == null || inbound.trySend(Inbound(observation, measuredAt, lastFix)).isFailure) controller.noteDropped(id, instanceId)
            }
        }, { /* The visible notification and next tick report the radio's state. */ })
        pump = scope.launch(Dispatchers.Default) {
            for (sample in inbound) controller.record(id, instanceId, sample.observation, sample.elapsedMs, sample.fix)
        }
        ticker = scope.launch {
            var lastSave = 0L
            while (isActive && !ending.get()) {
                val session = controller.current.value
                if (session?.id != id || session.state != TravelSessionState.ACTIVE) { end(true); break }
                // Consent revocation erases history as well as rejecting new coordinates.
                controller.reconcileLocationConsent()
                val available = controller.availability()
                if (!available.ready) { end(true, "Travel stopped: ${available.message}"); break }
                syncLocation()
                if (ble?.needsRestart() == true) {
                    if (ble?.isRunning() == true) {
                        ble?.stop()
                        updateReceptionStatus(id)
                        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification())
                        delay(ble?.restartBackoffMs() ?: 1_200L)
                    }
                    if (!ending.get()) ble?.start(ScanIntensity.BALANCED)
                }
                val elapsed = SystemClock.elapsedRealtime()
                updateReceptionStatus(id)
                if (elapsed - lastSave >= 5_000L) { controller.checkpoint(id, instanceId); lastSave = elapsed }
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification())
                notifyAssessments()
                if (elapsed - session.startedElapsedMs >= TravelLimits.MAX_SESSION_MS) { end(false, "The four-hour observation window ended. Start another Travel session explicitly if needed."); break }
                delay(1_000L)
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun syncLocation() {
        if (!controller.locationAllowed()) { stopLocation(); gpsAvailable = false; return }
        gpsAvailable = runCatching {
            LocationManager.GPS_PROVIDER in locations.allProviders && locations.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)
        if (!gpsAvailable) { stopLocation(); return }
        if (locating) return
        runCatching {
            // No last-known lookup or network geocoder; only new optional GPS fixes.
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60_000L, 30f, locationListener, Looper.getMainLooper())
            locating = true
        }.onFailure { stopLocation() }
    }

    private fun updateReceptionStatus(id: String) = controller.receptionStatus(id, instanceId,
        ble?.isRunning() == true, gpsAvailable, locating, lastFix, SystemClock.elapsedRealtime())

    private fun stopLocation() {
        if (locating) runCatching { locations.removeUpdates(locationListener) }
        locating = false
        lastFix = null
    }

    private fun end(interrupted: Boolean = false, reason: String? = null) {
        if (!ending.compareAndSet(false, true)) return
        ticker?.cancel(); runCatching { ble?.stop() }; stopLocation(); inbound.close()
        scope.launch {
            pump?.join()
            sessionId?.let { controller.finish(it, instanceId, interrupted, reason) }
            stopSelf()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) { end(); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() {
        ticker?.cancel(); runCatching { ble?.stop() }; stopLocation()
        inbound.cancel(); scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        clearNotice(this)
        sessionId?.let { controller.destroyed(it, instanceId) }
        super.onDestroy()
    }

    private fun openTravel() = PendingIntent.getActivity(this, NOTIFICATION_ID, Intent(this, SweepTravelActivity::class.java)
        .setAction("app.fieldwatch.sweep.travel.OPEN"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun notification(): Notification {
        val stop = PendingIntent.getService(this, 0, Intent(this, TravelObservationService::class.java).setAction(ACTION_STOP)
            .setData(Uri.parse("sweep:travel/$sessionId")).putExtra(EXTRA_SESSION_ID, sessionId), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val session = controller.current.value
        val sampleCount = session?.trackers?.sumOf { it.samples.size } ?: 0
        val status = "$sampleCount spaced observations · ${session?.status ?: "Starting Bluetooth observation"}"
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_fieldwatch)
            .setContentTitle("SWEEP Travel is enabled")
            .setContentText(status).setStyle(NotificationCompat.BigTextStyle().bigText(status))
            .setContentIntent(openTravel()).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, "Stop Travel", stop).setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE).build()
    }

    private fun notifyAssessments() {
        val id = sessionId ?: return
        if (!controller.notificationsAvailable().ready) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        controller.publishAssessments(id, instanceId) { assessment ->
            val previous = notified[assessment.address]
            if (previous == assessment.kind || previous == TravelEvidenceKind.DISTINCT_PHONE_LOCATIONS) return@publishAssessments
            notified[assessment.address] = assessment.kind
            // Address and phone coordinates are intentionally absent from the shade.
            val detail = if (assessment.kind == TravelEvidenceKind.DISTINCT_PHONE_LOCATIONS)
                "Observed at ${assessment.distinctPhoneLocations} phone locations over ${assessment.spanMinutes} minutes; ownership is unverified."
            else "Observed ${assessment.observations} times over ${assessment.spanMinutes} minutes; movement is unverified."
            manager.notify(ALERT_TAG, ALERT_NOTIFICATION_ID, NotificationCompat.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_fieldwatch).setContentTitle("Repeated tracker signal observations")
                .setContentText(detail).setStyle(NotificationCompat.BigTextStyle().bigText("$detail This does not establish unwanted tracking."))
                .setContentIntent(openTravel()).setAutoCancel(true).build())
        }
    }

    companion object {
        internal fun clearNotice(context: android.content.Context) {
            (context.getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(ALERT_TAG, ALERT_NOTIFICATION_ID)
        }
        const val EXTRA_SESSION_ID = "app.fieldwatch.sweep.travel.SESSION_ID"
        const val ACTION_STOP = "app.fieldwatch.sweep.travel.STOP"
        internal const val CHANNEL = "sweep-travel"
        internal const val ALERT_CHANNEL = "sweep-travel-observations"
        private const val NOTIFICATION_ID = 408
        private const val ALERT_NOTIFICATION_ID = 409
        private const val ALERT_TAG = "sweep-travel-notice"
    }
}
