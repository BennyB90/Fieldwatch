package app.fieldwatch.sweep.spaces

import app.fieldwatch.sweep.SweepPrivateFiles
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class SpaceHistoryUnreadable(cause: Exception) :
    IOException("Saved Spaces could not be read. Retry or explicitly clear Saved Spaces to recover.", cause)

/** The database is authoritative. A failed read prevents every mutation except explicit clear. */
internal class SpaceStore(
    private val directory: File,
    private val replaceFile: (File, File) -> Unit = { source, target ->
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    },
) {
    private val lock = locks.getOrPut(directory.canonicalPath) { Any() }
    private val database = File(directory, "spaces.json")

    fun load(): List<SavedSpace> = synchronized(lock) { read() }

    fun update(change: (List<SavedSpace>) -> List<SavedSpace>): List<SavedSpace> = synchronized(lock) {
        val previous = read()
        val next = change(previous)
        validate(next)
        if (next != previous) {
            val bytes = json.encodeToString(Database(1, next)).toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_BYTES) throw IOException("Saved Spaces storage is full.")
            SweepPrivateFiles.directory(directory)
            val temp = SweepPrivateFiles.temporary(directory, TEMP_PREFIX)
            try {
                FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
                replaceFile(temp, database)
            } finally {
                Files.deleteIfExists(temp.toPath())
            }
        }
        next
    }

    fun clear() = synchronized(lock) {
        cleanTemporary()
        Files.deleteIfExists(database.toPath())
        Unit
    }

    private fun read(): List<SavedSpace> = try {
        cleanTemporary()
        if (Files.notExists(database.toPath())) emptyList() else {
            require(database.isFile && database.length() in 1..MAX_BYTES)
            val content = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(database.readBytes()))
            val saved = json.decodeFromString<Database>(content.toString())
            require(saved.version == 1)
            saved.spaces.also(::validate)
        }
    } catch (failure: Exception) {
        throw SpaceHistoryUnreadable(failure)
    }

    private fun cleanTemporary() {
        if (!Files.notExists(directory.toPath())) {
            val files = directory.listFiles() ?: throw IOException("Cannot access Saved Spaces.")
            files.filter { it.name.startsWith(TEMP_PREFIX) }.forEach { Files.deleteIfExists(it.toPath()) }
        }
    }

    private fun validate(spaces: List<SavedSpace>) {
        require(spaces.size <= SpaceLimits.SPACES) { "The Saved Spaces limit is ${SpaceLimits.SPACES}." }
        require(spaces.distinctBy { it.id }.size == spaces.size)
        spaces.forEach { space ->
            require(id.matches(space.id) && space.name.isNotBlank() && space.name.length <= SpaceLimits.NAME)
            require(space.createdAt > 0 && space.updatedAt >= space.createdAt)
            space.baseline?.let { b ->
                require(b.startedAt > 0 && b.endedAt >= b.startedAt && b.sourceSessionId.isNotBlank())
                require(b.devices.size + b.ambiguousKeys.size <= SpaceLimits.BASELINE && b.unresolvedCount >= 0)
                val keys = b.devices.map { it.key }
                require(keys.distinct().size == keys.size && keys.none { it in b.ambiguousKeys })
                require(b.ambiguousKeys.all(key::matches))
                require(b.devices.all { key.matches(it.key) && it.key == "${it.transport}:${it.identifier}" })
            }
            require(space.expectedDevices.size <= SpaceLimits.EXPECTED) { "The expected device limit is ${SpaceLimits.EXPECTED}." }
            require(space.expectedDevices.distinctBy { it.key }.size == space.expectedDevices.size)
            require(space.expectedDevices.all { key.matches(it.key) && it.key.substringBefore(':') == it.transport })
            require(space.inspections.size <= SpaceLimits.INSPECTIONS)
            require(space.inspections.distinctBy { it.id }.size == space.inspections.size)
            val template = SpaceChecklists.forKind(space.kind)
            space.inspections.forEach { log ->
                require(id.matches(log.id) && log.spaceId == space.id && log.spaceKind == space.kind)
                require(log.createdAt > 0 && log.completedAt >= log.createdAt && digest.matches(log.reportSha256))
                require(log.sessionStartedAt > 0 && log.sessionEndedAt >= log.sessionStartedAt)
                require(log.checklist.map { it.id } == template.map { it.id })
                require(log.checklist.all { it.status != ChecklistStatus.UNCHECKED && it.note.length <= SpaceLimits.NOTE })
                val d = log.diffSummary
                listOf(d.newFindings, d.expectedFindings, d.notReobservedBaseline, d.unresolvedFindings).forEach {
                    require(it.details.size <= SpaceLimits.REPORT_ROWS_PER_GROUP && it.count >= it.details.size)
                }
            }
        }
    }

    @Serializable private data class Database(val version: Int, val spaces: List<SavedSpace>)

    private companion object {
        const val MAX_BYTES = 32L * 1024 * 1024
        const val TEMP_PREFIX = ".spaces-"
        val key = Regex("(BLE|WIFI):([0-9A-F]{2}:){5}[0-9A-F]{2}")
        val id = Regex("[a-zA-Z0-9_-]{1,128}")
        val digest = Regex("[0-9a-f]{64}")
        val locks = ConcurrentHashMap<String, Any>()
        val json = Json { encodeDefaults = true }
    }
}
