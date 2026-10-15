package app.fieldwatch.sweep.travel

/** Channel absence is allowed before first use; an explicitly blocked channel is not. */
internal object TravelVisibility {
    fun available(permissionGranted: Boolean, appEnabled: Boolean, statusChannelBlocked: Boolean, alertChannelBlocked: Boolean): TravelAvailability = when {
        !permissionGranted || !appEnabled -> TravelAvailability(false, "Allow SWEEP notifications before enabling Travel so its ongoing status, Stop action and repeated-observation alerts remain visible.")
        statusChannelBlocked -> TravelAvailability(false, "Enable the Travel observation notification channel in Android settings before starting Travel.")
        alertChannelBlocked -> TravelAvailability(false, "Enable the Repeated tracker observations notification channel in Android settings before starting Travel.")
        else -> TravelAvailability(true, "Travel notifications are available")
    }
}

/** A visible status describes actual reception and fix availability, rather than consent alone. */
internal object TravelReceptionStatus {
    fun text(bluetoothRunning: Boolean, locationConsent: Boolean, locationAllowed: Boolean, gpsAvailable: Boolean,
        gpsRequested: Boolean, fix: TravelLocation?, elapsedMs: Long, erasurePending: Boolean = false): String {
        val bluetooth = if (bluetoothRunning) "Bluetooth listening; Android may interrupt reception" else "Bluetooth waiting for Android"
        val gps = when {
            erasurePending -> "GPS off; removing saved phone coordinates"
            !locationConsent -> "GPS off"
            !locationAllowed -> "GPS unavailable: location access is disabled"
            !gpsAvailable -> "GPS unavailable: turn on the GPS provider"
            !gpsRequested -> "GPS unavailable: Android did not accept the request"
            TravelAnalyzer.validLocation(fix, elapsedMs) != null -> "GPS correlation using a fresh accurate phone fix"
            else -> "GPS correlation waiting for a fresh accurate phone fix"
        }
        return "$bluetooth · $gps"
    }
}
