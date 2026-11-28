package app.fieldwatch.sweep.spectrum

import kotlin.random.Random

internal object SpectrumDemo {
    fun sweep(preset: SpectrumPreset): SpectrumSweep {
        val noise = Random(315)
        val samples = IntArray(preset.defaultPoints) { -910 + noise.nextInt(-18, 19) }
        for ((center, height) in listOf(58 to 220, 131 to 330, 204 to 170)) {
            for (offset in -3..3) samples[center + offset] += height / (1 + kotlin.math.abs(offset))
        }
        return TinySaProtocol.analyze(samples, preset, TinySaModel.TINYSA_ULTRA, capturedAt = 0, demo = true)
    }
}
