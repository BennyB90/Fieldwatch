package app.fieldwatch.sweep.thermal

internal object ThermalFrameParser {
    const val WIDTH = 256
    const val HEIGHT = 192
    const val IMAGE_BYTES = WIDTH * HEIGHT * 2
    const val COMBINED_BYTES = IMAGE_BYTES * 2

    fun isShutter(bytes: ByteArray) = bytes.size == COMBINED_BYTES && bytes.last() == 1.toByte()

    /** The caller counts a null calibration frame without replacing the last rendered measurement. */
    fun parse(bytes: ByteArray, capturedAt: Long, rotation: Int = 0, demo: Boolean = false): ThermalFrame? {
        require(bytes.size == IMAGE_BYTES || bytes.size == COMBINED_BYTES) { "Unexpected thermal frame length" }
        require(rotation in listOf(0, 90, 180, 270))
        if (isShutter(bytes)) return null
        val size = WIDTH * HEIGHT
        val luma = ByteArray(size)
        val temperatures = if (bytes.size == COMBINED_BYTES) FloatArray(size) else null
        // Histogram provides exact central order statistics without sorting floats each frame.
        val histogram = temperatures?.let { IntArray(65536) }
        var valid = 0
        var low = 65535
        var high = 0
        for (i in 0 until size) {
            val x = i % WIDTH
            val y = i / WIDTH
            val dest = when (rotation) {
                90 -> x * HEIGHT + HEIGHT - 1 - y
                180 -> size - 1 - i
                270 -> (WIDTH - 1 - x) * HEIGHT + y
                else -> i
            }
            luma[dest] = bytes[i * 2]
            if (temperatures != null && histogram != null) {
                val offset = IMAGE_BYTES + i * 2
                val raw = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
                // Zero Kelvin and all-ones saturation carry no usable scene measurement.
                temperatures[dest] = if (raw == 0 || raw == 65535) Float.NaN else {
                    histogram[raw]++
                    valid++
                    if (raw < low) low = raw
                    if (raw > high) high = raw
                    kelvin64(raw)
                }
            }
        }
        var ambient: Float? = null
        if (valid > 0 && histogram != null) {
            var count = 0
            var left = low
            for (value in low..high) {
                count += histogram[value]
                if (count <= (valid - 1) / 2) continue
                if (count - histogram[value] <= (valid - 1) / 2) left = value
                if (count > valid / 2) {
                    ambient = (kelvin64(left) + kelvin64(value)) / 2f
                    break
                }
            }
        }
        val sideways = rotation == 90 || rotation == 270
        return ThermalFrame(if (sideways) HEIGHT else WIDTH, if (sideways) WIDTH else HEIGHT,
            luma, temperatures, if (valid > 0) kelvin64(low) else null, if (valid > 0) kelvin64(high) else null,
            ambient, capturedAt, demo)
    }

    private fun kelvin64(raw: Int) = raw / 64f - 273.15f
}
