// SPDX-License-Identifier: MIT
package app.fieldwatch.sweep.radio.monas

import app.fieldwatch.sweep.radio.ScannerFeedValidator
import java.net.URI
import java.util.Base64

/** Only fixed local messages cross the transport boundary. Never wrap a provider exception. */
class MonasException internal constructor(message: String) : Exception(message) {
    override fun toString() = "MonasException"
}

object MonasdServerAddress {
    fun normalizeBaseUrl(raw: String): String {
        try {
            val normalized = ScannerFeedValidator.normalizeUrl(raw)
            val uri = URI(normalized)
            require(uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null)
            require(uri.path.split('/').none { it == "." || it == ".." })
            require(';' !in uri.rawPath)
            require(!Regex("%(?:2f|5c|25|3b)", RegexOption.IGNORE_CASE).containsMatchIn(uri.rawPath))
            require(uri.path.codePoints().noneMatch { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() })
            return normalized.trimEnd('/') + "/"
        } catch (_: Exception) {
            throw MonasException("Enter an authorized HTTPS server address without credentials, query, fragment or traversal.")
        }
    }

    fun parseSpkiPin(raw: String): ByteArray? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        if (Regex("[0-9A-Fa-f]{64}|(?:[0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}").matches(value)) {
            return value.replace(":", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        if (Regex("sha256/[A-Za-z0-9+/]{43}=?").matches(value)) {
            val encoded = value.substringAfter('/')
            val decoded = Base64.getDecoder().decode(encoded)
            if (decoded.size == 32 && Base64.getEncoder().withoutPadding().encodeToString(decoded) == encoded.trimEnd('=')) return decoded
        }
        throw MonasException("Enter a SHA-256 public-key pin as 32 hex pairs or sha256/base64.")
    }
}

enum class MonasReferenceTier { RELATIVE, REFERENCED, TRACEABLE, UNKNOWN }

data class MonasTestimony(val atUnixMs: Long?, val stale: Boolean, val feedLoss: Boolean, val absent: String?)
data class MonasSigma(val value: Double, val sigma: Double)
data class MonasHealth(val radio: String, val view: String, val sampleRateHz: Double?, val streaming: Boolean,
                       val stale: Boolean, val lostSamples: Long?, val netRefused: Long? = null)
data class MonasProvenance(val tier: MonasReferenceTier, val referenceAgeSec: Long?, val masked: Boolean,
                           val mode: String?, val sessionSeconds: Long?, val expired: Boolean = false,
                           val addressesMode: String? = null)
data class MonasRadioInfo(val label: String, val backend: String?, val serial: String?, val open: Boolean, val verified: Boolean?)
data class MonasCensusDevice(
    val address: String, val kind: String, val name: String?, val company: String?, val packets: Long,
    val crcPassPct: Double?, val bestSnrDb: Double?, val meanSnrDb: MonasSigma?, val crystalOffsetPpm: MonasSigma?,
    val modulationIndex: MonasSigma?, val advIntervalMs: MonasSigma?, val firstSeenSec: Long?, val lastSeenSec: Long?,
    val maskedAddress: Boolean,
) {
    val rotating get() = kind.equals("rpa", true) || kind.equals("nrpa", true)
}

data class MonasBlePacket(val seq: Long, val channel: Long?, val address: String?, val crcOk: Boolean,
                          val snrDb: Double?, val cfoPpm: MonasSigma?, val modulationIndex: MonasSigma?)
data class MonasCensus(val testimony: MonasTestimony, val devices: List<MonasCensusDevice>, val total: Int)
data class MonasPacketPage(val testimony: MonasTestimony, val packets: List<MonasBlePacket>, val total: Int)
data class MonasSnapshot(
    val health: MonasHealth, val provenance: MonasProvenance, val radios: List<MonasRadioInfo>,
    val census: MonasCensus, val ble: MonasPacketPage, val sample: Boolean = false,
    val continuityIssue: String? = null,
) {
    val stale get() = health.stale || !health.streaming || census.testimony.stale || ble.testimony.stale
    val feedLoss get() = census.testimony.feedLoss || ble.testimony.feedLoss
    val masked get() = provenance.masked || census.devices.any { it.maskedAddress }
}
