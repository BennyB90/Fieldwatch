package app.fieldwatch.sweep.thermal

internal enum class UvcTransport { BULK, ISOCHRONOUS }
internal data class UvcEndpoint(val alt: Int, val address: Int, val transport: UvcTransport, val wMaxPacketSize: Int) {
    val capacity get() = (wMaxPacketSize and 0x7ff) * (1 + (wMaxPacketSize shr 11 and 3))
}
internal data class UvcFrameMode(val format: String, val formatIndex: Int, val frameIndex: Int,
    val width: Int, val height: Int, val interval: Long) {
    val frameBytes get() = width * height * 2
    val thermalProfile get() = (format == "YUY2" || format == "YUYV") && width == 256 && (height == 384 || height == 192)
}
internal data class UvcStream(val configuration: Int, val number: Int, val modes: List<UvcFrameMode>,
    val endpoints: List<UvcEndpoint>, val version: Int, val controlInterface: Int? = null) {
    fun endpoint(payloadBytes: Long): UvcEndpoint? =
        endpoints.firstOrNull { it.transport == UvcTransport.BULK } ?: endpoints
            .filter { payloadBytes > 0 && it.capacity >= payloadBytes }.minByOrNull { it.capacity }
}
internal data class UvcCatalog(val controlInterfaces: Set<Int>, val streams: List<UvcStream>) {
    fun thermal() = streams.flatMap { s -> s.modes.filter {
        s.endpoints.isNotEmpty() && it.thermalProfile
    }.map { s to it } }.maxWithOrNull(compareBy<Pair<UvcStream, UvcFrameMode>> { it.second.height }
        .thenBy { it.second.interval == 400000L })

    fun secondaryVisible(primary: UvcStream) = streams.any { it.configuration == primary.configuration &&
        it.number != primary.number && it.modes.any { mode -> mode.width > 256 && mode.width.toLong() * mode.height > 256 * 192 } }
}

internal object UvcProtocol {
    const val SET_CUR = 1
    const val GET_CUR = 0x81
    const val GET_LEN = 0x85
    const val OUT = 0x21
    const val IN = 0xa1
    const val PROBE = 1
    const val COMMIT = 2

