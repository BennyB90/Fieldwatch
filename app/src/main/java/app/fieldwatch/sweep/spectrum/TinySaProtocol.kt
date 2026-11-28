package app.fieldwatch.sweep.spectrum

import kotlin.math.round
import kotlin.math.roundToInt

object TinySaProtocol {
    private val ultra = Regex("""\btinysa(?:4|[\s_-]+ultra)(?=\b|_)|\bzs-40[67](?=\b|_)""", RegexOption.IGNORE_CASE)
    private val basic = Regex("""\btinysa(?=\b|_)""", RegexOption.IGNORE_CASE)
    private val usage = Regex("""\busage\s*:""", RegexOption.IGNORE_CASE)
    private val rejectedPrompt = Regex("""(?is)(?:scanraw[^\r\n]*[\r\n].*|(?:usage\s*:|error|unknown).*)\bch>""")
    internal const val REJECTED_MESSAGE =
        "Analyzer rejected scanraw. Confirm Spectrum mode is active and Ultra mode is enabled above 800 MHz."

    class ScanRawRejectedException(message: String = REJECTED_MESSAGE) : IllegalArgumentException(message)

    fun parseVersionBanner(text: String): TinySaModel? = when {
        ultra.containsMatchIn(text) -> TinySaModel.TINYSA_ULTRA
        basic.containsMatchIn(text) -> TinySaModel.TINYSA_BASIC
        else -> null
    }

    fun buildScanRawCommand(startHz: Long, stopHz: Long, points: Int, model: TinySaModel): ByteArray {
        require(points in 16..1024) { "Choose 16 to 1024 sweep points." }
        require(startHz >= model.minHz && stopHz <= model.maxHz && startHz < stopHz) { "Band outside the analyzer range." }
        return "scanraw $startHz $stopHz $points\r\n".toByteArray(Charsets.US_ASCII)
    }

    /** A new decoder per command. Braces inside a sample are ordinary unsigned data. */
    class ScanRawDecoder(private val points: Int, private val model: TinySaModel) {
        init { require(points in 16..1024) }
        private val samples = IntArray(points)
        private val preamble = StringBuilder()
        private var position = -1
        private var low = 0
        private var complete = false

        fun feed(bytes: ByteArray, count: Int = bytes.size): Boolean {
            require(count in 0..bytes.size)
            for (i in 0 until count) {
                if (complete) break
                val value = bytes[i].toInt() and 0xff
                when (position) {
                    -1 -> if (value == 0x7b) {
                        if (usage.containsMatchIn(preamble)) throw ScanRawRejectedException()
                        position = 0
                    } else {
                        if (preamble.length >= 512) preamble.delete(0, preamble.length - 383)
                        preamble.append(value.toChar())
                        if (value == '>'.code && rejectedPrompt.containsMatchIn(preamble)) {
                            throw ScanRawRejectedException()
                        }
                    }
                    3 * points -> {
                        require(value == 0x7d) { "Invalid scanraw closing byte." }
                        complete = true
                    }
                    else -> {
                        when (position % 3) {
                            0 -> require(value == 0x78) { "Invalid scanraw sample prefix." }
                            1 -> low = value
                            2 -> {
                                val raw = low or (value shl 8)
                                samples[position / 3] = round((raw / 32.0 - model.zeroOffsetDbm) * 10.0).toInt()
                            }
                        }
                        position++
                    }
                }
            }
            return complete
        }

        fun result(): IntArray {
            require(complete) { "Incomplete scanraw response." }
            return samples
        }
    }

    fun analyze(
        samples: IntArray, preset: SpectrumPreset, model: TinySaModel, capturedAt: Long,
        thresholdDeciDb: Int = 120, demo: Boolean = false,
    ): SpectrumSweep {
        require(samples.size in 16..1024 && thresholdDeciDb > 0)
        val sorted = samples.sortedArray()
        val middle = samples.size / 2
        val median = if (samples.size % 2 != 0) sorted[middle]
            else ((sorted[middle - 1] + sorted[middle]) / 2.0).roundToInt()
        fun hz(i: Int) = frequencyAt(preset.startHz, preset.stopHz, i, samples.size)
        val peak = samples.indices.maxBy { samples[it] }
        return SpectrumSweep(preset.id, preset.title, preset.startHz, preset.stopHz, samples, median, samples[peak],
            hz(peak), samples[peak] - median, clusters(samples, preset.startHz, preset.stopHz, median, thresholdDeciDb),
            capturedAt, model, demo)
    }

    fun clusters(samples: IntArray, startHz: Long, stopHz: Long, median: Int, thresholdDeciDb: Int): List<SpectrumPeakCluster> = buildList {
        fun hz(i: Int) = frequencyAt(startHz, stopHz, i, samples.size)
        var i = 0
        while (i < samples.size) {
            if (samples[i] - median < thresholdDeciDb) { i++; continue }
            val start = i
            var peak = i
            while (i < samples.size && samples[i] - median >= thresholdDeciDb) {
                if (samples[i] > samples[peak]) peak = i
                i++
            }
            if ((i - start) * 4 <= samples.size) {
                add(SpectrumPeakCluster(hz(start), hz(i - 1), hz(peak), samples[peak], samples[peak] - median))
            }
        }
    }
}
