// SPDX-License-Identifier: MIT
package app.fieldwatch.sweep.radio.monas

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.fieldwatch.sweep.SweepTheme
import app.fieldwatch.sweep.enableSweepEdgeToEdge
import app.fieldwatch.sweep.radio.ScannerRadioApplication
import kotlinx.coroutines.*

class SweepMonasActivity : ComponentActivity() {
    private val repository get() = (application as ScannerRadioApplication).radio
    private var client: MonasdHttpClient? = null
    private var request: Job? = null
    private var cursor = 0L
    @Volatile private var foreground = false
    @Volatile private var authorized = false

    private var server by mutableStateOf("")
    private var pin by mutableStateOf("")
    private val draft = MonasTokenDraft()
    private var state by mutableStateOf(MonasScreenState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableSweepEdgeToEdge()
        lifecycleScope.launch {
            repository.networkConsent.collect { if (!it && authorized) disconnect() }
        }
        setContent {
            val consent by repository.networkConsent.collectAsStateWithLifecycle()
            val loading by repository.loading.collectAsStateWithLifecycle()
            SweepTheme {
                SweepMonasUi(state, consent, loading, server, pin, draft, onBack = ::finish,
                    onServer = { server = it }, onPin = { pin = it },
                    onConsent = { if (!it) disconnect(); repository.setConsent(it) },
                    onConnect = ::connect, onDisconnect = ::disconnect, onRefresh = ::refresh, onAuto = ::auto,
                    onDemo = { disconnect(); publish(MonasDemo.snapshot()) }, onShare = ::share)
            }
        }
    }

    override fun onStart() { super.onStart(); foreground = true }
    override fun onStop() {
        foreground = false
        if (!isChangingConfigurations) disconnect()
        super.onStop()
    }
    override fun onDestroy() { disconnect(); super.onDestroy() }

    private fun allowed() = foreground && authorized && !repository.loading.value && repository.networkConsent.value

    private fun connect() {
        if (!foreground || repository.loading.value || !repository.networkConsent.value || client != null) return
        authorized = true
        try {
            client = MonasdHttpClient(server, draft.take(), pin, ::allowed)
            state = MonasScreenState(connected = true)
            refresh()
        } catch (e: MonasException) {
            disconnect()
            state = state.copy(error = e.message)
        }
    }

    private fun refresh() {
        val source = client ?: return
        if (!allowed() || state.busy) return
        request?.cancel()
        state = state.copy(busy = true, error = null)
        request = lifecycleScope.launch {
            try {
                val previous = state.snapshot
                val since = cursor
                val (snapshot, counts) = withContext(Dispatchers.IO) {
                    val reading = source.snapshot(since)
                    val checked = reading.copy(continuityIssue = MonasdAnalyzer.continuity(previous, reading))
                    checked to MonasdAnalyzer.counts(checked)
                }
                if (client !== source || !allowed()) return@launch
                cursor = snapshot.ble.packets.maxOfOrNull { it.seq }?.coerceAtLeast(cursor) ?: cursor
                publish(snapshot, counts)
                if (snapshot.continuityIssue != null) state = state.copy(auto = false)
                if (state.auto) {
                    delay(2_000)
                    if (state.auto && allowed()) refresh()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (client === source) {
                    disconnect()
                    state = state.copy(error = (e as? MonasException)?.message ?: "The metrology snapshot could not be read.")
                }
            }
        }
    }

    private fun publish(snapshot: MonasSnapshot, counts: MonasPairCounts = MonasdAnalyzer.counts(snapshot)) {
        state = state.copy(snapshot = snapshot, counts = counts, revision = state.revision + 1, busy = false)
    }

    private fun auto(on: Boolean) {
        state = state.copy(auto = on && allowed())
        if (state.auto && !state.busy) refresh()
    }

    private fun disconnect() {
        authorized = false
        request?.cancel()
        request = null
        client?.close()
        client = null
        draft.clear()
        cursor = 0
        state = MonasScreenState()
    }

    private fun share(snapshot: MonasSnapshot, identifiers: Boolean) {
        if (!foreground || state.snapshot !== snapshot) return
        auto(false)
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { MonasReports.shareFile(cacheDir, snapshot, identifiers) }
                if (!foreground || state.snapshot !== snapshot) { file.delete(); return@launch }
                val uri = FileProvider.getUriForFile(this@SweepMonasActivity, "$packageName.files", file)
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                send.clipData = ClipData.newRawUri("Monas report", uri)
                startActivity(Intent.createChooser(send, "Share Monas summary"))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { state = state.copy(error = "The metrology report could not be shared.") }
        }
    }
}

internal data class MonasScreenState(
    val connected: Boolean = false, val busy: Boolean = false, val auto: Boolean = false,
    val revision: Long = 0, val snapshot: MonasSnapshot? = null, val counts: MonasPairCounts? = null, val error: String? = null,
)