    /** Each descriptor is bounded by its own bLength before any field is read. */
    fun parse(raw: ByteArray): UvcCatalog {
        data class Interface(val config: Int, val number: Int)
        class Streaming {
            val modes = mutableListOf<UvcFrameMode>()
            val endpoints = mutableListOf<UvcEndpoint>()
            var address = 0
        }
        val streams = linkedMapOf<Interface, Streaming>()
        val versions = mutableMapOf<Interface, Int>()
        val streamControls = mutableMapOf<Interface, Int>()
        val controlsByConfig = mutableMapOf<Int, MutableSet<Int>>()
        val controls = mutableSetOf<Int>()
        var configuration = 1
        var number = -1
        var alt = 0
        var subclass = 0
        var format = ""
        var formatIndex = 0
        var expectedFrameType = 0
        var offset = 0
        while (offset + 2 <= raw.size) {
            fun u(i: Int) = raw[offset + i].toInt() and 255
            fun word(i: Int) = u(i) or (u(i + 1) shl 8)
            fun dword(i: Int) = word(i).toLong() or (word(i + 2).toLong() shl 16)
            val length = u(0)
            if (length < 2 || offset + length > raw.size) break
            when (u(1)) {
                2 -> {
                    if (length >= 9) configuration = u(5)
                    subclass = 0
                }
                4 -> {
                    subclass = if (length >= 9 && u(5) == 14) u(6) else 0
                    format = ""
                    expectedFrameType = 0
                    if (subclass != 0) {
                        number = u(2)
                        alt = u(3)
                        if (subclass == 1) {
                            controls.add(number)
                            controlsByConfig.getOrPut(configuration) { linkedSetOf() }.add(number)
                        }
                        if (subclass == 2) streams.getOrPut(Interface(configuration, number)) { Streaming() }
                    }
                }
                5 -> if (subclass == 2 && length >= 7 && u(2) and 0x80 != 0 && word(4) and 0x7ff != 0) {
                    val type = when (u(3) and 3) { 1 -> UvcTransport.ISOCHRONOUS; 2 -> UvcTransport.BULK; else -> null }
                    if (type != null) streams.getValue(Interface(configuration, number)).endpoints
                        .add(UvcEndpoint(alt, u(2), type, word(4)))
                }
                0x24 -> if (length >= 3) {
                    val type = u(2)
                    if (subclass == 1 && type == 1 && length >= 12 && length >= 12 + u(11)) {
                        repeat(u(11)) {
                            val target = Interface(configuration, u(12 + it))
                            versions[target] = word(3)
                            streamControls[target] = number
                        }
                    }
                    if (subclass == 2 && alt == 0) {
                        val stream = streams.getValue(Interface(configuration, number))
                        when (type) {
                            1 -> if (length >= 13) stream.address = u(6)
                            4, 6 -> {
                                expectedFrameType = type + 1
                                formatIndex = if (length > 3) u(3) else 0
                                format = when {
                                    type == 6 && length >= 11 -> "MJPEG"
                                    type == 4 && length >= 27 && u(21) == 16 &&
                                        GUID_TAIL.indices.all { u(9 + it) == GUID_TAIL[it] } ->
                                        String(raw, offset + 5, 4, Charsets.US_ASCII).trimEnd(' ')
                                    else -> ""
                                }
                            }
                            5, 7 -> if (type == expectedFrameType && format.isNotEmpty() && length >= 26) {
                                val count = u(25)
                                val preferred = 400000L
                                val interval = if (count == 0 && length >= 38) {
                                    val min = dword(26)
                                    val max = dword(30)
                                    val step = dword(34)
                                    if (preferred in min..max && (step == 0L || (preferred - min) % step == 0L)) preferred else dword(21)
                                } else if (count > 0 && length >= 26 + 4 * count) {
                                    val options = (0 until count).map { dword(26 + 4 * it) }
                                    if (preferred in options) preferred else options.firstOrNull { it == dword(21) } ?: options.first()
                                } else 0
                                if (interval > 0 && u(3) > 0 && formatIndex > 0) stream.modes
                                    .add(UvcFrameMode(format, formatIndex, u(3), word(5), word(7), interval))
                            }
                            else -> if (type in setOf(0x0a, 0x0c, 0x10, 0x12, 0x13, 0x15, 0x16)) format = ""
                        }
                    }
                }
            }
            offset += length
        }
        return UvcCatalog(controls, streams.map { (key, stream) ->
            UvcStream(key.config, key.number, stream.modes, stream.endpoints.filter {
                stream.address == 0 || it.address == stream.address }, versions[key] ?: 0x0100,
                streamControls[key] ?: controlsByConfig[key.config]?.firstOrNull())
        })
    }

    private val GUID_TAIL = intArrayOf(0, 0, 0x10, 0, 0x80, 0, 0, 0xaa, 0, 0x38, 0x9b, 0x71)
    const val MAX_USBFS_BULK_CHUNK = 16384

    fun probe(mode: UvcFrameMode, size: Int): ByteArray {
        require(size in 26..64)
        return ByteArray(size).apply {
            this[0] = 1 // fixed dwFrameInterval
            this[2] = mode.formatIndex.toByte()
            this[3] = mode.frameIndex.toByte()
            for (i in 0..3) this[4 + i] = (mode.interval ushr (8 * i)).toByte()
        }
    }

    data class Negotiated(val formatIndex: Int, val frameIndex: Int, val interval: Long, val frameBytes: Long, val payloadBytes: Long)

