package app.fieldwatch.sweep.travel

import kotlinx.serialization.Serializable

@Serializable enum class TravelSessionState { ACTIVE, COMPLETE, INTERRUPTED }
@Serializable enum class TravelEvidenceKind { REPEATED_OBSERVATIONS, DISTINCT_PHONE_LOCATIONS }

/** This is the observing phone's position, never a tracker position. */
@Serializable data class TravelLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val elapsedMs: Long,
)

@Serializable data class TravelSample(
    val observedAt: Long,
    val elapsedMs: Long,
    val rssi: Int,
    val location: TravelLocation? = null,
)

@Serializable data class TravelTracker(
    val address: String,
    val label: String,
    val randomizedAddress: Boolean,
    val samples: List<TravelSample> = emptyList(),
)

@Serializable data class TravelAssessment(
    val address: String,
    val label: String,
    val kind: TravelEvidenceKind,
    val observations: Int,
    val spanMinutes: Long,
    val distinctPhoneLocations: Int,
    val explanation: String,
)

@Serializable data class TravelSession(
    val id: String,
    val startedAt: Long,
    val startedElapsedMs: Long,
    val endedAt: Long? = null,
    val state: TravelSessionState = TravelSessionState.ACTIVE,
    val locationConsent: Boolean = false,
    val trackers: List<TravelTracker> = emptyList(),
    val assessments: List<TravelAssessment> = emptyList(),
    val droppedSamples: Long = 0,
    val status: String = "Starting Bluetooth observation",
    val error: String? = null,
)

@Serializable data class TravelArchive(
    val sessions: List<TravelSession> = emptyList(),
    /** Exact observed addresses only. A changed address is not silently linked. */
    val ignoredAddresses: Set<String> = emptySet(),
)

object TravelLimits {
    const val MIN_SAMPLE_SPACING_MS = 2 * 60_000L
    const val MIN_SPAN_MS = 15 * 60_000L
    const val MAX_GAP_MS = 10 * 60_000L
    const val OBSERVATION_WINDOW_MS = 2 * 60 * 60_000L
    const val MAX_SESSION_MS = 4 * 60 * 60_000L
    const val FIX_MAX_AGE_MS = 30_000L
    const val FIX_MAX_ACCURACY_METERS = 50f
    const val DISTINCT_LOCATION_METERS = 150.0
    const val MAX_TRACKERS = 32
    const val MAX_SAMPLES_PER_TRACKER = 64
    const val MAX_SESSIONS = 8
    const val MAX_IGNORED = 256
    const val RETENTION_MS = 7 * 24 * 60 * 60_000L
}

internal fun normalizedTravelAddress(address: String): String? {
    val hex = address.replace(":", "").replace("-", "").uppercase()
    if (hex.length != 12 || hex.any { it !in "0123456789ABCDEF" }) return null
    if (hex == "000000000000" || hex == "FFFFFFFFFFFF") return null
    return hex.chunked(2).joinToString(":")
}
