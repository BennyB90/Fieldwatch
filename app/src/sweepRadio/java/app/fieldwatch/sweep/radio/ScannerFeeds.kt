package app.fieldwatch.sweep.radio

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ScannerFeed(
    val id: String,
    val name: String,
    val url: String,
    val region: String = "",
    val category: String = "Public safety",
    val favorite: Boolean = false,
)

@Serializable
data class ScannerPreferences(
    val networkConsent: Boolean = false,
    val feeds: List<ScannerFeed> = emptyList(),
)

enum class RadioPlaybackStatus { IDLE, BUFFERING, PLAYING, PAUSED, ERROR }

data class RadioPlaybackState(
    val status: RadioPlaybackStatus = RadioPlaybackStatus.IDLE,
    val feedId: String? = null,
    val error: String? = null,
)

/** Validates operator-supplied addresses; never discovers or guesses a stream URL. */
object ScannerFeedValidator {
    private const val MAX_URL_LENGTH = 2_048
    private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val encodedControls = Regex("%(?:0[0-9a-f]|1[0-9a-f]|7f)|%c2%(?:8[0-9a-f]|9[0-9a-f])", RegexOption.IGNORE_CASE)

    fun normalizeUrl(raw: String): String {
        require(raw.length <= MAX_URL_LENGTH) { "The stream address is too long." }
        require(!hasControls(raw) && !encodedControls.containsMatchIn(raw)) { "The stream address contains unsupported control characters." }
        val address = raw.trim()
        require(address.isNotEmpty()) { "Enter an HTTPS stream address." }
        val uri = try { URI(address) } catch (_: URISyntaxException) {
            throw IllegalArgumentException("Enter a valid HTTPS stream address.")
        }
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.isOpaque) { "Only HTTPS audio streams are supported." }
        require(uri.rawUserInfo == null && uri.rawAuthority?.contains('@') != true) { "Stream addresses cannot include a username or password." }
        require(uri.rawFragment == null) { "Remove the fragment from the stream address." }
        val rawHost = uri.host
        require(!rawHost.isNullOrBlank()) { "Enter a valid stream host and port." }
        require(uri.port == -1 || uri.port in 1..65_535) { "The stream port must be between 1 and 65535." }
        require(uri.rawAuthority?.endsWith(':') != true) { "Enter a valid stream port." }
        // DNS names are case-insensitive. Preserve an IPv6 scope/interface suffix.
        val host = if (rawHost.contains('%')) {
            rawHost.substringBefore('%').lowercase(Locale.ROOT) + '%' + rawHost.substringAfter('%')
        } else rawHost.lowercase(Locale.ROOT).removeSuffix(".")
        require(host.isNotEmpty()) { "Enter a valid stream host." }
        require(host.length <= 253) { "The stream host is too long." }
        val providerHost = host.removeSuffix(".")
        require(!unsupportedProvider(providerHost)) {
            "Broadcastify and RadioReference integration is not supported. Use an operator-authorized HTTPS stream from another source."
        }
        val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        // Building from raw components avoids double-escaping signed query strings.
        val normalized = try { URI("https://$host$port$path$query").toASCIIString() } catch (_: URISyntaxException) {
            throw IllegalArgumentException("Enter a valid HTTPS stream address.")
        }
        require(normalized.length <= MAX_URL_LENGTH) { "The stream address is too long." }
        return normalized
    }

    fun validate(feed: ScannerFeed): ScannerFeed {
        require(idPattern.matches(feed.id)) { "The feed needs a valid ID of up to 128 letters, digits, dots, underscores or hyphens." }
        val name = metadata(feed.name, "Feed name", 120)
        require(name.isNotEmpty()) { "Enter a feed name." }
        val region = metadata(feed.region, "Region", 120)
        val category = metadata(feed.category, "Category", 80).ifEmpty { "Public safety" }
        return feed.copy(name = name, url = normalizeUrl(feed.url), region = region, category = category)
    }

    private fun metadata(raw: String, label: String, limit: Int): String {
        require(!hasControls(raw)) { "$label contains unsupported control characters." }
        val value = raw.trim()
        require(value.length <= limit) { "$label is too long." }
        return value
    }

    private fun hasControls(value: String): Boolean = value.any {
        it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()
    }

    private fun unsupportedProvider(host: String): Boolean = listOf(
        "broadcastify.com", "broadcastify.net", "radioreference.com",
    ).any { host == it || host.endsWith(".$it") }
}

/** Private, synchronous preferences storage. Caller supplies an app-private directory and uses IO. */
class ScannerFeedStore internal constructor(
    private val directory: File,
    private val replaceFile: (File, File) -> Unit,
) {
    constructor(directory: File) : this(directory, { temporary, destination ->
        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    })

    private val lock = locks.getOrPut(directory.canonicalPath) { Any() }
    private val database = File(directory, "scanner-preferences.json")

    fun load(): ScannerPreferences = synchronized(lock) { readPreferences() }

    fun save(preferences: ScannerPreferences) {
        synchronized(lock) {
            // Validate the old file before replacement. Corruption requires an explicit
            // reset; ordinary editing must not destroy the user's retained original.
            if (database.exists()) readPreferences()
            writePreferences(normalize(preferences))
        }
    }

    /** Explicit reset also revokes retained network consent and removes unfinished writes. */
    fun deleteAll() {
        synchronized(lock) {
            Files.deleteIfExists(database.toPath())
            deleteAbandonedWrites()
        }
    }

    private fun readPreferences(): ScannerPreferences {
        deleteAbandonedWrites()
        if (!database.exists()) return ScannerPreferences()
        if (!database.isFile || database.length() > MAX_FILE_BYTES) throw damagedPreferences()
        val contents = try { database.readText(Charsets.UTF_8) } catch (_: IOException) {
            throw IOException("Saved radio settings could not be read.")
        }
        return try {
            normalize(json.decodeFromString<ScannerPreferences>(contents))
        } catch (_: SerializationException) {
            throw damagedPreferences()
        } catch (_: IllegalArgumentException) {
            throw damagedPreferences()
        }
    }

    private fun normalize(preferences: ScannerPreferences): ScannerPreferences {
        require(preferences.feeds.size <= MAX_FEEDS) { "You can save up to 200 radio feeds." }
        val feeds = preferences.feeds.map(ScannerFeedValidator::validate)
        require(feeds.map { it.id }.toSet().size == feeds.size) { "Each feed needs a different ID." }
        return preferences.copy(feeds = feeds)
    }

    private fun writePreferences(preferences: ScannerPreferences) {
        val bytes = json.encodeToString(preferences).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) throw IOException("Radio settings exceed the storage limit.")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Radio settings could not be saved.")
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
        directory.listFiles().orEmpty().filter { it.name.startsWith(TEMP_PREFIX) }.forEach { Files.deleteIfExists(it.toPath()) }
    }

    private fun makePrivate(file: File, isDirectory: Boolean) {
        val removed = file.setReadable(false, false) && file.setWritable(false, false) &&
            (!isDirectory || file.setExecutable(false, false))
        val restored = file.setReadable(true, true) && file.setWritable(true, true) &&
            (!isDirectory || file.setExecutable(true, true))
        if (!removed || !restored) throw IOException("Radio settings could not be kept private.")
    }

    private fun damagedPreferences() = IOException("Saved radio settings are damaged. Clear radio settings to reset them.")

    private companion object {
        const val MAX_FEEDS = 200
        const val MAX_FILE_BYTES = 1_048_576L
        const val TEMP_PREFIX = ".scanner-feeds-"
        val locks = ConcurrentHashMap<String, Any>()
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