    /** Commit the device's full response, including vendor clock and framing fields. */
    fun negotiate(stream: UvcStream, preferred: UvcFrameMode, request: (Int, Int, Int, ByteArray) -> Int):
        Pair<UvcFrameMode, Negotiated> {
        val length = ByteArray(2)
        val candidateSizes = if (request(IN, GET_LEN, PROBE, length) == 2) {
            val reported = (length[0].toInt() and 255) or ((length[1].toInt() and 255) shl 8)
            check(reported in 26..64) { "Unsupported UVC streaming control length ($reported)." }
            listOf(reported)
        } else when {
            stream.version >= 0x0150 -> listOf(48, 34, 26)
            stream.version >= 0x0110 -> listOf(34, 26)
            else -> listOf(26)
        }
        val fallback = stream.modes.filter { it.thermalProfile && it.height == 192 && it != preferred }
        for (candidate in listOf(preferred) + fallback) {
            for (size in candidateSizes) {
                val block = probe(candidate, size)
                if (request(OUT, SET_CUR, PROBE, block) != size || request(IN, GET_CUR, PROBE, block) != size) continue
                val result = decode(block)
                val actual = stream.modes.find { it.thermalProfile && it.formatIndex == result.formatIndex &&
                    it.frameIndex == result.frameIndex && result.frameBytes == it.frameBytes.toLong() } ?: continue
                if (result.payloadBytes !in 2..1048576L || result.interval <= 0 || stream.endpoint(result.payloadBytes) == null) continue
                if (request(OUT, SET_CUR, COMMIT, block) == size) return actual to result
            }
        }
        error("Accessory did not accept a supported thermal mode and payload size.")
    }

    fun decode(bytes: ByteArray): Negotiated {
        require(bytes.size in 26..64)
        fun u(i: Int) = bytes[i].toInt() and 255
        fun dword(i: Int) = (0..3).sumOf { u(i + it).toLong() shl (8 * it) }
        return Negotiated(u(2), u(3), dword(4), dword(18), dword(22))
    }
}

/** Reassembles Linux/AOSP 16 KB-clamped USB bulk reads back into complete UVC payload transfers. */
internal class UvcBulkAccumulator(maxPayloadBytes: Int, private val deliverPayload: (ByteArray, Int) -> Unit) {
    init { require(maxPayloadBytes in 2..1048576) }
    val buffer = ByteArray(maxPayloadBytes)
    var offset = 0
        private set

    fun nextReadSize(): Int = minOf(UvcProtocol.MAX_USBFS_BULK_CHUNK, buffer.size - offset)

    fun onTransfer(length: Int) {
        if (length > 0) {
            val requested = nextReadSize()
            require(length <= requested)
            offset += length
            if (offset == buffer.size || length < requested) {
                val completed = offset
                offset = 0
                deliverPayload(buffer, completed)
            }
        } else if (length == 0) {
            if (offset > 0) {
                val completed = offset
                offset = 0
                deliverPayload(buffer, completed)
            }
        } else {
            offset = 0
        }
    }
}

/** One call per complete UVC payload transfer (not per USB bulk transaction). */
internal class UvcReassembler(val frameBytes: Int, private val deliver: (ByteArray) -> Unit) {
    private val frame = ByteArray(frameBytes)
    private var used = 0
    private var fid = -1
    private var corrupt = false
    private var ended = false
    var dropped = 0L
        private set
    var corruptFrames = 0L
        private set

    fun packet(packet: ByteArray, length: Int = packet.size) {
        require(length in 0..packet.size)
        if (length == 0) return
        val header = packet[0].toInt() and 255
        if (header !in 2..length) {
            corrupt = true
            return
        }
        val flags = packet[1].toInt() and 255
        val next = flags and 1
        if (fid != next) {
            if (fid >= 0 && !ended) finish()
            if (fid >= 0) corrupt = false
            fid = next
            ended = false
        }
        if (ended) return
        val size = length - header
        val minimumHeader = 2 + (if (flags and 4 != 0) 4 else 0) + (if (flags and 8 != 0) 6 else 0)
        if (flags and 0x40 != 0 || header < minimumHeader || size > frame.size - used) corrupt = true
        if (!corrupt) {
            packet.copyInto(frame, used, header, length)
            used += size
        }
        if (flags and 2 != 0) {
            finish()
            ended = true
        }
    }

    private fun finish() {
        if (!corrupt && used == frame.size) deliver(frame.copyOf())
        else {
            dropped++
            if (corrupt) corruptFrames++
        }
        used = 0
        corrupt = false
    }
}
