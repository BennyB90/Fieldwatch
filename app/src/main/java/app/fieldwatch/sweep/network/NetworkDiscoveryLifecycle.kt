package app.fieldwatch.sweep.network

import java.util.IdentityHashMap

/** Local timeout/exception bookkeeping must not invent a new Android result receipt. */
internal object NetworkDiscoveryReceiptTime {
    fun afterOutcome(previousReceiptMs: Long, androidReceiptMs: Long?): Long =
        androidReceiptMs ?: previousReceiptMs
}

/** Retained across logical stop/start; only the owning native terminal callback frees the slot. */
internal class NetworkDiscoveryResolutionLease<L : Any, W : Any> {
    data class Slot<L, W>(val listener: L, val work: W)
    private var slot: Slot<L, W>? = null

    fun busy(): Boolean = slot != null
    fun snapshot(): Slot<L, W>? = slot
    fun work(listener: L): W? = slot?.takeIf { it.listener === listener }?.work

    fun acquire(listener: L, work: W): Boolean {
        if (slot != null) return false
        slot = Slot(listener, work)
        return true
    }

    fun complete(listener: L): W? {
        val current = slot?.takeIf { it.listener === listener } ?: return null
        slot = null
        return current.work
    }
}

/** Failures are not receipts. Stop attempts are bounded, while handles survive until acknowledgment. */
internal class NetworkDiscoveryCleanup<L : Any>(
    private val capacity: Int,
    private val maxAttempts: Int = 4,
    private val baseDelayMs: Long = 250,
) {
    init { require(capacity > 0 && maxAttempts > 0 && baseDelayMs > 0) }
    private data class Entry(var stopping: Boolean = false, var attempts: Int = 0, var nextAttemptMs: Long = 0)
    private val entries = IdentityHashMap<L, Entry>()

    fun track(listener: L): Boolean {
        if (listener in entries) return true
        if (entries.size >= capacity) return false
        entries[listener] = Entry()
        return true
    }
    fun contains(listener: L): Boolean = listener in entries
    fun handles(): List<L> = entries.keys.toList()
    fun pending(): Boolean = entries.isNotEmpty()

    fun requestStop(listener: L, nowMs: Long) {
        val entry = entries[listener] ?: return
        if (!entry.stopping) { entry.stopping = true; entry.nextAttemptMs = nowMs }
    }

    fun tryStop(listener: L, nowMs: Long): Boolean {
        val entry = entries[listener] ?: return false
        if (!entry.stopping || entry.attempts >= maxAttempts || nowMs < entry.nextAttemptMs) return false
        entry.attempts++
        val shift = (entry.attempts - 1).coerceAtMost(4)
        entry.nextAttemptMs = nowMs + (baseDelayMs * (1L shl shift)).coerceAtMost(2_000L)
        return true
    }

    fun acknowledge(listener: L): Boolean = entries.remove(listener) != null
    fun exhausted(nowMs: Long): Boolean = entries.values.any {
        it.stopping && it.attempts >= maxAttempts && nowMs >= it.nextAttemptMs
    }
    fun nextDelayMs(nowMs: Long): Long? = entries.values.filter {
        it.stopping && (it.attempts < maxAttempts || nowMs < it.nextAttemptMs)
    }.minOfOrNull { (it.nextAttemptMs - nowMs).coerceAtLeast(1) }

    /** Explicit user retry only. No call to this can start discovery or forget an old handle. */
    fun retry(nowMs: Long) = entries.values.forEach { entry ->
        if (entry.stopping) { entry.attempts = 0; entry.nextAttemptMs = nowMs }
    }
}
