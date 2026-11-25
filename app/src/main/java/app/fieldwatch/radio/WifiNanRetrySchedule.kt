package app.fieldwatch.radio

/** Monotonic, bounded retries; ticker calls do not repeatedly attach to Android. */
internal class WifiNanRetrySchedule(private val intervalMs: Long = 30_000L) {
    init { require(intervalMs > 0L) }
    private var nextAttemptMs = 0L

    fun tryAttempt(nowElapsedMs: Long): Boolean {
        if (nowElapsedMs < 0L || nowElapsedMs < nextAttemptMs) return false
        defer(nowElapsedMs)
        return true
    }

    fun defer(nowElapsedMs: Long) {
        nextAttemptMs = if (nowElapsedMs > Long.MAX_VALUE - intervalMs) Long.MAX_VALUE else nowElapsedMs + intervalMs
    }

    fun reset() { nextAttemptMs = 0L }
}
