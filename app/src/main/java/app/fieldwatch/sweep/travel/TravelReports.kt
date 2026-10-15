package app.fieldwatch.sweep.travel

object TravelReports {
    /** The default report deliberately omits addresses, coordinates, and exact observation times. */
    fun text(session: TravelSession, includeIdentifiers: Boolean = false, includeLocations: Boolean = false): String = buildString {
        appendLine("SWEEP Travel observations")
        appendLine("State: ${session.state}")
        appendLine("Observed signals are indicators; identity, ownership, and unwanted tracking are unverified.")
        appendLine("A quiet interval cannot establish that no tracker is present. Android may interrupt scanning.")
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
