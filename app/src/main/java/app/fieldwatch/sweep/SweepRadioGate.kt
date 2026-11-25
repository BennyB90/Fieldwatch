package app.fieldwatch.sweep

import kotlinx.coroutines.delay

/** A process-local radio lease stays held until its owning mode finishes teardown. */
class SweepRadioGate {
    private var owner: String? = null

    @Synchronized fun acquire(token: String): Boolean {
        require(token.isNotBlank())
        if (owner != null) return false
        owner = token
        return true
    }

    @Synchronized fun release(token: String): Boolean {
        if (owner != token) return false
        owner = null
        return true
    }

    @Synchronized fun held(): Boolean = owner != null
}

internal const val BLUETOOTH_CLEANUP_PENDING = "Android is still stopping Bluetooth. Wait before starting another scan."
internal const val BLUETOOTH_CLEANUP_FAILED = "Android could not stop Bluetooth scanning. Force-stop and reopen SWEEP before starting another scan."

internal suspend fun retryBluetoothCleanup(stop: () -> Boolean, wait: suspend (Long) -> Unit = { delay(it) }): Boolean {
    repeat(3) { attempt ->
        wait(500L * (attempt + 1))
        if (stop()) return true
    }
    return false
}
