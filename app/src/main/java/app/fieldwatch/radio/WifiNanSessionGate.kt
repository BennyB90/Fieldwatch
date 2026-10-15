package app.fieldwatch.radio

/** A pending attach/discovery callback owns exactly one receiver generation. */
internal class WifiNanSessionGate {
    private var generation = 0L
    private var active = false

    @Synchronized fun begin(): Long {
        active = true
        return ++generation
    }

    @Synchronized fun cancel() {
        active = false
        generation++
    }

    @Synchronized fun accepts(token: Long): Boolean = active && token == generation
}
