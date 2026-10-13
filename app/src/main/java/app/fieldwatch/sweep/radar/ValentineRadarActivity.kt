package app.fieldwatch.sweep.radar

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.fieldwatch.sweep.SweepApplication
import app.fieldwatch.sweep.SweepTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Foreground, user-selected accessory connection; it never owns an RF scan. */
class ValentineRadarActivity : ComponentActivity() {
    private var controller: ValentineBleController? = null
    private var controllerJob: Job? = null
    private val radar = MutableStateFlow(ValentineRadarState())
    private val phoneStatus = MutableStateFlow("Bluetooth availability not checked")
    private val supported = MutableStateFlow(false)
    private val blocked = MutableStateFlow(false)
    private val actionError = MutableStateFlow<String?>(null)
    private var foreground = false
    private var pendingDiscover = false
    private var pendingAccessory: String? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (!foreground || isFinishing || isDestroyed) {
            pendingDiscover = false; pendingAccessory = null
            return@registerForActivityResult
        }
        refreshPlatform()
        if (!pendingDiscover && pendingAccessory == null) return@registerForActivityResult
        if (requiredPermissions(pendingDiscover).any { !hasPermission(it) }) {
            pendingDiscover = false; pendingAccessory = null
            actionError.value = "Bluetooth permission was not granted. Allow access in Android settings, then choose Discover or Connect again."
        } else {
            val requestedDiscovery = pendingDiscover
            val accessory = pendingAccessory
            pendingDiscover = false; pendingAccessory = null
            if (requestedDiscovery) discover() else if (accessory != null) connect(accessory)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by radar.collectAsStateWithLifecycle()
            val availability by phoneStatus.collectAsStateWithLifecycle()
            val hardware by supported.collectAsStateWithLifecycle()
            val sweepHeld by blocked.collectAsStateWithLifecycle()
            val error by actionError.collectAsStateWithLifecycle()
            SweepTheme {
                ValentineRadarScreen(state, availability, hardware, sweepHeld, error,
                    onBack = ::finish, onDiscover = ::discover, onConnect = ::connect,
                    onDisconnect = ::disconnect, onSettings = ::openSettings)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        val connection = ValentineBleController(this)
        controller = connection
        controllerJob = lifecycleScope.launch { connection.state.collect { radar.value = it } }
        refreshPlatform()
    }

    override fun onResume() {
        super.onResume()
        refreshPlatform()
        if (sweepIsHeld()) {
            controller?.disconnect()
            actionError.value = SWEEP_GATE
        } else if (actionError.value == SWEEP_GATE) actionError.value = null
    }

    override fun onStop() {
        foreground = false
        pendingDiscover = false; pendingAccessory = null
        controllerJob?.cancel(); controllerJob = null
        controller?.close(); controller = null
        radar.value = ValentineRadarState()
        super.onStop()
    }

    override fun onDestroy() {
        controller?.close(); controller = null
        super.onDestroy()
    }

    private fun discover() {
        if (!guardForeground()) return
        if (!guardSweep()) return
        if (!ensurePermissions(discover = true)) return
        if (!platformReady(requireLocation = true)) return
        actionError.value = null
        controller?.discover() ?: run { actionError.value = "Return to this screen, then choose Discover again." }
    }

    private fun connect(accessoryId: String) {
        if (!guardForeground()) return
        if (!guardSweep()) return
        if (!ensurePermissions(discover = false, accessoryId = accessoryId)) return
        if (!platformReady(requireLocation = false)) return
        actionError.value = null
        controller?.connect(accessoryId) ?: run { actionError.value = "Return to this screen, then choose the accessory again." }
    }

    private fun disconnect() {
        if (!guardForeground()) return
        pendingDiscover = false; pendingAccessory = null
        actionError.value = null
        controller?.disconnect()
    }

    private fun ensurePermissions(discover: Boolean, accessoryId: String? = null): Boolean {
        val required = requiredPermissions(discover)
        val missing = required.filter { !hasPermission(it) }
        if (missing.isEmpty()) return true
        pendingDiscover = discover; pendingAccessory = accessoryId
        permissions.launch(required.toTypedArray())
        return false
    }

    private fun requiredPermissions(discovery: Boolean = true): List<String> = buildList {
        // The shared RF scanner cannot assert neverForLocation without filtering beacon coverage.
        if (discovery) { add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION) }
        if (Build.VERSION.SDK_INT >= 31) { if (discovery) add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) }
    }

    private fun hasPermission(permission: String): Boolean = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    private fun guardForeground(): Boolean = foreground && !isFinishing && !isDestroyed
    private fun sweepIsHeld(): Boolean = (application as? SweepApplication)?.coordinator?.hasServiceRequest() == true
    private fun guardSweep(): Boolean {
        blocked.value = sweepIsHeld()
        if (!blocked.value) return true
        pendingDiscover = false; pendingAccessory = null
        actionError.value = SWEEP_GATE
        return false
    }

    private fun bluetoothEnabled(): Boolean = runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true }.getOrDefault(false)
    private fun locationEnabled(): Boolean = runCatching { getSystemService(LocationManager::class.java)?.isLocationEnabled == true }.getOrDefault(false)
    private fun refreshPlatform() {
        supported.value = packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        blocked.value = sweepIsHeld()
        phoneStatus.value = when {
            !supported.value -> "This phone has no Bluetooth LE support for accessory connections."
            !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) -> "Location access is needed for Bluetooth discovery. Choose Discover to allow it."
            requiredPermissions().any { !hasPermission(it) } -> "Bluetooth access is needed. Choose Discover or Connect to allow it."
            !bluetoothEnabled() -> "Bluetooth is off. Turn it on in Android settings."
            !locationEnabled() -> "Android location services must be on for accessory discovery."
            else -> "Bluetooth LE ready for accessory discovery."
        }
    }

    private fun platformReady(requireLocation: Boolean): Boolean {
        refreshPlatform()
        if (!supported.value || !bluetoothEnabled() || (requireLocation && !locationEnabled())) {
            actionError.value = phoneStatus.value
            return false
        }
        return true
    }

    private fun openSettings() {
        if (!guardForeground()) return
        val intent = when {
            requiredPermissions().any { !hasPermission(it) } -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            !locationEnabled() -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            else -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        }
        runCatching { startActivity(intent) }.onFailure { actionError.value = "Open Android settings to check Bluetooth and app permissions." }
    }

    companion object { private const val SWEEP_GATE = "End the RF scan before connecting a radar accessory." }
}
