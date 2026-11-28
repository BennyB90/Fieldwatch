// SPDX-License-Identifier: MIT
package app.fieldwatch.sweep.radio.monas

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.*

internal object MonasdParser {
    private fun invalid(): Nothing = throw MonasException("The server returned an invalid metrology reading.")

    fun decode(body: ByteArray): JsonElement {
        if (body.size > MAX_BODY) invalid()
        try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString()
            var depth = 0
            var quoted = false
            var escaped = false
            for (ch in text) {
                if (quoted) {
                    when {
                        escaped -> escaped = false
                        ch == '\\' -> escaped = true
                        ch == '"' -> quoted = false
                    }
                } else when (ch) {
                    '"' -> quoted = true
                    '{', '[' -> if (++depth > 16) invalid()
                    '}', ']' -> if (--depth < 0) invalid()
                }
            }
            if (depth != 0 || quoted) invalid()
            return Json.parseToJsonElement(text)
        } catch (_: Exception) { invalid() }
    }

    private fun JsonElement?.obj() = this as? JsonObject ?: invalid()

    /** A Sigma is itself an object containing "value", so unwrap only actual Reading envelopes. */
    private fun JsonElement?.reading(): JsonElement? = when {
        this == null || this == JsonNull -> null
        this is JsonObject && "absent" in this && this["absent"] != JsonNull -> {
            if ("value" in this || (this["absent"] as? JsonPrimitive)?.isString != true) invalid()
            null
        }
        this is JsonObject && keys == setOf("value") -> get("value").takeUnless { it == JsonNull }
        else -> this
    }

    private fun JsonElement?.absentReason(): String? {
        val obj = this as? JsonObject ?: return null
        val raw = obj["absent"] ?: return null
        if (raw == JsonNull) return null
        if ("value" in obj) invalid()
        val p = raw as? JsonPrimitive ?: invalid()
        if (!p.isString || p.content.length > 2048) invalid()
        return sanitize(p.content).takeIf(String::isNotBlank)
    }

    private fun sanitize(raw: String, maxChars: Int = 512): String {
        val out = StringBuilder(minOf(raw.length, maxChars))
        var i = 0
        while (i < raw.length && out.length < maxChars) {
            val cp = Character.codePointAt(raw, i)
            val type = Character.getType(cp)
            if (!Character.isISOControl(cp) && type != Character.FORMAT.toInt() && type != Character.SURROGATE.toInt()) {
                val width = Character.charCount(cp)
                if (out.length + width > maxChars) break
                out.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        return out.toString().trim()
    }

    private fun JsonObject.text(key: String): String? = get(key).reading()?.let {
        val p = it as? JsonPrimitive ?: invalid()
        if (!p.isString || p.content.length > 2048) invalid()
        sanitize(p.content)
    }
    private fun JsonObject.label(key: String) = text(key)?.takeIf(String::isNotBlank) ?: invalid()
    private fun JsonObject.numeric(key: String): JsonPrimitive? = get(key).reading()?.let {
        (it as? JsonPrimitive)?.takeUnless { p -> p.isString } ?: invalid()
    }
    private fun JsonObject.count(key: String) = numeric(key)?.let { it.longOrNull?.takeIf { n -> n >= 0 } ?: invalid() }
    private fun JsonObject.flexibleCount(key: String): Long? = get(key).reading()?.let {
        val p = it as? JsonPrimitive ?: invalid()
        if (p.isString) p.content.trim().takeIf { s -> s.all(Char::isDigit) }?.toLongOrNull()
        else p.longOrNull?.takeIf { n -> n >= 0 } ?: invalid()
    }
    private fun JsonObject.number(key: String) = numeric(key)?.let { it.doubleOrNull?.takeIf(Double::isFinite) ?: invalid() }
    private fun JsonObject.flag(key: String) = numeric(key)?.let { it.booleanOrNull ?: invalid() }
    private fun JsonObject.sigma(key: String) = get(key).reading()?.obj()?.let { pair ->
        MonasSigma(pair.number("value") ?: invalid(), pair.number("sigma")?.takeIf { it >= 0 } ?: invalid())
    }

    fun health(root: JsonElement): MonasHealth = root.obj().run {
        val streaming = flag("streaming") ?: invalid()
        val stale = flag("stale") ?: invalid()
        val radio = text("radio")?.takeIf(String::isNotBlank) ?: if (!streaming) "unavailable" else invalid()
        val view = text("view")?.takeIf(String::isNotBlank) ?: if (!streaming) "unavailable" else invalid()
        val rate = number("sample_rate_hz")?.also { if (it < 0.0 || (streaming && it == 0.0)) invalid() }?.takeIf { it > 0.0 }
        val loss = get("lost_samples").reading()
        val netFeed = (get("feeds") as? JsonObject)?.get("net")?.reading() as? JsonObject
        val refused = netFeed?.let { it.count("refused_session") ?: it.count("refused") }
        MonasHealth(radio, view, rate, streaming, stale,
            if (loss is JsonObject) loss.count("all") else count("lost_samples"), refused)
    }

    private val headerAge = Regex("""\b(?:aged?\s*[=:]?\s*(\d+)\s*s(?:ec(?:onds?)?)?\b|(\d+)\s*s(?:ec(?:onds?)?)?\s+(?:ago|old)\b)""", RegexOption.IGNORE_CASE)
    private val duration = Regex("""(\d{1,9}):([0-5]\d):([0-5]\d)""")
    private fun parseDurationSeconds(raw: String): Long? =
        duration.matchEntire(raw.trim())?.destructured?.let { (h, m, s) -> h.toLong() * 3600 + m.toLong() * 60 + s.toLong() }
            ?: raw.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLongOrNull()

    fun provenance(root: JsonElement): MonasProvenance = root.obj().run {
        val rawRef = get("reference") ?: get("frequency_reference")
        val absentRef = rawRef.absentReason()
        val ref = if (absentRef != null) null else rawRef.reading()
        val structured = ref as? JsonObject
        val header = absentRef ?: if (structured != null) structured.text("provenance")
        else (ref as? JsonPrimitive)?.takeIf { it.isString && it.content.length <= 2048 }?.let { sanitize(it.content) }
        val description = header.orEmpty().trim().lowercase(java.util.Locale.ROOT)
        val expired = description.contains("expired") || structured?.flag("expired") == true
        val tier = when {
            Regex("""\b(user-stated|referenced)\b""").containsMatchIn(description) -> MonasReferenceTier.REFERENCED
            Regex("""\btraceable\b""").containsMatchIn(description) -> MonasReferenceTier.TRACEABLE
            !expired && Regex("""\b(relative|unreferenced|no reference|none)\b""").containsMatchIn(description) -> MonasReferenceTier.RELATIVE
            else -> MonasReferenceTier.UNKNOWN
        }
        val hmsAge = if (!expired) duration.find(description)?.destructured?.let { (h, m, s) ->
            h.toLong() * 3600 + m.toLong() * 60 + s.toLong()
        } else null
        val age = structured?.flexibleCount("age_s") ?: flexibleCount("reference_age_s")
            ?: headerAge.find(description)?.groupValues?.drop(1)?.first { it.isNotEmpty() }?.toLongOrNull()
            ?: hmsAge
        val elapsed = (text("session") ?: text("duration"))?.let(::parseDurationSeconds)
        val addrRaw = text("addresses")?.trim()?.lowercase(java.util.Locale.ROOT)?.takeIf(String::isNotEmpty)
        MonasProvenance(tier, age, masked(addrRaw), text("mode"), elapsed, expired, addrRaw)
    }

    fun radios(root: JsonElement): List<MonasRadioInfo> {
        val rows = root as? JsonArray ?: invalid()
        if (rows.size > 500) invalid()
        return rows.map { it.obj().run { MonasRadioInfo(label("label"), text("backend"), text("serial"), flag("open") ?: invalid(), flag("verified")) } }
    }

    private fun testimony(group: JsonObject): MonasTestimony {
        val fallbackAbsent = group.absentReason() ?: group.text("absent")?.takeIf(String::isNotBlank)
        val t = group["testimony"] as? JsonObject ?: return MonasTestimony(null, true, false, fallbackAbsent ?: invalid())
        return MonasTestimony(t.count("at_unix_ms"), t.flag("stale") ?: invalid(), t.flag("feed_loss") ?: invalid(),
            t.text("absent")?.takeIf(String::isNotBlank) ?: fallbackAbsent)
    }

    private fun rows(group: JsonObject, key: String, testimony: MonasTestimony): JsonArray {
        val entries = group[key].reading()
        if (entries == null && testimony.absent != null) return JsonArray(emptyList())
        return entries as? JsonArray ?: invalid()
    }

    private val alias = Regex("""(?:.*\s)?#\d+$""")
    private fun masked(mode: String?) = mode?.substringBefore(' ')?.equals("masked", true) == true

    private fun group(root: JsonElement): JsonObject {
        val envelope = root.obj()
        val inner = envelope["value"] as? JsonObject
        if (inner != null && (envelope.keys == setOf("value") || envelope.keys == setOf("testimony", "value"))) {
            return if ("testimony" in envelope && "testimony" !in inner) {
                JsonObject(inner + ("testimony" to envelope.getValue("testimony")))
            } else inner
        }
        return envelope
    }

    fun census(root: JsonElement, provenanceMasked: Boolean = false): MonasCensus = group(root).run {
        val t = testimony(this)
        val rows = rows(this, "devices", t)
        val masked = provenanceMasked || masked(text("addresses"))
        val devices = rows.take(500).map { entry -> entry.obj().run {
            val address = label("address")
            MonasCensusDevice(address, label("kind"), text("name"), text("company"), count("packets") ?: invalid(),
                number("crc_pass_pct")?.also { if (it !in 0.0..100.0) invalid() }, number("best_snr_db"),
                sigma("mean_snr_db"), sigma("crystal_offset_ppm"), sigma("modulation_index"),
                get("advertising").reading()?.obj()?.sigma("interval_ms"), count("first_seen_s"), count("last_seen_s"),
                masked || alias.matches(address))
        } }
        MonasCensus(t, devices, rows.size)
    }

    fun ble(root: JsonElement): MonasPacketPage = group(root).run {
        val t = testimony(this)
        val rows = rows(this, "packets", t)
        val packets = rows.take(200).map { entry -> entry.obj().run {
            MonasBlePacket(count("seq") ?: invalid(), count("channel"), text("address"), flag("crc_ok") ?: invalid(),
                number("snr_db"), sigma("cfo_ppm"), sigma("mod_index"))
        } }
        MonasPacketPage(t, packets, rows.size)
    }
}
