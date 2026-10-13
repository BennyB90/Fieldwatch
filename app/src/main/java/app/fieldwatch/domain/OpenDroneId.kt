package app.fieldwatch.domain

/**
 * ASTM F3411 / OpenDroneID packed messages from BLE FFFA or Wi-Fi vendor IE
 * FA:0B:BC type 0x0D. Location, Basic ID, System, Self ID. Heading follows
 * opendroneid.c (direction byte + EW flag), not the catalog *2 scale.
 */
object OpenDroneId {
    const val BLE_UUID = "FFFA"
    const val NAN_SERVICE_NAME = "org.opendroneid.remoteid"
    const val WIFI_OUI = "FA:0B:BC"
    const val WIFI_TYPE = 0x0D
    private const val BLE_APP_CODE = 0x0D
    private const val MSG = 25
    private const val INV_DIR = 255
    private const val INV_SPEED = 255
    /** ASTM Basic ID id_type. 1 is a CTA-2063 serial. Session and CAA are not. */
    private const val ID_SERIAL = 1

    fun fromFacts(facts: RadioFacts): PayloadLocation {
        var acc = PayloadLocation()
        for (sd in facts.serviceData) {
            if (sd.uuid == NAN_SERVICE_NAME) {
                acc = parseMessages(OpenDroneIdNan.messagesHex(sd.dataHex)).mergeSticky(acc)
                continue
            }
            if (uuid16(sd.uuid) != 0xFFFA) continue
            acc = parseMessages(messagesBle(sd.dataHex)).mergeSticky(acc)
        }
        for (ie in facts.vendorIes) {
            if (!ie.oui.equals(WIFI_OUI, ignoreCase = true) || ie.type != WIFI_TYPE) continue
            acc = parseMessages(messagesWifi(ie.dataHex)).mergeSticky(acc)
        }
        return acc
    }

    /**
     * Wi-Fi vendor IE and validated NAN payloads normalized for the existing
     * FFFA catalog field decoder: `0x0D` + counter + each 25-byte ASTM message.
     * This internal view does not add a BLE service to the observed radio facts.
     */
    fun wifiFffaPayloads(facts: RadioFacts): List<String> {
        val out = ArrayList<String>()
        for (sd in facts.serviceData) {
            if (sd.uuid != NAN_SERVICE_NAME) continue
            if (OpenDroneIdNan.messagesHex(sd.dataHex).isEmpty()) continue
            out += wrapWifiAsFffa(sd.dataHex)
        }
        for (ie in facts.vendorIes) {
            if (!ie.oui.equals(WIFI_OUI, ignoreCase = true) || ie.type != WIFI_TYPE) continue
            out += wrapWifiAsFffa(ie.dataHex)
        }
        return out
    }

    internal fun wrapWifiAsFffa(dataHex: String): List<String> {
        val b = hex(dataHex) ?: return emptyList()
        if (b.isEmpty()) return emptyList()
        // WifiIeParser has already removed the OUI and application code. The
        // first remaining byte is always the counter, including counter 0x0D.
        val counter = b[0].toInt() and 0xFF
        return framedMessages(b, 1).map { msg ->
            buildString(2 + 2 + msg.size * 2) {
                append("0D")
                append("%02X".format(counter))
                for (byte in msg) append("%02X".format(byte.toInt() and 0xFF))
            }
        }
    }

    internal fun messagesBle(dataHex: String): List<ByteArray> {
        val b = hex(dataHex) ?: return emptyList()
        // FFFA service data includes the application code and counter.
        if (b.size < 2 + MSG || b[0] != BLE_APP_CODE.toByte()) return emptyList()
        return framedMessages(b, 2)
    }

    internal fun messagesWifi(dataHex: String): List<ByteArray> {
        val b = hex(dataHex) ?: return emptyList()
        if (b.isEmpty()) return emptyList()
        // VendorIeRecord.dataHex excludes the Wi-Fi OUI/type: [counter][msgs].
        // Do not infer BLE framing from the counter's value.
        return framedMessages(b, 1)
    }

    /** Single 25-byte messages, or an ASTM message pack (type nibble 0xF). */
    private fun framedMessages(b: ByteArray, start: Int): List<ByteArray> {
        if (start >= b.size) return emptyList()
        val head = b[start].toInt() and 0xFF
        if (head shr 4 == 0xF && start + 3 <= b.size) {
            val size = (b[start + 1].toInt() and 0xFF).coerceAtLeast(MSG)
            val count = b[start + 2].toInt() and 0xFF
            val out = ArrayList<ByteArray>(count)
            var i = start + 3
            repeat(count) {
                if (i + MSG > b.size) return@repeat
                out += b.copyOfRange(i, i + MSG)
                i += size
            }
            return out
        }
        return chunks(b, start)
    }

