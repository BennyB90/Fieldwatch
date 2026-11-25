package app.fieldwatch.sweep

/** Used only by a single ordered writer. Failed or fenced writes can be retried. */
internal class CheckpointWriter<T : Any> {
    private var committed: T? = null

    fun writeIfChanged(snapshot: T, write: () -> Boolean): Boolean {
        if (snapshot == committed) return true
        committed = null
        if (!write()) return false
        committed = snapshot
        return true
    }

    fun clear() { committed = null }
}
