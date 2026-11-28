package app.fieldwatch.sweep.spectrum

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.fieldwatch.sweep.SweepTheme
import app.fieldwatch.sweep.enableSweepEdgeToEdge
import app.fieldwatch.sweep.spaces.SpaceSpectrumCheck
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SweepSpectrumActivity : ComponentActivity() {
    private lateinit var usb: TinySaUsbController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableSweepEdgeToEdge()
        usb = TinySaUsbController(this)
        val target = intent.getStringExtra(PRESET)?.let(SpectrumPreset::byId)
        setContent {
            SweepTheme {
                SweepSpectrumUi(usb, target, ::finish) { sweep ->
                    if (sweep.presetId == target?.id && usb.current(sweep)) {
                        val check = sweep.toSpaceCheck()
                        if (runCatching { check.requireLive() }.isSuccess) {
                            setResult(Activity.RESULT_OK, Intent().putExtra(CHECK, Json.encodeToString(check)))
                            finish()
                        }
                    }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); usb.start() }
    override fun onStop() { if (!isChangingConfigurations) usb.stop(); super.onStop() }
    override fun onDestroy() { usb.close(); super.onDestroy() }

    internal companion object {
        private const val PRESET = "spectrum_preset"
        private const val CHECK = "spectrum_check"
        fun captureIntent(context: Context, preset: SpectrumPreset) =
            Intent(context, SweepSpectrumActivity::class.java).putExtra(PRESET, preset.id)
        fun reading(data: Intent?) = data?.getStringExtra(CHECK)?.let {
            runCatching {
                Json.decodeFromString<SpaceSpectrumCheck>(it).also { check -> check.requireLive() }
            }.getOrNull()
        }
    }
}

internal fun SpectrumSweep.toSpaceCheck() = SpaceSpectrumCheck(
    presetId, startHz, stopHz, medianDeciDbm, peakDeciDbm, peakHz, prominenceDeciDb, capturedAt,
    modelLabel = model.label, deviceLabel = deviceLabel, demo = demo,
    manufacturer = manufacturer, serialNumber = serialNumber,
)

internal fun SpectrumSweep.canAttachToSpace(): Boolean = !demo && runCatching { toSpaceCheck().requireLive() }.isSuccess
