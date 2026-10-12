package app.fieldwatch.sweep

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.fieldwatch.data.DeviceStore
import app.fieldwatch.domain.DefaultCatalog
import app.fieldwatch.domain.Fleet
import app.fieldwatch.domain.Observation
import app.fieldwatch.domain.OpenDroneId
import app.fieldwatch.domain.OperatorLoc
import app.fieldwatch.domain.PayloadLocation
import app.fieldwatch.domain.RadioKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** On-device session owner. No account, geocoder, maps SDK, phone GPS, or network client. */
class SweepCoordinator(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val store = SweepSessionStore(File(context.filesDir, "sweep-sessions"))
    private val persistence = SweepPersistenceQueue(scope)
    private val serviceOwnership = SweepServiceOwnership()
    private var serviceStopper: Pair<String, (Boolean) -> Unit>? = null
    private var startupWatchdog: Pair<String, Job>? = null
    private var historyGeneration = 0L
    private val deleting = HashSet<String>()
    val catalog = DefaultCatalog.fleets()
    private var devices = DeviceStore()
    private var observationsSinceRefresh = 0
    private var lastDeviceRefreshAt = 0L
    private val findings = LinkedHashMap<String, SweepFinding>()
    private val _current = MutableStateFlow<SweepSession?>(null)
    val current = _current.asStateFlow()
    private val _history = MutableStateFlow<List<SweepSession>>(emptyList())
    val history = _history.asStateFlow()
    private val _coverage = MutableStateFlow(SweepCoverage())
    val coverage = _coverage.asStateFlow()
    private val _starting = MutableStateFlow(false)
    val starting = _starting.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun initialize() {
        refreshCoverage()
        synchronized(lock) {
            val generation = historyGeneration
            persistence.enqueue(onFailure = { _error.value = "Saved sessions could not be loaded." }) {
                val savedSessions = store.load()
                if (!mayPersist(generation)) return@enqueue
                val recovered = savedSessions.map { saved ->
                    if (saved.state != SweepSessionState.ACTIVE) saved else saved.copy(
                        state = SweepSessionState.INTERRUPTED,
                        endedAt = null,
                        error = "Android ended this session. Only observations saved before interruption are available.",
                    ).also { if (mayPersist(generation, it.id)) store.save(it) }
                }
                synchronized(lock) {
                    if (generation == historyGeneration) {
                        _history.value = (_history.value + recovered.filterNot { it.id in deleting })
                            .distinctBy { it.id }.sortedByDescending { it.startedAt }.take(100)
                    }
                }
            }
        }
    }

    fun hasPermission(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    fun locationEnabled(): Boolean = (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.isLocationEnabled == true
    fun bluetoothPermissions(): Boolean = Build.VERSION.SDK_INT < 31 ||
        (hasPermission(Manifest.permission.BLUETOOTH_SCAN) && hasPermission(Manifest.permission.BLUETOOTH_CONNECT))

    fun refreshCoverage(): SweepCoverage = refreshCoverageFor(null, null)!!

    fun refreshCoverage(sessionId: String, serviceInstanceId: String): SweepCoverage? =
        refreshCoverageFor(sessionId, serviceInstanceId)

    @SuppressLint("MissingPermission")
    private fun refreshCoverageFor(sessionId: String?, serviceInstanceId: String?): SweepCoverage? {
        val wifiSupported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)
        val bleSupported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val locationPermission = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val locationOn = locationEnabled()
        val wifiOn = runCatching { (context.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled == true }.getOrDefault(false)
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val blePermission = bluetoothPermissions() && locationPermission
        val bleOn = blePermission && runCatching { adapter?.isEnabled == true }.getOrDefault(false)
        val wifiReady = wifiSupported && locationPermission && locationOn && wifiOn
        val bleReady = bleSupported && blePermission && locationOn && bleOn
        return synchronized(lock) {
            if (sessionId != null && (serviceInstanceId == null || !activeServiceMatches(sessionId, serviceInstanceId))) return@synchronized null
            _coverage.updateAndGet { previous -> previous.copy(
            wifiSupported = wifiSupported,
            bleSupported = bleSupported,
            wifiReady = wifiReady,
            bleReady = bleReady,
            wifiStatus = when {
                !wifiSupported -> "Wi-Fi unavailable on this device"
                !locationPermission -> "Location permission needed for Wi-Fi scanning"
                !locationOn -> "Turn on Android Location for Wi-Fi scanning"
                !wifiOn -> "Wi-Fi is off"
                else -> "Wi-Fi ready"
            },
            bleStatus = when {
                !bleSupported -> "Bluetooth LE unavailable on this device"
                !blePermission -> "Bluetooth and location permissions needed"
                !locationOn -> "Turn on Android Location for Bluetooth scanning"
                !bleOn -> "Bluetooth is off"
                else -> "Bluetooth ready"
            },
            wifiIeAvailable = Build.VERSION.SDK_INT >= 30,
            // API flags do not establish successful Remote ID long-range reception.
            bleExtendedSupported = null,
            wifiNanAvailable = false,
            ) }
        }
    }

    fun start() {
        val health = refreshCoverage()
        val sessionId = synchronized(lock) {
            if (_starting.value || _current.value?.state == SweepSessionState.ACTIVE) return
            if (serviceOwnership.isHeld()) {
                _error.value = SCANNER_STOPPING_MESSAGE
                return
            }
            if (!health.wifiReady && !health.bleReady) {
                _error.value = "No radio is ready. Check permissions, Android Location, and Wi-Fi or Bluetooth."
                return
            }
            devices = DeviceStore()
            observationsSinceRefresh = 0
            lastDeviceRefreshAt = SystemClock.elapsedRealtime()
            findings.clear()
            val cleanCoverage = health.copy(wifiSamples = 0, bleSamples = 0, droppedSamples = 0, wifiLastSampleAt = null)
            _coverage.value = cleanCoverage
            _error.value = null
            val id = UUID.randomUUID().toString()
            check(serviceOwnership.reserve(id))
            _current.value = SweepSession(
                id = id, startedAt = System.currentTimeMillis(),
                phone = "${Build.MANUFACTURER} ${Build.MODEL}",
                androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}, ${Build.DISPLAY})",
                startBattery = battery(), coverage = cleanCoverage,
            )
            _starting.value = true
            startupWatchdog = id to scope.launch {
                delay(STARTUP_TIMEOUT_MS)
                startupTimedOut(id)
            }
            checkpoint()
            id
        }
        runCatching {
            checkNotNull(ContextCompat.startForegroundService(context,
                Intent(context, SweepScanService::class.java).putExtra(SweepScanService.EXTRA_SESSION_ID, sessionId),
            )) { "Android did not create the scanner service" }
        }.onFailure {
            startRequestFailed(sessionId)
        }
    }

    /** Called during onCreate, so a known service keeps its lease until final teardown. */
    fun attachPendingService(serviceInstanceId: String, stop: (Boolean) -> Unit): String? = synchronized(lock) {
        val current = _current.value ?: return@synchronized null
        if (current.state != SweepSessionState.ACTIVE || current.demo) return@synchronized null
        val sessionId = serviceOwnership.attachPending(serviceInstanceId) ?: return@synchronized null
        if (sessionId != current.id) return@synchronized null
        serviceStopper = sessionId to stop
        sessionId
    }

    fun attachRequestedService(sessionId: String, serviceInstanceId: String, stop: (Boolean) -> Unit): Boolean = synchronized(lock) {
        if (_current.value?.let { it.id == sessionId && it.state == SweepSessionState.ACTIVE } != true) return@synchronized false
        if (!serviceOwnership.claim(sessionId, serviceInstanceId)) return@synchronized false
        serviceStopper = sessionId to stop
        true
    }

    fun scanStarted(sessionId: String, serviceInstanceId: String): Boolean = synchronized(lock) {
        if (!activeServiceMatches(sessionId, serviceInstanceId) || !serviceOwnership.started(sessionId, serviceInstanceId)) return@synchronized false
        _starting.value = false
        cancelStartupWatchdog(sessionId)
        true
    }

    fun hasServiceRequest(): Boolean = synchronized(lock) { serviceOwnership.isHeld() }

    fun stop() {
        val stop = synchronized(lock) {
            val session = _current.value ?: return
            if (session.state != SweepSessionState.ACTIVE) return
            if (serviceOwnership.releaseUncreated(session.id)) {
                cancelStartupWatchdog(session.id)
                finish(session.id)
                null
            } else serviceStopper?.takeIf { it.first == session.id }?.second
        }
        stop?.invoke(false)
    }

    fun record(sessionId: String, serviceInstanceId: String, batch: List<Observation>) = synchronized(lock) {
        if (!activeServiceMatches(sessionId, serviceInstanceId)) return@synchronized
        val session = _current.value ?: return@synchronized
        if (session.state != SweepSessionState.ACTIVE || session.demo) return@synchronized
        val observations = batch.filter { it.at >= session.startedAt && it.fresh }
        if (observations.isEmpty()) return@synchronized
        for (observation in observations) {
            // Apply each rotating Remote ID message before the next message replaces its
            // payload. ingestBatch returns the final sighting for duplicate device keys.
            val key = devices.keyOf(observation.kind, observation.mac)
            val mapped = sweepFindingForObservation(devices, observation, catalog, findings[key])
            findings[mapped.id] = mapped
            observationsSinceRefresh++
            if (observationsSinceRefresh >= 80) refreshDevices()
        }
        if (SystemClock.elapsedRealtime() - lastDeviceRefreshAt >= 1_000L) refreshDevices()
        // Explicit capacity and dropped count rather than unbounded session memory.
        while (findings.size > 1_000) { findings.remove(findings.minByOrNull { it.value.lastSeenAt }!!.key); noteDropped() }
        val wifiCount = observations.count { it.kind == RadioKind.WIFI }
        _coverage.update { it.copy(
            wifiSamples = it.wifiSamples + wifiCount,
            bleSamples = it.bleSamples + observations.size - wifiCount,
            wifiLastSampleAt = observations.filter { row -> row.kind == RadioKind.WIFI }.maxOfOrNull { row -> row.at } ?: it.wifiLastSampleAt,
        ) }
    }

    fun noteDropped(sessionId: String, serviceInstanceId: String) = synchronized(lock) {
        if (activeServiceMatches(sessionId, serviceInstanceId)) noteDropped()
    }

    private fun noteDropped() { _coverage.update { it.copy(droppedSamples = it.droppedSamples + 1) } }

    fun publish(sessionId: String, serviceInstanceId: String, wifiStatus: String? = null, bleStatus: String? = null) = synchronized(lock) {
        if (!activeServiceMatches(sessionId, serviceInstanceId)) return@synchronized
        val session = _current.value ?: return@synchronized
        if (session.state != SweepSessionState.ACTIVE) return@synchronized
        if (wifiStatus != null || bleStatus != null) _coverage.update { it.copy(wifiStatus = wifiStatus ?: it.wifiStatus, bleStatus = bleStatus ?: it.bleStatus) }
        _current.value = session.copy(findings = findings.values.sortedWith(compareBy<SweepFinding> { it.category == SweepCategory.OTHER }.thenByDescending { it.lastSeenAt }), coverage = _coverage.value)
    }

    fun checkpoint(sessionId: String, serviceInstanceId: String) = synchronized(lock) {
        if (activeServiceMatches(sessionId, serviceInstanceId)) checkpoint()
    }

    private fun checkpoint() {
        synchronized(lock) {
            val snapshot = _current.value ?: return
            if (snapshot.demo || snapshot.state != SweepSessionState.ACTIVE) return
            val generation = historyGeneration
            persistence.enqueue(onFailure = { _error.value = "Session saving is unavailable. You can still share the current report." }) {
                if (mayPersist(generation, snapshot.id)) store.save(snapshot)
            }
        }
    }

    fun finishSession(sessionId: String, serviceInstanceId: String, interrupted: Boolean = false, reason: String? = null) = synchronized(lock) {
        if (serviceOwnership.owns(sessionId, serviceInstanceId)) finish(sessionId, interrupted, reason)
    }

    private fun finish(sessionId: String, interrupted: Boolean = false, reason: String? = null) {
        synchronized(lock) {
            val session = _current.value ?: return
            if (session.id != sessionId || session.state != SweepSessionState.ACTIVE) return
            _starting.value = false
            val finished = session.copy(
                endedAt = System.currentTimeMillis(), state = if (interrupted) SweepSessionState.INTERRUPTED else SweepSessionState.COMPLETE,
                findings = findings.values.toList(), coverage = _coverage.value, endBattery = battery(), error = reason,
            ).also { _current.value = it; if (reason != null) _error.value = reason }
            val generation = historyGeneration
            persistence.enqueue(onFailure = { _error.value = "This session could not be saved. Share its report before closing SWEEP." }) {
                if (!mayPersist(generation, finished.id)) return@enqueue
                store.save(finished)
                synchronized(lock) {
                    if (generation == historyGeneration && finished.id !in deleting) {
                        _history.value = (listOf(finished) + _history.value).distinctBy { it.id }
                            .sortedByDescending { it.startedAt }.take(100)
                    }
                }
            }
        }
    }

    /** Release only after the owning service has cancelled callbacks and removed its foreground notification. */
    fun serviceDestroyed(sessionId: String, serviceInstanceId: String) = synchronized(lock) {
        if (!serviceOwnership.owns(sessionId, serviceInstanceId)) return@synchronized
        finish(sessionId, true, "Android ended scanning unexpectedly. This session may be incomplete.")
        serviceOwnership.release(sessionId, serviceInstanceId)
        serviceStopper = null
        cancelStartupWatchdog(sessionId)
        if (_error.value == SCANNER_STOPPING_MESSAGE) _error.value = null
    }

    fun reportServiceError(sessionId: String, serviceInstanceId: String, message: String) = synchronized(lock) {
        if (activeServiceMatches(sessionId, serviceInstanceId)) _error.value = message
    }

    private fun startRequestFailed(sessionId: String) {
        val stop = synchronized(lock) {
            if (!serviceOwnership.ownsSession(sessionId)) return
            finish(sessionId, true, "Android could not start scanning. Reopen SWEEP and check permissions.")
            cancelStartupWatchdog(sessionId)
            if (serviceOwnership.releaseUncreated(sessionId)) null else serviceStopper?.takeIf { it.first == sessionId }?.second
        }
        stop?.invoke(true)
    }

    private fun startupTimedOut(sessionId: String) {
        val stop = synchronized(lock) {
            if (!serviceOwnership.awaitingStart(sessionId)) return
            finish(sessionId, true, "Android did not start the scanner. Try starting a new session.")
            if (serviceOwnership.releaseUncreated(sessionId)) null else serviceStopper?.takeIf { it.first == sessionId }?.second
        }
        stop?.invoke(true)
    }

    private fun cancelStartupWatchdog(sessionId: String) {
        startupWatchdog?.takeIf { it.first == sessionId }?.second?.cancel()
        if (startupWatchdog?.first == sessionId) startupWatchdog = null
    }

    private fun activeServiceMatches(sessionId: String, serviceInstanceId: String): Boolean =
        serviceOwnership.owns(sessionId, serviceInstanceId) &&
            _current.value?.let { it.id == sessionId && it.state == SweepSessionState.ACTIVE && !it.demo } == true

    fun showDemo() = synchronized(lock) {
        if (_current.value?.state == SweepSessionState.ACTIVE || _starting.value) return@synchronized
        _error.value = null
        _current.value = SweepDemo.session(System.currentTimeMillis())
    }

    fun clearDemo() { if (_current.value?.demo == true) _current.value = null }

    fun delete(id: String) {
        synchronized(lock) {
            if (_current.value?.let { it.id == id && it.state == SweepSessionState.ACTIVE } == true) {
                _error.value = "End the scan before deleting its session."; return
            }
            deleting += id
            persistence.enqueue(onFailure = {
                synchronized(lock) { deleting -= id }
                _error.value = "The session could not be deleted."
            }) {
                store.delete(id)
                clearExportCache()
                synchronized(lock) {
                    _history.value = _history.value.filterNot { it.id == id }
                    if (_current.value?.id == id) _current.value = null
                    deleting -= id
                }
            }
        }
    }

    fun deleteAll() {
        synchronized(lock) {
            if (_current.value?.state == SweepSessionState.ACTIVE) { _error.value = "End the scan before clearing saved sessions."; return }
            val currentId = _current.value?.id
            historyGeneration++
            persistence.enqueue(onFailure = { _error.value = "Saved sessions could not be cleared." }) {
                store.deleteAll()
                clearExportCache()
                synchronized(lock) {
                    _history.value = emptyList()
                    // A scan or demo started after this request owns its new current state.
                    if (_current.value?.id == currentId && _current.value?.state != SweepSessionState.ACTIVE) _current.value = null
                }
            }
        }
    }

    /** Export creation and deletion share the same queue, so a deleted report cannot reappear in the cache. */
    fun prepareShare(session: SweepSession, identifiers: Boolean, coordinates: Boolean): Deferred<File> {
        val result = CompletableDeferred<File>()
        synchronized(lock) {
            val generation = historyGeneration
            persistence.enqueue(onFailure = { result.completeExceptionally(it) }) {
                if (result.isCancelled) return@enqueue
                val available = synchronized(lock) {
                    generation == historyGeneration && session.id !in deleting &&
                        (session.demo || _current.value?.id == session.id || _history.value.any { it.id == session.id })
                }
                if (!available) throw IllegalStateException("The session was deleted or cleared before sharing")
                val contents = SweepReports.text(session, identifiers, coordinates)
                val directory = File(context.cacheDir, "export")
                if (!directory.isDirectory && !directory.mkdirs()) throw java.io.IOException("Unable to create report cache")
                val cutoff = System.currentTimeMillis() - 24 * 60 * 60_000L
                directory.listFiles().orEmpty().filter { it.lastModified() < cutoff }.forEach {
                    if (!it.delete()) throw java.io.IOException("Unable to remove an expired report")
                }
                val report = File(directory, "SWEEP-report-${System.currentTimeMillis()}-${UUID.randomUUID()}.txt")
                try {
                    report.writeText(contents, Charsets.UTF_8)
                    result.complete(report)
                } catch (failure: Exception) {
                    report.delete()
                    throw failure
                }
            }
        }
        return result
    }

    private fun mayPersist(generation: Long, id: String? = null): Boolean = synchronized(lock) {
        generation == historyGeneration && (id == null || id !in deleting)
    }

    private fun refreshDevices() {
        devices.refresh(catalog, 45)
        observationsSinceRefresh = 0
        lastDeviceRefreshAt = SystemClock.elapsedRealtime()
    }

    private fun clearExportCache() {
        val exports = File(context.cacheDir, "export")
        if (exports.exists() && !exports.deleteRecursively()) throw java.io.IOException("Unable to clear shared report cache")
    }

    fun reportError(message: String) { _error.value = message }
    private fun battery(): Int? = (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }

    private companion object {
        const val STARTUP_TIMEOUT_MS = 10_000L
        const val SCANNER_STOPPING_MESSAGE = "The previous scanner is still stopping. Try starting again in a moment."
    }
}

