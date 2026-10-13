/*
 * Protocol UUIDs and field definitions derived from ValentineResearch/AndroidESPLibrary
 * commit 7e2c8492b18f798b8b8345bebf83281627f738ce. This bounded Kotlin decoder is original.
 * Copyright (c) 2016 Valentine Research, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the "Software"), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify,
 * merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit
 * persons to whom the Software is furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all copies
 * or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
 * PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
 * FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */
package app.fieldwatch.sweep.radar

import java.util.UUID

object ValentineEspProtocol {
    const val SOURCE_SHA = "7e2c8492b18f798b8b8345bebf83281627f738ce"
    const val SOURCE_URL = "https://github.com/ValentineResearch/AndroidESPLibrary"
    val SERVICE_UUID: UUID = UUID.fromString("92a0aff4-9e05-11e2-aa59-f23c91aec05e")
    val NOTIFY_UUID: UUID = UUID.fromString("92a0b2ce-9e05-11e2-aa59-f23c91aec05e")
    val REQUEST_UUID: UUID = UUID.fromString("92a0b6d4-9e05-11e2-aa59-f23c91aec05e")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Complete raw short-characteristic frame. Long/chunked ESP packets are not used. */
    data class Frame(val source: Int, val destination: Int, val packetId: Int, val payload: List<Int>, val withChecksum: Boolean)

    fun decode(bytes: ByteArray): Frame? {
        if (bytes.size < 3) return null
        val checksum = when (unsigned(bytes[2]) and 0x0F) { 0x09 -> false; 0x0A -> true; else -> return null }
        return decodeFrame(bytes, checksum)
    }

    /** Addendum p.4: the bridge reports a dropped request with both device IDs set to 6. */
    fun decodeControl(bytes: ByteArray, withChecksum: Boolean): Frame? {
        val frame = decodeFrame(bytes, withChecksum) ?: return null
        return frame.takeIf { it.source == 6 && it.destination == 6 && it.packetId == 0x65 && it.payload.size == 1 }
    }

    private fun decodeFrame(bytes: ByteArray, checksum: Boolean): Frame? {
        if (bytes.size !in 6..20 || unsigned(bytes[0]) != 0xAA || unsigned(bytes.last()) != 0xAB) return null
        val destination = unsigned(bytes[1])
        val source = unsigned(bytes[2])
        if (destination and 0xF0 != 0xD0 || source and 0xF0 != 0xE0) return null
        val declared = unsigned(bytes[4])
        if (bytes.size != declared + 6 || checksum && declared == 0) return null
        val payloadEnd = bytes.lastIndex - if (checksum) 1 else 0
        if (checksum) {
            val sum = bytes.take(payloadEnd).sumOf(::unsigned) and 0xFF
            if (sum != unsigned(bytes[payloadEnd])) return null
        }
        return Frame(source and 0x0F, destination and 0x0F, unsigned(bytes[3]),
            bytes.slice(5 until payloadEnd).map(::unsigned), checksum)
    }

    fun display(frame: Frame, receivedAt: Long): RadarDisplay? {
        if (frame.source !in 9..10 || frame.packetId != 0x31 || frame.payload.size != 8) return null
        val data = frame.payload
        return RadarDisplay(
            bogeyText = sevenSegment(data[0]), signalBars = Integer.bitCount(data[2]),
            bands = bands(data[3], allowKu = false), directions = directions(data[3]),
            receivedAt = receivedAt, muted = data[5] and 0x01 != 0,
            systemStatus = data[5] and 0x04 != 0, legacy = data[5] and 0x40 != 0,
        )
    }

    fun alert(frame: Frame, receivedAt: Long): RadarAlert? {
        if (frame.source !in 9..10 || frame.packetId != 0x43 || frame.payload.size != 7) return null
        val data = frame.payload
        val index = data[0] shr 4
        val count = data[0] and 0x0F
        if (count == 0 && index != 0 || count > 0 && index !in 1..count) return null
        return RadarAlert(index, count, (data[1] shl 8) or data[2], data[3], data[4],
            bands(data[5], allowKu = true).filterNot { it == RadarBand.LASER }, directions(data[5]),
            data[6] and 0x80 != 0, receivedAt)
    }

    /** The sole ESP request this integration emits: subscribe to the detailed read stream. */
    fun startAlertRequest(withChecksum: Boolean): ByteArray {
        val header = listOf(0xAA, if (withChecksum) 0xDA else 0xD9, 0xE6, 0x41, if (withChecksum) 1 else 0)
        return (header + (if (withChecksum) listOf(header.sum() and 0xFF) else emptyList()) + 0xAB).map { it.toByte() }.toByteArray()
    }

    private fun unsigned(value: Byte) = value.toInt() and 0xFF

    private fun bands(value: Int, allowKu: Boolean) = buildList {
        if (value and 0x08 != 0) add(RadarBand.X)
        if (value and 0x04 != 0) add(RadarBand.K)
        if (value and 0x02 != 0) add(RadarBand.KA)
        if (allowKu && value and 0x10 != 0) add(RadarBand.KU)
        if (value and 0x01 != 0) add(RadarBand.LASER)
    }

    private fun directions(value: Int) = buildList {
        if (value and 0x20 != 0) add(RadarDirection.FRONT)
        if (value and 0x40 != 0) add(RadarDirection.SIDE)
        if (value and 0x80 != 0) add(RadarDirection.REAR)
    }

    private fun sevenSegment(value: Int): String = when (value and 0x7F) {
        0x00 -> " "; 0x3F -> "0"; 0x06 -> "1"; 0x5B -> "2"; 0x4F -> "3"; 0x66 -> "4"
        0x6D -> "5"; 0x7D -> "6"; 0x07 -> "7"; 0x7F -> "8"; 0x6F -> "9"
        0x77 -> "A"; 0x38 -> "L"; 0x18 -> "l"; 0x79 -> "E"; 0x40 -> "-"; else -> "?"
    } + if (value and 0x80 != 0) "." else ""
}

/** Publish complete, bounded tables only; prevent old and new cycles from being mixed. */
class ValentineAlertTableAssembler {
    private val pending = HashMap<Int, RadarAlert>()
    private var expected = 0
    private var beganAt = 0L

    fun accept(alert: RadarAlert, elapsedMs: Long = alert.receivedAt): List<RadarAlert>? {
        if (alert.count == 0 && alert.index == 0) { reset(); return emptyList() }
        if (alert.count !in 1..15 || alert.index !in 1..alert.count) { reset(); return null }
        if (alert.index == 1) { reset(); expected = alert.count; beganAt = elapsedMs }
        if (expected != alert.count || elapsedMs < beganAt || elapsedMs - beganAt > 2_000L) { reset(); return null }
        pending[alert.index] = alert
        if (pending.size != expected || (1..expected).any { it !in pending }) return null
        return (1..expected).map { pending.getValue(it) }.also { reset() }
    }

    fun reset() { pending.clear(); expected = 0; beganAt = 0L }
}
