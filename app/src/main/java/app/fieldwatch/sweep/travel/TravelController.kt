package app.fieldwatch.sweep.travel

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import app.fieldwatch.domain.Observation
import app.fieldwatch.sweep.SweepRadioGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** Root app supplies the controller; this interface does not alter normal Fieldwatch. */
interface TravelModeHost { val travelController: TravelController }

data class TravelAvailability(val ready: Boolean, val message: String)

/** Explicit, local mode owner. No worker, boot receiver, network client, or auto-resume flag. */
class TravelController(
    context: Context,
    private val canStart: () -> Boolean = { true },
    private val radioGate: SweepRadioGate = SweepRadioGate(),
) {
    private val context = context.applicationContext
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = TravelStore(File(context.filesDir, "sweep-travel"))
    private val lease = TravelServiceLease(radioGate)
    private val writes = Channel<Unit>(Channel.CONFLATED)
    private var desiredArchive: Pair<Long, TravelArchive>? = null
    private var clearRequested = false
    private var generation = 0L
    private var initializing = true
    private var ignored = emptySet<String>()
    private var qualifier = TravelTrackerQualifier()
    private var stopService: ((Boolean) -> Unit)? = null
    private var startupWatchdog: Job? = null
    private val _current = MutableStateFlow<TravelSession?>(null)
    val current = _current.asStateFlow()
    private val _history = MutableStateFlow<List<TravelSession>>(emptyList())
    val history = _history.asStateFlow()
    private val _starting = MutableStateFlow(false)
    val starting = _starting.asStateFlow()
    private val _clearing = MutableStateFlow(false)
    val clearing = _clearing.asStateFlow()
    private val _locationErasurePending = MutableStateFlow(false)
    val locationErasurePending = _locationErasurePending.asStateFlow()
    private val _ignoredAddresses = MutableStateFlow<Set<String>>(emptySet())
    val ignoredAddresses = _ignoredAddresses.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            for (signal in writes) {
                val mutation = synchronized(lock) {
                    if (clearRequested) {
                        clearRequested = false
                        Triple(generation, true, null)
                    } else {
                        val pending = desiredArchive
                        desiredArchive = null
                        Triple(pending?.first ?: generation, false, pending?.second)
                    }
                }
                try {
                    if (mutation.second) {
                        store.deleteAll()
                        synchronized(lock) {
                            if (generation == mutation.first) {
                                _clearing.value = false
                                _locationErasurePending.value = false
                            }
                        }
                    } else if (mutation.third != null) {
                        if (synchronized(lock) { _locationErasurePending.value && generation == mutation.first }) store.markLocationErasurePending()
                        val saved = store.save(mutation.third!!, System.currentTimeMillis()) { replace ->
                            synchronized(lock) {
                                if (mutation.first != generation || clearRequested || _clearing.value) false
                                else { replace(); true }
                            }
                        }
                        synchronized(lock) {
                            if (saved && generation == mutation.first && _locationErasurePending.value && TravelPrivacy.coordinateFree(mutation.third!!)) {
                                store.completeLocationErasure()
                                _locationErasurePending.value = false
                                if (_error.value in setOf(ERASURE_FAILED, ERASURE_UNCONFIRMED, ERASURE_BEFORE_START)) _error.value = null
                            }
                        }
                    }
                } catch (_: Exception) {
                    synchronized(lock) {
                        if (generation == mutation.first) {
                            _error.value = if (mutation.second) "Travel observations could not be deleted. Try clearing them again."
                            else if (_locationErasurePending.value) ERASURE_FAILED
                            else "Travel observations could not be saved."
                            if (mutation.second) _clearing.value = false
                        }
                    }
                }
            }
        }
    }

    fun initialize() {
        val initializedGeneration = synchronized(lock) { generation }
        scope.launch(Dispatchers.IO) {
            val pendingErasure = runCatching { store.locationErasurePending() }.getOrDefault(false)
            val archive = runCatching { store.load(System.currentTimeMillis()) }
            synchronized(lock) {
                if (!initializing) return@synchronized
                if (generation != initializedGeneration) { initializing = false; return@synchronized }
                archive.onSuccess {
                    _locationErasurePending.value = pendingErasure
                    ignored = it.ignoredAddresses
                    _ignoredAddresses.value = ignored
                    _history.value = it.sessions
                }.onFailure {
                    _locationErasurePending.value = pendingErasure
                    _error.value = if (pendingErasure) ERASURE_UNCONFIRMED
                    else "Saved travel observations are unavailable. Nothing has resumed."
                }
                initializing = false
                if (archive.isSuccess) checkpointLocked()
            }
        }
    }

    fun availability(): TravelAvailability {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val nearby = Build.VERSION.SDK_INT < 31 || (granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT))
        val locationOn = (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.isLocationEnabled == true
        val bluetoothOn = nearby && runCatching { (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true }.getOrDefault(false)
        val notifications = notificationsAvailable()
        return when {
            !context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) -> TravelAvailability(false, "Bluetooth LE is unavailable.")
            !fine || !nearby -> TravelAvailability(false, "Grant Bluetooth and location permissions needed by Android scanning. Optional GPS correlation is a separate choice.")
            !notifications.ready -> notifications
            !locationOn -> TravelAvailability(false, "Turn on Android Location for Bluetooth scanning. Phone GPS is not collected unless you choose location correlation.")
            !bluetoothOn -> TravelAvailability(false, "Bluetooth is off.")
            else -> TravelAvailability(true, "Bluetooth is ready")
        }
    }

    internal fun notificationsAvailable(): TravelAvailability = runCatching {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        TravelVisibility.available(
            Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS),
            NotificationManagerCompat.from(context).areNotificationsEnabled(),
            manager.getNotificationChannel(TravelObservationService.CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE,
            manager.getNotificationChannel(TravelObservationService.ALERT_CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE,
        )
    }.getOrElse { TravelAvailability(false, "Android notification availability could not be checked. Review SWEEP notifications in Android settings.") }

    private fun locationAccessAvailable(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
        (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.isLocationEnabled == true

    fun locationAllowed(): Boolean = current.value?.let { it.locationConsent && travelWindowOpen(it, SystemClock.elapsedRealtime()) } == true && locationAccessAvailable()

    fun start(locationConsent: Boolean = false) {
        val available = availability()
        if (!available.ready) { _error.value = available.message; return }
        // Caller UI checks are outside our lock. The shared atomic gate is authoritative.
        if (!canStart()) { _error.value = "End the other radio mode before starting Travel."; return }
        val id = synchronized(lock) {
            if (initializing || _clearing.value) { _error.value = "Travel history is loading or being cleared. Try again shortly."; return }
            if (_locationErasurePending.value) { _error.value = ERASURE_BEFORE_START; return }
            if (lease.held()) { _error.value = "Travel is running or still stopping. Wait for teardown before restarting."; return }
            val sessionId = UUID.randomUUID().toString()
            if (!lease.reserve(sessionId)) { _error.value = "End the RF scan or radar mode before starting Travel."; return }
            _error.value = null
            qualifier = TravelTrackerQualifier()
            _current.value = TravelSession(sessionId, System.currentTimeMillis(), SystemClock.elapsedRealtime(), locationConsent = locationConsent)
            _starting.value = true
            startupWatchdog = scope.launch { delay(10_000L); startupTimedOut(sessionId) }
            checkpointLocked()
            sessionId
        }
        runCatching {
            checkNotNull(ContextCompat.startForegroundService(context, Intent(context, TravelObservationService::class.java)
                .putExtra(TravelObservationService.EXTRA_SESSION_ID, id)))
        }.onFailure { startupTimedOut(id) }
    }

    fun hasServiceRequest(): Boolean = synchronized(lock) { lease.held() }

    internal fun attachPending(instanceId: String, stopper: (Boolean) -> Unit): String? = synchronized(lock) {
        val id = lease.sessionId() ?: return@synchronized null
        if (_current.value?.let { it.id == id && it.state == TravelSessionState.ACTIVE } != true || !lease.attach(id, instanceId)) return@synchronized null
        stopService = stopper
        id
    }

    internal fun attach(id: String, instanceId: String, stopper: (Boolean) -> Unit): Boolean = synchronized(lock) {
        if (_current.value?.let { it.id == id && it.state == TravelSessionState.ACTIVE } != true || !lease.attach(id, instanceId)) return@synchronized false
        stopService = stopper
        true
    }

    internal fun started(id: String, instanceId: String): Boolean = synchronized(lock) {
        if (!lease.owns(id, instanceId) || _current.value?.state != TravelSessionState.ACTIVE) return@synchronized false
        _starting.value = false
        startupWatchdog?.cancel(); startupWatchdog = null
        _current.value = _current.value?.copy(status = "Observing Bluetooth; Android may interrupt reception")
        true
    }

    fun stop() {
        val stopper = synchronized(lock) {
            val current = _current.value ?: return
            if (current.state != TravelSessionState.ACTIVE) return
            if (lease.releaseUncreated(current.id)) {
                finishLocked(current.id, interrupted = false)
                null
            } else stopService
        }
        stopper?.invoke(false)
    }

    private fun startupTimedOut(id: String) {
        val stopper = synchronized(lock) {
            if (!lease.ownsSession(id) || _current.value?.state != TravelSessionState.ACTIVE) return
            if (lease.releaseUncreated(id)) {
                finishLocked(id, true, "Android did not start Travel observation. Start again explicitly.")
                null
            } else stopService
        }
        stopper?.invoke(true)
    }

    internal fun record(id: String, instanceId: String, observation: Observation, elapsedMs: Long, fix: TravelLocation?) {
        synchronized(lock) {
            val session = _current.value ?: return
            if (!lease.owns(id, instanceId) || session.id != id || session.state != TravelSessionState.ACTIVE || !observation.fresh) return
            if (TravelSampleTiming.elapsed(observation, session, System.currentTimeMillis(), SystemClock.elapsedRealtime()) != elapsedMs) return
            val label = qualifier.label(observation)
            if (label == null) {
                val cleaned = TravelPhoneEvidence.removeGenericRow(session, observation, qualifier)
                if (cleaned != session) {
                    _current.value = cleaned
                    TravelObservationService.clearNotice(context)
                    checkpointLocked()
                }
                return
            }
            val sample = TravelSample(observation.at, elapsedMs, observation.rssi, if (locationAllowed()) fix else null)
            _current.value = TravelSamples.record(session, observation.mac, label, sample, ignored)
        }
    }

    internal fun receptionStatus(id: String, instanceId: String, bluetoothRunning: Boolean, gpsAvailable: Boolean,
        gpsRequested: Boolean, fix: TravelLocation?, elapsedMs: Long) = synchronized(lock) {
        val session = _current.value ?: return@synchronized
        if (!lease.owns(id, instanceId) || session.id != id || session.state != TravelSessionState.ACTIVE) return@synchronized
        _current.value = session.copy(status = TravelReceptionStatus.text(bluetoothRunning, session.locationConsent,
            locationAllowed(), gpsAvailable, gpsRequested, fix, elapsedMs, _locationErasurePending.value))
    }

    /** Publish under the same lock that clears notices and redacts observations. */
    internal fun publishAssessments(id: String, instanceId: String, publish: (TravelAssessment) -> Unit) = synchronized(lock) {
        val session = _current.value ?: return@synchronized
        if (!lease.owns(id, instanceId) || session.id != id || session.state != TravelSessionState.ACTIVE) return@synchronized
        session.assessments.filter { it.address !in ignored &&
            (it.kind != TravelEvidenceKind.DISTINCT_PHONE_LOCATIONS || locationAllowed()) }.forEach(publish)
    }

    internal fun reconcileLocationConsent() = synchronized(lock) {
        if (_current.value?.locationConsent == true && !locationAccessAvailable()) {
            redactLocationLocked()
            _error.value = "GPS correlation stopped because location access changed."
        }
    }

    internal fun noteDropped(id: String, instanceId: String) = synchronized(lock) {
        if (lease.owns(id, instanceId) && _current.value?.state == TravelSessionState.ACTIVE) {
            _current.value = _current.value?.let { it.copy(droppedSamples = it.droppedSamples + 1) }
        }
    }

    internal fun checkpoint(id: String, instanceId: String) = synchronized(lock) {
        if (lease.owns(id, instanceId)) checkpointLocked()
    }

    internal fun finish(id: String, instanceId: String, interrupted: Boolean, reason: String? = null) = synchronized(lock) {
        if (lease.owns(id, instanceId)) finishLocked(id, interrupted, reason)
    }

    private fun finishLocked(id: String, interrupted: Boolean, reason: String? = null) {
        val current = _current.value ?: return
        if (current.id != id || current.state != TravelSessionState.ACTIVE) return
        startupWatchdog?.cancel(); startupWatchdog = null
        _starting.value = false
        val finished = current.copy(state = if (interrupted) TravelSessionState.INTERRUPTED else TravelSessionState.COMPLETE,
            endedAt = System.currentTimeMillis(), status = "Travel mode stopped", error = reason)
        _current.value = finished
        _history.value = (listOf(finished) + _history.value).distinctBy { it.id }.take(TravelLimits.MAX_SESSIONS)
        if (reason != null) _error.value = reason
        checkpointLocked()
    }

    internal fun destroyed(id: String, instanceId: String) = synchronized(lock) {
        if (!lease.owns(id, instanceId)) return@synchronized
        finishLocked(id, true, "Android ended Travel observation. Nothing will resume until you start it again.")
        lease.release(id, instanceId)
        stopService = null
    }

    fun setIgnored(address: String, ignored: Boolean) = synchronized(lock) {
        val identity = normalizedTravelAddress(address) ?: return@synchronized
        if (ignored && identity !in this.ignored && this.ignored.size >= TravelLimits.MAX_IGNORED) { _error.value = "The familiar-address list is full. Remove an entry first."; return@synchronized }
        this.ignored = if (ignored) this.ignored + identity else this.ignored - identity
        _ignoredAddresses.value = this.ignored
        if (ignored) {
            fun without(session: TravelSession) = session.copy(trackers = session.trackers.filterNot { it.address == identity }, assessments = session.assessments.filterNot { it.address == identity })
            _current.value = _current.value?.let(::without)
            _history.value = _history.value.map(::without)
            TravelObservationService.clearNotice(context)
        }
        checkpointLocked()
    }

    /** Revoking this separate consent erases saved phone coordinates and stops future correlation. */
    fun disableLocation() = synchronized(lock) {
        if (initializing) { _error.value = "Travel history is loading. Wait before erasing saved locations."; return@synchronized }
        _error.value = null
        redactLocationLocked()
    }

    private fun redactLocationLocked() {
        generation++
        desiredArchive = null
        _locationErasurePending.value = true
        _current.value = _current.value?.let { TravelPrivacy.withoutLocations(it, ignored) }
        _history.value = _history.value.map { TravelPrivacy.withoutLocations(it, ignored) }
        TravelObservationService.clearNotice(context)
        checkpointLocked()
    }

    fun deleteAll() {
        val stopper = synchronized(lock) {
            _error.value = null
            generation++
            desiredArchive = null
            clearRequested = true
            _clearing.value = true
            _current.value = null
            _history.value = emptyList()
            ignored = emptySet(); _ignoredAddresses.value = emptySet()
            qualifier = TravelTrackerQualifier()
            _starting.value = false
            startupWatchdog?.cancel(); startupWatchdog = null
            lease.sessionId()?.let { lease.releaseUncreated(it) }
            writes.trySend(Unit)
            TravelObservationService.clearNotice(context)
            stopService
        }
        stopper?.invoke(true)
    }

    private fun checkpointLocked() {
        if (_clearing.value || initializing) return
        val now = System.currentTimeMillis()
        _history.value = _history.value.filter { it.startedAt in (now - TravelLimits.RETENTION_MS)..now }.take(TravelLimits.MAX_SESSIONS)
        val sessions = (_current.value?.let { listOf(it) }.orEmpty() + _history.value).distinctBy { it.id }
        desiredArchive = generation to TravelArchive(sessions, ignored)
        writes.trySend(Unit)
    }

    private fun granted(permission: String): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val ERASURE_FAILED = "Correlation stopped, but saved phone locations were not erased. Retry erasing locations or clear all Travel observations."
        const val ERASURE_UNCONFIRMED = "Saved phone location erasure is unconfirmed. Retry erasing locations or clear all Travel observations."
        const val ERASURE_BEFORE_START = "Saved phone location erasure is unconfirmed. Retry erasing locations before starting another Travel session."
    }
}

/** Process-local ownership cannot be reconstructed from saved observations. */
internal class TravelServiceLease(private val gate: SweepRadioGate) {
    private data class Lease(val id: String, val instance: String? = null)
    private var lease: Lease? = null
    fun reserve(id: String): Boolean {
        if (lease != null || !gate.acquire("travel:$id")) return false
        lease = Lease(id)
        return true
    }
    fun attach(id: String, instance: String): Boolean {
        val owner = lease ?: return false
        if (owner.id != id || (owner.instance != null && owner.instance != instance)) return false
        lease = owner.copy(instance = instance)
        return true
    }
    fun owns(id: String, instance: String) = lease?.let { it.id == id && it.instance == instance } == true
    fun ownsSession(id: String) = lease?.id == id
    fun held() = lease != null
    fun sessionId() = lease?.id
    fun releaseUncreated(id: String): Boolean {
        if (lease?.let { it.id == id && it.instance == null } != true) return false
        lease = null
        return gate.release("travel:$id")
    }
    fun release(id: String, instance: String): Boolean {
        if (!owns(id, instance)) return false
        lease = null
        return gate.release("travel:$id")
    }
}
