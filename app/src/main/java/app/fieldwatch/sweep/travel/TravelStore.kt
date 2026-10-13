package app.fieldwatch.sweep.travel

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** Private app storage, bounded and atomically replaced. No external path or upload client. */
class TravelStore(private val directory: File, private val replaceFile: (File, File) -> Boolean = { pending, archive -> pending.renameTo(archive) }) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val archiveFile get() = File(directory, "observations.json")
    private val pendingFile get() = File(directory, "observations.pending")
    private val erasureFile get() = File(directory, "location-erasure.pending")

    @Synchronized fun locationErasurePending(): Boolean = erasureFile.exists()

    @Synchronized fun markLocationErasurePending() {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Unable to create travel storage")
        erasureFile.outputStream().use { it.write(byteArrayOf(1)); it.flush(); (it as java.io.FileOutputStream).fd.sync() }
    }

    @Synchronized fun completeLocationErasure() {
        if (erasureFile.exists() && !erasureFile.delete()) throw IOException("Unable to confirm coordinate erasure")
    }

    @Synchronized fun load(now: Long): TravelArchive {
        // An unfinished atomic write never reactivates an earlier mode.
        if (pendingFile.exists() && !pendingFile.delete()) throw IOException("Unable to clear incomplete travel storage")
        if (!archiveFile.exists()) return TravelArchive()
        if (archiveFile.length() > MAX_BYTES) throw IOException("Travel storage exceeded its limit")
        val archive = bounded(json.decodeFromString<TravelArchive>(archiveFile.readText()), now)
        return archive.copy(
            sessions = archive.sessions.map { saved ->
                val it = if (erasureFile.exists()) TravelPrivacy.withoutLocations(saved, archive.ignoredAddresses) else saved
                if (it.state != TravelSessionState.ACTIVE) it else it.copy(state = TravelSessionState.INTERRUPTED,
                    endedAt = null, status = "Travel mode stopped", error = "Android ended this observation mode. Start it explicitly to observe again.")
            },
        )
    }

    /** The owner may fence the final replacement against a concurrent privacy mutation. */
    @Synchronized fun save(archive: TravelArchive, now: Long, commit: (() -> Unit) -> Boolean = { replace -> replace(); true }): Boolean {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Unable to create travel storage")
        var clean = bounded(archive, now)
        var encoded = json.encodeToString(clean)
        while (encoded.toByteArray(Charsets.UTF_8).size > MAX_BYTES && clean.sessions.size > 1) {
            clean = clean.copy(sessions = clean.sessions.dropLast(1)); encoded = json.encodeToString(clean)
        }
        if (encoded.toByteArray(Charsets.UTF_8).size > MAX_BYTES) throw IOException("Travel storage exceeded its limit")
        try {
            pendingFile.outputStream().use { it.write(encoded.toByteArray(Charsets.UTF_8)); it.flush(); (it as java.io.FileOutputStream).fd.sync() }
            return commit {
                if (!replaceFile(pendingFile, archiveFile)) throw IOException("Unable to replace travel storage")
            }
        } finally { pendingFile.delete() }
    }

    @Synchronized fun deleteAll() {
        if (directory.exists() && !directory.deleteRecursively()) throw IOException("Unable to delete travel observations")
    }

    companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
        fun bounded(archive: TravelArchive, now: Long): TravelArchive = archive.copy(
            ignoredAddresses = archive.ignoredAddresses.mapNotNull(::normalizedTravelAddress).take(TravelLimits.MAX_IGNORED).toSet(),
            sessions = archive.sessions.filter { it.startedAt in (now - TravelLimits.RETENTION_MS)..now }.distinctBy { it.id }
                .sortedByDescending { it.startedAt }.take(TravelLimits.MAX_SESSIONS).map { session ->
                    val trackers = session.trackers.take(TravelLimits.MAX_TRACKERS).map { tracker ->
                        tracker.copy(label = tracker.label.take(80), samples = tracker.samples.takeLast(TravelLimits.MAX_SAMPLES_PER_TRACKER).map {
                            it.copy(location = if (session.locationConsent) TravelAnalyzer.validLocation(it.location, it.elapsedMs) else null)
                        })
                    }
                    session.copy(trackers = trackers, assessments = session.assessments.take(TravelLimits.MAX_TRACKERS), status = session.status.take(200), error = session.error?.take(200))
                },
        )
    }
}
