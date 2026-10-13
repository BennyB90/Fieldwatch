package app.fieldwatch.sweep.radio.rdio

import java.io.ByteArrayOutputStream
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class RdioRequest internal constructor(val url: String, val body: ByteArray, val maxResponseBytes: Int) {
    override fun toString() = "RdioRequest(POST; credentials withheld)"
}
class RdioResponse(val status: Int, val mimeType: String, val body: ByteArray) {
    override fun toString() = "RdioResponse(status=$status; body withheld)"
}
interface RdioTransport : AutoCloseable {
    fun post(request: RdioRequest): RdioResponse
    fun cancelRequests() {}
    override fun close() {}
}

/** Finite HTTPS POSTs. No redirects, cookies, logs, polling, archive, or disk audio cache. */
class RdioHttpsTransport : RdioTransport {
    private val closed = AtomicBoolean(false)
    private val cancellation = AtomicLong()
    private val active = ConcurrentHashMap<HttpsURLConnection, Boolean>()

    override fun post(request: RdioRequest): RdioResponse {
        val generation = cancellation.get()
        if (closed.get()) throw RdioException("The Rdio connection is closed.")
        val connection = try { URL(request.url).openConnection() as HttpsURLConnection }
        catch (_: Exception) { throw RdioException("The Rdio server could not be reached.") }
        active[connection] = true
        try {
            if (closed.get()) throw RdioException("The Rdio connection is closed.")
            if (generation != cancellation.get()) throw RdioException("The Rdio request was cancelled.")
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.useCaches = false
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json, audio/*")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "SWEEP-Rdio-HTTP/1")
            connection.setFixedLengthStreamingMode(request.body.size)
            connection.outputStream.use { it.write(request.body) }
            val status = connection.responseCode
            // Do not follow a redirect with the credential body, including same-host ones.
            if (status in 300..399) throw RdioException("The server redirected this request. Enter its final HTTPS address.")
            if (connection.contentLengthLong > request.maxResponseBytes) throw RdioException("The Rdio response exceeds the allowed size.")
            val encoding = connection.getHeaderField("Content-Encoding").orEmpty()
            if (encoding.isNotEmpty() && !encoding.equals("identity", true)) throw RdioException("The server returned an unsupported response encoding.")
            val body = ByteArrayOutputStream()
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            stream?.use { input ->
                val buffer = ByteArray(8_192)
                val deadline = System.nanoTime() + 20_000_000_000L
                while (true) {
                    if (closed.get()) throw RdioException("The Rdio connection is closed.")
                    if (generation != cancellation.get()) throw RdioException("The Rdio request was cancelled.")
                    if (System.nanoTime() > deadline) throw RdioException("The Rdio request timed out.")
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (body.size() + read > request.maxResponseBytes) throw RdioException("The Rdio response exceeds the allowed size.")
                    body.write(buffer, 0, read)
                }
            }
            return RdioResponse(status, connection.contentType.orEmpty(), body.toByteArray())
        } catch (failure: RdioException) { throw failure }
        catch (_: Exception) { throw RdioException("The Rdio request failed. Check the server and try again.") }
        finally { active.remove(connection); connection.disconnect() }
    }

    override fun cancelRequests() { cancellation.incrementAndGet(); active.keys.forEach { it.disconnect() }; active.clear() }
    override fun close() { closed.set(true); cancelRequests() }
}

/** Blocking API: call on an IO dispatcher. Consent is checked before and after every request. */
class RdioHttpClient(
    baseUrl: String,
    pin: String,
    private val allowed: () -> Boolean,
    private val transport: RdioTransport = RdioHttpsTransport(),
) : AutoCloseable {
    private val address = RdioServerAddress.normalizeBaseUrl(baseUrl)
    private val closed = AtomicBoolean(false)
    private val code = pin.toCharArray().also {
        if (it.size > 128 || it.any { ch -> ch.isISOControl() || Character.getType(ch) == Character.FORMAT.toInt() }) {
            throw RdioException("The access code contains unsupported characters or is too long.")
        }
    }

    fun config(): RdioConfig = RdioParser.config(json("config"))

    fun calls(limit: Int = 50, offset: Int = 0): RdioCallPage {
        if (limit !in 1..100 || offset !in 0..1_000_000) throw RdioException("Choose a page of 1–100 calls and a valid offset.")
        return RdioParser.calls(json("calls") { put("limit", limit); put("offset", offset) }, limit, offset)
    }

    fun audio(callId: Long): RdioAudio {
        if (callId <= 0) throw RdioException("Choose a valid call.")
        val response = request("audio", MAX_AUDIO_BYTES) { put("callId", callId) }
        return RdioParser.audio(response.body, response.mimeType)
    }

    private fun json(route: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject {
        val response = request(route, MAX_JSON_BYTES, fields)
        if (response.mimeType.substringBefore(';').trim().lowercase() != "application/json") {
            throw RdioException("This server does not provide the SWEEP Rdio HTTP companion. Ask its operator to install version 1.")
        }
        return RdioParser.objectBody(response.body)
    }

    private fun request(route: String, maxBytes: Int, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): RdioResponse {
        checkAllowed()
        val body = buildJsonObject { put("pin", String(code)); fields() }.toString().toByteArray(Charsets.UTF_8)
        val response = try { transport.post(RdioRequest(address + "sweep-rdio/v1/" + route, body, maxBytes)) }
        catch (failure: RdioException) { throw failure }
        catch (_: Exception) { throw RdioException("The Rdio request failed. Check the server and try again.") }
        finally { body.fill(0) }
        checkAllowed()
        if (response.body.size > maxBytes) throw RdioException("The Rdio response exceeds the allowed size.")
        when (response.status) {
            200 -> return response
            401 -> throw RdioException("The access code is invalid or expired.")
            403 -> throw RdioException("This access code cannot use the HTTP companion. Ask the operator about its permissions and connection limit.")
            404 -> throw RdioException("This server does not provide the requested HTTP companion endpoint or call.")
            413 -> throw RdioException("This call or request exceeds the allowed size.")
            429 -> throw RdioException("The server has limited requests. Wait before trying again.")
            in 300..399 -> throw RdioException("The server redirected this request. Enter its final HTTPS address.")
            else -> throw RdioException("The Rdio server could not complete this request.")
        }
    }

    private fun checkAllowed() {
        if (closed.get()) throw RdioException("The Rdio connection is closed.")
        if (!allowed()) throw RdioException("Allow internet audio before connecting to Rdio.")
    }

    fun cancelRequests() { transport.cancelRequests() }
    override fun close() { if (closed.compareAndSet(false, true)) { code.fill('\u0000'); transport.close() } }

    companion object {
        const val MAX_JSON_BYTES = 1_048_576
        const val MAX_AUDIO_BYTES = 8 * 1_048_576
    }
}

internal object RdioParser {
    private fun invalid(): Nothing = throw RdioException("The HTTP companion returned an unsupported or invalid response.")

    fun objectBody(bytes: ByteArray): JsonObject {
        if (bytes.size > RdioHttpClient.MAX_JSON_BYTES) invalid()
        try {
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
            var depth = 0; var quoted = false; var escaped = false
            text.forEach { ch ->
                if (quoted) { if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false }
                else when (ch) { '"' -> quoted = true; '{', '[' -> if (++depth > 16) invalid(); '}', ']' -> if (--depth < 0) invalid() }
            }
            return Json.parseToJsonElement(text) as? JsonObject ?: invalid()
        } catch (failure: RdioException) { throw failure }
        catch (_: Exception) { invalid() }
    }

    fun config(root: JsonObject): RdioConfig {
        version(root)
        val systems = array(root, "systems")
        if (systems.size > 200) invalid()
        var total = 0
        return RdioConfig(systems.map { entry ->
            val system = entry as? JsonObject ?: invalid()
            val groups = array(system, "talkgroups")
            total += groups.size
            if (groups.size > 1_000 || total > 5_000) invalid()
            RdioSystem(integer(system, "id"), label(system, "label"), groups.map { item ->
                val group = item as? JsonObject ?: invalid()
                RdioTalkgroup(integer(group, "id"), label(group, "label"), label(group, "name", optional = true))
            })
        }.also { items -> if (items.map { it.id }.distinct().size != items.size) invalid() })
    }

    fun calls(root: JsonObject, expectedLimit: Int, expectedOffset: Int): RdioCallPage {
        version(root)
        val limit = integer(root, "limit")
        val offset = integer(root, "offset")
        val count = long(root, "count")
        val entries = array(root, "calls")
        if (limit != expectedLimit || offset != expectedOffset || entries.size > limit || count < entries.size || (entries.isNotEmpty() && count < offset.toLong() + entries.size)) invalid()
        val next = root["nextOffset"].let { if (it == JsonNull) null else (it as? JsonPrimitive)?.intOrNull ?: invalid() }
        if (next != null && (entries.isEmpty() || next != offset + entries.size || next > 1_000_000 || next >= count)) invalid()
        val calls = entries.map { entry ->
            val item = entry as? JsonObject ?: invalid()
            val id = long(item, "id").takeIf { it > 0 } ?: invalid()
            val date = label(item, "dateTime")
            try { Instant.parse(date) } catch (_: Exception) { invalid() }
            RdioCall(id, integer(item, "system"), integer(item, "talkgroup"), date)
        }
        if (calls.map { it.id }.distinct().size != calls.size) invalid()
        return RdioCallPage(calls, next, count, limit, offset)
    }

    fun audio(bytes: ByteArray, rawMime: String): RdioAudio {
        if (bytes.isEmpty() || bytes.size > RdioHttpClient.MAX_AUDIO_BYTES) invalid()
        val mime = rawMime.substringBefore(';').trim().lowercase()
        fun starts(text: String, at: Int = 0): Boolean = bytes.size >= at + text.length && text.indices.all { bytes[at + it] == text[it].code.toByte() }
        fun sync(): Boolean = bytes.size >= 2 && bytes[0].toInt() and 255 == 255 && bytes[1].toInt() and 0xE0 == 0xE0
        val valid = when (mime) {
            "audio/mpeg", "audio/mp3" -> starts("ID3") || sync()
            "audio/aac" -> sync()
            "audio/wav", "audio/x-wav", "audio/wave" -> starts("RIFF") && starts("WAVE", 8)
            "audio/mp4", "audio/x-m4a" -> starts("ftyp", 4)
            "audio/ogg" -> starts("OggS")
            "audio/flac", "audio/x-flac" -> starts("fLaC")
            else -> false
        }
        if (!valid) throw RdioException("The selected call did not provide supported audio.")
        return RdioAudio(bytes, mime)
    }

    private fun version(root: JsonObject) { if (integer(root, "apiVersion") != 1) invalid() }
    private fun array(root: JsonObject, key: String): JsonArray = root[key] as? JsonArray ?: invalid()
    private fun integer(root: JsonObject, key: String): Int = (root[key] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 } ?: invalid()
    private fun long(root: JsonObject, key: String): Long = (root[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 } ?: invalid()
    private fun label(root: JsonObject, key: String, optional: Boolean = false): String {
        val value = root[key]
        if (optional && (value == null || value == JsonNull)) return ""
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
        if (text.length > 256) invalid()
        return text.filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
    }
}
