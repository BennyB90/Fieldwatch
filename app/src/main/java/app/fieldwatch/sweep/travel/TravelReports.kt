package app.fieldwatch.sweep.travel

object TravelReports {
    /** The default report deliberately omits addresses, coordinates, and exact observation times. */
    fun text(session: TravelSession, includeIdentifiers: Boolean = false, includeLocations: Boolean = false): String = buildString {
        appendLine("SWEEP Travel observations")
        appendLine("State: ${session.state}")
        appendLine("Bluetooth scanning: ${if (session.lowPowerBluetooth) "low power" else "balanced"}")
        if (session.state == TravelSessionState.INTERRUPTED) {
            appendLine("Session ended early or was interrupted; reception evidence may be incomplete.")
        }
        if (session.droppedSamples > 0) {
            appendLine("Dropped samples: ${session.droppedSamples} (not all received advertisements were retained).")
        }
        appendLine("Observed signals are indicators; identity, ownership, and unwanted tracking are unverified.")
        appendLine("A quiet interval cannot establish that no tracker is present. Android may interrupt scanning.")
        if (session.trackers.isEmpty()) {
            appendLine("No eligible tracker broadcasts recorded.")
        }
        session.trackers.forEach { tracker ->
            appendLine("${tracker.label}: ${tracker.samples.size} spaced observations")
            if (includeIdentifiers) appendLine("Observed address: ${tracker.address}")
            val assessment = session.assessments.firstOrNull { it.address == tracker.address }
            if (assessment != null) {
                appendLine("Observed over ${assessment.spanMinutes} minutes.")
                if (includeLocations) appendLine("${assessment.distinctPhoneLocations} distinct phone locations qualified.")
            }
            if (includeLocations) tracker.samples.mapNotNull { it.location }.forEach {
                appendLine("Phone location: ${it.latitude}, ${it.longitude}; accuracy ${it.accuracyMeters} m")
            }
            if (tracker.randomizedAddress) appendLine("Address may change; different addresses are not linked as one physical device.")
        }
    }
}
