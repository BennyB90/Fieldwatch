package app.fieldwatch.sweep.network

import app.fieldwatch.sweep.SweepRadioGate

/** A visible scan's receipt window is independent of delayed Android callbacks. */
internal class NetworkRunLease(private val gate: SweepRadioGate) {
    private var id: String? = null
    private var startedElapsed = 0L

    @Synchronized fun acquire(runId: String, elapsed: Long): Boolean {
        if (id != null || elapsed < 0L || !gate.acquire("network:$runId")) return false
        id = runId
        startedElapsed = elapsed
        return true
    }

    @Synchronized fun accepts(runId: String, elapsed: Long, visible: Boolean): Boolean =
        visible && id == runId && elapsed >= startedElapsed && elapsed - startedElapsed < WINDOW_MS

    @Synchronized fun owns(runId: String): Boolean = id == runId

    @Synchronized fun release(runId: String): Boolean {
        if (id != runId) return false
        id = null
        return gate.release("network:$runId")
    }

    companion object { const val WINDOW_MS = 60_000L }
}
