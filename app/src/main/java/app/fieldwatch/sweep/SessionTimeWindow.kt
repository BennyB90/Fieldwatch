package app.fieldwatch.sweep

/** Boot time bounds collection; a material wall-clock correction invalidates report chronology. */
internal class SessionTimeWindow(
    val startedAt: Long,
    val startedElapsedMs: Long,
    val durationMs: Long,
) {
    enum class State { OPEN, EXPIRED, CLOCK_CHANGED }

    fun state(nowWallMs: Long, nowElapsedMs: Long): State {
        val elapsed = runCatching { Math.subtractExact(nowElapsedMs, startedElapsedMs) }.getOrNull()
            ?: return State.CLOCK_CHANGED
        val wallAdvance = runCatching { Math.subtractExact(nowWallMs, startedAt) }.getOrNull()
            ?: return State.CLOCK_CHANGED
        val correction = runCatching { Math.subtractExact(wallAdvance, elapsed) }.getOrNull()
            ?: return State.CLOCK_CHANGED
        if (elapsed < 0 || correction !in -CLOCK_TOLERANCE_MS..CLOCK_TOLERANCE_MS) return State.CLOCK_CHANGED
        return if (elapsed >= durationMs) State.EXPIRED else State.OPEN
    }

    fun acceptsMeasurement(measuredElapsedMs: Long?, nowWallMs: Long, nowElapsedMs: Long): Boolean =
        state(nowWallMs, nowElapsedMs) == State.OPEN && measuredElapsedMs != null &&
            measuredElapsedMs in startedElapsedMs..nowElapsedMs

    companion object {
        const val ROOM_DURATION_MS = 20 * 60_000L
        // Small clock synchronization adjustments are tolerated; large corrections stop collection.
        const val CLOCK_TOLERANCE_MS = 5_000L
        const val CLOCK_CHANGED_MESSAGE = "The phone clock changed during observation. Scanning stopped because report times are no longer reliable; start a new session explicitly."
    }
}
