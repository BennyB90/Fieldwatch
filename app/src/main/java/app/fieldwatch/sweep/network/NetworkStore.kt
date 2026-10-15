package app.fieldwatch.sweep.network

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Synchronous app-private snapshot store. Call from the controller's ordered IO
 * queue; replace() writes its authoritative history so a stale save cannot be
 * scheduled after deletion. All instances for a directory share one JVM lock.
 * No backups are made. Damaged history is preserved until explicit deleteAll().
 */
class NetworkSessionStore internal constructor(
    private val directory: File,
    private val replaceFile: (File, File) -> Unit,
) {
    constructor(directory: File) : this(directory, { temporary, destination ->
        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    })

    private val lock = locks.getOrPut(directory.canonicalPath) { Any() }
    private val database = File(directory, DATABASE_NAME)

    fun load(now: Long = System.currentTimeMillis()): List<NetworkSession> = synchronized(lock) {
        val retained = readDatabase()
        val bounded = bound(retained, now)
        if (bounded != retained) writeDatabase(bounded)
        bounded
    }

    fun save(session: NetworkSession, now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            NetworkValidation.session(session)
            val previous = readDatabase()
            writeDatabase(bound(previous.filterNot { it.id == session.id } + session, now))
        }
    }

    /** Atomic whole-history write for the controller's ordered, revision-aware writer. */
    fun replace(sessions: List<NetworkSession>, now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val bounded = bound(sessions, now)
            // A failed load must not look like empty history and erase an unread
            // original on the controller's next checkpoint.
            if (database.exists()) readDatabase()
            writeDatabase(bounded)
        }
    }

    fun delete(id: String) {
        synchronized(lock) {
            val previous = readDatabase()
            val retained = previous.filterNot { it.id == id }
            if (retained.isEmpty()) Files.deleteIfExists(database.toPath())
            else if (retained != previous) writeDatabase(retained)
            deleteAbandonedWrites()
        }
    }

    /** Explicit reset removes every file created by this store, even damaged history. */
    fun deleteAll() {
        synchronized(lock) {
            Files.deleteIfExists(database.toPath())
            deleteAbandonedWrites()
        }
    }

    /** Recover process-death checkpoints without inventing an unknown session end time. */
    fun recoverInterrupted(now: Long = System.currentTimeMillis()): List<NetworkSession> = synchronized(lock) {
        val previous = readDatabase()
        val recovered = bound(previous, now).map { session ->
            if (session.state == NetworkSessionState.ACTIVE) session.copy(state = NetworkSessionState.INTERRUPTED, endedAt = null)
            else session
        }
        if (previous != recovered) writeDatabase(recovered)
        recovered
    }

    private fun bound(sessions: List<NetworkSession>, now: Long): List<NetworkSession> {
        require(now > 0) { "The network history retention time is invalid." }
        require(sessions.map { it.id }.toSet().size == sessions.size) { "Network session IDs must be distinct." }
        sessions.forEach(NetworkValidation::session)
        val cutoff = (now - NetworkLimits.RETENTION_MS).coerceAtLeast(0)
        return sessions.filter { it.startedAt >= cutoff }
            .sortedWith(compareByDescending<NetworkSession> { it.startedAt }.thenBy { it.id })
            .take(NetworkLimits.MAX_SESSIONS)
    }

    private fun readDatabase(): List<NetworkSession> {
        deleteAbandonedWrites()
        if (!database.exists()) return emptyList()
        if (!database.isFile || database.length() > NetworkLimits.MAX_FILE_BYTES) throw damagedHistory()
        val contents = try {
            val output = ByteArrayOutputStream()
            FileInputStream(database).use { input ->
                val buffer = ByteArray(8_192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > NetworkLimits.MAX_FILE_BYTES) throw damagedHistory()
                    output.write(buffer, 0, count)
                }
            }
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
        } catch (_: CharacterCodingException) {
            throw damagedHistory()
        } catch (_: IOException) {
            throw IOException("Saved network history could not be read. It has not been replaced.")
        }
        val saved = try {
            json.decodeFromString<Database>(contents).also { database ->
                require(database.schemaVersion == 1 && database.sessions.size <= NetworkLimits.MAX_SESSIONS)
                require(database.sessions.map { it.id }.toSet().size == database.sessions.size)
                database.sessions.forEach(NetworkValidation::session)
            }
        } catch (_: SerializationException) {
            throw damagedHistory()
        } catch (_: IllegalArgumentException) {
            throw damagedHistory()
        }
        return saved.sessions
    }

    private fun writeDatabase(sessions: List<NetworkSession>) {
        val bytes = json.encodeToString(Database(sessions = sessions)).toByteArray(Charsets.UTF_8)
        if (bytes.size > NetworkLimits.MAX_FILE_BYTES) throw IOException("Network history exceeds the storage limit.")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Network history could not be saved.")
        makePrivate(directory, isDirectory = true)
        val temporary = File.createTempFile(TEMP_PREFIX, ".tmp", directory)
        try {
            makePrivate(temporary, isDirectory = false)
            FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            replaceFile(temporary, database)
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    private fun deleteAbandonedWrites() {
        if (!directory.exists()) return
        val files = directory.listFiles() ?: throw IOException("Network history files could not be accessed.")
        files.filter { it.name.startsWith(TEMP_PREFIX) }.forEach { Files.deleteIfExists(it.toPath()) }
    }

    private fun makePrivate(file: File, isDirectory: Boolean) {
        val removed = file.setReadable(false, false) && file.setWritable(false, false) && (!isDirectory || file.setExecutable(false, false))
        val restored = file.setReadable(true, true) && file.setWritable(true, true) && (!isDirectory || file.setExecutable(true, true))
        if (!removed || !restored) throw IOException("Network history could not be kept private.")
    }

    private fun damagedHistory() = IOException("Saved network history is damaged. Clear network history explicitly to reset it.")

    @Serializable
    private data class Database(val schemaVersion: Int = 1, val sessions: List<NetworkSession>)

    private companion object {
        const val DATABASE_NAME = "network-sessions.json"
        const val TEMP_PREFIX = ".network-sessions-"
        val locks = ConcurrentHashMap<String, Any>()
        val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    }
}
