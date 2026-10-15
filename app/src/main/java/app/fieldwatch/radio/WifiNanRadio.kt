package app.fieldwatch.radio

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.fieldwatch.domain.Observation
import app.fieldwatch.domain.OpenDroneId
import app.fieldwatch.domain.OpenDroneIdNan
import app.fieldwatch.domain.RadioFacts
import app.fieldwatch.domain.RadioKind
import app.fieldwatch.domain.ServiceDataRecord
import app.fieldwatch.domain.toHexUpper
import java.util.UUID

data class WifiNanStatus(
    val supported: Boolean = false,
    val available: Boolean = false,
    val running: Boolean = false,
    val message: String = "Wi-Fi NAN unsupported",
    /** Number of structurally valid received SSI payloads, not number of drones. */
    val samples: Long = 0L,
)

/**
 * Passive OpenDroneID discovery using the Android 8+ public Wi-Fi Aware APIs.
 * No app service publication, message transmission, ranging, or data-path connection.
 * Android still manages cluster synchronization itself; this is not monitor mode.
 * All callbacks and lifecycle methods share a monitor so stop() is an emission barrier.
 */
class WifiNanRadio(
    context: Context,
    private val onObservation: (Observation) -> Unit,
    private val onStatus: (WifiNanStatus) -> Unit = {},
) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val supported = this.context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
    private val manager = if (supported) this.context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager else null
    private val gate = WifiNanSessionGate()
    private val retry = WifiNanRetrySchedule()
    private val receiverId = UUID.randomUUID().toString().replace("-", "")
    private var receiver: BroadcastReceiver? = null
    private var awareSession: WifiAwareSession? = null
    private var discoverySession: SubscribeDiscoverySession? = null
    private var timeout: Runnable? = null
    private var requested = false
    private var closed = false
    private val peers = LinkedHashMap<PeerHandle, Long>()
    private var nextPeer = 0L
    @Volatile private var current = WifiNanStatus(supported = supported,
        message = if (supported) "Wi-Fi NAN stopped" else "Wi-Fi NAN unsupported")

    fun status(): WifiNanStatus = current
    fun isRunning(): Boolean = current.running

    @Synchronized fun start() {
        if (closed) return
        if (requested) {
            if (supported && manager != null && !current.running && awareSession == null &&
                discoverySession == null && timeout == null) attachIfAvailable()
            return
        }
        requested = true
        retry.reset()
        updateStatus(current.copy(samples = 0L))
        if (!requested) return
        if (!supported || manager == null) {
            updateStatus(current.copy(available = false, running = false,
                message = if (supported) "Wi-Fi NAN service unavailable" else "Wi-Fi NAN unsupported"))
            return
        }
        val watcher = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) = synchronized(this@WifiNanRadio) {
                if (receiver !== this || !requested || intent?.action != WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED) return@synchronized
                // Availability changes invalidate pending callbacks as well as active sessions.
                releaseSessions()
                retry.reset()
                attachIfAvailable()
            }
        }
        receiver = watcher
        try {
            ContextCompat.registerReceiver(context, watcher,
                IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            attachIfAvailable()
        } catch (e: RuntimeException) {
            receiver = null
            requested = false
            runCatching { context.unregisterReceiver(watcher) }
            fail("Wi-Fi NAN receiver failed: ${e.javaClass.simpleName}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun attachIfAvailable() {
        if (!requested || closed || !retry.tryAttempt(SystemClock.elapsedRealtime())) return
        val available = availableNow()
        if (!available) {
            updateStatus(current.copy(available = false, running = false, message = "Wi-Fi NAN unavailable on this handset right now"))
            return
        }
        val missing = listOf(Manifest.permission.ACCESS_WIFI_STATE, Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION) + if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.NEARBY_WIFI_DEVICES) else emptyList()
        if (missing.any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }) {
            fail("Wi-Fi NAN needs Wi-Fi and precise-location permissions")
            return
        }
        val token = gate.begin()
        peers.clear()
        nextPeer = 0L
        updateStatus(current.copy(available = true, running = false, message = "Wi-Fi NAN starting"))
        if (!requested || !gate.accepts(token)) return
        val deadline = Runnable { synchronized(this@WifiNanRadio) {
            if (gate.accepts(token)) fail("Wi-Fi NAN start timed out")
        } }
        timeout = deadline
        main.postDelayed(deadline, START_TIMEOUT_MS)
        try {
            manager!!.attach(object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) = synchronized(this@WifiNanRadio) {
                    if (!requested || !gate.accepts(token)) {
                        runCatching { session.close() }
                        return@synchronized
                    }
                    awareSession = session
                    subscribe(session, token)
                }
                override fun onAttachFailed() = synchronized(this@WifiNanRadio) {
                    if (gate.accepts(token)) fail("Wi-Fi NAN attach failed")
                }
                // Added in Android 13; older versions report discovery termination/state changes.
                override fun onAwareSessionTerminated() = synchronized(this@WifiNanRadio) {
                    if (gate.accepts(token)) fail("Wi-Fi NAN connection ended")
                }
            }, main)
        } catch (e: RuntimeException) {
            fail("Wi-Fi NAN attach failed: ${e.javaClass.simpleName}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun subscribe(session: WifiAwareSession, token: Long) {
        try {
            val config = SubscribeConfig.Builder()
                .setServiceName(OpenDroneId.NAN_SERVICE_NAME)
                .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
                .setTerminateNotificationEnabled(true)
                .build()
            // No match filter: RID uses an unsolicited publisher of this service name.
            session.subscribe(config, object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(session: SubscribeDiscoverySession) = synchronized(this@WifiNanRadio) {
                    if (!requested || !gate.accepts(token)) {
                        runCatching { session.close() }
                        return@synchronized
                    }
                    discoverySession = session
                    cancelTimeout()
                    updateStatus(current.copy(available = true, running = true, message = "Wi-Fi NAN listening · reception not yet verified"))
                }
                override fun onSessionConfigFailed() = synchronized(this@WifiNanRadio) {
                    if (gate.accepts(token)) fail("Wi-Fi NAN subscription failed")
                }
                override fun onSessionTerminated() = synchronized(this@WifiNanRadio) {
                    if (gate.accepts(token)) fail("Wi-Fi NAN subscription ended")
                }
                override fun onServiceDiscovered(peerHandle: PeerHandle?, serviceSpecificInfo: ByteArray?, matchFilter: MutableList<ByteArray>?) = synchronized(this@WifiNanRadio) {
                    if (!requested || !gate.accepts(token) || !current.running || peerHandle == null) return@synchronized
                    if (OpenDroneIdNan.messages(serviceSpecificInfo).isEmpty()) return@synchronized
                    val bytes = serviceSpecificInfo!!
                    val peer = peers.getOrPut(peerHandle) { ++nextPeer }
                    if (peers.size > MAX_PEERS) peers.remove(peers.keys.first())
                    val observation = Observation(
                        kind = RadioKind.WIFI, mac = WifiNanPeerIdentity.address(receiverId, token, peer),
                        name = "Remote ID (Wi-Fi NAN)", rssi = 127, channel = 0, frequencyMhz = 0,
                        hiddenSsid = false, serviceUuids = emptyList(), manufacturerId = null,
                        manufacturerDataHex = "", rawHex = bytes.toHexUpper(),
                        extras = "Wi-Fi NAN; session-scoped peer identity; RSSI and channel unavailable",
                        at = System.currentTimeMillis(), fresh = true,
                        elapsedRealtimeMs = SystemClock.elapsedRealtime(),
                        facts = RadioFacts(addressType = "Wi-Fi NAN peer handle", wifiStandard = "Wi-Fi NAN",
                            serviceData = listOf(ServiceDataRecord(OpenDroneId.NAN_SERVICE_NAME, bytes.toHexUpper()))),
                    )
                    // Call under the same monitor as stop(): a stopped session cannot emit late.
                    onObservation(observation)
                    if (requested && gate.accepts(token)) updateStatus(current.copy(samples = current.samples + 1,
                        message = "Wi-Fi NAN listening · valid Remote ID payload received"))
                }
            }, main)
        } catch (e: RuntimeException) {
            fail("Wi-Fi NAN subscription failed: ${e.javaClass.simpleName}")
        }
    }

    @Synchronized fun stop() {
        requested = false
        val oldReceiver = receiver
        receiver = null
        if (oldReceiver != null) runCatching { context.unregisterReceiver(oldReceiver) }
        releaseSessions()
        retry.reset()
        updateStatus(current.copy(available = availableNow(), running = false,
            message = if (supported) "Wi-Fi NAN stopped" else "Wi-Fi NAN unsupported"))
    }

    @Synchronized fun close() {
        if (closed) return
        closed = true
        stop()
    }

    private fun fail(message: String) {
        releaseSessions()
        retry.defer(SystemClock.elapsedRealtime())
        updateStatus(current.copy(available = availableNow(), running = false, message = message))
    }

    private fun releaseSessions() {
        gate.cancel()
        cancelTimeout()
        val discovery = discoverySession
        val aware = awareSession
        discoverySession = null
        awareSession = null
        peers.clear()
        runCatching { discovery?.close() }
        runCatching { aware?.close() }
    }

    private fun cancelTimeout() { timeout?.let(main::removeCallbacks); timeout = null }
    private fun availableNow(): Boolean = runCatching { manager?.isAvailable == true }.getOrDefault(false)
    private fun updateStatus(status: WifiNanStatus) { current = status; onStatus(status) }

    private companion object {
        const val START_TIMEOUT_MS = 10_000L
        const val MAX_PEERS = 512
    }
}
