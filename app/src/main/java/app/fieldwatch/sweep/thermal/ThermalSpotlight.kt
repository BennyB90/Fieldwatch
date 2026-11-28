package app.fieldwatch.sweep.thermal

internal data class ThermalRendering(val argb: IntArray, val compactClusters: Int, val broadSurfaces: Int)

internal object ThermalSpotlight {
    const val DEFAULT_THRESHOLD = 2.5f
    const val MIN_THRESHOLD = 1.5f
    const val MAX_THRESHOLD = 8f

    fun render(frame: ThermalFrame, palette: ThermalPalette, threshold: Float = DEFAULT_THRESHOLD): ThermalRendering {
        require(threshold in MIN_THRESHOLD..MAX_THRESHOLD)
        val pixels = IntArray(frame.luminance.size)
        val heat = frame.celsius
        val ambient = frame.ambientMedianC
        val mask = if (heat != null && ambient != null && palette == ThermalPalette.DELTA_SPOTLIGHT) BooleanArray(pixels.size) else null
        for (i in pixels.indices) {
            val y = frame.luminance[i].toInt() and 255
            val delta = if (mask != null) heat!![i] - ambient!! else Float.NaN
            pixels[i] = when {
                delta >= threshold -> {
                    mask!![i] = true
                    ThermalPalettes.spotlight[((delta - threshold) / (12f - threshold) * 255).toInt().coerceIn(0, 255)]
                }
                palette == ThermalPalette.IRONBOW -> ThermalPalettes.ironbow[y]
                else -> ThermalPalettes.whiteHot[y]
            }
        }
        var compact = 0
        var broad = 0
        if (mask != null) {
            val queue = IntArray(mask.size)
            for (i in mask.indices) {
                if (!mask[i]) continue
                var read = 0
                var written = 0
                fun visit(index: Int) {
                    if (mask[index]) {
                        mask[index] = false
                        queue[written++] = index
                    }
                }
                visit(i)
                while (read < written) {
                    val at = queue[read++]
                    val x = at % frame.width
                    if (x > 0) visit(at - 1)
                    if (x + 1 < frame.width) visit(at + 1)
                    if (at >= frame.width) visit(at - frame.width)
                    if (at + frame.width < mask.size) visit(at + frame.width)
                }
                if (written <= mask.size * 0.15f) compact++ else {
                    broad++
                    for (j in 0 until written) {
                        val at = queue[j]
                        pixels[at] = ThermalPalettes.whiteHot[frame.luminance[at].toInt() and 255]
                    }
                }
            }
        }
        return ThermalRendering(pixels, compact, broad)
    }
}
