package app.fieldwatch.radio

/**
 * Android ScanResult.timestamp is microseconds since boot, not delivery time.
 * A scan completion can contain cached AP rows. Only a new, in-session sample
 * is evidence that this session heard the AP. Keep this policy Android-free
 * so cache, timestamp and clock behavior can be tested on the JVM.
 */
class WifiScanEvidence(private val sessionStartUs: Long) {
    private val lastSampleUs = HashMap<String, Long>()

    @Synchronized
    fun observationTimeMs(
        bssid: String,
        sampleUs: Long,
        nowElapsedUs: Long,
        nowWallMs: Long,
    ): Long? {
        if (bssid.isBlank() || sampleUs <= 0L) return null
        if (sampleUs < sessionStartUs || sampleUs > nowElapsedUs) return null
        if (sampleUs <= (lastSampleUs[bssid] ?: 0L)) return null
        lastSampleUs[bssid] = sampleUs
        return nowWallMs - (nowElapsedUs - sampleUs) / 1_000L
    }
}
