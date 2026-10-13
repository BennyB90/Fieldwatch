package app.fieldwatch.radio

/**
 * ScanResult.timestampNanos is the observation's boot-clock time, not delivery time.
 * Each physical scan owns one generation; cancelling it fences callbacks and cache.
 * This is delivery deduplication, not proof that an on-air advertisement is authentic.
 */
internal class BleScanEvidence(private val maxKeys: Int = 4_096) {
    init { require(maxKeys > 0) }

    data class Measurement(val wallAt: Long, val elapsedMs: Long, val ageMs: Long)
    private var generation = 0L
    private var startedNanos: Long? = null
    private val lastNanos = HashMap<String, Long>()

    @Synchronized fun begin(startElapsedNanos: Long): Long {
        startedNanos = startElapsedNanos
        lastNanos.clear()
        return ++generation
    }

    @Synchronized fun cancel() {
        startedNanos = null
        lastNanos.clear()
        generation++
    }

    @Synchronized fun accepts(token: Long): Boolean = startedNanos != null && token == generation

    @Synchronized fun measurement(
        token: Long,
        address: String,
        sampleNanos: Long,
        receivedElapsedNanos: Long,
        receivedWallMs: Long,
    ): Measurement? {
        val start = startedNanos ?: return null
        if (token != generation || start <= 0L || address.isBlank() || address.length > 64) return null
        if (sampleNanos <= 0L || sampleNanos < start || sampleNanos > receivedElapsedNanos) return null
        val key = address.uppercase()
        val previous = lastNanos[key]
        if (previous != null && sampleNanos <= previous) return null
        // Fail closed at capacity rather than evicting replay evidence and admitting it again.
        if (previous == null && lastNanos.size >= maxKeys) return null
        val ageMs = (receivedElapsedNanos - sampleNanos) / NANOS_PER_MS
        if (receivedWallMs < ageMs) return null
        lastNanos[key] = sampleNanos
        return Measurement(receivedWallMs - ageMs, sampleNanos / NANOS_PER_MS, ageMs)
    }

    private companion object { const val NANOS_PER_MS = 1_000_000L }
}
