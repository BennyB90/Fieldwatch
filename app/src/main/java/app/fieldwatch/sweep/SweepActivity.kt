package app.fieldwatch.sweep

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.getValue
import app.fieldwatch.domain.PayloadLocation
import app.fieldwatch.BuildConfig
import kotlinx.coroutines.launch

class SweepActivity : ComponentActivity() {
    private val coordinator get() = (application as SweepApplication).coordinator
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        coordinator.refreshCoverage()
        coordinator.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val current by coordinator.current.collectAsStateWithLifecycle()
            val history by coordinator.history.collectAsStateWithLifecycle()
            val coverage by coordinator.coverage.collectAsStateWithLifecycle()
            val starting by coordinator.starting.collectAsStateWithLifecycle()
            val error by coordinator.error.collectAsStateWithLifecycle()
            SweepTheme {
                SweepRoot(
                    current = current, history = history, coverage = coverage, catalog = coordinator.catalog,
                    starting = starting, error = error, onStart = ::requestScan, onStop = coordinator::stop,
                    onDelete = coordinator::delete, onDeleteAll = coordinator::deleteAll, onShare = ::share,
                    onDemo = coordinator::showDemo, onClearDemo = coordinator::clearDemo,
                    onSettings = ::openSettings, onMap = ::openMap,
                    radioEnabled = BuildConfig.SWEEP_RADIO_ENABLED, onRadio = ::openRadio,
                    onRadar = ::openRadar,
                )
            }
        }
    }

    override fun onResume() { super.onResume(); coordinator.refreshCoverage() }

    private fun openRadio() {
        if (!BuildConfig.SWEEP_RADIO_ENABLED) return
        startActivity(Intent().setClassName(this, "app.fieldwatch.sweep.radio.ScannerRadioActivity"))
    }

    private fun openRadar() {
        if (coordinator.hasServiceRequest()) {
            coordinator.reportError("End the RF scan before connecting a radar accessory.")
            return
        }
        startActivity(Intent().setClassName(this, "app.fieldwatch.sweep.radar.ValentineRadarActivity"))
    }

    private fun requestScan() {
        val required = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) { required += Manifest.permission.BLUETOOTH_SCAN; required += Manifest.permission.BLUETOOTH_CONNECT }
        if (Build.VERSION.SDK_INT >= 33) required += Manifest.permission.POST_NOTIFICATIONS
        if (required.any { !coordinator.hasPermission(it) }) permissions.launch(required.toTypedArray()) else coordinator.start()
    }

    private fun openSettings() {
        val intent = if (!coordinator.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) || !coordinator.bluetoothPermissions()) {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        } else if (!coordinator.locationEnabled()) Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS) else Intent(Settings.ACTION_WIRELESS_SETTINGS)
        runCatching { startActivity(intent) }.onFailure { coordinator.reportError("Open Android Settings to check Location, Wi-Fi, Bluetooth, and app permissions.") }
    }

    private fun openMap(pin: SweepPin) {
        if (!PayloadLocation.validCoord(pin.latitude, pin.longitude)) return
        val coordinates = "${pin.latitude},${pin.longitude}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$coordinates?q=$coordinates"))
        try { startActivity(intent) } catch (_: ActivityNotFoundException) {
            coordinator.reportError("No maps app is installed. The reported coordinates are still available in the finding.")
        }
    }

    private fun share(session: SweepSession, identifiers: Boolean, coordinates: Boolean) {
        lifecycleScope.launch {
            runCatching {
                val file = coordinator.prepareShare(session, identifiers, coordinates).await()
                val uri = FileProvider.getUriForFile(this@SweepActivity, "$packageName.files", file)
                val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, if (session.demo) "SWEEP sample report" else "SWEEP scan report")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(intent, "Share SWEEP report"))
            }.onFailure { coordinator.reportError("The report could not be shared. Your saved session remains on this phone.") }
        }
    }
}
