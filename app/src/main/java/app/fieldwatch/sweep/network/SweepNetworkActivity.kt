package app.fieldwatch.sweep.network

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.fieldwatch.sweep.SweepTheme
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Visible, explicitly authorized service discovery and local inventory review. */
class SweepNetworkActivity : ComponentActivity() {
    private val controller get() = (application as NetworkModeHost).networkController
    private val availability = MutableStateFlow(NetworkAvailability(false, "Checking discovery availability"))
    private val readingDocument = MutableStateFlow(false)
    private var foreground = false
    private var pickerAuthorized = false
    private var pendingDocument: Uri? = null
    private var importJob: Job? = null
    private var importGeneration = 0L
    private val document = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val allowed = pickerAuthorized
        pickerAuthorized = false
        if (uri == null || !allowed || isFinishing || isDestroyed) return@registerForActivityResult
        pendingDocument = uri
        if (foreground) readPendingDocument()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (application !is NetworkModeHost) { finish(); return }
        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) { refreshAvailability(); delay(2_000) }
            }
        }
        setContent {
            val current by controller.current.collectAsStateWithLifecycle()
            val history by controller.history.collectAsStateWithLifecycle()
            val error by controller.error.collectAsStateWithLifecycle()
            val initializing by controller.initializing.collectAsStateWithLifecycle()
            val busy by controller.busy.collectAsStateWithLifecycle()
            val available by availability.collectAsStateWithLifecycle()
            val reading by readingDocument.collectAsStateWithLifecycle()
            SweepTheme {
                SweepNetworkScreen(
                    current = current, history = history, availability = available,
                    initializing = initializing, busy = busy || reading, error = error,
                    onBack = ::finish, onStart = ::startDiscovery, onStop = ::stopDiscovery,
                    onImport = ::chooseDocument, onShare = ::share,
                    onDelete = controller::delete, onClear = controller::clearAll,
                    onSettings = ::openSettings, onClearError = controller::clearError,
                    onRetryHistory = controller::retrySaving,
                    onRetryCleanup = controller::retryCleanup,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (application !is NetworkModeHost) return
        foreground = true
        controller.onVisible()
        refreshAvailability()
        // This can only complete the document the user selected in our SAF picker.
        readPendingDocument()
    }

    override fun onResume() {
        super.onResume()
        if (application is NetworkModeHost) refreshAvailability()
    }

    override fun onStop() {
        foreground = false
        ++importGeneration
        importJob?.cancel(); importJob = null
        readingDocument.value = false
        pendingDocument = null
        if (application is NetworkModeHost) controller.onHidden()
        super.onStop()
    }

    private fun visible(): Boolean = foreground && !isFinishing && !isDestroyed
    private fun refreshAvailability() { availability.value = controller.availability() }
    private fun startDiscovery() {
        if (!visible() || readingDocument.value) return
        refreshAvailability()
        controller.start(authorized = true)
    }
    private fun stopDiscovery() { if (visible()) controller.stop() }
    private fun chooseDocument() {
        if (!visible() || readingDocument.value) return
        pickerAuthorized = true
        runCatching { document.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
            .onFailure { pickerAuthorized = false; controller.reportError("No document picker is available. Choose an authorized JSON inventory file on this phone.") }
    }

    private fun readPendingDocument() {
        if (!visible()) return
        val uri = pendingDocument ?: return
        pendingDocument = null
        importJob?.cancel()
        val ticket = ++importGeneration
        readingDocument.value = true
        importJob = lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    val input = contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("unavailable_document")
                    input.use { source ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8_192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val received = source.read(buffer)
                            if (received < 0) break
                            if (output.size() + received > MAX_IMPORT_BYTES) throw IllegalArgumentException("inventory_too_large")
                            output.write(buffer, 0, received)
                        }
                        output.toByteArray()
                    }
                }
                if (ticket == importGeneration && visible()) controller.importRouter(bytes, authorized = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Exception) {
                if (ticket == importGeneration && visible()) controller.reportError(
                    if (problem is IllegalArgumentException && problem.message == "inventory_too_large") "Choose a JSON inventory no larger than 1 MiB."
                    else "The inventory file could not be opened. Choose an authorized JSON file and try again.",
                )
            } finally { if (ticket == importGeneration) readingDocument.value = false }
        }
    }

    private fun share(session: NetworkSession, identifiers: Boolean) {
        if (!visible()) return
        runCatching {
            val report = NetworkReports.text(session, includeIdentifiers = identifiers)
            val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "SWEEP network snapshot")
                .putExtra(Intent.EXTRA_TEXT, report)
            startActivity(Intent.createChooser(intent, "Share network snapshot"))
        }.onFailure { controller.reportError("No sharing app is available. Your local snapshot is still saved.") }
    }

    private fun openSettings() {
        if (!visible()) return
        runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            .onFailure { controller.reportError("Open Android settings to check the connected Wi-Fi network.") }
    }

    companion object { private const val MAX_IMPORT_BYTES = 1_048_576 }
}
