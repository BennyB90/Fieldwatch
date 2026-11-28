package app.fieldwatch.sweep.thermal

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import app.fieldwatch.sweep.SweepTheme
import app.fieldwatch.sweep.enableSweepEdgeToEdge
import app.fieldwatch.sweep.spaces.SpaceThermalCheck
import kotlin.math.roundToInt
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun ThermalFrame.measuredCheck(device: ThermalDevice): SpaceThermalCheck? {
    if (demo || !radiometric || ambientMedianC == null || maxC == null || capturedAt <= 0) return null
    return SpaceThermalCheck((ambientMedianC * 10).roundToInt(), (maxC * 10).roundToInt(),
        (requireNotNull(maxDeltaC) * 10).roundToInt(), capturedAt, deviceLabel = device.label,
        manufacturer = device.manufacturer, serialNumber = device.serialNumber)
}

class SweepThermalActivity : ComponentActivity() {
    private lateinit var usb: ThermalUsbController
    private var pendingDeviceId: Int? = null
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val id = pendingDeviceId
        pendingDeviceId = null
        if (granted && id != null) usb.connect(id)
        else if (!granted) usb.disconnect(ThermalUsbController.CAMERA_REQUIRED)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableSweepEdgeToEdge()
        usb = ThermalUsbController(this)
        setContent {
            SweepTheme {
                SweepThermalUi(
                    usb = usb,
                    item = intent.getStringExtra(ITEM),
                    back = ::finish,
                    onConnect = { id ->
                        if (usb.hasCameraPermission()) usb.connect(id)
                        else {
                            pendingDeviceId = id
                            cameraPermission.launch(Manifest.permission.CAMERA)
                        }
                    },
                    onAppSettings = ::openAppSettings,
                ) { frame, capture ->
                    val check = usb.currentDevice(capture)?.let(frame::measuredCheck)
                    if (check != null) {
                        setResult(Activity.RESULT_OK, Intent().putExtra(CHECK, Json.encodeToString(check)))
                        finish()
                    }
                    check != null
                }
            }
        }
    }

    override fun onStart() { super.onStart(); usb.start() }
    override fun onStop() { if (!isChangingConfigurations) usb.stop(); super.onStop() }
    override fun onDestroy() { usb.close(); super.onDestroy() }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        runCatching { startActivity(intent) }
    }

    internal companion object {
        private const val ITEM = "checklist_item"
        private const val CHECK = "thermal_check"
        fun captureIntent(context: Context, item: String) = Intent(context, SweepThermalActivity::class.java).putExtra(ITEM, item)
        fun reading(data: Intent?): SpaceThermalCheck? = data?.getStringExtra(CHECK)?.let {
            Json.decodeFromString<SpaceThermalCheck>(it).takeUnless { t -> t.demo }
        }
    }
}
