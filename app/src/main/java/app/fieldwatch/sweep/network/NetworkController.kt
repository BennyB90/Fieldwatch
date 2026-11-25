package app.fieldwatch.sweep.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import app.fieldwatch.BuildConfig
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
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

interface NetworkModeHost { val networkController: NetworkController }
data class NetworkAvailability(val ready: Boolean, val message: String)

/** User-directed discovery only. No VPN, boot work, endpoint requests or automatic resume. */
class NetworkController(context: Context, radioGate: SweepRadioGate) {
    private val context = context.applicationContext
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = NetworkSessionStore(File(context.filesDir, "sweep-network"))
    private val lease = NetworkRunLease(radioGate)
    private val writes = Channel<Unit>(Channel.CONFLATED)
    private var revision = 0L
    private var desired: Pair<Long, List<NetworkSession>>? = null
    private var clearDiskRequested = false
    private var pendingPersistence = false
    private var saving = false
    private var importing = false
    private var loaded = false
    private var loading = false
    private var visible = false
    private var visibilityEpoch = 0L
    private var radio: NetworkDiscoveryRadio? = null
    private var receiverSessionId: String? = null
    private var cleanupOwner: String? = null
    private var timeout: Job? = null
    private val _current = MutableStateFlow<NetworkSession?>(null)
    val current = _current.asStateFlow()
    private val _history = MutableStateFlow<List<NetworkSession>>(emptyList())
    val history = _history.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _initializing = MutableStateFlow(true)
    val initializing = _initializing.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            for (signal in writes) {
                val work = synchronized(lock) {
                    desired?.let { saving = true; updateBusy(); Triple(it.first, it.second, clearDiskRequested) }
                } ?: continue
                val result = runCatching {
                    if (work.third) store.deleteAll()
                    else store.replace(work.second)
                }
                synchronized(lock) {
                    saving = false
                    if (revision == work.first) {
                        if (result.isSuccess) {
                            desired = null
                            pendingPersistence = false
                            if (work.third) { clearDiskRequested = false; loaded = true }
                        } else {
                            pendingPersistence = true
                            _error.value = "Network history changes are not confirmed on disk. Retry saving before starting another operation."
                        }
                    }
                    updateBusy()
                }
            }
        }
    }

    fun initialize() {
        synchronized(lock) {
            if (loaded || loading || clearDiskRequested) return
            loading = true; _initializing.value = true; updateBusy()
        }
        scope.launch(Dispatchers.IO) {
            val result = runCatching { store.recoverInterrupted() }
            withContext(Dispatchers.Main) {
                synchronized(lock) {
                    loading = false
                    _initializing.value = false
                    if (result.isSuccess) {
                        _history.value = result.getOrThrow()
                        loaded = true
                    } else _error.value = "Network history could not be loaded. Retry, or explicitly clear it before continuing."
                    updateBusy()
                }
            }
        }
    }

    fun onVisible() {
        synchronized(lock) {
            visible = true
            val session = _current.value
            if (session?.state == NetworkSessionState.ACTIVE && !lease.accepts(session.id, SystemClock.elapsedRealtime(), true)) {
                finishLocked(false, "The 60-second discovery window ended.")
            }
        }
    }

    fun onHidden() = synchronized(lock) { visible = false; visibilityEpoch++; finishLocked(true, "Discovery stopped when its screen left view. Start again explicitly.") }

    fun availability(): NetworkAvailability = synchronized(lock) {
        when {
            !BuildConfig.SWEEP_NETWORK_ENABLED -> NetworkAvailability(false, "Network discovery is available in the network-enabled SWEEP + Radio build.")
            !loaded -> NetworkAvailability(false, "Network history must finish loading before discovery starts.")
            pendingPersistence -> NetworkAvailability(false, "Retry saving the pending network-history change first.")
            cleanupOwner != null || radio?.hasPendingCleanup() == true -> NetworkAvailability(false, "Android has not confirmed discovery cleanup. Retry cleanup; if it remains blocked, force-stop and reopen the app before another live scan.")
            importing || saving -> NetworkAvailability(false, "Wait for the current network-history operation to finish.")
            Build.VERSION.SDK_INT < 33 -> NetworkAvailability(false, "Live network-scoped discovery requires Android 13 or newer. Router imports and saved reports remain available.")
            !visible -> NetworkAvailability(false, "Open the network discovery screen before starting.")
            else -> runCatching {
                val active = connectivity.activeNetwork
                val caps = active?.let(connectivity::getNetworkCapabilities)
                when {
                    connectivity.allNetworks.any { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true } -> NetworkAvailability(false, "End the VPN or use a router export. Discovery needs a directly connected Wi-Fi network.")
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true -> NetworkAvailability(false, "Connect to an authorized Wi-Fi network first.")
                    else -> NetworkAvailability(true, "Ready to request advertised services on the current Wi-Fi network.")
                }
            }.getOrElse { NetworkAvailability(false, "Android could not confirm the connected Wi-Fi network.") }
        }
    }

    fun start(authorized: Boolean) = synchronized(lock) {
        if (!authorized) { _error.value = "Confirm that you own, manage or are authorized to inspect this network."; return@synchronized }
        if (_current.value?.state == NetworkSessionState.ACTIVE) return@synchronized
        val available = availability()
        if (!available.ready) { _error.value = available.message; return@synchronized }
        val selectedNetwork = connectivity.activeNetwork ?: return@synchronized
        val id = UUID.randomUUID().toString()
        if (!lease.acquire(id, SystemClock.elapsedRealtime())) {
            _error.value = "End the room scan, Travel mode or radar connection before network discovery."
            return@synchronized
        }
        _error.value = null
        _current.value = NetworkSession(id = id, startedAt = System.currentTimeMillis(), source = NetworkSource.NSD)
        receiverSessionId = id
        // Retain the native resolver across sessions. Older Android 13 builds cannot
        // cancel a resolve, so its pending terminal callback still owns that slot.
        val receiver = runCatching { radio ?: NetworkDiscoveryRadio(context,
            onService = { result -> receiverSessionId?.let { callbackId -> scope.launch { receive(callbackId, result) } } },
            onStatus = { event ->
                val callbackId = receiverSessionId
                scope.launch {
                    synchronized(lock) {
                        // Consult current native ownership, rather than a queued old status.
                        if (radio?.hasPendingCleanup() != true) cleanupOwner?.let { owner ->
                            lease.release(owner); cleanupOwner = null
                        }
                        if (callbackId != null) status(callbackId, event)
                    }
                }
            },
        ) }.getOrElse {
            finishLocked(true, "Android could not prepare network discovery. Check Wi-Fi and permissions, then start again.")
            return@synchronized
        }
        radio = receiver
        checkpointLocked()
        timeout = scope.launch { delay(NetworkRunLease.WINDOW_MS); synchronized(lock) { if (lease.owns(id)) finishLocked(false, "The 60-second discovery window ended.") } }
        runCatching { receiver.start(selectedNetwork) }.onFailure {
            finishLocked(true, "Android could not start network discovery. Check Wi-Fi and permissions, then start again.")
        }
    }

    private fun receive(id: String, result: NetworkDiscoveryService) = synchronized(lock) {
        val session = _current.value ?: return@synchronized
        if (session.id != id || session.state != NetworkSessionState.ACTIVE || !lease.accepts(id, SystemClock.elapsedRealtime(), visible)) return@synchronized
        val now = System.currentTimeMillis()
        if (result.observedAt < session.startedAt || result.observedAt > now) return@synchronized
        val prior = session.endpoints.find { it.id == result.key }
        if (prior?.lastSeenAt != null && result.observedAt < prior.lastSeenAt) return@synchronized
        if (prior == null && session.endpoints.size >= 128) return@synchronized
        val endpoint = NetworkEndpoint(id = result.key, serviceType = result.serviceType,
            firstSeenAt = prior?.firstSeenAt ?: result.observedAt, lastSeenAt = result.observedAt,
            source = NetworkSource.NSD, instanceName = result.instanceName,
            hostnames = result.hostnames.toList(), addresses = result.addresses.toList(), port = result.port,
            txt = result.txt.toMap())
        _current.value = session.copy(endpoints = session.endpoints.filterNot { it.id == endpoint.id } + endpoint)
        checkpointLocked()
    }

    private fun status(id: String, status: NetworkDiscoveryStatus) = synchronized(lock) {
        val session = _current.value ?: return@synchronized
        if (session.id != id || session.state != NetworkSessionState.ACTIVE || !lease.owns(id)) return@synchronized
        if (!lease.accepts(id, SystemClock.elapsedRealtime(), visible)) {
            finishLocked(!visible, "The discovery window ended or its screen left view.")
            return@synchronized
        }
        _current.value = session.copy(coverage = NetworkCoverage(discoveryStarted = status.typesStarted.isNotEmpty(),
            serviceTypesRequested = status.typesRequested,
            resolvedServices = status.resolved, unresolvedServices = status.unresolved,
            droppedServices = status.dropped, scopedToNetwork = status.scoped,
            typesStarted = status.typesStarted, typesFailed = status.typesFailed,
            status = status.message))
        checkpointLocked()
        if (!status.running) finishLocked(true, status.message)
    }

    fun stop(interrupted: Boolean = false) = synchronized(lock) { finishLocked(interrupted, "Discovery stopped.") }

    private fun finishLocked(interrupted: Boolean, reason: String) {
        val session = _current.value ?: return
        if (session.state != NetworkSessionState.ACTIVE) return
        timeout?.cancel(); timeout = null
        // The logical lease is retained while platform stop calls fence old callbacks.
        receiverSessionId = null
        cleanupOwner = session.id
        runCatching { radio?.stop() }
        if (radio?.hasPendingCleanup() != true) {
            lease.release(session.id)
            cleanupOwner = null
        }
        val now = System.currentTimeMillis()
        val validEnd = now.takeIf { it >= session.startedAt && session.endpoints.all { row -> row.lastSeenAt == null || row.lastSeenAt <= it } }
        val finished = session.copy(endedAt = validEnd,
            state = if (interrupted || validEnd == null) NetworkSessionState.INTERRUPTED else NetworkSessionState.COMPLETE,
            coverage = session.coverage.copy(status = if (validEnd == null) "Discovery stopped after the clock changed; the end time is unknown." else reason))
        _current.value = finished
        _history.value = (listOf(finished) + _history.value).distinctBy { it.id }.take(20)
        checkpointLocked()
    }

    fun importRouter(bytes: ByteArray, authorized: Boolean) {
        val claim = synchronized(lock) {
            if (!authorized) { _error.value = "Confirm permission to use this router's device inventory."; return }
            if (!loaded || importing || saving || pendingPersistence || !visible || _current.value?.state == NetworkSessionState.ACTIVE) {
                _error.value = "End discovery and finish pending history operations before importing a router export."; return
            }
            importing = true; updateBusy(); revision to visibilityEpoch
        }
        scope.launch(Dispatchers.IO) {
            val importedAt = System.currentTimeMillis()
            val parsed = runCatching { RouterInventoryParser.parse(bytes, importedAt) }
            withContext(Dispatchers.Main) {
                synchronized(lock) {
                    importing = false
                    if (claim.first != revision || claim.second != visibilityEpoch || !visible) { updateBusy(); return@synchronized }
                    if (parsed.isFailure) {
                        _error.value = "Router export was rejected: ${parsed.exceptionOrNull()?.message ?: "invalid format"}"
                        updateBusy(); return@synchronized
                    }
                    val data = parsed.getOrThrow()
                    val session = NetworkSession(id = UUID.randomUUID().toString(), startedAt = importedAt,
                        endedAt = importedAt, state = NetworkSessionState.COMPLETE, source = NetworkSource.ROUTER_IMPORT,
                        capturedAt = data.capturedAt, networkLabel = data.networkLabel, endpoints = data.endpoints,
                        coverage = NetworkCoverage(status = "Imported router report. Current connection and recording activity have not been independently verified."))
                    _history.value = (listOf(session) + _history.value).distinctBy { it.id }.take(20)
                    _current.value = session
                    _error.value = null
                    checkpointLocked(); updateBusy()
                }
            }
        }
    }

    fun delete(id: String) = synchronized(lock) {
        if (!loaded || loading) { _error.value = "Wait for history loading before deleting a snapshot."; return@synchronized }
        if (_current.value?.id == id) { finishLocked(true, "Snapshot deleted."); _current.value = null }
        _history.value = _history.value.filterNot { it.id == id }
        checkpointLocked()
    }

    fun clearAll() = synchronized(lock) {
        if (loading) { _error.value = "Wait for history loading before clearing it."; return@synchronized }
        finishLocked(true, "Network history clearing requested.")
        _current.value = null; _history.value = emptyList()
        clearDiskRequested = true
        _error.value = null
        checkpointLocked()
    }

    fun retrySaving() {
        synchronized(lock) {
            if (!loaded && !clearDiskRequested) { initialize(); return }
            if (desired != null) { _error.value = null; writes.trySend(Unit) }
        }
    }

    fun clearError() { _error.value = null }
    fun reportError(message: String) { _error.value = message }

    fun retryCleanup() = synchronized(lock) {
        if (cleanupOwner == null) return@synchronized
        runCatching { radio?.retryCleanup() }.onFailure {
            _error.value = "Android cleanup remains unconfirmed. Force-stop and reopen the app if retrying does not finish it."
        }
        if (radio?.hasPendingCleanup() != true) cleanupOwner?.let { owner ->
            lease.release(owner); cleanupOwner = null
        }
    }

    private fun checkpointLocked() {
        revision++
        val sessions = (listOfNotNull(_current.value) + _history.value).distinctBy { it.id }.sortedByDescending { it.startedAt }.take(20)
        desired = revision to sessions
        pendingPersistence = true
        writes.trySend(Unit)
    }

    private fun updateBusy() { _busy.value = loading || saving || importing }
}