/** Pure ownership gate: finishing a report does not release its service's teardown lease. */
internal class SweepServiceOwnership {
    private data class Lease(val sessionId: String, val instanceId: String? = null, val running: Boolean = false)
    private var lease: Lease? = null

    @Synchronized fun reserve(sessionId: String): Boolean {
        if (lease != null) return false
        lease = Lease(sessionId)
        return true
    }

    @Synchronized fun attachPending(instanceId: String): String? {
        val owner = lease ?: return null
        if (owner.instanceId != null && owner.instanceId != instanceId) return null
        lease = owner.copy(instanceId = instanceId)
        return owner.sessionId
    }

    @Synchronized fun claim(sessionId: String, instanceId: String): Boolean {
        if (lease?.sessionId != sessionId) return false
        return attachPending(instanceId) == sessionId
    }

    @Synchronized fun started(sessionId: String, instanceId: String): Boolean {
        if (!owns(sessionId, instanceId)) return false
        lease = lease!!.copy(running = true)
        return true
    }

    @Synchronized fun owns(sessionId: String, instanceId: String): Boolean =
        lease?.let { it.sessionId == sessionId && it.instanceId == instanceId } == true

    @Synchronized fun ownsSession(sessionId: String): Boolean = lease?.sessionId == sessionId
    @Synchronized fun awaitingStart(sessionId: String): Boolean = lease?.let { it.sessionId == sessionId && !it.running } == true
    @Synchronized fun isHeld(): Boolean = lease != null

