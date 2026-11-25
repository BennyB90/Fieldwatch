package app.fieldwatch.sweep

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * App-private, on-device session history. Call on an IO dispatcher.
 *
 * ACTIVE checkpoints are intentionally stored unchanged: the coordinator decides which
 * checkpoints to recover as INTERRUPTED when the process next starts. IDs are data only;
 * every session lives in the same database, never in a path derived from radio/user input.
 */
class SweepSessionStore internal constructor(
    private val directory: File,
    private val replaceFile: (File, File) -> Unit,
) {
    constructor(directory: File) : this(directory, { temporary, destination ->
        Files.move(
            temporary.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        Unit
    })

    private val lock = locks.getOrPut(directory.canonicalPath) { Any() }
    private val database = File(directory, DATABASE_NAME)

    fun load(): List<SweepSession> = synchronized(lock) { readSessions() }

    fun save(session: SweepSession) {
        // Demo observations must never enter measured/session history, including updates
        // to an existing real session ID.
        if (session.demo) return
        require(validId(session.id)) { "Session ID must contain 1–128 letters, digits, dots, underscores or hyphens" }
        synchronized(lock) {
            val existing = readSessions().filterNot { it.id == session.id }
            writeSessions(normalize(existing + session))
        }
    }

    fun delete(id: String) {
        require(validId(id)) { "Invalid session ID" }
        synchronized(lock) {
            val sessions = readSessions()
            if (sessions.any { it.id == id }) writeSessions(sessions.filterNot { it.id == id })
            // A diagnostic original can contain this same session. User deletion takes
            // precedence over retaining those older recovery copies.
            deleteRecoveryCopies()
        }
    }

    /** Also removes retained recovery copies, which can contain the deleted history. */
    fun deleteAll() {
        synchronized(lock) {
            if (!directory.exists()) return
            writeSessions(emptyList())
            directory.listFiles().orEmpty()
                .filter { it.name.startsWith(CORRUPT_PREFIX) || it.name.startsWith(TEMP_PREFIX) }
                .forEach { Files.deleteIfExists(it.toPath()) }
        }
    }

    private fun readSessions(): List<SweepSession> {
        // A killed process may leave its unfinished replacement behind. It was never
        // committed, so only sessions.json is authoritative on restart.
        directory.listFiles().orEmpty().filter { it.name.startsWith(TEMP_PREFIX) }
            .forEach { Files.deleteIfExists(it.toPath()) }
        if (!database.exists()) return emptyList()
        if (!database.isFile) throw IOException("Session database is not a regular file")
        if (database.length() > MAX_DATABASE_BYTES) return recover(emptyList())
        // Ordinary IO errors are propagated, never mistaken for an empty history that a
        // subsequent save could overwrite. Only invalid serialized contents are recovered.
        val contents = database.readText(Charsets.UTF_8)
        val rows = try {
            json.parseToJsonElement(contents) as? JsonArray ?: return recover(emptyList())
        } catch (_: SerializationException) {
            return recover(emptyList())
        } catch (_: IllegalArgumentException) {
            return recover(emptyList())
        }

        var malformed = false
        val sessions = rows.mapNotNull { row ->
            try {
                json.decodeFromJsonElement<SweepSession>(row).takeIf {
                    if (!validId(it.id)) malformed = true
                    validId(it.id) && !it.demo
                }
            } catch (_: SerializationException) {
                malformed = true
                null
            } catch (_: IllegalArgumentException) {
                malformed = true
                null
            }
        }
        val normalized = normalize(sessions)
        if (malformed) return recover(normalized)
        // Enforce limits and remove any legacy/demo records on disk as well as in memory.
        if (rows.size != normalized.size || sessions != normalized) writeSessions(normalized)
        return normalized
    }

    private fun recover(validSessions: List<SweepSession>): List<SweepSession> {
        val original = File(directory, "$CORRUPT_PREFIX${System.currentTimeMillis()}-${UUID.randomUUID()}.json")
        // Keep the original before attempting repair; an unsuccessful repair leaves both
        // the old database and this diagnostic copy intact.
        database.copyTo(original, overwrite = false)
        writeSessions(validSessions)
        directory.listFiles().orEmpty().filter { it.name.startsWith(CORRUPT_PREFIX) }
            .sortedByDescending { it.name }
            .drop(MAX_RECOVERY_COPIES)
            .forEach { Files.deleteIfExists(it.toPath()) }
        return validSessions
    }

    private fun deleteRecoveryCopies() {
        directory.listFiles().orEmpty().filter { it.name.startsWith(CORRUPT_PREFIX) }
            .forEach { Files.deleteIfExists(it.toPath()) }
    }

    private fun writeSessions(sessions: List<SweepSession>) {
        val bytes = json.encodeToString(sessions).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_DATABASE_BYTES) throw IOException("Session history exceeds the storage limit")
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Unable to create session history directory")
        }
        val temporary = File.createTempFile(TEMP_PREFIX, ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            // Do not fall back to deleting/recreating the database when atomic replacement
            // is unavailable. The caller receives the error and the prior file survives.
            replaceFile(temporary, database)
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    private fun normalize(sessions: List<SweepSession>): List<SweepSession> = sessions
        .filterNot { it.demo }
        .sortedWith(compareByDescending<SweepSession> { it.startedAt }.thenBy { it.id })
        .distinctBy { it.id }
        .take(MAX_SESSIONS)
        .map { session ->
            if (session.findings.size <= MAX_FINDINGS) session else session.copy(
                findings = session.findings.sortedByDescending { it.lastSeenAt }.take(MAX_FINDINGS),
            )
        }

    private fun validId(id: String) = ID_PATTERN.matches(id) && id != "." && id != ".."

    private companion object {
        const val DATABASE_NAME = "sessions.json"
        const val CORRUPT_PREFIX = "sessions.corrupt-"
        const val TEMP_PREFIX = ".sessions-"
        const val MAX_SESSIONS = 100
        const val MAX_FINDINGS = 1_000
        const val MAX_RECOVERY_COPIES = 3
        const val MAX_DATABASE_BYTES = 64L * 1_024L * 1_024L
        val ID_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
        val locks = ConcurrentHashMap<String, Any>()
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
