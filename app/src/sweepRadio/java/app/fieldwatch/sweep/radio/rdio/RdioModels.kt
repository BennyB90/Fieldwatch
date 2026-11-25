package app.fieldwatch.sweep.radio.rdio

import app.fieldwatch.sweep.radio.ScannerFeedValidator
import java.net.URI

/** Original SWEEP HTTP companion protocol; not a claim of upstream listener REST routes. */
data class RdioConfig(val systems: List<RdioSystem>)
data class RdioSystem(val id: Int, val label: String, val talkgroups: List<RdioTalkgroup>)
data class RdioTalkgroup(val id: Int, val label: String, val name: String)
data class RdioCall(val id: Long, val system: Int, val talkgroup: Int, val dateTime: String)
data class RdioCallPage(val calls: List<RdioCall>, val nextOffset: Int?, val count: Long, val limit: Int, val offset: Int)
class RdioAudio(val bytes: ByteArray, val mimeType: String) {
    override fun toString() = "RdioAudio(${bytes.size} bytes, $mimeType)"
}

/** Contains a fixed user-facing message, never an upstream body, URL, PIN or wrapped cause. */
class RdioException(message: String) : Exception(message)

object RdioServerAddress {
    fun normalizeBaseUrl(raw: String): String {
        try {
            val uri = URI(raw.trim())
            require(uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null)
            require(uri.path.split('/').none { it == "." || it == ".." })
            require(!Regex("%(?:2f|5c)", RegexOption.IGNORE_CASE).containsMatchIn(uri.rawPath.orEmpty()))
            val normalized = ScannerFeedValidator.normalizeUrl(raw)
            return normalized.trimEnd('/') + "/"
        } catch (_: Exception) {
            throw RdioException("Enter an HTTPS Rdio server address without credentials, query parameters or fragments.")
        }
    }
}
