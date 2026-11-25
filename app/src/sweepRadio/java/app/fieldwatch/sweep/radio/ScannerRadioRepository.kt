package app.fieldwatch.sweep.radio

import app.fieldwatch.sweep.SweepApplication
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ScannerRadioApplication : SweepApplication() {
    lateinit var radio: ScannerRadioRepository
        private set
    override fun onCreate() {
        super.onCreate()
        radio = ScannerRadioRepository(File(filesDir, "scanner-radio"))
    }
}

/** Separate from RF history; no network requests, analytics or audio archives. */
class ScannerRadioRepository(directory: File) {
    private val store = ScannerFeedStore(directory)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val changes = Channel<() -> Unit>(Channel.UNLIMITED)
    private val consentRevision = AtomicLong()
    private val _preferences = MutableStateFlow(ScannerPreferences())
    val preferences = _preferences.asStateFlow()
    private val _networkConsent = MutableStateFlow(false)
    val networkConsent = _networkConsent.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        scope.launch {
            runCatching {
                val saved = store.load()
                _preferences.value = saved
                if (consentRevision.get() == 0L) _networkConsent.value = saved.networkConsent
            }.onFailure { _error.value = "Saved radio feeds could not be read. Clear radio settings to reset them." }
            _loading.value = false
            for (change in changes) {
                try { change(); _error.value = null }
                catch (_: Exception) { _error.value = "Radio settings could not be saved. Check phone storage or clear radio settings." }
            }
        }
    }

    fun save(feed: ScannerFeed) = enqueue {
        val checked = ScannerFeedValidator.validate(feed)
        persist(_preferences.value.copy(feeds = _preferences.value.feeds.filterNot { it.id == checked.id } + checked))
    }
    fun delete(id: String) = enqueue { persist(_preferences.value.copy(feeds = _preferences.value.feeds.filterNot { it.id == id })) }
    fun favorite(id: String) = enqueue {
        persist(_preferences.value.copy(feeds = _preferences.value.feeds.map { if (it.id == id) it.copy(favorite = !it.favorite) else it }))
    }
    fun setConsent(allowed: Boolean) {
        val revision = consentRevision.incrementAndGet()
        // Revocation takes effect before disk IO and before any queued older enable.
        if (!allowed) _networkConsent.value = false
        enqueue {
            persist(_preferences.value.copy(networkConsent = allowed))
            if (consentRevision.get() == revision) _networkConsent.value = allowed
        }
    }
    fun deleteAll() {
        val revision = consentRevision.incrementAndGet()
        _networkConsent.value = false
        enqueue {
            store.deleteAll()
            _preferences.value = ScannerPreferences()
            if (consentRevision.get() == revision) _networkConsent.value = false
        }
    }
    private fun persist(preferences: ScannerPreferences) { store.save(preferences); _preferences.value = preferences }
    private fun enqueue(change: () -> Unit) { changes.trySend(change).getOrThrow() }
}
