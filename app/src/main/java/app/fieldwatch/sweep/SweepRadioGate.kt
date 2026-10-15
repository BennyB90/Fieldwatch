package app.fieldwatch.sweep

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
