package app.fieldwatch.sweep.travel

import app.fieldwatch.domain.Observation
import app.fieldwatch.domain.RadioKind
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Original conservative qualification; company/name-only matches do not qualify. */
object TravelTrackerClassifier {
    internal const val GENERIC_FIND_MY = "Apple Find My signal"
    fun label(observation: Observation): String? {
        if (observation.kind != RadioKind.BLE || normalizedTravelAddress(observation.mac) == null) return null
        val services = (observation.serviceUuids + observation.facts.serviceData.map { it.uuid }).mapNotNull(::uuid16).toSet()
        if (0xFD44 in services) return "Find My accessory signal"
        if (0xFD5A in services) return "Samsung item-finder service"
        if (services.any { it in setOf(0xFEED, 0xFEEC, 0xFEDD) }) return "Tile item-finder service"
        if (0xFCB2 in services) return "DULT accessory signal"
        if (observation.facts.serviceData.any {
            uuid16(it.uuid) == 0xFEAA && it.dataHex.take(2).uppercase() in setOf("40", "41")
        }) return "Find Hub accessory signal"
        val manufacturers = observation.facts.mfgRecords.ifEmpty {
            observation.manufacturerId?.let { listOf(app.fieldwatch.domain.MfgRecord(it, observation.manufacturerDataHex)) }.orEmpty()
        }
        val apple = manufacturers.filter { it.companyId == 0x004C }
        val continuity = apple.any { it.dataHex.take(2).uppercase() in setOf("05", "07", "08", "09", "0A", "0B", "0C", "0D", "0E", "0F", "10") }
        if (!continuity && apple.any { it.dataHex.take(2).uppercase() == "12" }) return GENERIC_FIND_MY
        return null
    }

    internal fun isAppleContinuity(observation: Observation): Boolean {
        if (observation.kind != RadioKind.BLE) return false
        if ((observation.serviceUuids + observation.facts.serviceData.map { it.uuid }).any { uuid16(it) == 0xFD44 }) return false
        val records = observation.facts.mfgRecords.ifEmpty {
            observation.manufacturerId?.let { listOf(app.fieldwatch.domain.MfgRecord(it, observation.manufacturerDataHex)) }.orEmpty()
        }
        return records.any { it.companyId == 0x004C && it.dataHex.take(2).uppercase() in setOf("05", "07", "08", "09", "0A", "0B", "0C", "0D", "0E", "0F", "10") }
    }

    private fun uuid16(value: String): Int? {
        val hex = value.replace("-", "").uppercase()
        return when {
            hex.length == 4 -> hex.toIntOrNull(16)
            hex.length == 32 && hex.startsWith("0000") && hex.endsWith("00001000800000805F9B34FB") -> hex.substring(4, 8).toIntOrNull(16)
            else -> null
        }
    }
}

internal object TravelPhoneEvidence {
    fun removeGenericRow(session: TravelSession, observation: Observation, qualifier: TravelTrackerQualifier): TravelSession {
        // Current phone evidence remains decisive even when the bounded cache is full.
        if (!TravelTrackerClassifier.isAppleContinuity(observation) && !qualifier.isPhone(observation.mac)) return session
        val identity = normalizedTravelAddress(observation.mac) ?: return session
        if (session.trackers.none { it.address == identity && it.label == TravelTrackerClassifier.GENERIC_FIND_MY }) return session
        return session.copy(trackers = session.trackers.filterNot { it.address == identity },
            assessments = session.assessments.filterNot { it.address == identity })
    }
}

/** Remember phone evidence across alternating packets at the exact same address. */
internal class TravelTrackerQualifier {
    private val phones = LinkedHashSet<String>()
    private var phoneCapacityReached = false
    fun label(observation: Observation): String? {
        if (observation.kind != RadioKind.BLE) return null
        val address = normalizedTravelAddress(observation.mac) ?: return null
        if (TravelTrackerClassifier.isAppleContinuity(observation)) {
            if (phones.size < 1_024 || address in phones) phones += address else phoneCapacityReached = true
            return null
        }
        val label = TravelTrackerClassifier.label(observation)
        if (label == TravelTrackerClassifier.GENERIC_FIND_MY && (address in phones || phoneCapacityReached)) return null
        return label
    }
    fun isPhone(address: String): Boolean = normalizedTravelAddress(address) in phones
}

/** Reception time cannot substitute for a missing or stale measurement timestamp. */
internal object TravelSampleTiming {
    fun elapsed(observation: Observation, session: TravelSession, nowWallMs: Long, nowElapsedMs: Long): Long? {
        val measured = observation.elapsedRealtimeMs ?: return null
        return measured.takeIf {
            observation.kind == RadioKind.BLE && observation.fresh && it > 0 && travelWindowOpen(session, nowElapsedMs) &&
                it in session.startedElapsedMs..nowElapsedMs &&
                observation.at in session.startedAt..(nowWallMs + 1_000L)
        }
    }
}

/** The evidence window closes independently of when Android schedules service teardown. */
internal fun travelWindowOpen(session: TravelSession, elapsedMs: Long): Boolean =
    session.state == TravelSessionState.ACTIVE && elapsedMs >= session.startedElapsedMs &&
        elapsedMs - session.startedElapsedMs in 0L until TravelLimits.MAX_SESSION_MS

