package app.fieldwatch.sweep.spaces

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.getValue
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fieldwatch.sweep.SweepApplication
import app.fieldwatch.sweep.SweepTheme
import app.fieldwatch.sweep.enableSweepEdgeToEdge
import app.fieldwatch.sweep.spectrum.SpectrumPreset
import app.fieldwatch.sweep.spectrum.SweepSpectrumActivity
import java.io.File

internal class CaptureSpectrumBand : ActivityResultContract<SpectrumPreset, SpaceSpectrumCheck?>() {
    override fun createIntent(context: Context, input: SpectrumPreset) = SweepSpectrumActivity.captureIntent(context, input)
    override fun parseResult(resultCode: Int, intent: Intent?) =
        if (resultCode == Activity.RESULT_OK) SweepSpectrumActivity.reading(intent) else null
}

class SweepSpacesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableSweepEdgeToEdge()
        val app = application as SweepApplication
        setContent {
            val state by app.spaceController.state.collectAsStateWithLifecycle()
            val history by app.coordinator.history.collectAsStateWithLifecycle()
            SweepTheme {
                SweepSpacesUi(state, history, app.spaceController, onRoomScan = ::finish, onShare = ::share)
            }
        }
    }

    private fun share(file: File, format: SpaceExportFormat) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_SEND).setType(format.mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "SWEEP timestamped inspection log")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri("Inspection log", uri)
        startActivity(Intent.createChooser(intent, "Share inspection log"))
    }
}
