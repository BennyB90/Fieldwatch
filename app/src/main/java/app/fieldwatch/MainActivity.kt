package app.fieldwatch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.fieldwatch.alert.Alerter
import app.fieldwatch.radio.RadioPermissions
import app.fieldwatch.ui.FieldwatchRoot
import app.fieldwatch.ui.FieldwatchViewModel
import app.fieldwatch.ui.SweepSpikeShell
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: FieldwatchViewModel by viewModels()
    private val isSweepSpike get() = BuildConfig.BUILD_TYPE == "spike"
    // Opens the inherited first-run disclaimer while service start is pending.
    // Actual scanning/stop state always comes from the service's StateFlow.
    private var spikeStartRequested by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        vm.refreshPermissions()
        if (RadioPermissions.granted(this)) {
            if (isSweepSpike) startSpikeSession() else {
                (application as FieldwatchApp).refreshFix()
                vm.startScan()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            if (isSweepSpike) {
                val scanStats by (application as FieldwatchApp).devices.stats.collectAsStateWithLifecycle()
                LaunchedEffect(scanStats.scanning) {
                    if (scanStats.scanning) spikeStartRequested = false
                }
                SweepSpikeShell(
                    active = scanStats.scanning || spikeStartRequested,
                    onStart = {
                        if (RadioPermissions.granted(this)) {
                            startSpikeSession()
                        } else {
                            permissionLauncher.launch(RadioPermissions.required())
                        }
                    },
                    onStop = {
                        spikeStartRequested = false
                        vm.stopScan()
                    },
                ) {
                    FieldwatchRoot(vm) { permissionLauncher.launch(RadioPermissions.required()) }
                }
            } else {
                FieldwatchRoot(vm) {
                    permissionLauncher.launch(RadioPermissions.required())
                }
            }
        }
        if (!isSweepSpike && RadioPermissions.granted(this)) {
            lifecycleScope.launch { vm.startScan() }
        }
        intent?.getStringExtra(Alerter.EXTRA_DEVICE_KEY)?.let { vm.select(it) }
    }

    private fun startSpikeSession() {
        val app = application as FieldwatchApp
        if (app.devices.stats.value.scanning) return
        app.resetFollowSession()
        app.devices.resetForNewSession()
        app.alerter.forgetAnnounced()
        app.refreshFix()
        spikeStartRequested = true
        vm.startScan()
    }

    override fun onResume() {
        super.onResume()
        vm.refreshPermissions()
        if (!isSweepSpike && RadioPermissions.granted(this) && !(application as FieldwatchApp).devices.stats.value.scanning) {
            vm.startScan()
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Alerter.EXTRA_DEVICE_KEY)?.let { vm.select(it) }
    }
}
