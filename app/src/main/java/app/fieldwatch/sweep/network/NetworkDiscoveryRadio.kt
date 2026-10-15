package app.fieldwatch.sweep.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.ext.SdkExtensions
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executor

data class NetworkDiscoveryService(
    val key: String,
    val instanceName: String,
    val serviceType: String,
    val addresses: List<String> = emptyList(),
    val hostnames: List<String> = emptyList(),
    val port: Int? = null,
    /** Not collected by this receiver; TXT values can contain credentials and private metadata. */
    val txt: Map<String, String> = emptyMap(),
    val resolved: Boolean = false,
    /** Result receipt time. Android mDNS results can be cached; this is not packet age. */
    val observedAt: Long,
    val resolutionError: String? = null,
)

data class NetworkDiscoveryStatus(
    val running: Boolean = false,
    val message: String = "Not started",
    val discovered: Int = 0,
    val resolved: Int = 0,
    val unresolved: Int = 0,
    val dropped: Int = 0,
    val scoped: Boolean = false,
    val typesStarted: List<String> = emptyList(),
    val typesFailed: List<String> = emptyList(),
    val typesRequested: List<String> = emptyList(),
    val cleanupPending: Boolean = false,
)

/**
 * User-started DNS-SD browsing on exactly the authorized, current default Wi-Fi network.
 * Caller must stop/close in Activity.onStop. A 60-second elapsed-time ceiling is also enforced.
 * No registration, endpoint socket, port scan, HTTP request, VPN, or payload inspection.
 * API 29-32 fail closed because NSD cannot scope returned services to one Network.
 * https://developer.android.com/reference/android/net/nsd/NsdManager
 */