    private fun chunks(b: ByteArray, start: Int): List<ByteArray> {
        if (start + MSG > b.size) return emptyList()
        val out = ArrayList<ByteArray>((b.size - start) / MSG)
        var i = start
        while (i + MSG <= b.size) {
            out += b.copyOfRange(i, i + MSG)
            i += MSG
        }
        return out
    }

    internal fun parseMessages(msgs: List<ByteArray>): PayloadLocation {
        var acc = PayloadLocation()
        for (m in msgs) {
            if (m.size < MSG) continue
            acc = parseOne(m).mergeSticky(acc)
        }
        return acc
    }

    private fun parseOne(m: ByteArray): PayloadLocation {
        val type = (m[0].toInt() and 0xFF) shr 4
        return when (type) {
            0 -> basicId(m)
            1 -> location(m)
            3 -> selfId(m)
            4 -> systemMsg(m)
            else -> PayloadLocation()
        }
    }

    private fun location(m: ByteArray): PayloadLocation {
        val flags = m[1].toInt() and 0xFF
        val ew = (flags shr 1) and 1
        val speedMult = flags and 1
        val dirRaw = m[2].toInt() and 0xFF
        val heading = if (dirRaw == INV_DIR) {
            null
        } else {
            val deg = dirRaw + if (ew == 1) 180 else 0
            (deg % 360).toDouble()
        }
        val sh = m[3].toInt() and 0xFF
        val speed = if (sh == INV_SPEED) {
            null
        } else if (speedMult == 0) {
            sh * 0.25
        } else {
            sh * 0.75 + 255 * 0.25
        }
        val vRaw = m[4].toInt()
        val vspeed = (if (vRaw > 127) vRaw - 256 else vRaw) * 0.5
        val lat = i32le(m, 5) * 1e-7
        val lon = i32le(m, 9) * 1e-7
        val altGeo = (u16le(m, 15) * 0.5) - 1000.0
        return PayloadLocation(
            lat = lat,
            lon = lon,
            alt = altGeo,
            headingDeg = heading,
            speedMps = speed,
            vspeedMps = vspeed,
        )
    }

    private fun basicId(m: ByteArray): PayloadLocation {
        val idType = (m[1].toInt() and 0xFF) shr 4
        val id = m.copyOfRange(2, 22).toString(Charsets.US_ASCII).trim('\u0000', ' ')
        val maker = if (idType == ID_SERIAL) Cta2063.label(id).orEmpty() else ""
        return PayloadLocation(
            uasId = id.takeIf { it.isNotEmpty() },
            aircraft = maker,
            basicId = true,
        )
    }

    private fun selfId(m: ByteArray): PayloadLocation {
        val text = m.copyOfRange(2, 25).toString(Charsets.US_ASCII).trim('\u0000', ' ')
        return PayloadLocation(selfId = text.takeIf { it.isNotEmpty() })
    }

    private fun systemMsg(m: ByteArray): PayloadLocation {
        val kind = (m[1].toInt() and 0xFF) and 0x3
        val lat = i32le(m, 2) * 1e-7
        val lon = i32le(m, 6) * 1e-7
        val altRaw = u16le(m, 18)
        val alt = if (altRaw == 0) null else (altRaw * 0.5) - 1000.0
        return PayloadLocation(opLat = lat, opLon = lon, opKind = kind, opAlt = alt)
    }

    private fun i32le(m: ByteArray, at: Int): Int {
        val b0 = m[at].toInt() and 0xFF
        val b1 = m[at + 1].toInt() and 0xFF
        val b2 = m[at + 2].toInt() and 0xFF
        val b3 = m[at + 3].toInt() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun u16le(m: ByteArray, at: Int): Int =
        (m[at].toInt() and 0xFF) or ((m[at + 1].toInt() and 0xFF) shl 8)

    private fun uuid16(uuid: String): Int? {
        val hex = uuid.filter { it.isLetterOrDigit() }
        return when {
            hex.length == 4 -> hex.toIntOrNull(16)
            hex.length >= 8 && hex.startsWith("0000", ignoreCase = true) ->
                hex.substring(4, 8).toIntOrNull(16)
            else -> hex.take(4).toIntOrNull(16)
        }
    }

    private fun hex(s: String): ByteArray? {
        val h = s.filter { it.isLetterOrDigit() }
        if (h.isEmpty() || h.length % 2 != 0) return null
        return ByteArray(h.length / 2) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
