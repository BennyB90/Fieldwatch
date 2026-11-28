package app.fieldwatch.sweep.thermal

internal enum class ThermalPalette(val label: String) {
    DELTA_SPOTLIGHT("ΔT spotlight"), IRONBOW("Ironbow"), WHITE_HOT("White hot")
}

internal data class ThermalFrame(
    val width: Int,
    val height: Int,
    val luminance: ByteArray,
    val celsius: FloatArray?,
    val minC: Float?,
    val maxC: Float?,
    val ambientMedianC: Float?,
    val capturedAt: Long,
    val demo: Boolean,
) {
    val radiometric get() = celsius != null
    val maxDeltaC get() = maxC?.let { it - requireNotNull(ambientMedianC) }

    fun spotC(x: Int, y: Int): Float? =
        if (x in 0 until width && y in 0 until height) celsius?.get(y * width + x)?.takeIf(Float::isFinite) else null

    fun spotDeltaC(x: Int, y: Int): Float? = spotC(x, y)?.minus(requireNotNull(ambientMedianC))
}
