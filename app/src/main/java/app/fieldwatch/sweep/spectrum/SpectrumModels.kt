package app.fieldwatch.sweep.spectrum

import java.util.Locale

enum class TinySaModel(val label: String, val zeroOffsetDbm: Float, val minHz: Long, val calibratedMaxHz: Long, val maxHz: Long) {
    TINYSA_ULTRA("tinySA Ultra", 174f, 100_000L, 5_340_000_000L, 6_000_000_000L),
    TINYSA_BASIC("tinySA Basic", 128f, 100_000L, 960_000_000L, 960_000_000L),
}

enum class SpectrumPreset(
    val id: String, val title: String, val startHz: Long, val stopHz: Long,
    val guidance: String, val ultraOnly: Boolean = false, val defaultPoints: Int = 290,
) {
    REMOTES("subghz_315_433", "315 / 433 MHz", 300_000_000, 450_000_000,
        "Keyfobs, relays and other ISM transmitters share the 315 and 433.92 MHz bands."),
    ISM("subghz_868_915", "868 / 915 MHz", 860_000_000, 928_000_000,
        "Smart meters, LoRa equipment and sub-GHz tags can transmit here."),
    CELL_LOW("cell_uplink_low", "Low-band cellular", 698_000_000, 862_000_000,
        "This range includes cellular uplinks. A nearby phone can produce a strong peak."),
    L_BAND("analog_1_2g", "1.2 GHz analog", 1_080_000_000, 1_360_000_000,
        "Analog video and other L-band links can share these frequencies.", true),
    CELL_MID("cell_uplink_mid", "Mid-band cellular", 1_710_000_000, 1_920_000_000,
        "This range includes AWS and PCS uplinks used by nearby phones.", true),
    ISM_24("ism_2_4g", "2.4 GHz ISM", 2_400_000_000, 2_500_000_000,
        "Wi-Fi routers, BLE, frequency-hopping devices, appliances and analog links share this band.", true),
    UNII("unii_5g", "5 GHz U-NII", 5_150_000_000, 5_340_000_000,
        "Indoor U-NII-1/2 links, including Wi-Fi, fall within the calibrated Ultra range here.", true),
    FPV("ism_5_8g", "5.8 GHz extended", 5_645_000_000, 5_945_000_000,
        "Analog video and FPV share this band. Above 5.34 GHz the Ultra requires extended harmonic mode. Levels are uncalibrated.", true);

    companion object {
        fun byId(id: String) = entries.find { it.id == id }
    }
}

data class SpectrumPeakCluster(val startHz: Long, val endHz: Long, val peakHz: Long, val peakDeciDbm: Int, val prominenceDeciDb: Int)

data class SpectrumSweep(
    val presetId: String, val title: String, val startHz: Long, val stopHz: Long,
    val samplesDeciDbm: IntArray, val medianDeciDbm: Int, val peakDeciDbm: Int, val peakHz: Long,
    val prominenceDeciDb: Int, val clusters: List<SpectrumPeakCluster>, val capturedAt: Long,
    val model: TinySaModel, val demo: Boolean = false, val deviceLabel: String = "",
    val manufacturer: String = "", val serialNumber: String = "",
)

fun deciDbm(value: Int): String = String.format(Locale.ROOT, "%.1f", value / 10.0)
fun formatMhz(hz: Long): String = String.format(Locale.ROOT, "%.6f", hz / 1_000_000.0).trimEnd('0').trimEnd('.')
internal fun frequencyAt(startHz: Long, stopHz: Long, index: Int, points: Int) =
    startHz + (stopHz - startHz) * index / (points - 1)
