package app.fieldwatch.sweep.thermal

import kotlin.math.roundToInt

internal object ThermalDemo {
    const val LABEL = "SAMPLE · SYNTHETIC THERMAL"

    fun bytes(): ByteArray {
        val bytes = ByteArray(ThermalFrameParser.COMBINED_BYTES)
        for (y in 0 until 192) for (x in 0 until 256) {
            val distance = (x - 172) * (x - 172) + (y - 78) * (y - 78)
            val warmth = 5.6f * (1f - distance / 196f).coerceAtLeast(0f)
            val i = 2 * (y * 256 + x)
            bytes[i] = (78 + x / 8 + y / 12 + (warmth * 18).toInt()).toByte()
            bytes[i + 1] = 128.toByte()
            val raw = ((21.5f + warmth + 273.15f) * 64f).roundToInt()
            bytes[ThermalFrameParser.IMAGE_BYTES + i] = raw.toByte()
            bytes[ThermalFrameParser.IMAGE_BYTES + i + 1] = (raw ushr 8).toByte()
        }
        return bytes
    }

    fun frame(rotation: Int = 0) = requireNotNull(ThermalFrameParser.parse(bytes(), 0L, rotation, demo = true))
}