class NetworkDiscoveryRadio(
    context: Context,
    private val onService: (NetworkDiscoveryService) -> Unit,
    private val onStatus: (NetworkDiscoveryStatus) -> Unit,
    private val onLost: (String) -> Unit = {},
) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executor { command -> main.post(command) }
    private val connectivity = this.context.getSystemService(ConnectivityManager::class.java)
    private val nsd = this.context.getSystemService(NsdManager::class.java)
    private val wifi = this.context.getSystemService(WifiManager::class.java)
    private val guard = NetworkDiscoveryGuard()
    private var selected: Network? = null
    private var token = 0L
    private var session = ""
    private var deadlineMs = 0L
    private var scoped = false
    private var closed = false
    private var poll: Runnable? = null
    private var watcher: ConnectivityManager.NetworkCallback? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var cleanupTask: Runnable? = null
    private var cleanupRunning = false
    private var stoppedReason = "Discovery stopped"
    private val discoveryCleanup = NetworkDiscoveryCleanup<NsdManager.DiscoveryListener>(8)
    private val resolverCleanup = NetworkDiscoveryCleanup<NsdManager.ResolveListener>(1)
    private val watcherCleanup = NetworkDiscoveryCleanup<ConnectivityManager.NetworkCallback>(1)
    private val multicastCleanup = NetworkDiscoveryCleanup<WifiManager.MulticastLock>(1)
    private val listeners = LinkedHashMap<String, NsdManager.DiscoveryListener>()
    private val startedTypes = HashSet<String>()
    private val failedTypes = HashSet<String>()
    private val requestedTypes = LinkedHashSet<String>()
    private val candidates = LinkedHashMap<String, Candidate>()
    private val pending = ArrayDeque<ResolveWork>()
    // Retain a cancelled old one-shot until its terminal callback; never overlap resolutions.
    private val nativeResolution = NetworkDiscoveryResolutionLease<NsdManager.ResolveListener, ResolveWork>()
    private var dropped = 0
    private var attempts = 0
    @Volatile private var current = NetworkDiscoveryStatus()

    private data class Candidate(var row: NetworkDiscoveryService, var lost: Boolean = false)
    private data class ResolveWork(val key: String, val info: NsdServiceInfo, val token: Long,
        var expired: Boolean = false, var deadlineMs: Long = 0L, var timeout: Runnable? = null)

    fun status(): NetworkDiscoveryStatus = current
    fun isRunning(): Boolean = current.running
    @Synchronized fun hasPendingCleanup(): Boolean = !guard.active(token) &&
        (discoveryCleanup.pending() || nativeResolution.busy() || watcherCleanup.pending() || multicastCleanup.pending())

    @Synchronized fun start(expectedNetwork: Network) {
        if (closed || current.running) return
        if (hasPendingCleanup()) { cleanupStatus(); runCleanup(); return }
        // Prior stop has already fenced and acknowledged all native resources. Do not emit
        // a stopped status here: the caller has just acquired ownership for this new run.
        cleanupTask?.let(main::removeCallbacks); cleanupTask = null
        candidates.clear(); startedTypes.clear(); failedTypes.clear(); requestedTypes.clear(); dropped = 0; attempts = 0; scoped = false
        if (!NetworkDiscoveryGuard.supported(Build.VERSION.SDK_INT)) {
            update("Live network discovery requires Android 13 or later; older NSD cannot verify the selected network", false)
            return
        }
        selected = expectedNetwork
        val source = source()
        if (!source.permitted) { finish("Network discovery permissions are unavailable"); return }
        if (source.vpn) { finish("Turn off the active VPN before starting local discovery"); return }
        if (!source.wifi || source.defaultNetwork != networkKey(expectedNetwork) || source.wifiInterface == null) {
            finish("Connect to the selected Wi-Fi network before starting discovery"); return
        }
        if (nsd == null || wifi == null || connectivity == null) { finish("Network discovery is unavailable on this phone"); return }
        session = UUID.randomUUID().toString()
        token = guard.begin(networkKey(expectedNetwork), source.wifiInterface)
        scoped = true
        deadlineMs = SystemClock.elapsedRealtime() + MAX_SESSION_MS
        update("Starting advertised-service discovery", true)
        if (!guard.active(token)) return // An owner callback may have stopped the session.
        try {
            multicast = wifi.createMulticastLock("sweep-network-discovery")
            multicastCleanup.track(multicast!!)
            multicast!!.setReferenceCounted(false)
            multicast!!.acquire()
            watchNetwork(token)
            startScoped(expectedNetwork, token)
            schedulePoll(token)
        } catch (_: SecurityException) {
            finish("Android denied network discovery permissions")
        } catch (_: RuntimeException) {
            finish("Android could not start network discovery")
        }
    }

    @SuppressLint("NewApi") // Reached only after the API33 guard in start().
    private fun startScoped(network: Network, generation: Long) {
        for (type in NetworkDiscoveryTypes.requested) {
            if (!guard.active(generation)) break
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = synchronized(this@NetworkDiscoveryRadio) {
                    if (!discoveryCleanup.contains(this)) return@synchronized
                    if (!guard.active(generation)) {
                        discoveryCleanup.requestStop(this, SystemClock.elapsedRealtime())
                        runCleanup(); return@synchronized
                    }
                    if (!verify(generation)) return@synchronized
                    startedTypes += type
                    update("Observing advertised services; results may be cached", true)
                }
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = synchronized(this@NetworkDiscoveryRadio) {
                    if (!discoveryCleanup.acknowledge(this)) return@synchronized
                    if (!guard.active(generation)) { cleanupStatus(); return@synchronized }
                    listeners.remove(type)
                    failedTypes += type
                    if (errorCode == PERMISSION_DENIED) finish("Android denied local-network discovery")
                    else if (listeners.isEmpty()) finish("Android could not browse the requested service types")
                    else update("Some requested service types could not be observed", true)
                }
                override fun onDiscoveryStopped(serviceType: String) = synchronized(this@NetworkDiscoveryRadio) {
                    if (!discoveryCleanup.acknowledge(this)) return@synchronized
                    if (!guard.active(generation)) { cleanupStatus(); return@synchronized }
                    listeners.remove(type)
                    failedTypes += type
                    if (listeners.isEmpty()) finish("Advertised-service discovery ended")
                    else update("Some requested service types stopped", true)
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = synchronized(this@NetworkDiscoveryRadio) {
                    if (discoveryCleanup.contains(this)) {
                        discoveryCleanup.requestStop(this, SystemClock.elapsedRealtime())
                        runCleanup()
                    }
                }
                override fun onServiceFound(info: NsdServiceInfo) = synchronized(this@NetworkDiscoveryRadio) {
                    found(info, type, generation)
                }
                override fun onServiceLost(info: NsdServiceInfo) = synchronized(this@NetworkDiscoveryRadio) {
                    if (!verifyService(generation, info)) return@synchronized
                    val actualType = NetworkDiscoveryTypes.canonical(info.serviceType.orEmpty()) ?: return@synchronized
                    if (actualType != type || !NetworkDiscoveryTypes.validName(info.serviceName.orEmpty())) return@synchronized
                    val key = NetworkDiscoveryTypes.key(session, actualType, info.serviceName)
                    candidates[key]?.let { row ->
                        row.lost = true
                        onLost(key)
                    }
                    Unit
                }
            }
            listeners[type] = listener
            if (!discoveryCleanup.track(listener)) { finish("Previous discovery cleanup remains pending"); break }
            requestedTypes += type
            update("Starting advertised-service discovery", true)
            if (!guard.active(generation)) break
            try {
                nsd!!.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, network, executor, listener)
            } catch (_: SecurityException) {
                finish("Android denied local-network discovery"); break
            } catch (_: RuntimeException) {
                listeners.remove(type); failedTypes += type
            }
        }
        if (guard.active(generation) && listeners.isEmpty()) finish("Android could not browse the requested service types")
    }

    @SuppressLint("NewApi")
    private fun found(info: NsdServiceInfo, requestedType: String, generation: Long) {
        if (!verifyService(generation, info)) return
        val type = NetworkDiscoveryTypes.canonical(info.serviceType.orEmpty())
        val name = info.serviceName.orEmpty()
        if (type != requestedType || !NetworkDiscoveryTypes.validName(name)) { drop(); return }
        val key = NetworkDiscoveryTypes.key(session, type, name)
        // One result/resolve attempt per advertised identity in a session, including reappearances.
        if (key in candidates) return
        if (candidates.size >= MAX_SERVICES) { drop(); return }
        val row = NetworkDiscoveryService(key, name, type, observedAt = System.currentTimeMillis())
        candidates[key] = Candidate(row)
        onService(row)
        if (!guard.active(generation)) return
        pending.addLast(ResolveWork(key, info, generation))
        update("Observing advertised services; addresses are not device identity", true)
        resolveNext()
    }

    @SuppressLint("NewApi", "Deprecated")
    private fun resolveNext() {
        if (!guard.active(token) || nativeResolution.busy() || attempts >= MAX_SERVICES) return
        while (pending.isNotEmpty()) {
            val work = pending.removeFirst()
            if (!verifyService(work.token, work.info)) return
            if (candidates[work.key]?.lost != false) continue
            attempts++
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = synchronized(this@NetworkDiscoveryRadio) {
                    terminal(this, work, null, errorCode)
                }
                override fun onServiceResolved(info: NsdServiceInfo) = synchronized(this@NetworkDiscoveryRadio) {
                    terminal(this, work, info, null)
                }
                override fun onResolutionStopped(info: NsdServiceInfo) = synchronized(this@NetworkDiscoveryRadio) {
                    terminal(this, work, null, null)
                }
                override fun onStopResolutionFailed(info: NsdServiceInfo, errorCode: Int) = synchronized(this@NetworkDiscoveryRadio) {
                    // A different stop failure does not prove that the old one-shot ended.
                    if (errorCode == NsdManager.FAILURE_OPERATION_NOT_RUNNING) terminal(this, work, null, null)
                }
            }
            if (!nativeResolution.acquire(listener, work)) { pending.addFirst(work); attempts--; return }
            resolverCleanup.track(listener)
            work.deadlineMs = SystemClock.elapsedRealtime() + RESOLVE_TIMEOUT_MS
            val timeout = Runnable { synchronized(this@NetworkDiscoveryRadio) {
                if (nativeResolution.work(listener) !== work || work.expired || !guard.active(work.token)) return@synchronized
                work.expired = true
                result(work, null, "Address resolution exceeded its wait limit")
                if (canCancelResolution()) {
                    resolverCleanup.requestStop(listener, SystemClock.elapsedRealtime())
                    runCleanup()
                }
                else finish("Address resolution timed out; discovery stopped")
            } }
            work.timeout = timeout
            main.postDelayed(timeout, RESOLVE_TIMEOUT_MS)
            try {
                // Keep the exact discovery info: it carries the network into resolution.
                nsd!!.resolveService(work.info, executor, listener)
            } catch (_: SecurityException) {
                nativeResolution.complete(listener); resolverCleanup.acknowledge(listener)
                cancelResolveTimeout(work); finish("Android denied service resolution")
            } catch (_: RuntimeException) {
                nativeResolution.complete(listener)
                resolverCleanup.acknowledge(listener)
                cancelResolveTimeout(work)
                result(work, null, "Android could not resolve this advertised service")
                continue
            }
            return
        }
    }

    private fun terminal(listener: NsdManager.ResolveListener, work: ResolveWork, info: NsdServiceInfo?, error: Int?) {
        if (nativeResolution.complete(listener) !== work) return
        resolverCleanup.acknowledge(listener)
        cancelResolveTimeout(work)
        if (guard.active(work.token) && !work.expired) {
            val androidReceiptMs = System.currentTimeMillis()
            if (error == PERMISSION_DENIED) { finish("Android denied service resolution"); return }
            if (SystemClock.elapsedRealtime() >= work.deadlineMs) result(work, null, "Resolution arrived after its wait limit", androidReceiptMs)
            else if (info != null) result(work, info, null, androidReceiptMs)
            else if (error != null) result(work, null, "Address resolution failed ($error)", androidReceiptMs)
        }
        // A terminal callback for a stopped generation may free the single resolver for a new one.
        if (guard.active(token)) resolveNext()
        else cleanupStatus()
    }

    @SuppressLint("NewApi", "Deprecated")
    private fun result(work: ResolveWork, info: NsdServiceInfo?, error: String?, androidReceiptMs: Long? = null) {
        if (!verify(work.token)) return
        val candidate = candidates[work.key]?.takeIf { !it.lost } ?: return
        if (info != null && !verifyService(work.token, info)) return
        if (info != null && (info.serviceName != candidate.row.instanceName ||
            NetworkDiscoveryTypes.canonical(info.serviceType.orEmpty()) != candidate.row.serviceType)) { drop(); return }
        val addresses = if (info == null) emptyList() else {
            val hosts = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
            hosts.mapNotNull { it.hostAddress }.distinct().take(MAX_ADDRESSES)
        }
        val row = candidate.row.copy(addresses = addresses,
            port = info?.port?.takeIf { it in 1..65_535 }, resolved = info != null,
            observedAt = NetworkDiscoveryReceiptTime.afterOutcome(candidate.row.observedAt, androidReceiptMs), resolutionError = error)
        candidate.row = row
        onService(row)
        if (guard.active(work.token)) update("Advertised services received; resolution is not a connection test", true)
    }

    @SuppressLint("NewApi") // start() has already enforced API33 before this network-scoped path.
    private fun watchNetwork(generation: Long) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = check()
            override fun onLost(network: Network) = check()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = check()
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = check()
            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) = synchronized(this@NetworkDiscoveryRadio) {
                if (network == selected && blocked && guard.active(generation)) finish("Android blocked the selected Wi-Fi network")
            }
            private fun check() = synchronized(this@NetworkDiscoveryRadio) { if (guard.active(generation)) verify(generation) }
        }
        watcher = callback
        watcherCleanup.track(callback)
        connectivity!!.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities().build(), callback, main)
    }

    private fun schedulePoll(generation: Long) {
        val task = object : Runnable {
            override fun run() = synchronized(this@NetworkDiscoveryRadio) {
                if (verify(generation)) main.postDelayed(this, POLL_MS)
                Unit
            }
        }
        poll = task
        main.postDelayed(task, POLL_MS)
    }

    private fun source(): NetworkDiscoverySource {
        val permitted = listOf(Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_MULTICAST_STATE, Manifest.permission.INTERNET).all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        } && (Build.VERSION.SDK_INT < 37 || context.applicationInfo.targetSdkVersion < 37 ||
            context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") == PackageManager.PERMISSION_GRANTED)
        if (!permitted || connectivity == null) return NetworkDiscoverySource(null, false, false, false, null)
        return runCatching {
            val active = connectivity.activeNetwork
            val caps = active?.let(connectivity::getNetworkCapabilities)
            val vpn = connectivity.allNetworks.any {
                connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
            NetworkDiscoverySource(active?.let(::networkKey), caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
                vpn, true, active?.let(connectivity::getLinkProperties)?.interfaceName)
        }.getOrElse { NetworkDiscoverySource(null, false, false, false, null) }
    }

    private fun verify(generation: Long): Boolean {
        if (!guard.active(generation)) return false
        if (SystemClock.elapsedRealtime() >= deadlineMs) { finish("Discovery session reached its 60-second limit"); return false }
        val currentSource = source()
        if (!guard.acceptsSource(generation, currentSource)) {
            finish(when {
                !currentSource.permitted -> "Network discovery permissions became unavailable"
                currentSource.vpn -> "A VPN became active; discovery stopped"
                else -> "Wi-Fi network changed; start a new authorized session"
            })
            return false
        }
        return true
    }

    @SuppressLint("NewApi")
    private fun verifyService(generation: Long, info: NsdServiceInfo): Boolean {
        if (!verify(generation)) return false
        if (!guard.acceptsService(generation, source(), info.network?.let(::networkKey))) { drop(); return false }
        return true
    }

    private fun drop() { if (dropped < Int.MAX_VALUE) dropped++; update("Some results could not be attributed or retained", current.running) }

    private fun update(message: String, running: Boolean) {
        val resolved = candidates.values.count { it.row.resolved }
        current = NetworkDiscoveryStatus(running, message, candidates.size, resolved, candidates.size - resolved,
            dropped, scoped,
            NetworkDiscoveryTypes.requested.filter { it in startedTypes },
            NetworkDiscoveryTypes.requested.filter { it in failedTypes }, requestedTypes.toList(), hasPendingCleanup())
        onStatus(current)
    }

    @Synchronized fun stop() { finish("Discovery stopped") }
    @Synchronized fun close() { if (!closed) { closed = true; finish("Discovery stopped") } }
    private fun finish(message: String) { stoppedReason = message; release(); cleanupStatus() }

    @SuppressLint("NewApi")
    private fun release() {
        guard.cancel() // Fence every callback before starting asynchronous platform cleanup.
        poll?.let(main::removeCallbacks); poll = null
        val oldWatcher = watcher; watcher = null
        val now = SystemClock.elapsedRealtime()
        if (oldWatcher != null) watcherCleanup.requestStop(oldWatcher, now)
        listeners.clear()
        discoveryCleanup.handles().forEach { discoveryCleanup.requestStop(it, now) }
        pending.clear()
        nativeResolution.snapshot()?.let { old ->
            old.work.expired = true
            cancelResolveTimeout(old.work)
            resolverCleanup.requestStop(old.listener, now)
        }
        val oldLock = multicast; multicast = null
        if (oldLock != null) multicastCleanup.requestStop(oldLock, now)
        selected = null
        runCleanup()
    }

    /** Cleanup-only retry initiated by the user; never restarts browsing. */
    @Synchronized fun retryCleanup() {
        if (guard.active(token)) return
        val now = SystemClock.elapsedRealtime()
        discoveryCleanup.retry(now); resolverCleanup.retry(now); watcherCleanup.retry(now); multicastCleanup.retry(now)
        runCleanup()
    }

    @SuppressLint("NewApi")
    private fun runCleanup() {
        if (cleanupRunning) return
        cleanupRunning = true
        try {
        cleanupTask?.let(main::removeCallbacks); cleanupTask = null
        val now = SystemClock.elapsedRealtime()
        discoveryCleanup.handles().forEach { listener ->
            if (discoveryCleanup.tryStop(listener, now)) runCatching { nsd?.stopServiceDiscovery(listener) }
            // Only terminal callbacks acknowledge an NSD discovery handle, even after an exception.
        }
        if (canCancelResolution()) resolverCleanup.handles().forEach { listener ->
            if (resolverCleanup.tryStop(listener, now)) runCatching { nsd?.stopServiceResolution(listener) }
        }
        watcherCleanup.handles().forEach { callback ->
            if (watcherCleanup.tryStop(callback, now)) {
                if (runCatching { connectivity?.unregisterNetworkCallback(callback) }.isSuccess) watcherCleanup.acknowledge(callback)
            }
        }
        multicastCleanup.handles().forEach { lock ->
            if (multicastCleanup.tryStop(lock, now)) {
                if (runCatching { if (lock.isHeld) lock.release() }.isSuccess) multicastCleanup.acknowledge(lock)
            }
        }
        val delay = listOfNotNull(discoveryCleanup.nextDelayMs(now), watcherCleanup.nextDelayMs(now),
            multicastCleanup.nextDelayMs(now), if (canCancelResolution()) resolverCleanup.nextDelayMs(now) else null).minOrNull()
        if (delay != null) {
            val task = Runnable { synchronized(this@NetworkDiscoveryRadio) { runCleanup() } }
            cleanupTask = task
            main.postDelayed(task, delay)
        }
        cleanupStatus()
        } finally { cleanupRunning = false }
    }

    private fun cleanupStatus() {
        if (guard.active(token)) return
        val now = SystemClock.elapsedRealtime()
        val message = if (!hasPendingCleanup()) stoppedReason else if (discoveryCleanup.exhausted(now) ||
            resolverCleanup.exhausted(now) || watcherCleanup.exhausted(now) || multicastCleanup.exhausted(now)) {
            "$stoppedReason; Android cleanup is not confirmed. Retry cleanup or restart SWEEP."
        } else "$stoppedReason; waiting for Android cleanup"
        update(message, false)
    }

    private fun networkKey(network: Network): String = network.networkHandle.toString()
    private fun cancelResolveTimeout(work: ResolveWork) { work.timeout?.let(main::removeCallbacks); work.timeout = null }
    private fun canCancelResolution(): Boolean = Build.VERSION.SDK_INT >= 34 || (Build.VERSION.SDK_INT >= 33 &&
        SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= 7)
    private companion object {
        const val MAX_SESSION_MS = 60_000L
        const val POLL_MS = 1_000L
        const val MAX_SERVICES = 128
        const val MAX_ADDRESSES = 8
        const val RESOLVE_TIMEOUT_MS = 8_000L
        const val PERMISSION_DENIED = 7
    }
}