internal object TravelPrivacy {
    fun withoutLocations(session: TravelSession, ignored: Set<String> = emptySet()): TravelSession {
        val trackers = session.trackers.map { it.copy(samples = it.samples.map { sample -> sample.copy(location = null) }) }
        return session.copy(locationConsent = false, trackers = trackers,
            assessments = trackers.mapNotNull { TravelAnalyzer.assess(it, it.samples.lastOrNull()?.elapsedMs ?: 0L, ignored) })
    }

    fun coordinateFree(archive: TravelArchive): Boolean = archive.sessions.all { session ->
        !session.locationConsent && session.trackers.all { tracker -> tracker.samples.all { it.location == null } } &&
            session.assessments.none { it.kind == TravelEvidenceKind.DISTINCT_PHONE_LOCATIONS || it.distinctPhoneLocations > 0 }
    }
}

object TravelAnalyzer {
    fun validLocation(fix: TravelLocation?, sampleElapsedMs: Long): TravelLocation? = fix?.takeIf {
        it.latitude.isFinite() && it.longitude.isFinite() && it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 &&
            !(it.latitude == 0.0 && it.longitude == 0.0) && it.accuracyMeters.isFinite() &&
            it.accuracyMeters in 0f..TravelLimits.FIX_MAX_ACCURACY_METERS &&
            it.elapsedMs > 0 && sampleElapsedMs - it.elapsedMs in 0..TravelLimits.FIX_MAX_AGE_MS
    }

    /** No cross-session or randomized-address stitching, and no time-only movement claim. */
    fun assess(tracker: TravelTracker, nowElapsedMs: Long, ignored: Set<String> = emptySet()): TravelAssessment? {
        if (tracker.address in ignored) return null
        val samples = tracker.samples.filter { nowElapsedMs - it.elapsedMs in 0..TravelLimits.OBSERVATION_WINDOW_MS }
            .sortedBy { it.elapsedMs }.distinctBy { it.elapsedMs }
        val spaced = ArrayList<TravelSample>()
        for (sample in samples) {
            val previous = spaced.lastOrNull()
            if (previous != null && sample.elapsedMs - previous.elapsedMs < TravelLimits.MIN_SAMPLE_SPACING_MS) continue
            if (previous != null && sample.elapsedMs - previous.elapsedMs > TravelLimits.MAX_GAP_MS) spaced.clear()
            spaced += sample
        }
        if (spaced.size < 4) return null
        val span = spaced.last().elapsedMs - spaced.first().elapsedMs
        if (span < TravelLimits.MIN_SPAN_MS) return null
        val locations = ArrayList<TravelLocation>()
        for (sample in spaced) {
            val fix = validLocation(sample.location, sample.elapsedMs) ?: continue
            if (locations.all { distanceMeters(it, fix) >= TravelLimits.DISTINCT_LOCATION_METERS + it.accuracyMeters + fix.accuracyMeters }) locations += fix
        }
        val correlated = locations.size >= 3
        return TravelAssessment(
            address = tracker.address, label = tracker.label,
            kind = if (correlated) TravelEvidenceKind.DISTINCT_PHONE_LOCATIONS else TravelEvidenceKind.REPEATED_OBSERVATIONS,
            observations = spaced.size, spanMinutes = span / 60_000L, distinctPhoneLocations = locations.size,
            explanation = if (correlated) {
                "This Bluetooth address was observed ${spaced.size} times over ${span / 60_000L} minutes at ${locations.size} distinct phone locations. This does not prove unwanted tracking or establish the physical device's identity."
            } else {
                "This Bluetooth address was observed ${spaced.size} times over ${span / 60_000L} minutes. Movement and locations are unverified; repeated observations alone do not prove unwanted tracking."
            },
        )
    }

    private fun distanceMeters(a: TravelLocation, b: TravelLocation): Double {
        val lat = Math.toRadians(b.latitude - a.latitude)
        val lon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(lat / 2) * sin(lat / 2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(lon / 2) * sin(lon / 2)
        return 2 * 6_371_000 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}

/** Pure reducer used by the service controller and evidence tests. */
object TravelSamples {
    fun record(session: TravelSession, address: String, label: String, sample: TravelSample, ignored: Set<String>): TravelSession {
        val identity = normalizedTravelAddress(address) ?: return session
        if (!travelWindowOpen(session, sample.elapsedMs) || identity in ignored || sample.observedAt < session.startedAt) return session
        val previous = session.trackers.firstOrNull { it.address == identity }
        if (previous == null && session.trackers.size >= TravelLimits.MAX_TRACKERS) return session.copy(droppedSamples = session.droppedSamples + 1)
        if (previous?.samples?.lastOrNull()?.let { sample.elapsedMs - it.elapsedMs < TravelLimits.MIN_SAMPLE_SPACING_MS } == true) return session
        val clean = sample.copy(location = if (session.locationConsent) TravelAnalyzer.validLocation(sample.location, sample.elapsedMs) else null)
        val samples = (previous?.samples.orEmpty() + clean).filter { clean.elapsedMs - it.elapsedMs <= TravelLimits.OBSERVATION_WINDOW_MS }
            .takeLast(TravelLimits.MAX_SAMPLES_PER_TRACKER)
        val tracker = TravelTracker(identity, label, (identity.take(2).toInt(16) and 0x02) != 0, samples)
        val trackers = session.trackers.filterNot { it.address == identity } + tracker
        return session.copy(trackers = trackers, assessments = trackers.mapNotNull { TravelAnalyzer.assess(it, sample.elapsedMs, ignored) })
    }
}