    @Synchronized fun releaseUncreated(sessionId: String): Boolean {
        if (lease?.let { it.sessionId == sessionId && it.instanceId == null } != true) return false
        lease = null
        return true
    }

    @Synchronized fun release(sessionId: String, instanceId: String): Boolean {
        if (!owns(sessionId, instanceId)) return false
        lease = null
        return true
    }
}

/** One packet updates sticky decoded state before a following packet can replace it. */
internal fun sweepFindingForObservation(
    devices: DeviceStore,
    observation: Observation,
    catalog: List<Fleet>,
    previous: SweepFinding?,
): SweepFinding {
    val mapped = SweepMapper.finding(devices.ingest(observation, catalog, 45), catalog)
    val decoded = OpenDroneId.fromFacts(observation.facts)
    return mapped.copy(dronePins = mapped.dronePins.map { pin ->
        pin.copy(observedAt = sweepPinObservationTime(pin, decoded, previous?.dronePins.orEmpty(), observation.at))
    })
}

/** A retained coordinate does not become newer when a different message is received. */
internal fun sweepPinObservationTime(
    pin: SweepPin,
    decoded: PayloadLocation,
    previous: List<SweepPin>,
    observedAt: Long,
): Long? {
    val decodedHere = if (pin.kind == SweepPinKind.AIRCRAFT) {
        PayloadLocation.validCoord(decoded.lat, decoded.lon) &&
            decoded.lat == pin.latitude && decoded.lon == pin.longitude
    } else {
        val decodedKind = when (decoded.opKind) {
            OperatorLoc.TAKEOFF -> SweepPinKind.TAKEOFF
            OperatorLoc.LIVE -> SweepPinKind.CONTROL_STATION
            OperatorLoc.FIXED -> SweepPinKind.FIXED_OPERATOR
            else -> SweepPinKind.UNSPECIFIED_OPERATOR
        }
        PayloadLocation.validCoord(decoded.opLat, decoded.opLon) &&
            decoded.opLat == pin.latitude && decoded.opLon == pin.longitude && decodedKind == pin.kind
    }
    if (decodedHere) return observedAt
    return previous.firstOrNull {
        it.kind == pin.kind && it.latitude == pin.latitude && it.longitude == pin.longitude
    }?.observedAt
}

/** FIFO mutation ordering is established at enqueue time, before dispatcher scheduling. */
internal class SweepPersistenceQueue(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private data class Mutation(val operation: () -> Unit, val onFailure: (Throwable) -> Unit)
    private val mutations = Channel<Mutation>(Channel.UNLIMITED)

    init {
        scope.launch(dispatcher) {
            for (mutation in mutations) {
                try { mutation.operation() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { mutation.onFailure(failure) }
            }
        }
    }

    fun enqueue(onFailure: (Throwable) -> Unit = {}, operation: () -> Unit) {
        mutations.trySend(Mutation(operation, onFailure)).getOrThrow()
    }
}
